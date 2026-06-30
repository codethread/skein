package command

import (
	"bytes"
	"encoding/json"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"skein-strand-cli/internal/client"
	"skein-strand-cli/internal/config"
)

func run(args ...string) (string, error) {
	return runWithStdin("", args...)
}

func runWithStdin(stdin string, args ...string) (string, error) {
	origMill := millCall
	millCall = func(operation string, world client.MillWorldRequest) (any, error) {
		if operation == "init" {
			w, err := config.BootstrapWorld(world.CWD, world.ConfigDir, world.Source)
			if err != nil {
				return nil, err
			}
			return map[string]any{"config_dir": w.ConfigDir}, nil
		}
		return origMill(operation, world)
	}
	defer func() { millCall = origMill }()
	var out, er bytes.Buffer
	app := New(&out, &er)
	app.Stdin = strings.NewReader(stdin)
	err := app.Run(args)
	return out.String() + er.String(), err
}

func TestHelpIncludesCommandTree(t *testing.T) {
	root, err := run("--help")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"Available Commands:", "init", "add", "update", "show", "supersede", "burn", "list", "ready", "graph", "weave", "pattern", "op", "weaver"} {
		if !strings.Contains(root, want) {
			t.Fatalf("root help missing %q in:\n%s", want, root)
		}
	}
	add, err := run("add", "--help")
	if err != nil {
		t.Fatal(err)
	}
	initHelp, err := run("init", "--help")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(initHelp, "selected config workspace") || strings.Contains(initHelp, "--source") {
		t.Fatalf("init help should describe workspace bootstrap and omit --source:\n%s", initHelp)
	}

	for _, want := range []string{"add <title>", "--state", "--attr", "--attr-file", "--attr-stdin", "--attributes-stdin"} {
		if !strings.Contains(add, want) {
			t.Fatalf("add help missing %q in:\n%s", want, add)
		}
	}
	for _, command := range []string{"add", "update", "list"} {
		help, err := run(command, "--help")
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(help, "--state") || strings.Contains(help, "--"+"active") {
			t.Fatalf("%s help should expose --state and omit old lifecycle flag:\n%s", command, help)
		}
	}

	supersede, err := run("supersede", "--help")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"supersede <old-id> <replacement-id>", "supersedes", "marks old replaced", "update --edge"} {
		if !strings.Contains(supersede, want) {
			t.Fatalf("supersede help missing %q in:\n%s", want, supersede)
		}
	}

	weaver, err := run("weaver", "--help")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"start", "status", "stop", "repl"} {
		if !strings.Contains(weaver, want) {
			t.Fatalf("weaver help missing %q in:\n%s", want, weaver)
		}
	}

	start, err := run("weaver", "start", "--help")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"start", "--config-dir"} {
		if !strings.Contains(start, want) {
			t.Fatalf("weaver start help missing %q in:\n%s", want, start)
		}
	}
}

func TestRejectsRemovedAndMalformedInputs(t *testing.T) {
	cases := [][]string{
		{"--format", "json", "list"},
		{"init", "--source", "/tmp/skein"},
		{"list", "--where", "[:= :status \"strand\"]"},
		{"list", "--where", ""},
		{"list", "extra"},
		{"ready", "--query", "q", "extra"},
		{"ready", "--query", ""},
		{"add", "x", "extra"},
		{"add", "x", "--status", "bogus"},
		{"add", "x", "--" + "active", "false"},
		{"add", "x", "--state", "replaced"},
		{"add", "x", "--priority", "high"},
		{"add", "x", "--parent", "id"},
		{"add", "x", "--attr", "novalue"},
		{"update", "id", "extra"},
		{"update", "id", "--edge", "depends-on"},
		{"update", "id", "--edge", ":target"},
		{"update", "id", "--edge", "depends-on:"},
		{"update", "id", "--" + "active", "false"},
		{"update", "id", "--state", "replaced"},
		{"update", "id", "--priority", "high"},
		{"supersede", "old"},
		{"supersede", "old", "new", "extra"},
		{"list", "--" + "active", "false"},
		{"list", "--state", "bogus"},
		{"list", "--param", "novalue"},
		{"weave"},
		{"weave", "extra", "--pattern", "x"},
		{"pattern", "explain"},
		{"pattern", "explain", "x", "extra"},
		{"graph", "subgraph"},
		{"graph", "subgraph", "root", "extra"},
		{"op"},
		{"pattern", "list", "extra"},
	}
	for _, c := range cases {
		if _, err := run(c...); err == nil {
			t.Fatalf("expected error for %v", c)
		}
	}
}

func TestPatternListCommandPassesThroughToSocketClientPayloads(t *testing.T) {
	cfg := testConfig(t)
	orig := newClient
	fc := &fakeClient{result: []any{}}
	newClient = func(o Options) Caller { return fc }
	t.Cleanup(func() { newClient = orig })
	out, err := run("--config-dir", cfg, "pattern", "list")
	if err != nil {
		t.Fatal(err)
	}
	if strings.TrimSpace(out) != "[]" {
		t.Fatalf("expected empty pattern list output: %q", out)
	}
	if len(fc.calls) != 1 || fc.calls[0].op != "pattern-list" || !reflect.DeepEqual(fc.calls[0].args, map[string]any{}) {
		t.Fatalf("bad pattern-list call: %#v", fc.calls)
	}
}

func TestConfigDirPrecedenceAndValidation(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "config.json"), []byte(`{"configFormat":"alpha"}`), 0644); err != nil {
		t.Fatal(err)
	}
	var captured Options
	orig := newClient
	newClient = func(o Options) Caller {
		captured = o
		return &fakeClient{result: []any{}}
	}
	t.Cleanup(func() { newClient = orig })
	if _, err := run("--config-dir", dir, "list"); err != nil {
		t.Fatal(err)
	}
	if captured.Source != "" || captured.ConfigDir != dir || !captured.ConfigDirExplicit {
		t.Fatalf("unexpected forwarding options: %#v", captured)
	}

	if _, err := run("--config-path", dir, "list"); err == nil || !strings.Contains(err.Error(), "unknown flag: --config-path") {
		t.Fatalf("expected removed config-path error, got %v", err)
	}

}

func TestInitBootstrapsWorkspaceWhenMissingWithoutWeaverInit(t *testing.T) {
	cfg := t.TempDir()
	source := t.TempDir()
	if err := os.WriteFile(filepath.Join(source, "deps.edn"), []byte(`{}`), 0644); err != nil {
		t.Fatal(err)
	}
	oldWD, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chdir(source); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chdir(oldWD) })
	var clientCalled bool
	origClient := newClient
	newClient = func(o Options) Caller {
		clientCalled = true
		return &fakeClient{}
	}
	t.Cleanup(func() { newClient = origClient })
	if _, err := run("--config-dir", cfg, "init"); err != nil {
		t.Fatal(err)
	}
	if clientCalled {
		t.Fatal("init must not call weaver init")
	}
	if _, err := os.Stat(filepath.Join(cfg, ".git")); !os.IsNotExist(err) {
		t.Fatalf("explicit --config-dir init must not run git init, stat err=%v", err)
	}
	cfgFile := filepath.Join(cfg, "config.json")
	if _, err := os.Stat(cfgFile); err != nil {
		t.Fatalf("missing config.json: %v", err)
	}
	var c map[string]any
	f, err := os.ReadFile(cfgFile)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(f, &c); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(c, map[string]any{"configFormat": "alpha"}) {
		t.Fatalf("unexpected config fields: %#v", c)
	}
	if _, err := os.Stat(filepath.Join(cfg, "libs")); err != nil {
		t.Fatalf("missing libs directory: %v", err)
	}
	if _, err := os.Stat(filepath.Join(cfg, "libs.edn")); err != nil {
		t.Fatalf("missing libs.edn: %v", err)
	}
	initPath := filepath.Join(cfg, "init.clj")
	if _, err := os.Stat(initPath); err != nil {
		t.Fatalf("missing init.clj: %v", err)
	}
	if got := string(mustReadFile(t, initPath)); got != config.DefaultInitCLJ {
		t.Fatalf("unexpected init.clj contents: %q", got)
	}
	if got := string(mustReadFile(t, filepath.Join(cfg, ".gitignore"))); got != config.DefaultSkeinGitignore {
		t.Fatalf("unexpected .gitignore contents: %q", got)
	}
}

func TestInitValidatesExistingConfigButDoesNotRewriteMissingKeys(t *testing.T) {
	cfg := t.TempDir()
	source := t.TempDir()
	if err := os.WriteFile(filepath.Join(source, "deps.edn"), []byte(`{}`), 0644); err != nil {
		t.Fatal(err)
	}
	oldWD, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chdir(source); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chdir(oldWD) })
	if err := os.MkdirAll(filepath.Join(cfg, ".git"), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(cfg, "libs"), 0755); err != nil {
		t.Fatal(err)
	}
	original := `{"configFormat":"alpha"}`
	if err := os.WriteFile(filepath.Join(cfg, "config.json"), []byte(original), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(cfg, "libs.edn"), []byte("{:libs {}}\n"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(cfg, "init.clj"), []byte("(comment \"custom\")\n"), 0644); err != nil {
		t.Fatal(err)
	}
	origClient := newClient
	newClient = func(o Options) Caller { return &fakeClient{} }
	t.Cleanup(func() { newClient = origClient })
	if _, err := run("--config-dir", cfg, "init"); err != nil {
		t.Fatalf("init failed: %v", err)
	}
	raw, err := os.ReadFile(filepath.Join(cfg, "config.json"))
	if err != nil {
		t.Fatal(err)
	}
	if string(raw) != original {
		t.Fatalf("config should not be rewritten\nexpected: %q\nactual: %q", original, string(raw))
	}
	initPath := filepath.Join(cfg, "init.clj")
	if got := string(mustReadFile(t, initPath)); got != "(comment \"custom\")\n" {
		t.Fatalf("init.clj should not be overwritten, got: %q", got)
	}
}

func TestWeaverStartRoutesThroughMill(t *testing.T) {
	cfg := t.TempDir()
	orig := millCall
	var operation string
	var world client.MillWorldRequest
	millCall = func(op string, req client.MillWorldRequest) (any, error) {
		operation = op
		world = req
		return map[string]any{"state": "starting"}, nil
	}
	t.Cleanup(func() { millCall = orig })
	out, err := run("--config-dir", cfg, "weaver", "start")
	if err != nil {
		t.Fatal(err)
	}
	if operation != "weaver-start" || world.ConfigDir != cfg || world.CWD == "" || strings.TrimSpace(out) != `{"state":"starting"}` {
		t.Fatalf("unexpected mill route op=%s world=%#v out=%q", operation, world, out)
	}
}

func TestWeaverReplUsesMillReturnedSource(t *testing.T) {
	cfg := t.TempDir()
	if err := os.WriteFile(filepath.Join(cfg, "config.json"), []byte(`{"configFormat":"alpha"}`), 0644); err != nil {
		t.Fatal(err)
	}
	source := tempSource(t)
	stateDir := filepath.Join(t.TempDir(), "state")
	origMill := millCall
	var operation string
	var world client.MillWorldRequest
	millCall = func(op string, req client.MillWorldRequest) (any, error) {
		operation = op
		world = req
		return map[string]any{"state": "running", "source": source, "state_dir": stateDir}, nil
	}
	origRun := runReplProcess
	var launched Options
	var launchedStdin bool
	runReplProcess = func(o Options, stdin bool, in io.Reader, out, errOut io.Writer) error {
		launched = o
		launchedStdin = stdin
		return nil
	}
	t.Cleanup(func() { millCall = origMill; runReplProcess = origRun })
	if _, err := run("--config-dir", cfg, "weaver", "repl", "--stdin"); err != nil {
		t.Fatal(err)
	}
	realCfg, err := filepath.EvalSymlinks(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if operation != "weaver-repl-context" || world.ConfigDir != realCfg || world.CWD == "" {
		t.Fatalf("unexpected mill route op=%s world=%#v", operation, world)
	}
	if launched.Source != source || launched.StateDir != stateDir || launched.ConfigDir != realCfg || !launchedStdin {
		t.Fatalf("unexpected repl launch options=%#v stdin=%v", launched, launchedStdin)
	}
}

func TestDiscoveredWeaverReplUsesMillReturnedSourceWithoutConfigSource(t *testing.T) {
	repo := t.TempDir()
	runGitCommand(t, repo, "init")
	cfg := filepath.Join(repo, ".skein")
	if err := os.MkdirAll(cfg, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(cfg, "config.json"), []byte(`{"configFormat":"alpha"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	withChdir(t, repo)
	source := tempSource(t)
	stateDir := filepath.Join(t.TempDir(), "state")
	origMill := millCall
	var world client.MillWorldRequest
	millCall = func(op string, req client.MillWorldRequest) (any, error) {
		if op != "weaver-repl-context" {
			t.Fatalf("unexpected mill op: %s", op)
		}
		world = req
		return map[string]any{"state": "running", "source": source, "state_dir": stateDir}, nil
	}
	origRun := runReplProcess
	var launched Options
	runReplProcess = func(o Options, stdin bool, in io.Reader, out, errOut io.Writer) error {
		launched = o
		return nil
	}
	t.Cleanup(func() { millCall = origMill; runReplProcess = origRun })
	if _, err := run("weaver", "repl", "--stdin"); err != nil {
		t.Fatal(err)
	}
	realCfg, err := filepath.EvalSymlinks(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if world.ConfigDir != realCfg || world.CWD == "" {
		t.Fatalf("unexpected discovered world request: %#v", world)
	}
	if launched.ConfigDir != realCfg || launched.Source != source || launched.StateDir != stateDir {
		t.Fatalf("unexpected discovered repl launch options: %#v", launched)
	}
}

func TestWeaverReplStoppedStateDoesNotRequireSource(t *testing.T) {
	cfg := testConfig(t)
	origMill := millCall
	millCall = func(op string, req client.MillWorldRequest) (any, error) {
		if op != "weaver-repl-context" {
			t.Fatalf("unexpected mill op: %s", op)
		}
		return map[string]any{"state": "none", "state_dir": filepath.Join(t.TempDir(), "state")}, nil
	}
	origRun := runReplProcess
	runReplProcess = func(o Options, stdin bool, in io.Reader, out, errOut io.Writer) error {
		t.Fatal("repl process should not launch when weaver is stopped")
		return nil
	}
	t.Cleanup(func() { millCall = origMill; runReplProcess = origRun })
	if _, err := run("--config-dir", cfg, "weaver", "repl"); err == nil || !strings.Contains(err.Error(), "start one with: strand weaver start") {
		t.Fatalf("expected weaver start remediation, got %v", err)
	}
}

func TestWeaverStatusAndStopRouteThroughMill(t *testing.T) {
	cfg := t.TempDir()
	orig := millCall
	var operations []string
	millCall = func(op string, req client.MillWorldRequest) (any, error) {
		operations = append(operations, op)
		if req.ConfigDir != cfg || req.CWD == "" {
			t.Fatalf("unexpected world request: %#v", req)
		}
		return map[string]any{"state": "running"}, nil
	}
	t.Cleanup(func() { millCall = orig })
	if _, err := run("--config-dir", cfg, "weaver", "status"); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--config-dir", cfg, "weaver", "stop"); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(operations, []string{"weaver-status", "weaver-stop"}) {
		t.Fatalf("unexpected operations: %#v", operations)
	}
}

func TestOrdinaryCommandsRequireMill(t *testing.T) {
	cfg := testConfig(t)
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	if _, err := run("--config-dir", cfg, "list"); err == nil || !strings.Contains(err.Error(), "start one with: mill start") {
		t.Fatalf("expected mill remediation, got %v", err)
	}
}

func TestWeaverStartRequiresMill(t *testing.T) {
	cfg := t.TempDir()
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	if _, err := run("--config-dir", cfg, "weaver", "start"); err == nil || !strings.Contains(err.Error(), "start one with: mill start") {
		t.Fatalf("expected mill remediation, got %v", err)
	}
}

func TestWeaverReplRequiresMill(t *testing.T) {
	cfg := t.TempDir()
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	if err := os.WriteFile(filepath.Join(cfg, "config.json"), []byte(`{"configFormat":"alpha"}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--config-dir", cfg, "weaver", "repl"); err == nil || !strings.Contains(err.Error(), "start one with: mill start") {
		t.Fatalf("expected mill remediation, got %v", err)
	}
}

func TestRepoWorldDiscoveryFromSubdirectory(t *testing.T) {
	repo := t.TempDir()
	cfg := filepath.Join(repo, ".skein")
	if err := os.MkdirAll(filepath.Join(repo, "a", "b"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(cfg, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(cfg, "config.json"), []byte(`{"configFormat":"alpha"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	withChdir(t, filepath.Join(repo, "a", "b"))
	orig := newClient
	var captured Options
	newClient = func(o Options) Caller {
		captured = o
		return &fakeClient{result: []any{}}
	}
	t.Cleanup(func() { newClient = orig })
	if _, err := run("list"); err != nil {
		t.Fatal(err)
	}
	if captured.ConfigDir != "" || captured.Source != "" || captured.ConfigDirExplicit {
		t.Fatalf("unexpected discovered options: %#v", captured)
	}
}

func TestNoConfigDirOrdinaryCommandRequiresMillFirst(t *testing.T) {
	withChdir(t, t.TempDir())
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	if _, err := run("list"); err == nil || !strings.Contains(err.Error(), "start one with: mill start") {
		t.Fatalf("expected mill remediation failure, got %v", err)
	}
}

func TestDiscoveredWeaverLifecycleRequiresMill(t *testing.T) {
	repo := t.TempDir()
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	cfg := filepath.Join(repo, ".skein")
	if err := os.MkdirAll(cfg, 0o755); err != nil {
		t.Fatal(err)
	}
	withChdir(t, repo)
	if _, err := run("weaver", "start"); err == nil || !strings.Contains(err.Error(), "start one with: mill start") {
		t.Fatalf("expected mill remediation, got %v", err)
	}
}

func TestDiscoveredIncompleteWorldOrdinaryCommandRequiresMill(t *testing.T) {
	repo := t.TempDir()
	cfg := filepath.Join(repo, ".skein")
	if err := os.MkdirAll(cfg, 0o755); err != nil {
		t.Fatal(err)
	}
	withChdir(t, repo)
	t.Setenv("XDG_STATE_HOME", filepath.Join(t.TempDir(), "state"))
	if _, err := run("list"); err == nil || !strings.Contains(err.Error(), "start one with: mill start") {
		t.Fatalf("expected mill remediation, got %v", err)
	}
}

func TestDiscoveredWeaverStartSendsCwdToMill(t *testing.T) {
	repo := t.TempDir()
	subdir := filepath.Join(repo, "pkg")
	if err := os.MkdirAll(subdir, 0o755); err != nil {
		t.Fatal(err)
	}
	withChdir(t, subdir)
	orig := millCall
	var captured client.MillWorldRequest
	millCall = func(op string, req client.MillWorldRequest) (any, error) {
		captured = req
		return map[string]any{"state": "none"}, nil
	}
	t.Cleanup(func() { millCall = orig })
	if _, err := run("weaver", "start"); err != nil {
		t.Fatal(err)
	}
	realSubdir, err := filepath.EvalSymlinks(subdir)
	if err != nil {
		t.Fatal(err)
	}
	if captured.ConfigDir != "" || captured.CWD != realSubdir {
		t.Fatalf("unexpected discovered mill request: %#v", captured)
	}
}

func TestInitUsesGitRootForImplicitRepoWorld(t *testing.T) {
	repo := t.TempDir()
	runGitCommand(t, repo, "init")
	nested := filepath.Join(repo, "nested", "dir")
	if err := os.MkdirAll(nested, 0o755); err != nil {
		t.Fatal(err)
	}
	withChdir(t, nested)
	origClient := newClient
	newClient = func(o Options) Caller { return &fakeClient{} }
	t.Cleanup(func() { newClient = origClient })
	if _, err := run("init"); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(repo, ".skein", "config.json")); err != nil {
		t.Fatalf("expected repo-root config.json: %v", err)
	}
	if _, err := os.Stat(filepath.Join(nested, ".skein")); !os.IsNotExist(err) {
		t.Fatalf("did not expect nested .skein, stat err=%v", err)
	}
}

func TestInitDoesNotPassCallerEnvSourceToMill(t *testing.T) {
	t.Setenv("SKEIN_SOURCE", tempSource(t))
	cfg := t.TempDir()
	orig := millCall
	var captured client.MillWorldRequest
	millCall = func(operation string, world client.MillWorldRequest) (any, error) {
		captured = world
		return map[string]any{}, nil
	}
	t.Cleanup(func() { millCall = orig })
	var out, er bytes.Buffer
	app := New(&out, &er)
	if err := app.Run([]string{"--config-dir", cfg, "init"}); err != nil {
		t.Fatal(err)
	}
	if captured.Source != "" {
		t.Fatalf("init should not pass source to mill, got %#v", captured)
	}
}

func TestInitOutsideGitFailsWithoutCreatingCwdWorld(t *testing.T) {
	dir := t.TempDir()
	withChdir(t, dir)
	if _, err := run("init"); err == nil || !strings.Contains(err.Error(), "requires cwd inside a supported non-bare Git worktree") {
		t.Fatalf("expected outside-git failure, got %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, ".skein")); !os.IsNotExist(err) {
		t.Fatalf("did not expect cwd .skein, stat err=%v", err)
	}
}

func TestInitWritesMarkerConfigAndDoesNotOverwriteBootstrapFiles(t *testing.T) {
	withChdir(t, tempSource(t))
	t.Setenv("SKEIN_SOURCE", tempSource(t))
	cfg := t.TempDir()
	if err := os.WriteFile(filepath.Join(cfg, "libs.edn"), []byte("custom libs"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(cfg, "init.clj"), []byte("custom init"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(cfg, ".gitignore"), []byte("custom ignore"), 0o644); err != nil {
		t.Fatal(err)
	}
	origClient := newClient
	newClient = func(o Options) Caller { return &fakeClient{} }
	t.Cleanup(func() { newClient = origClient })
	if _, err := run("--config-dir", cfg, "init"); err != nil {
		t.Fatal(err)
	}
	var c configFileForTest
	readJSONFile(t, filepath.Join(cfg, "config.json"), &c)
	if c.ConfigFormat != "alpha" || c.Source != "" {
		t.Fatalf("unexpected marker config: %#v", c)
	}
	for path, want := range map[string]string{"libs.edn": "custom libs", "init.clj": "custom init", ".gitignore": "custom ignore"} {
		if got := string(mustReadFile(t, filepath.Join(cfg, path))); got != want {
			t.Fatalf("%s overwritten: %q", path, got)
		}
	}
}

func mustReadFile(t *testing.T, path string) []byte {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func withChdir(t *testing.T, dir string) {
	t.Helper()
	oldWD, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chdir(dir); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chdir(oldWD) })
}

func tempSource(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "deps.edn"), []byte(`{}`), 0o644); err != nil {
		t.Fatal(err)
	}
	return dir
}

func runGitCommand(t *testing.T, dir string, args ...string) {
	t.Helper()
	cmd := exec.Command("git", args...)
	cmd.Dir = dir
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	if err := cmd.Run(); err != nil {
		t.Fatalf("git %v failed: %v\n%s", args, err, out.String())
	}
}

type configFileForTest struct {
	ConfigFormat string `json:"configFormat"`
	Source       string `json:"source"`
}

func readJSONFile(t *testing.T, path string, into any) {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(raw, into); err != nil {
		t.Fatal(err)
	}
}

type fakeClient struct {
	calls  []fakeCall
	result any
	err    error
}
type fakeCall struct {
	op   string
	args map[string]any
}

func (f *fakeClient) Call(op string, args map[string]any) (any, error) {
	f.calls = append(f.calls, fakeCall{op, args})
	if f.err != nil {
		return nil, f.err
	}
	if f.result != nil {
		return f.result, nil
	}
	return map[string]any{"id": "task-1", "title": "Write docs", "state": "active", "attributes": map[string]any{}}, nil
}

func testConfig(t *testing.T) string {
	t.Helper()
	path := t.TempDir()
	if err := os.WriteFile(filepath.Join(path, "config.json"), []byte(`{"configFormat":"alpha"}`), 0644); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestQueryCommandsUseSocketClientPayloads(t *testing.T) {
	cfg := testConfig(t)
	orig := newClient
	fc := &fakeClient{result: []any{map[string]any{"id": "task-1"}}}
	newClient = func(o Options) Caller { return fc }
	t.Cleanup(func() { newClient = orig })
	out, err := run("--config-dir", cfg, "list")
	if err != nil {
		t.Fatal(err)
	}
	if strings.TrimSpace(out) != `[{"id":"task-1"}]` {
		t.Fatalf("unexpected list output: %q", out)
	}
	out, err = run("--config-dir", cfg, "list", "--state", "closed")
	if err != nil {
		t.Fatal(err)
	}
	out, err = run("--config-dir", cfg, "ready", "--query", "by-owner", "--param", "owner=agent")
	if err != nil {
		t.Fatal(err)
	}
	if len(fc.calls) != 3 {
		t.Fatalf("calls = %#v", fc.calls)
	}
	if fc.calls[0].op != "list" || !reflect.DeepEqual(fc.calls[0].args, map[string]any{}) {
		t.Fatalf("bad list call: %#v", fc.calls[0])
	}
	if fc.calls[1].op != "list" || !reflect.DeepEqual(fc.calls[1].args, map[string]any{"state": "closed"}) {
		t.Fatalf("bad list state call: %#v", fc.calls[1])
	}
	expected := map[string]any{"query": "by-owner", "params": map[string]any{"owner": "agent"}}
	if fc.calls[2].op != "ready-query" || !reflect.DeepEqual(fc.calls[2].args, expected) {
		t.Fatalf("bad ready-query call: %#v", fc.calls[2])
	}
	fc.result = []any{}
	out, err = run("--config-dir", cfg, "list", "--query", "empty")
	if err != nil {
		t.Fatal(err)
	}
	if strings.TrimSpace(out) != `[]` {
		t.Fatalf("empty row output = %q", out)
	}
}

func TestGraphSubgraphCommandUsesSocketClientPayloads(t *testing.T) {
	cfg := testConfig(t)
	orig := newClient
	fc := &fakeClient{result: map[string]any{"root_ids": []any{"task-1"}, "strands": []any{}, "edges": []any{}}}
	newClient = func(o Options) Caller { return fc }
	t.Cleanup(func() { newClient = orig })
	out, err := run("--config-dir", cfg, "graph", "subgraph", "task-1")
	if err != nil {
		t.Fatal(err)
	}
	if strings.TrimSpace(out) != `{"edges":[],"root_ids":["task-1"],"strands":[]}` {
		t.Fatalf("unexpected graph output: %q", out)
	}
	_, err = run("--config-dir", cfg, "graph", "subgraph", "task-1", "--relation", "depends-on")
	if err != nil {
		t.Fatal(err)
	}
	if len(fc.calls) != 2 {
		t.Fatalf("calls = %#v", fc.calls)
	}
	if fc.calls[0].op != "subgraph" || !reflect.DeepEqual(fc.calls[0].args, map[string]any{"root_ids": []string{"task-1"}}) {
		t.Fatalf("bad default subgraph call: %#v", fc.calls[0])
	}
	if fc.calls[1].op != "subgraph" || !reflect.DeepEqual(fc.calls[1].args, map[string]any{"root_ids": []string{"task-1"}, "type": "depends-on"}) {
		t.Fatalf("bad relation subgraph call: %#v", fc.calls[1])
	}
}

func TestOpCommandPassesThroughUserArgs(t *testing.T) {
	cfg := testConfig(t)
	orig := newClient
	fc := &fakeClient{result: map[string]any{"ok": true}}
	newClient = func(o Options) Caller { return fc }
	t.Cleanup(func() { newClient = orig })
	out, err := run("--config-dir", cfg, "op", "help", "--topic", "custom invocations")
	if err != nil {
		t.Fatal(err)
	}
	if strings.TrimSpace(out) != `{"ok":true}` {
		t.Fatalf("unexpected op output: %q", out)
	}
	expected := map[string]any{"name": "help", "args": []string{"--topic", "custom invocations"}}
	if len(fc.calls) != 1 || fc.calls[0].op != "op" || !reflect.DeepEqual(fc.calls[0].args, expected) {
		t.Fatalf("bad op call: %#v", fc.calls)
	}
}

func TestWeaveAndPatternCommandsUseSocketClientPayloads(t *testing.T) {
	cfg := testConfig(t)
	orig := newClient
	fc := &fakeClient{result: map[string]any{"created": []any{}, "refs": map[string]any{}}}
	newClient = func(o Options) Caller { return fc }
	t.Cleanup(func() { newClient = orig })
	out, err := runWithStdin(`{"title":"Implement"}`, "--config-dir", cfg, "weave", "--pattern", "dev-task")
	if err != nil {
		t.Fatal(err)
	}
	if strings.TrimSpace(out) != `{"created":[],"refs":{}}` {
		t.Fatalf("unexpected weave output: %q", out)
	}
	fc.result = map[string]any{"name": "dev-task"}
	if _, err := run("--config-dir", cfg, "pattern", "explain", "dev-task"); err != nil {
		t.Fatal(err)
	}
	if len(fc.calls) != 2 {
		t.Fatalf("calls = %#v", fc.calls)
	}
	if fc.calls[0].op != "weave" || !reflect.DeepEqual(fc.calls[0].args, map[string]any{"pattern": "dev-task", "input": map[string]any{"title": "Implement"}}) {
		t.Fatalf("bad weave call: %#v", fc.calls[0])
	}
	if fc.calls[1].op != "pattern-explain" || !reflect.DeepEqual(fc.calls[1].args, map[string]any{"pattern": "dev-task"}) {
		t.Fatalf("bad pattern explain call: %#v", fc.calls[1])
	}
	for _, stdin := range []string{"", `{bad`, `{} {}`} {
		if _, err := runWithStdin(stdin, "--config-dir", cfg, "weave", "--pattern", "dev-task"); err == nil {
			t.Fatalf("expected stdin error for %q", stdin)
		}
	}
}

func TestAddMergesAttributeInputs(t *testing.T) {
	cfg := testConfig(t)
	file := filepath.Join(t.TempDir(), "plan.md")
	if err := os.WriteFile(file, []byte("# Plan\n\nDo it.\n"), 0644); err != nil {
		t.Fatal(err)
	}
	orig := newClient
	fc := &fakeClient{}
	newClient = func(o Options) Caller { return fc }
	t.Cleanup(func() { newClient = orig })
	stdin := `{"kind":"template","owner":"default","body":"placeholder","count":2}`
	_, err := runWithStdin(stdin, "--config-dir", cfg, "add", "Implement", "--attributes-stdin", "--attr-file", "body="+file, "--attr", "owner=agent")
	if err != nil {
		t.Fatal(err)
	}
	expected := map[string]any{"kind": "template", "owner": "agent", "body": "# Plan\n\nDo it.\n", "count": float64(2)}
	if len(fc.calls) != 1 || fc.calls[0].op != "add" || !reflect.DeepEqual(fc.calls[0].args["attributes"], expected) {
		t.Fatalf("bad add attributes: %#v", fc.calls)
	}
}

func TestAddReadsSingleAttributeFromStdin(t *testing.T) {
	cfg := testConfig(t)
	orig := newClient
	fc := &fakeClient{}
	newClient = func(o Options) Caller { return fc }
	t.Cleanup(func() { newClient = orig })
	_, err := runWithStdin("# Plan\n", "--config-dir", cfg, "add", "Implement", "--attr-stdin", "body")
	if err != nil {
		t.Fatal(err)
	}
	expected := map[string]any{"body": "# Plan\n"}
	if !reflect.DeepEqual(fc.calls[0].args["attributes"], expected) {
		t.Fatalf("bad stdin attributes: %#v", fc.calls[0].args)
	}
}

func TestAddAttributeInputsFailLoudly(t *testing.T) {
	cfg := testConfig(t)
	file := filepath.Join(t.TempDir(), "body.md")
	if err := os.WriteFile(file, []byte("body"), 0644); err != nil {
		t.Fatal(err)
	}
	orig := newClient
	newClient = func(o Options) Caller { return &fakeClient{} }
	t.Cleanup(func() { newClient = orig })
	cases := []struct {
		stdin string
		args  []string
	}{
		{"", []string{"--config-dir", cfg, "add", "x", "--attr", "a=1", "--attr", "a=2"}},
		{"", []string{"--config-dir", cfg, "add", "x", "--attr-file", "body=" + file, "--attr-file", "body=" + file}},
		{"", []string{"--config-dir", cfg, "add", "x", "--attr-file", "body=" + file, "--attr-stdin", "body"}},
		{"", []string{"--config-dir", cfg, "add", "x", "--attr-stdin", ""}},
		{"", []string{"--config-dir", cfg, "add", "x", "--attr-stdin", "body", "--attr-stdin", "notes"}},
		{"{}", []string{"--config-dir", cfg, "add", "x", "--attr-stdin", "body", "--attributes-stdin"}},
		{"", []string{"--config-dir", cfg, "add", "x", "--attributes-stdin"}},
		{"[]", []string{"--config-dir", cfg, "add", "x", "--attributes-stdin"}},
		{"{} {}", []string{"--config-dir", cfg, "add", "x", "--attributes-stdin"}},
		{"", []string{"--config-dir", cfg, "add", "x", "--attr-file", "body=/missing/file"}},
	}
	for _, c := range cases {
		if _, err := runWithStdin(c.stdin, c.args...); err == nil {
			t.Fatalf("expected error for stdin %q args %v", c.stdin, c.args)
		}
	}
}

func TestSupersedeCommandUsesSocketPayloadAndJsonOutput(t *testing.T) {
	cfg := testConfig(t)
	orig := newClient
	result := map[string]any{
		"old":                  map[string]any{"before": map[string]any{"id": "old", "state": "active"}, "after": map[string]any{"id": "old", "state": "replaced"}},
		"replacement_id":       "new",
		"supersedes_edge":      map[string]any{"from_strand_id": "new", "to_strand_id": "old", "edge_type": "supersedes"},
		"rewired_dependencies": []any{},
	}
	fc := &fakeClient{result: result}
	newClient = func(o Options) Caller { return fc }
	t.Cleanup(func() { newClient = orig })
	out, err := run("--config-dir", cfg, "supersede", "old", "new")
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]any
	if err := json.Unmarshal([]byte(out), &got); err != nil {
		t.Fatalf("supersede output is not JSON: %q", out)
	}
	if got["replacement_id"] != "new" {
		t.Fatalf("unexpected supersede output: %#v", got)
	}
	if len(fc.calls) != 1 || fc.calls[0].op != "supersede" || !reflect.DeepEqual(fc.calls[0].args, map[string]any{"old_id": "old", "replacement_id": "new"}) {
		t.Fatalf("bad supersede call: %#v", fc.calls)
	}
}

func TestStrandCommandsUseSocketClientPayloads(t *testing.T) {
	cfg := testConfig(t)
	orig := newClient
	fc := &fakeClient{}
	newClient = func(o Options) Caller { return fc }
	t.Cleanup(func() { newClient = orig })
	out, err := run("--config-dir", cfg, "add", "Write docs", "--attr", "owner=agent", "--edge", "depends-on:task-0")
	if err != nil {
		t.Fatal(err)
	}
	if strings.TrimSpace(out) != `{"attributes":{},"id":"task-1","state":"active","title":"Write docs"}` {
		t.Fatalf("expected JSON row, got %q", out)
	}
	if out, err = run("--config-dir", cfg, "update", "task-1", "--state=closed", "--edge", "depends-on:task-0"); err != nil || !strings.Contains(out, `"id":"task-1"`) {
		t.Fatalf("update output/error = %q/%v", out, err)
	}
	if _, err = run("--config-dir", cfg, "show", "task-1"); err != nil {
		t.Fatal(err)
	}
	if _, err = run("--config-dir", cfg, "burn", "task-2"); err != nil {
		t.Fatal(err)
	}
	if len(fc.calls) != 4 {
		t.Fatalf("calls = %#v", fc.calls)
	}
	if fc.calls[0].op != "add" || fc.calls[0].args["title"] != "Write docs" {
		t.Fatalf("bad add call: %#v", fc.calls[0])
	}
	if _, ok := fc.calls[0].args["state"]; ok {
		t.Fatalf("add should omit default state: %#v", fc.calls[0])
	}
	if !reflect.DeepEqual(fc.calls[0].args["attributes"], map[string]any{"owner": "agent"}) {
		t.Fatalf("bad attrs: %#v", fc.calls[0].args)
	}
	if !reflect.DeepEqual(fc.calls[0].args["edges"], []map[string]any{{"type": "depends-on", "to": "task-0"}}) {
		t.Fatalf("bad add edges: %#v", fc.calls[0].args)
	}
	expectedUpdate := map[string]any{"id": "task-1", "title": nil, "state": "closed", "attributes": nil, "edges": []map[string]any{{"type": "depends-on", "to": "task-0"}}}
	if fc.calls[1].op != "update" || !reflect.DeepEqual(fc.calls[1].args, expectedUpdate) {
		t.Fatalf("bad update call: %#v", fc.calls[1])
	}
	if fc.calls[2].op != "show" || fc.calls[2].args["id"] != "task-1" {
		t.Fatalf("bad show call: %#v", fc.calls[2])
	}
	if fc.calls[3].op != "burn" || fc.calls[3].args["id"] != "task-2" {
		t.Fatalf("bad burn call: %#v", fc.calls[3])
	}
	if _, err = run("--config-dir", cfg, "update", "task-1", "--title", ""); err != nil {
		t.Fatal(err)
	}
	if fc.calls[4].args["title"] != "" {
		t.Fatalf("empty title flag should be sent to weaver validation: %#v", fc.calls[4])
	}
}
