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

func TestInitBootstrapsConfigDirWorkspaceAndStartsWeaver(t *testing.T) {
	cfg := shortTempDir(t)
	source, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	source = filepath.Join(source, "..")
	source = filepath.Clean(source)
	source, err = filepath.Abs(source)
	if err != nil {
		t.Fatal(err)
	}
	bin := buildStrand(t)
	out, err := outputStrand(bin, cfg, source, "init")
	if err == nil || (!strings.Contains(out, "weaver socket unreachable") && !strings.Contains(out, "no running weaver")) {
		t.Fatalf("expected weaver connection error on init, got out=%q err=%v", out, err)
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
	if cfgFile["source"] != source || cfgFile["configFormat"] != "alpha" {
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
	if _, err := os.Stat(filepath.Join(cfg, ".git")); err != nil {
		t.Fatalf("expected .git bootstrap: %v", err)
	}

	weaver := exec.Command(bin, "--config-dir", cfg, "weaver", "start")
	weaver.Dir = source
	var weaverOut bytes.Buffer
	weaver.Stdout = &weaverOut
	weaver.Stderr = &weaverOut
	if err := weaver.Start(); err != nil {
		t.Fatalf("start weaver: %v", err)
	}
	t.Cleanup(func() { _ = weaver.Process.Kill(); _, _ = weaver.Process.Wait() })
	waitForStatus(t, bin, cfg, source, &weaverOut)
	if err := runStrand(bin, cfg, source, "weaver", "stop"); err != nil {
		t.Fatal(err)
	}
	if err := weaver.Wait(); err != nil {
		t.Fatalf("weaver did not exit cleanly: %v\n%s", err, weaverOut.String())
	}
}

func TestGoWeaverLifecycleCommands(t *testing.T) {
	dir := shortTempDir(t)
	writeClientConfig(t, dir)
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
	waitForStatus(t, bin, dir, runDir, &weaverOut)
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
	if status["healthy"] != true || status["database_path"] == "" || status["config_dir"] != realDir || status["data_dir"] == "" || status["weaver_id"] == "" || status["socket_path"] != filepath.Join(realDir, "state", "weaver.sock") || status["pid"].(float64) <= 0 {
		t.Fatalf("unexpected status payload: %#v", status)
	}
	if err := runStrand(bin, dir, runDir, "weaver", "stop"); err != nil {
		t.Fatal(err)
	}
	if err := weaver.Wait(); err != nil {
		t.Fatalf("weaver did not exit cleanly: %v\n%s", err, weaverOut.String())
	}
	if _, err := outputStrand(bin, dir, runDir, "weaver", "status"); err == nil {
		t.Fatal("expected status to fail after stop cleanup")
	}
}

func TestTaskAndQueryCommandsRunOutsideCheckoutWithoutSource(t *testing.T) {
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
	source, err := filepath.Abs("..")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "config.json"), []byte(`{"configFormat":"alpha","source":`+quote(source)+`}`), 0644); err != nil {
		t.Fatal(err)
	}
}
func quote(s string) string { b, _ := json.Marshal(s); return string(b) }
func waitForStatus(t *testing.T, bin, configDir, cwd string, weaverErr *bytes.Buffer) {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second)
	var lastErr error
	for time.Now().Before(deadline) {
		if _, err := outputStrand(bin, configDir, cwd, "weaver", "status"); err == nil {
			return
		} else {
			lastErr = err
		}
		time.Sleep(250 * time.Millisecond)
	}
	t.Fatalf("weaver did not become ready: %v\n%s", lastErr, weaverErr.String())
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
	full := append([]string{"--config-dir", configDir}, args...)
	cmd := exec.Command(bin, full...)
	cmd.Dir = cwd
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	if err := cmd.Run(); err != nil {
		return out.String(), err
	}
	return out.String(), nil
}
