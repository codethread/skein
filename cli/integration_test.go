package cli_test

import (
	"bytes"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestInitBootstrapsConfigDirWorkspaceThroughMill(t *testing.T) {
	cfg := shortTempDir(t)
	source := sourceRoot(t)
	bin := buildStrand(t)
	startMill(t)
	if out, err := outputStrand(bin, cfg, source, "init"); err != nil {
		t.Fatalf("init failed: %v\n%s", err, out)
	}
	configPath := filepath.Join(cfg, "config.json")
	if _, err := os.Stat(configPath); err != nil {
		t.Fatalf("expected config bootstrap: %v", err)
	}
	var cfgFile map[string]any
	b, err := os.ReadFile(configPath)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(b, &cfgFile); err != nil {
		t.Fatal(err)
	}
	if _, ok := cfgFile["source"]; ok || cfgFile["configFormat"] != "alpha" {
		t.Fatalf("unexpected bootstrap config: %#v", cfgFile)
	}
	if _, err := os.Stat(filepath.Join(cfg, "libs.edn")); err != nil {
		t.Fatalf("expected libs.edn bootstrap: %v", err)
	}
	initPath := filepath.Join(cfg, "init.clj")
	if _, err := os.Stat(initPath); err != nil {
		t.Fatalf("expected init.clj bootstrap: %v", err)
	}
	if got := string(mustReadFile(t, initPath)); got != "(require '[skein.libs.alpha :as libs])\n\n(libs/sync!)\n" {
		t.Fatalf("unexpected init.clj bootstrap contents: %q", got)
	}
	if _, err := os.Stat(filepath.Join(cfg, ".git")); !os.IsNotExist(err) {
		t.Fatalf("explicit --config-dir init must not run git init, stat err=%v", err)
	}
}

func TestInitRequiresRunningMill(t *testing.T) {
	cfg := shortTempDir(t)
	t.Setenv("XDG_STATE_HOME", filepath.Join(shortTempDir(t), "state"))
	bin := buildStrand(t)
	out, err := outputStrand(bin, cfg, sourceRoot(t), "init")
	if err == nil || !strings.Contains(out, "mill start") {
		t.Fatalf("expected mill remediation, got out=%q err=%v", out, err)
	}
	if _, err := os.Stat(filepath.Join(cfg, "config.json")); !os.IsNotExist(err) {
		t.Fatalf("init without mill should create no config, stat err=%v", err)
	}
}

func TestGoWeaverLifecycleCommands(t *testing.T) {
	dir := shortTempDir(t)
	writeClientConfig(t, dir)
	bin := buildStrand(t)
	t.Setenv("SKEIN_SOURCE", sourceRoot(t))
	startMill(t)
	runDir := shortTempDir(t)
	if out, err := outputStrand(bin, dir, runDir, "weaver", "start"); err != nil {
		t.Fatalf("start weaver: %v\n%s", err, out)
	}
	waitForStatus(t, bin, dir, runDir, &bytes.Buffer{})
	out, err := outputStrand(bin, dir, runDir, "weaver", "status")
	if err != nil {
		t.Fatal(err)
	}
	var status map[string]any
	if err := json.Unmarshal([]byte(out), &status); err != nil {
		t.Fatalf("status is not json: %v\n%s", err, out)
	}
	realDir, err := filepath.EvalSymlinks(dir)
	if err != nil {
		t.Fatal(err)
	}
	if status["state"] != "running" || status["database_path"] == "" || status["config_dir"] != realDir || status["data_dir"] == "" || status["weaver_id"] == "" || status["socket_path"] == filepath.Join(realDir, "state", "weaver.sock") || status["pid"].(float64) <= 0 {
		t.Fatalf("unexpected status payload: %#v", status)
	}
	if err := runStrand(bin, dir, runDir, "weaver", "stop"); err != nil {
		t.Fatal(err)
	}
	waitForNotRunning(t, bin, dir, runDir)
}

func TestWeaverReplStdinAttachesThroughMillMetadata(t *testing.T) {
	dir := shortTempDir(t)
	writeClientConfig(t, dir)
	bin := buildStrand(t)
	t.Setenv("SKEIN_SOURCE", sourceRoot(t))
	startMill(t)
	runDir := shortTempDir(t)
	if out, err := outputStrand(bin, dir, runDir, "weaver", "start"); err != nil {
		t.Fatalf("weaver start failed: %v\n%s", err, out)
	}
	waitForStatus(t, bin, dir, runDir, &bytes.Buffer{})
	out, err := outputStrandWithInput(bin, dir, runDir, "(strands)\n", "weaver", "repl", "--stdin")
	if err != nil {
		t.Fatalf("repl stdin failed: %v\n%s", err, out)
	}
	if strings.TrimSpace(out) != "[]" {
		t.Fatalf("unexpected repl output: %q", out)
	}
	if out, err := outputStrand(bin, dir, runDir, "weaver", "stop"); err != nil {
		t.Fatalf("weaver stop failed: %v\n%s", err, out)
	}
}

func TestRepoLocalDiscoveryAndInitLocalOverlay(t *testing.T) {
	repo := shortTempDir(t)
	if err := exec.Command("git", "init", repo).Run(); err != nil {
		t.Fatalf("git init repo: %v", err)
	}
	subdir := filepath.Join(repo, "work", "nested")
	if err := os.MkdirAll(subdir, 0755); err != nil {
		t.Fatal(err)
	}
	bin := buildStrand(t)
	startMill(t)
	if out, err := outputStrand(bin, "", subdir, "init"); err != nil {
		t.Fatalf("init failed: %v\n%s", err, out)
	}
	configDir := filepath.Join(repo, ".skein")
	if _, err := os.Stat(filepath.Join(configDir, "config.json")); err != nil {
		t.Fatalf("expected repo .skein config.json: %v", err)
	}
	if _, err := os.Stat(filepath.Join(subdir, ".skein")); !os.IsNotExist(err) {
		t.Fatalf("did not expect nested .skein, stat err=%v", err)
	}
}

func TestMillRoutedStrandAddAndList(t *testing.T) {
	repo := shortTempDir(t)
	if err := exec.Command("git", "init", repo).Run(); err != nil {
		t.Fatalf("git init repo: %v", err)
	}
	bin := buildStrand(t)
	t.Setenv("SKEIN_SOURCE", sourceRoot(t))
	startMill(t)
	if out, err := outputStrand(bin, "", repo, "init"); err != nil {
		t.Fatalf("init failed: %v\n%s", err, out)
	}
	if out, err := outputStrand(bin, "", repo, "weaver", "start"); err != nil {
		t.Fatalf("weaver start failed: %v\n%s", err, out)
	}
	waitForStatus(t, bin, "", repo, &bytes.Buffer{})
	out, err := outputStrand(bin, "", repo, "add", "hello")
	if err != nil || !strings.Contains(out, `"title":"hello"`) {
		t.Fatalf("add output/error = %q/%v", out, err)
	}
	out, err = outputStrand(bin, "", repo, "list")
	if err != nil || !strings.Contains(out, `"title":"hello"`) {
		t.Fatalf("list output/error = %q/%v", out, err)
	}
	if out, err := outputStrand(bin, "", repo, "weaver", "stop"); err != nil {
		t.Fatalf("weaver stop failed: %v\n%s", err, out)
	}
}

func TestTaskAndQueryCommandsRunOutsideCheckoutWithoutSource(t *testing.T) {
	t.Skip("ordinary command routing moves to mill in a later slice")
	dir := shortTempDir(t)
	writeClientConfig(t, dir)
	initPath := filepath.Join(dir, "init.clj")
	init := `(require '[skein.weaver.api :as api] '[skein.weaver.runtime :as runtime])
(api/register-query @runtime/current-runtime 'by-owner {:params [:owner] :where [:= [:attr :owner] [:param :owner]]})`
	if err := os.WriteFile(initPath, []byte(init), 0644); err != nil {
		t.Fatal(err)
	}
	bin := buildStrand(t)
	runDir := shortTempDir(t)
	weaver := exec.Command(bin, "--config-dir", dir, "weaver", "start")
	weaver.Dir = runDir
	var weaverOut bytes.Buffer
	weaver.Stdout = &weaverOut
	weaver.Stderr = &weaverOut
	if err := weaver.Start(); err != nil {
		t.Fatalf("start weaver: %v", err)
	}
	t.Cleanup(func() { _ = weaver.Process.Kill(); _, _ = weaver.Process.Wait() })
	waitForWeaverAndInit(t, bin, dir, runDir, &weaverOut)
	if err := os.WriteFile(filepath.Join(dir, "config.json"), []byte(`{"configFormat":"alpha"}`), 0644); err != nil {
		t.Fatal(err)
	}
	if out, err := outputStrand(bin, dir, runDir, "weaver", "status"); err != nil || !strings.Contains(out, `"healthy":true`) {
		t.Fatalf("status after source removal output/error = %q/%v", out, err)
	}
	design := addJSON(t, bin, dir, runDir, "Design", "owner=agent")
	docs := addJSON(t, bin, dir, runDir, "Docs", "owner=agent")
	if out, err := outputStrand(bin, dir, runDir, "show", docs); err != nil || !strings.Contains(out, `"title":"Docs"`) {
		t.Fatalf("show output/error = %q/%v", out, err)
	}
	if err := runStrand(bin, dir, runDir, "update", docs, "--edge", "depends-on:"+design, "--attr", "phase=write"); err != nil {
		t.Fatal(err)
	}
	if err := runStrand(bin, dir, runDir, "update", design, "--state", "closed"); err != nil {
		t.Fatal(err)
	}
	if out, err := outputStrand(bin, dir, runDir, "list", "--state", "closed"); err != nil || !strings.Contains(out, design) || strings.Contains(out, docs) {
		t.Fatalf("list state=closed output/error = %q/%v", out, err)
	}
	if out, err := outputStrand(bin, dir, runDir, "list", "--query", "by-owner", "--param", "owner=agent"); err != nil || !strings.Contains(out, docs) || !strings.Contains(out, design) {
		t.Fatalf("list query output/error = %q/%v", out, err)
	}
	if out, err := outputStrand(bin, dir, runDir, "list", "--query", "by-owner", "--param", "owner=agent", "--state", "closed"); err != nil || !strings.Contains(out, design) || strings.Contains(out, docs) {
		t.Fatalf("list query state=closed output/error = %q/%v", out, err)
	}
	if out, err := outputStrand(bin, dir, runDir, "ready", "--query", "by-owner", "--param", "owner=agent"); err != nil || !strings.Contains(out, docs) || strings.Contains(out, design) {
		t.Fatalf("ready query output/error = %q/%v", out, err)
	}
	if err := runStrand(bin, dir, runDir, "weaver", "stop"); err != nil {
		t.Fatal(err)
	}
	if err := weaver.Wait(); err != nil {
		t.Fatalf("weaver did not exit cleanly: %v\n%s", err, weaverOut.String())
	}
}

func shortTempDir(t *testing.T) string {
	t.Helper()
	dir, err := os.MkdirTemp("/tmp", "td-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	return dir
}

func buildStrand(t *testing.T) string {
	t.Helper()
	bin := filepath.Join(shortTempDir(t), "strand")
	cmd := exec.Command("go", "build", "-o", bin, "./cmd/strand")
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	if err := cmd.Run(); err != nil {
		t.Fatalf("build strand: %v\n%s", err, out.String())
	}
	return bin
}

func buildMill(t *testing.T) string {
	t.Helper()
	bin := filepath.Join(shortTempDir(t), "mill")
	cmd := exec.Command("go", "build", "-o", bin, "./cmd/mill")
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	if err := cmd.Run(); err != nil {
		t.Fatalf("build mill: %v\n%s", err, out.String())
	}
	return bin
}

func startMill(t *testing.T) {
	t.Helper()
	t.Setenv("XDG_STATE_HOME", filepath.Join(shortTempDir(t), "state"))
	mill := exec.Command(buildMill(t), "start")
	var out bytes.Buffer
	mill.Stdout = &out
	mill.Stderr = &out
	if err := mill.Start(); err != nil {
		t.Fatalf("start mill: %v", err)
	}
	t.Cleanup(func() { _ = mill.Process.Signal(os.Interrupt); _, _ = mill.Process.Wait() })
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if strings.Contains(out.String(), "error:") {
			t.Fatalf("mill failed: %s", out.String())
		}
		root := filepath.Join(os.Getenv("XDG_STATE_HOME"), "skein")
		if _, err := os.Stat(filepath.Join(root, "mill.json")); err == nil {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("mill did not publish metadata: %s", out.String())
}

func mustReadFile(t *testing.T, path string) []byte {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func addJSON(t *testing.T, bin, configDir, cwd, title, attr string) string {
	t.Helper()
	out, err := outputStrand(bin, configDir, cwd, "add", title, "--attr", attr)
	if err != nil {
		t.Fatalf("add %s: %v\n%s", title, err, out)
	}
	return parseAddedID(t, out)
}

func parseAddedID(t *testing.T, out string) string {
	t.Helper()
	var row map[string]any
	if err := json.Unmarshal([]byte(out), &row); err != nil {
		t.Fatalf("add output is not json: %v\n%s", err, out)
	}
	id, ok := row["id"].(string)
	if !ok || id == "" {
		t.Fatalf("add output missing id: %#v", row)
	}
	return id
}

func writeClientConfig(t *testing.T, dir string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, "config.json"), []byte(`{"configFormat":"alpha"}`), 0644); err != nil {
		t.Fatal(err)
	}
}

func sourceRoot(t *testing.T) string {
	t.Helper()
	source, err := filepath.Abs("..")
	if err != nil {
		t.Fatal(err)
	}
	return source
}
func waitForStatus(t *testing.T, bin, configDir, cwd string, weaverErr *bytes.Buffer) {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second)
	var lastErr error
	var lastOut string
	for time.Now().Before(deadline) {
		out, err := outputStrand(bin, configDir, cwd, "weaver", "status")
		if err == nil {
			var status map[string]any
			if json.Unmarshal([]byte(out), &status) == nil && status["state"] == "running" {
				return
			}
			lastOut = out
		} else {
			lastErr = err
			lastOut = out
		}
		time.Sleep(250 * time.Millisecond)
	}
	t.Fatalf("weaver did not become ready: %v\nlast status: %s\n%s", lastErr, lastOut, weaverErr.String())
}
func waitForNotRunning(t *testing.T, bin, configDir, cwd string) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	var lastOut string
	for time.Now().Before(deadline) {
		out, err := outputStrand(bin, configDir, cwd, "weaver", "status")
		lastOut = out
		if err == nil {
			var status map[string]any
			if json.Unmarshal([]byte(out), &status) == nil && status["state"] != "running" {
				return
			}
		}
		time.Sleep(250 * time.Millisecond)
	}
	t.Fatalf("weaver still running after stop; last status: %s", lastOut)
}

func waitForWeaverAndInit(t *testing.T, bin, configDir, cwd string, weaverErr *bytes.Buffer) {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second)
	var lastErr error
	for time.Now().Before(deadline) {
		if err := runStrand(bin, configDir, cwd, "init"); err == nil {
			return
		} else {
			lastErr = err
		}
		time.Sleep(250 * time.Millisecond)
	}
	t.Fatalf("weaver did not become ready: %v\n%s", lastErr, weaverErr.String())
}
func runStrand(bin, configDir, cwd string, args ...string) error {
	_, err := outputStrand(bin, configDir, cwd, args...)
	return err
}
func outputStrand(bin, configDir, cwd string, args ...string) (string, error) {
	return outputStrandWithInput(bin, configDir, cwd, "", args...)
}

func outputStrandWithInput(bin, configDir, cwd, stdin string, args ...string) (string, error) {
	if configDir != "" {
		args = append([]string{"--config-dir", configDir}, args...)
	}
	cmd := exec.Command(bin, args...)
	cmd.Dir = cwd
	if stdin != "" {
		cmd.Stdin = strings.NewReader(stdin)
	}
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	if err := cmd.Run(); err != nil {
		return out.String(), err
	}
	return out.String(), nil
}
