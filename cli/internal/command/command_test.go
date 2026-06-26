package command

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"skein-strand-cli/internal/config"
)

func run(args ...string) (string, error) {
	var out, er bytes.Buffer
	err := New(&out, &er).Run(args)
	return out.String() + er.String(), err
}

func TestHelpIncludesCommandTree(t *testing.T) {
	root, err := run("--help")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"Available Commands:", "init", "add", "update", "show", "list", "ready", "weaver"} {
		if !strings.Contains(root, want) {
			t.Fatalf("root help missing %q in:\n%s", want, root)
		}
	}
	add, err := run("add", "--help")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"add <title>", "--active", "--ephemeral", "--attr"} {
		if !strings.Contains(add, want) {
			t.Fatalf("add help missing %q in:\n%s", want, add)
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
		{"--format", "edn", "list"},
		{"list", "--where", "[:= :status \"strand\"]"},
		{"list", "--where", ""},
		{"list", "extra"},
		{"ready", "--query", "q", "extra"},
		{"ready", "--query", ""},
		{"add", "x", "extra"},
		{"add", "x", "--status", "bogus"},
		{"add", "x", "--active", "maybe"},
		{"add", "x", "--active=false", "--ephemeral=true"},
		{"add", "x", "--attr", "novalue"},
		{"update", "id", "extra"},
		{"update", "id", "--edge", "depends-on"},
		{"update", "id", "--edge", ":target"},
		{"update", "id", "--edge", "depends-on:"},
		{"update", "id", "--active=false", "--ephemeral=true"},
		{"list", "--param", "novalue"},
	}
	for _, c := range cases {
		if _, err := run(c...); err == nil {
			t.Fatalf("expected error for %v", c)
		}
	}
}

func TestConfigDirPrecedenceAndValidation(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "config.json"), []byte(`{"configFormat":"alpha","source":"/tmp/source","format":"json"}`), 0644); err != nil {
		t.Fatal(err)
	}
	var captured Options
	orig := newClient
	newClient = func(o Options) Caller {
		captured = o
		return &fakeClient{result: []any{}}
	}
	t.Cleanup(func() { newClient = orig })
	if _, err := run("--config-dir", dir, "--format", "human", "list"); err != nil {
		t.Fatal(err)
	}
	realDir, err := filepath.EvalSymlinks(dir)
	if err != nil {
		t.Fatal(err)
	}
	if captured.Format != "human" || captured.Source != "/tmp/source" || captured.ConfigDir != realDir {
		t.Fatalf("unexpected resolved options: %#v", captured)
	}

	if _, err := run("--config-path", dir, "list"); err == nil || !strings.Contains(err.Error(), "unknown flag: --config-path") {
		t.Fatalf("expected removed config-path error, got %v", err)
	}

	badDir := filepath.Join(t.TempDir(), "bad")
	if err := os.MkdirAll(badDir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(badDir, "config.json"), []byte(`{"configFormat":"alpha","where":"nope"}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--config-dir", badDir, "list"); err == nil || !strings.Contains(err.Error(), "unsupported client config key: where") {
		t.Fatalf("expected unsupported key error, got %v", err)
	}

	oldDBDir := filepath.Join(t.TempDir(), "old-db")
	if err := os.MkdirAll(oldDBDir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(oldDBDir, "config.json"), []byte(`{"configFormat":"alpha","db":"old.sqlite"}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--config-dir", oldDBDir, "list"); err == nil || !strings.Contains(err.Error(), "unsupported client config key: db") {
		t.Fatalf("expected db unsupported error, got %v", err)
	}

	missingFormat := filepath.Join(t.TempDir(), "missing-format")
	if err := os.MkdirAll(missingFormat, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(missingFormat, "config.json"), []byte(`{"source":"/tmp/source"}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--config-dir", missingFormat, "list"); err == nil || !strings.Contains(err.Error(), "configFormat is required") {
		t.Fatalf("expected configFormat required error, got %v", err)
	}

	malformedDir := filepath.Join(t.TempDir(), "malformed")
	if err := os.MkdirAll(malformedDir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(malformedDir, "config.json"), []byte(`{"configFormat":"alpha","source":`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--config-dir", malformedDir, "list"); err == nil || !strings.Contains(err.Error(), "malformed client config") {
		t.Fatalf("expected malformed config error, got %v", err)
	}

	wrongTypeDir := filepath.Join(t.TempDir(), "wrong-type")
	if err := os.MkdirAll(wrongTypeDir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(wrongTypeDir, "config.json"), []byte(`{"configFormat":"alpha","source":123}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--config-dir", wrongTypeDir, "list"); err == nil || !strings.Contains(err.Error(), "client config source must be a string") {
		t.Fatalf("expected source type error, got %v", err)
	}
}

func TestInitBootstrapsWorkspaceWhenMissingAndCallsInit(t *testing.T) {
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
	var initCalled bool
	captured := Options{}
	origClient := newClient
	origGit := runGitInit
	newClient = func(o Options) Caller {
		clientCalled = true
		captured = o
		return &fakeClient{}
	}
	runGitInit = func(configDir string) error {
		tgt, err := filepath.EvalSymlinks(cfg)
		if err != nil {
			t.Fatal(err)
		}
		if configDir != tgt {
			t.Fatalf("unexpected git init dir: %s", configDir)
		}
		initCalled = true
		return nil
	}
	t.Cleanup(func() {
		newClient = origClient
		runGitInit = origGit
	})
	if _, err := run("--config-dir", cfg, "init"); err != nil {
		t.Fatal(err)
	}
	if !clientCalled {
		t.Fatal("expected init to call weaver init")
	}
	if !initCalled {
		t.Fatal("expected git init to run")
	}
	cfgFile := filepath.Join(cfg, "config.json")
	if _, err := os.Stat(cfgFile); err != nil {
		t.Fatalf("missing config.json: %v", err)
	}
	var c struct {
		ConfigFormat string `json:"configFormat"`
		Source       string `json:"source"`
		Format       string `json:"format"`
	}
	f, err := os.ReadFile(cfgFile)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(f, &c); err != nil {
		t.Fatal(err)
	}
	if c.ConfigFormat != "alpha" || c.Format != config.DefaultFormat {
		t.Fatalf("unexpected config fields: %#v", c)
	}
	realSource, err := filepath.EvalSymlinks(source)
	if err != nil {
		t.Fatal(err)
	}
	if c.Source != realSource {
		t.Fatalf("unexpected source: %q", c.Source)
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
	if got := string(mustReadFile(t, initPath)); got != defaultInitCLJ {
		t.Fatalf("unexpected init.clj contents: %q", got)
	}
	realCfg, err := filepath.EvalSymlinks(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if captured.ConfigDir != realCfg {
		t.Fatalf("unexpected resolved config-dir: %#v", captured)
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
	original := `{"configFormat":"alpha","format":"json","source":"` + source + `"}`
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
	origGit := runGitInit
	newClient = func(o Options) Caller { return &fakeClient{} }
	runGitInit = func(configDir string) error {
		t.Fatalf("git init should not run when .git exists")
		return nil
	}
	t.Cleanup(func() { newClient = origClient; runGitInit = origGit })
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

func TestWeaverStartLaunchesFromConfiguredSource(t *testing.T) {
	cfg := t.TempDir()
	source := t.TempDir()
	if err := os.WriteFile(filepath.Join(source, "deps.edn"), []byte(`{}`), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(cfg, "config.json"), []byte(`{"configFormat":"alpha","source":"`+source+`"}`), 0644); err != nil {
		t.Fatal(err)
	}
	var launched Options
	orig := runWeaverProcess
	runWeaverProcess = func(o Options, out, errOut io.Writer) error {
		launched = o
		return nil
	}
	t.Cleanup(func() { runWeaverProcess = orig })
	if _, err := run("--config-dir", cfg, "weaver", "start"); err != nil {
		t.Fatal(err)
	}
	realCfg, err := filepath.EvalSymlinks(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if launched.Source != source || launched.ConfigDir != realCfg || !launched.ConfigDirExplicit {
		t.Fatalf("unexpected launch options: %#v", launched)
	}
	if !reflect.DeepEqual(weaverArgs(launched), []string{"-M:skein", "-m", "skein.weaver.runtime", "--config-dir", realCfg}) {
		t.Fatalf("unexpected explicit weaver args: %#v", weaverArgs(launched))
	}
	defaultLaunch := launched
	defaultLaunch.ConfigDirExplicit = false
	if !reflect.DeepEqual(weaverArgs(defaultLaunch), []string{"-M:skein", "-m", "skein.weaver.runtime"}) {
		t.Fatalf("unexpected default weaver args: %#v", weaverArgs(defaultLaunch))
	}
}

func TestWeaverReplVerifiesWeaverAndLaunchesFromConfiguredSource(t *testing.T) {
	cfg := t.TempDir()
	source := t.TempDir()
	if err := os.WriteFile(filepath.Join(source, "deps.edn"), []byte(`{}`), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(cfg, "config.json"), []byte(`{"configFormat":"alpha","source":"`+source+`"}`), 0644); err != nil {
		t.Fatal(err)
	}
	origClient := newClient
	origRun := runReplProcess
	var launched Options
	var launchedStdin bool
	fc := &fakeClient{}
	newClient = func(o Options) Caller { return fc }
	runReplProcess = func(o Options, stdin bool, in io.Reader, out, errOut io.Writer) error {
		launched = o
		launchedStdin = stdin
		return nil
	}
	t.Cleanup(func() { newClient = origClient; runReplProcess = origRun })
	if _, err := run("--config-dir", cfg, "weaver", "repl", "--stdin"); err != nil {
		t.Fatal(err)
	}
	realCfg, err := filepath.EvalSymlinks(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if launched.Source != source || launched.ConfigDir != realCfg || !launched.ConfigDirExplicit || !launchedStdin {
		t.Fatalf("unexpected repl launch: %#v stdin=%v", launched, launchedStdin)
	}
	if !reflect.DeepEqual(replArgs(launched, true), []string{"-M", "-m", "skein.repl", "--stdin", realCfg}) {
		t.Fatalf("unexpected repl args: %#v", replArgs(launched, true))
	}
	if len(fc.calls) != 1 || fc.calls[0].op != "status" {
		t.Fatalf("weaver repl should verify status first: %#v", fc.calls)
	}
}

func TestWeaverStartSupportsHomeRelativeSource(t *testing.T) {
	cfg := t.TempDir()
	home := t.TempDir()
	homeSource := filepath.Join(home, "skein")
	if err := os.MkdirAll(homeSource, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(homeSource, "deps.edn"), []byte(`{}`), 0644); err != nil {
		t.Fatal(err)
	}
	t.Setenv("HOME", home)
	if err := os.WriteFile(filepath.Join(cfg, "config.json"), []byte(`{"configFormat":"alpha","source":"~/skein"}`), 0644); err != nil {
		t.Fatal(err)
	}
	var launched Options
	orig := runWeaverProcess
	runWeaverProcess = func(o Options, out, errOut io.Writer) error {
		launched = o
		return nil
	}
	t.Cleanup(func() { runWeaverProcess = orig })
	if _, err := run("--config-dir", cfg, "weaver", "start"); err != nil {
		t.Fatal(err)
	}
	realCfg, err := filepath.EvalSymlinks(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if launched.Source != homeSource || launched.ConfigDir != realCfg || !launched.ConfigDirExplicit {
		t.Fatalf("unexpected launch options: %#v", launched)
	}
	raw, err := os.ReadFile(filepath.Join(cfg, "config.json"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(raw), `"source":"~/skein"`) {
		t.Fatalf("expected stored source to remain unchanged, got: %q", string(raw))
	}
}

func TestWeaverReplStatusFailureBlocksLaunch(t *testing.T) {
	cfg := t.TempDir()
	source := t.TempDir()
	if err := os.WriteFile(filepath.Join(source, "deps.edn"), []byte(`{}`), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(cfg, "config.json"), []byte(`{"configFormat":"alpha","source":"`+source+`"}`), 0644); err != nil {
		t.Fatal(err)
	}
	origClient := newClient
	origRun := runReplProcess
	launched := false
	newClient = func(o Options) Caller { return &fakeClient{err: errors.New("no running weaver")} }
	runReplProcess = func(o Options, stdin bool, in io.Reader, out, errOut io.Writer) error {
		launched = true
		return nil
	}
	t.Cleanup(func() { newClient = origClient; runReplProcess = origRun })
	if _, err := run("--config-dir", cfg, "weaver", "repl"); err == nil || !strings.Contains(err.Error(), "no running weaver") {
		t.Fatalf("expected status failure, got %v", err)
	}
	if launched {
		t.Fatal("repl process launched after status failure")
	}
}

func TestSourceValidationForWeaverStart(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "config.json"), []byte(`{"configFormat":"alpha","source":"relative"}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--config-dir", dir, "weaver", "start"); err == nil || !strings.Contains(err.Error(), "source must be an absolute path") {
		t.Fatalf("expected absolute source error, got %v", err)
	}

	missingDir := t.TempDir()
	if err := os.WriteFile(filepath.Join(missingDir, "config.json"), []byte(`{"configFormat":"alpha","source":"/definitely/missing/atom-source"}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--config-dir", missingDir, "weaver", "start"); err == nil || !strings.Contains(err.Error(), "source must be an existing directory") {
		t.Fatalf("expected missing source error, got %v", err)
	}

	source := t.TempDir()
	noDepsDir := t.TempDir()
	if err := os.WriteFile(filepath.Join(noDepsDir, "config.json"), []byte(`{"configFormat":"alpha","source":"`+source+`"}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--config-dir", noDepsDir, "weaver", "start"); err == nil || !strings.Contains(err.Error(), "source must contain deps.edn") {
		t.Fatalf("expected deps.edn source error, got %v", err)
	}
}

func TestXDGConfigLoading(t *testing.T) {
	dir := t.TempDir()
	stateDir := t.TempDir()
	dataDir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	t.Setenv("XDG_STATE_HOME", stateDir)
	t.Setenv("XDG_DATA_HOME", dataDir)
	path := filepath.Join(dir, "skein")
	if err := os.MkdirAll(path, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(path, "config.json"), []byte(`{"configFormat":"alpha","format":"json"}`), 0644); err != nil {
		t.Fatal(err)
	}
	var captured Options
	orig := newClient
	newClient = func(o Options) Caller {
		captured = o
		return &fakeClient{result: []any{}}
	}
	t.Cleanup(func() { newClient = orig })
	if _, err := run("list"); err != nil {
		t.Fatal(err)
	}
	if captured.ConfigDir != filepath.Join(dir, "skein") || captured.StateDir != filepath.Join(stateDir, "skein") {
		t.Fatalf("unexpected default world: %#v", captured)
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
	return map[string]any{"id": "task-1", "title": "Write docs", "active": true, "ephemeral": false, "attributes": map[string]any{}}, nil
}

func testConfig(t *testing.T) string {
	t.Helper()
	path := t.TempDir()
	if err := os.WriteFile(filepath.Join(path, "config.json"), []byte(`{"configFormat":"alpha","format":"human"}`), 0644); err != nil {
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
	if strings.TrimSpace(out) != `{"id":"task-1"}` {
		t.Fatalf("unexpected human list output: %q", out)
	}
	out, err = run("--config-dir", cfg, "list", "--active", "false")
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
	if fc.calls[1].op != "list" || !reflect.DeepEqual(fc.calls[1].args, map[string]any{"active": false}) {
		t.Fatalf("bad list active call: %#v", fc.calls[1])
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
	if strings.TrimSpace(out) != "(no rows)" {
		t.Fatalf("empty row output = %q", out)
	}
}

func TestStrandCommandsUseSocketClientPayloads(t *testing.T) {
	cfg := testConfig(t)
	orig := newClient
	fc := &fakeClient{}
	newClient = func(o Options) Caller { return fc }
	t.Cleanup(func() { newClient = orig })
	out, err := run("--config-dir", cfg, "--format", "human", "add", "Write docs", "--attr", "owner=agent")
	if err != nil {
		t.Fatal(err)
	}
	if strings.TrimSpace(out) != "task-1" {
		t.Fatalf("expected generated id, got %q", out)
	}
	if out, err = run("--config-dir", cfg, "update", "task-1", "--active=false", "--edge", "depends-on:task-0"); err != nil || out != "" {
		t.Fatalf("update output/error = %q/%v", out, err)
	}
	if _, err = run("--config-dir", cfg, "--format", "json", "show", "task-1"); err != nil {
		t.Fatal(err)
	}
	if len(fc.calls) != 3 {
		t.Fatalf("calls = %#v", fc.calls)
	}
	if fc.calls[0].op != "add" || fc.calls[0].args["title"] != "Write docs" {
		t.Fatalf("bad add call: %#v", fc.calls[0])
	}
	if _, ok := fc.calls[0].args["active"]; ok {
		t.Fatalf("add should omit default active: %#v", fc.calls[0])
	}
	if !reflect.DeepEqual(fc.calls[0].args["attributes"], map[string]any{"owner": "agent"}) {
		t.Fatalf("bad attrs: %#v", fc.calls[0].args)
	}
	expectedUpdate := map[string]any{"id": "task-1", "title": nil, "active": false, "ephemeral": nil, "attributes": nil, "edges": []map[string]any{{"type": "depends-on", "to": "task-0"}}}
	if fc.calls[1].op != "update" || !reflect.DeepEqual(fc.calls[1].args, expectedUpdate) {
		t.Fatalf("bad update call: %#v", fc.calls[1])
	}
	if fc.calls[2].op != "show" || fc.calls[2].args["id"] != "task-1" {
		t.Fatalf("bad show call: %#v", fc.calls[2])
	}
	if _, err = run("--config-dir", cfg, "update", "task-1", "--title", ""); err != nil {
		t.Fatal(err)
	}
	if fc.calls[3].args["title"] != "" {
		t.Fatalf("empty title flag should be sent to weaver validation: %#v", fc.calls[3])
	}
	if _, err = run("--config-dir", cfg, "add", "Scratch", "--ephemeral", "true"); err != nil {
		t.Fatal(err)
	}
	if fc.calls[4].args["ephemeral"] != true {
		t.Fatalf("explicit ephemeral add flag should be sent: %#v", fc.calls[4])
	}
	if _, err = run("--config-dir", cfg, "update", "task-1", "--ephemeral", "true"); err != nil {
		t.Fatal(err)
	}
	if fc.calls[5].args["ephemeral"] != true {
		t.Fatalf("explicit ephemeral update flag should be sent: %#v", fc.calls[5])
	}
}
