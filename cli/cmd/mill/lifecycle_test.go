package main

import (
	"bytes"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"testing"
	"time"

	"skein-strand-cli/internal/client"
	"skein-strand-cli/internal/config"
)

func TestWeaverLifecycleWithFakeLauncher(t *testing.T) {
	var logs bytes.Buffer
	origLogOut := millLogOut
	millLogOut = &logs
	t.Cleanup(func() { millLogOut = origLogOut })
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	req := client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}

	orig := launchWeaver
	var launches int
	var launchedSource string
	var launchedArgs []string
	launchWeaver = func(source string, args []string, out, errOut io.Writer) (*exec.Cmd, error) {
		launches++
		launchedSource = source
		launchedArgs = append([]string(nil), args...)
		if out != io.Discard || errOut != io.Discard {
			t.Fatalf("weaver child output should not be forwarded to mill logs")
		}
		cmd := exec.Command("sleep", "60")
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		world, err := config.RuntimeWorld(cfg)
		if err != nil {
			t.Fatal(err)
		}
		writeWeaverMetadata(t, world, cmd.Process.Pid, "weaver-one")
		return cmd, nil
	}
	t.Cleanup(func() { launchWeaver = orig })

	s := server{children: map[string]*weaverChild{}}
	status, err := s.startWeaver(req)
	if err != nil {
		t.Fatal(err)
	}
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if launchedSource != source || !reflect.DeepEqual(launchedArgs, weaverArgs(world)) {
		t.Fatalf("unexpected launch source/args: source=%q args=%#v", launchedSource, launchedArgs)
	}
	if status["state"] != "running" || status["pid"] == nil || status["weaver_id"] != "weaver-one" || status["socket_path"] == nil || status["nrepl"] == nil {
		t.Fatalf("running status missing required fields: %#v", status)
	}
	if status["config_dir"] == "" || status["state_dir"] == "" || status["data_dir"] == "" || status["database_path"] == "" {
		t.Fatalf("running status missing identity/path fields: %#v", status)
	}
	status, err = s.startWeaver(req)
	if err != nil {
		t.Fatal(err)
	}
	if launches != 1 || status["weaver_id"] != "weaver-one" {
		t.Fatalf("second start should be idempotent after identity check launches=%d status=%#v", launches, status)
	}
	status, err = s.weaverStatus(req)
	if err != nil || status["state"] != "running" {
		t.Fatalf("bad status %#v err=%v", status, err)
	}
	stoppedPID := status["pid"].(int)
	status, err = s.stopWeaver(req)
	if err != nil || status["state"] != "stopped" {
		t.Fatalf("bad stop %#v err=%v", status, err)
	}
	if processAlive(stoppedPID) {
		t.Fatalf("stop should terminate selected child pid %d", stoppedPID)
	}
	status, err = s.weaverStatus(req)
	if err != nil || status["state"] == "running" || status["state"] == "stale" {
		t.Fatalf("bad post-stop status %#v err=%v", status, err)
	}
	if _, err := os.Stat(filepath.Join(world.StateDir, "weaver.json")); !os.IsNotExist(err) {
		t.Fatalf("stop should remove weaver.json, stat err=%v", err)
	}
	logText := logs.String()
	for _, want := range []string{"weaver started config_dir=" + world.ConfigDir, "weaver stopped config_dir=" + world.ConfigDir, "pid=" + intString(stoppedPID)} {
		if !strings.Contains(logText, want) {
			t.Fatalf("missing log %q in:\n%s", want, logText)
		}
	}
	if strings.Contains(logText, "weaver running config_dir=") {
		t.Fatalf("idempotent start should not log a lifecycle transition:\n%s", logText)
	}
}

func TestResolveLaunchSourcePrecedence(t *testing.T) {
	envSource := tempSource(t)
	installedSource := tempSource(t)
	cwdSource := tempSkeinCheckout(t)
	origInstalled := config.InstalledSource
	config.InstalledSource = installedSource
	t.Cleanup(func() { config.InstalledSource = origInstalled })
	t.Setenv("SKEIN_SOURCE", envSource)
	resolved, err := resolveLaunchSource(cwdSource)
	if err != nil || resolved != envSource {
		t.Fatalf("SKEIN_SOURCE should win, got source=%q err=%v", resolved, err)
	}
	t.Setenv("SKEIN_SOURCE", "")
	resolved, err = resolveLaunchSource(cwdSource)
	if err != nil || resolved != installedSource {
		t.Fatalf("installed source should win, got source=%q err=%v", resolved, err)
	}
	config.InstalledSource = ""
	resolved, err = resolveLaunchSource(cwdSource)
	if err != nil || resolved != cwdSource {
		t.Fatalf("Skein checkout cwd should win, got source=%q err=%v", resolved, err)
	}
}

func TestStartFailsBeforeLaunchWhenSourceCannotResolve(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	t.Setenv("SKEIN_SOURCE", "")
	origInstalled := config.InstalledSource
	config.InstalledSource = ""
	t.Cleanup(func() { config.InstalledSource = origInstalled })
	cfg := tempConfigWithoutSource(t)
	orig := launchWeaver
	launched := false
	launchWeaver = func(source string, args []string, out, errOut io.Writer) (*exec.Cmd, error) {
		launched = true
		return orig(source, args, out, errOut)
	}
	t.Cleanup(func() { launchWeaver = orig })
	s := server{children: map[string]*weaverChild{}}
	_, err := s.startWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg})
	if err == nil || !strings.Contains(err.Error(), "SKEIN_SOURCE") || !strings.Contains(err.Error(), "install-time source") || !strings.Contains(err.Error(), "canonical Skein checkout cwd") {
		t.Fatalf("expected actionable source failure, got %v", err)
	}
	if launched {
		t.Fatal("start should not launch without a resolved source")
	}
}

func TestWeaverReplContextAddsSourceWithoutPublicStatusIdentity(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	req := client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command("sleep", "60")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _, _ = cmd.Process.Wait() })
	writeWeaverMetadata(t, world, cmd.Process.Pid, "weaver-repl")
	s := server{children: map[string]*weaverChild{}}
	status, err := s.weaverStatus(req)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := status["source"]; ok {
		t.Fatalf("public status must not expose source: %#v", status)
	}
	context, err := s.weaverReplContext(req)
	if err != nil {
		t.Fatal(err)
	}
	if context["state"] != "running" || context["source"] != source || context["state_dir"] == "" {
		t.Fatalf("unexpected repl context: %#v", context)
	}
}

func TestWeaverStatusDistinguishesStaleMetadata(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	req := client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(world.StateDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(world.StateDir, "weaver.json"), []byte(`{bad`), 0o644); err != nil {
		t.Fatal(err)
	}
	s := server{children: map[string]*weaverChild{}}
	status, err := s.weaverStatus(req)
	if err != nil || status["state"] != "stale" || status["stale_reason"] == nil {
		t.Fatalf("expected malformed stale status, got %#v err=%v", status, err)
	}
	if err := os.WriteFile(filepath.Join(world.StateDir, "weaver.json"), []byte(`{"protocol_version":1,"pid":1,"database_path":"/wrong","weaver_id":"wrong","config_dir":"/wrong","data_dir":"/wrong","socket_path":"/wrong","started_at":"now","nrepl":{"host":"127.0.0.1","port":1}}`), 0o644); err != nil {
		t.Fatal(err)
	}
	status, err = s.weaverStatus(req)
	if err != nil || status["state"] != "stale" || status["stale_reason"] != "weaver metadata identity mismatch" {
		t.Fatalf("expected identity stale status, got %#v err=%v", status, err)
	}
	writeWeaverMetadata(t, world, 999999, "dead-weaver")
	status, err = s.weaverStatus(req)
	if err != nil || status["state"] != "stale" || !strings.Contains(status["stale_reason"].(string), "not alive") {
		t.Fatalf("expected dead-pid stale status, got %#v err=%v", status, err)
	}
}

func TestStartFailsLoudlyOnStaleMetadata(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(world.StateDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(world.StateDir, "weaver.json"), []byte(`{bad`), 0o644); err != nil {
		t.Fatal(err)
	}
	orig := launchWeaver
	launched := false
	launchWeaver = func(source string, args []string, out, errOut io.Writer) (*exec.Cmd, error) {
		launched = true
		return orig(source, args, out, errOut)
	}
	t.Cleanup(func() { launchWeaver = orig })
	s := server{children: map[string]*weaverChild{}}
	if _, err := s.startWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}); err == nil || !strings.Contains(err.Error(), "stale weaver metadata") {
		t.Fatalf("expected stale metadata start failure, got %v", err)
	}
	if launched {
		t.Fatal("start should not launch through stale metadata")
	}
}

func TestStopCleansStaleMetadata(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	req := client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(world.StateDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(world.StateDir, "weaver.json"), []byte(`{bad`), 0o644); err != nil {
		t.Fatal(err)
	}
	s := server{children: map[string]*weaverChild{}}
	status, err := s.stopWeaver(req)
	if err != nil || status["state"] != "stopped" {
		t.Fatalf("bad stale stop %#v err=%v", status, err)
	}
	if _, err := os.Stat(filepath.Join(world.StateDir, "weaver.json")); !os.IsNotExist(err) {
		t.Fatalf("stale stop should remove weaver.json, stat err=%v", err)
	}

}

func TestDifferentReposHaveDistinctRuntimeDirsAndStopSelectedOnly(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfgA := tempConfig(t, source)
	cfgB := tempConfig(t, source)
	orig := launchWeaver
	launchWeaver = func(source string, args []string, out, errOut io.Writer) (*exec.Cmd, error) {
		cmd := exec.Command("sleep", "60")
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		world, err := config.RuntimeWorld(configDirArg(args))
		if err != nil {
			t.Fatal(err)
		}
		writeWeaverMetadata(t, world, cmd.Process.Pid, "weaver-"+filepath.Base(world.ConfigDir))
		return cmd, nil
	}
	t.Cleanup(func() { launchWeaver = orig })
	s := server{children: map[string]*weaverChild{}}
	stA, err := s.startWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfgA})
	if err != nil {
		t.Fatal(err)
	}
	stB, err := s.startWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfgB})
	if err != nil {
		t.Fatal(err)
	}
	if stA["state_dir"] == stB["state_dir"] || stA["data_dir"] == stB["data_dir"] {
		t.Fatalf("different config worlds share dirs: A=%#v B=%#v", stA, stB)
	}
	if _, err := s.stopWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfgA}); err != nil {
		t.Fatal(err)
	}
	worldB, err := config.RuntimeWorld(cfgB)
	if err != nil {
		t.Fatal(err)
	}
	if child := s.children[worldB.ConfigDir]; child == nil || !processAlive(child.cmd.Process.Pid) {
		t.Fatalf("stopping A should not stop B: %#v", s.children)
	}
	s.stopAll()
}

func TestCleanupPreviousMillStateRemovesStaleSocketsAndWeaverMetadata(t *testing.T) {
	root := t.TempDir()
	socketPath := filepath.Join(root, config.MillSocketFileName)
	metadataPath := filepath.Join(root, config.MillMetadataFileName)
	if err := os.WriteFile(socketPath, []byte("stale"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(metadataPath, []byte(`{"protocol_version":1,"pid":999999,"mill_id":"old","state_root":"`+root+`","socket_path":"`+socketPath+`","started_at":"now"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	stateDir := filepath.Join(root, "weavers", "abc")
	if err := os.MkdirAll(stateDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(stateDir, "weaver.json"), []byte(`{"protocol_version":1,"pid":999999}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(stateDir, "weaver.sock"), []byte("stale"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := cleanupPreviousMillState(root, socketPath, metadataPath); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{socketPath, metadataPath, filepath.Join(stateDir, "weaver.json"), filepath.Join(stateDir, "weaver.sock")} {
		if _, err := os.Stat(path); !os.IsNotExist(err) {
			t.Fatalf("expected stale artifact removed: %s stat=%v", path, err)
		}
	}
}

func TestStartFailsWhenWeaverExitsBeforeReadyMetadata(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	source := tempSource(t)
	cfg := tempConfig(t, source)
	orig := launchWeaver
	launchWeaver = func(source string, args []string, out, errOut io.Writer) (*exec.Cmd, error) {
		cmd := exec.Command("false")
		if err := cmd.Start(); err != nil {
			return nil, err
		}
		return cmd, nil
	}
	t.Cleanup(func() { launchWeaver = orig })
	s := server{children: map[string]*weaverChild{}}
	_, err := s.startWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg})
	if err == nil || !strings.Contains(err.Error(), "before publishing ready metadata") {
		t.Fatalf("expected ready metadata failure, got %v", err)
	}
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if child := s.children[world.ConfigDir]; child != nil {
		t.Fatalf("failed startup should remove child handle: %#v", child)
	}
}

func configDirArg(args []string) string {
	for i, arg := range args {
		if arg == "--config-dir" && i+1 < len(args) {
			return args[i+1]
		}
	}
	return ""
}

func tempSource(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "deps.edn"), []byte("{}"), 0o644); err != nil {
		t.Fatal(err)
	}
	return dir
}

func tempSkeinCheckout(t *testing.T) string {
	t.Helper()
	dir := tempSource(t)
	cmd := exec.Command("git", "init")
	cmd.Dir = dir
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("git init failed: %v\n%s", err, out)
	}
	real, err := filepath.EvalSymlinks(dir)
	if err != nil {
		t.Fatal(err)
	}
	return real
}

func tempConfig(t *testing.T, source string) string {
	t.Helper()
	t.Setenv("SKEIN_SOURCE", source)
	return tempConfigWithoutSource(t)
}

func tempConfigWithoutSource(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "config.json"), []byte(`{"configFormat":"alpha"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	real, err := filepath.EvalSymlinks(dir)
	if err != nil {
		t.Fatal(err)
	}
	return real
}

func writeWeaverMetadata(t *testing.T, world config.World, pid int, id string) {
	t.Helper()
	if err := os.MkdirAll(world.StateDir, 0o755); err != nil {
		t.Fatal(err)
	}
	meta := `{"protocol_version":1,"pid":` + intString(pid) + `,"database_path":"` + world.DBPath + `","weaver_id":"` + id + `","config_dir":"` + world.ConfigDir + `","data_dir":"` + world.DataDir + `","socket_path":"` + filepath.Join(world.StateDir, "weaver.sock") + `","started_at":"` + time.Now().UTC().Format(time.RFC3339Nano) + `","nrepl":{"host":"127.0.0.1","port":5555}}`
	if err := os.WriteFile(filepath.Join(world.StateDir, "weaver.json"), []byte(meta), 0o644); err != nil {
		t.Fatal(err)
	}
}

func intString(i int) string { return strconv.Itoa(i) }
