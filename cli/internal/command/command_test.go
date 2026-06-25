package command

import (
	"bytes"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
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
	for _, want := range []string{"Available Commands:", "init", "add", "update", "show", "list", "ready", "daemon"} {
		if !strings.Contains(root, want) {
			t.Fatalf("root help missing %q in:\n%s", want, root)
		}
	}

	add, err := run("add", "--help")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"add <title>", "--status", "--attr"} {
		if !strings.Contains(add, want) {
			t.Fatalf("add help missing %q in:\n%s", want, add)
		}
	}

	daemon, err := run("daemon", "--help")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"start", "status", "stop"} {
		if !strings.Contains(daemon, want) {
			t.Fatalf("daemon help missing %q in:\n%s", want, daemon)
		}
	}

	start, err := run("daemon", "start", "--help")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"start", "--config-dir"} {
		if !strings.Contains(start, want) {
			t.Fatalf("daemon start help missing %q in:\n%s", want, start)
		}
	}
}

func TestRejectsRemovedAndMalformedInputs(t *testing.T) {
	cases := [][]string{
		{"--format", "edn", "list"},
		{"list", "--where", "[:= :status \"todo\"]"},
		{"list", "--where", ""},
		{"list", "extra"},
		{"ready", "--query", "q", "extra"},
		{"ready", "--query", ""},
		{"add", "x", "extra"},
		{"add", "x", "--status", "bogus"},
		{"add", "x", "--attr", "novalue"},
		{"update", "id", "extra"},
		{"update", "id", "--edge", "depends-on"},
		{"update", "id", "--edge", ":target"},
		{"update", "id", "--edge", "depends-on:"},
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
	if err := os.WriteFile(filepath.Join(dir, "config.json"), []byte(`{"source":"/tmp/source","format":"json"}`), 0644); err != nil {
		t.Fatal(err)
	}
	opts, rest, err := Resolve([]string{"--config-dir", dir, "--format", "human", "list"})
	if err != nil {
		t.Fatal(err)
	}
	if opts.DB != filepath.Join(dir, "data", "tasks.sqlite") || opts.Format != "human" || opts.Source != "/tmp/source" || len(rest) != 1 || rest[0] != "list" {
		t.Fatalf("unexpected resolved options/rest: %#v %#v", opts, rest)
	}

	if _, err := run("--config-path", dir, "list"); err == nil || !strings.Contains(err.Error(), "unknown flag: --config-path") {
		t.Fatalf("expected removed config-path error, got %v", err)
	}

	badDir := filepath.Join(t.TempDir(), "bad")
	if err := os.MkdirAll(badDir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(badDir, "config.json"), []byte(`{"where":"nope"}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--config-dir", badDir, "list"); err == nil || !strings.Contains(err.Error(), "unsupported client config key: where") {
		t.Fatalf("expected unsupported key error, got %v", err)
	}

	oldDBDir := filepath.Join(t.TempDir(), "old-db")
	if err := os.MkdirAll(oldDBDir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(oldDBDir, "config.json"), []byte(`{"db":"old.sqlite"}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--config-dir", oldDBDir, "list"); err == nil || !strings.Contains(err.Error(), "unsupported client config key: db") {
		t.Fatalf("expected db unsupported error, got %v", err)
	}

	malformedDir := filepath.Join(t.TempDir(), "malformed")
	if err := os.MkdirAll(malformedDir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(malformedDir, "config.json"), []byte(`{"source":`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--config-dir", malformedDir, "list"); err == nil || !strings.Contains(err.Error(), "malformed client config") {
		t.Fatalf("expected malformed config error, got %v", err)
	}

	wrongTypeDir := filepath.Join(t.TempDir(), "wrong-type")
	if err := os.MkdirAll(wrongTypeDir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(wrongTypeDir, "config.json"), []byte(`{"source":123}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--config-dir", wrongTypeDir, "list"); err == nil || !strings.Contains(err.Error(), "client config source must be a string") {
		t.Fatalf("expected wrong type config error, got %v", err)
	}
}

func TestSourceValidationForDaemonStart(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "config.json"), []byte(`{"source":"relative"}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--config-dir", dir, "daemon", "start"); err == nil || !strings.Contains(err.Error(), "source must be an absolute path") {
		t.Fatalf("expected absolute source error, got %v", err)
	}

	missingDir := t.TempDir()
	if err := os.WriteFile(filepath.Join(missingDir, "config.json"), []byte(`{"source":"/definitely/missing/atom-source"}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--config-dir", missingDir, "daemon", "start"); err == nil || !strings.Contains(err.Error(), "source must be an existing directory") {
		t.Fatalf("expected missing source error, got %v", err)
	}

	source := t.TempDir()
	noDepsDir := t.TempDir()
	if err := os.WriteFile(filepath.Join(noDepsDir, "config.json"), []byte(`{"source":"`+source+`"}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--config-dir", noDepsDir, "daemon", "start"); err == nil || !strings.Contains(err.Error(), "source must contain deps.edn") {
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
	path := filepath.Join(dir, "atom")
	if err := os.MkdirAll(path, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(path, "config.json"), []byte(`{"format":"json"}`), 0644); err != nil {
		t.Fatal(err)
	}
	opts, _, err := Resolve([]string{"list"})
	if err != nil {
		t.Fatal(err)
	}
	if opts.DB != filepath.Join(dataDir, "atom", "tasks.sqlite") || opts.ConfigDir != filepath.Join(dir, "atom") {
		t.Fatalf("unexpected default world: %#v", opts)
	}
	_, err = run("list")
	if err == nil || !strings.Contains(err.Error(), "no running daemon") {
		t.Fatalf("expected xdg config to parse and reach daemon metadata lookup, got %v", err)
	}
}

type fakeClient struct {
	calls  []fakeCall
	result any
}
type fakeCall struct {
	op   string
	args map[string]any
}

func (f *fakeClient) Call(op string, args map[string]any) (any, error) {
	f.calls = append(f.calls, fakeCall{op, args})
	if f.result != nil {
		return f.result, nil
	}
	return map[string]any{"id": "task-1", "title": "Write docs", "status": "todo", "attributes": map[string]any{}}, nil
}

func testConfig(t *testing.T) string {
	t.Helper()
	path := t.TempDir()
	if err := os.WriteFile(filepath.Join(path, "config.json"), []byte(`{"format":"human"}`), 0644); err != nil {
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
	out, err = run("--config-dir", cfg, "ready", "--query", "by-owner", "--param", "owner=agent")
	if err != nil {
		t.Fatal(err)
	}
	if len(fc.calls) != 2 {
		t.Fatalf("calls = %#v", fc.calls)
	}
	if fc.calls[0].op != "list" || !reflect.DeepEqual(fc.calls[0].args, map[string]any{}) {
		t.Fatalf("bad list call: %#v", fc.calls[0])
	}
	expected := map[string]any{"query": "by-owner", "params": map[string]any{"owner": "agent"}}
	if fc.calls[1].op != "ready-query" || !reflect.DeepEqual(fc.calls[1].args, expected) {
		t.Fatalf("bad ready-query call: %#v", fc.calls[1])
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

func TestTaskCommandsUseSocketClientPayloads(t *testing.T) {
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
	if out, err = run("--config-dir", cfg, "update", "task-1", "--status", "done", "--edge", "depends-on:task-0"); err != nil || out != "" {
		t.Fatalf("update output/error = %q/%v", out, err)
	}
	if _, err = run("--config-dir", cfg, "--format", "json", "show", "task-1"); err != nil {
		t.Fatal(err)
	}
	if len(fc.calls) != 3 {
		t.Fatalf("calls = %#v", fc.calls)
	}
	if fc.calls[0].op != "add" || fc.calls[0].args["title"] != "Write docs" || fc.calls[0].args["status"] != "todo" {
		t.Fatalf("bad add call: %#v", fc.calls[0])
	}
	if !reflect.DeepEqual(fc.calls[0].args["attributes"], map[string]any{"owner": "agent"}) {
		t.Fatalf("bad attrs: %#v", fc.calls[0].args)
	}
	expectedUpdate := map[string]any{"id": "task-1", "title": nil, "status": "done", "attributes": nil, "edges": []map[string]any{{"type": "depends-on", "to": "task-0"}}}
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
		t.Fatalf("empty title flag should be sent to daemon validation: %#v", fc.calls[3])
	}
}
