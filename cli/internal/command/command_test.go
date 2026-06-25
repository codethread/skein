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
	out, err := run("--help")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"init", "add <title>", "update <id>", "show <id>", "list [--query name]", "ready [--query name]", "daemon start [--config trusted.edn]", "daemon status", "daemon stop"} {
		if !strings.Contains(out, want) {
			t.Fatalf("help missing %q in:\n%s", want, out)
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

func TestClientConfigPrecedenceAndValidation(t *testing.T) {
	dir := t.TempDir()
	cfg := filepath.Join(dir, "config.json")
	if err := os.WriteFile(cfg, []byte(`{"db":"from-config.sqlite","format":"json"}`), 0644); err != nil {
		t.Fatal(err)
	}
	opts, rest, err := Resolve([]string{"--client-config", cfg, "--db", "from-flag.sqlite", "--format", "human", "list"})
	if err != nil {
		t.Fatal(err)
	}
	if opts.DB != "from-flag.sqlite" || opts.Format != "human" || len(rest) != 1 || rest[0] != "list" {
		t.Fatalf("unexpected resolved options/rest: %#v %#v", opts, rest)
	}

	bad := filepath.Join(dir, "bad.json")
	if err := os.WriteFile(bad, []byte(`{"where":"nope"}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--client-config", bad, "list"); err == nil || !strings.Contains(err.Error(), "unsupported client config key") {
		t.Fatalf("expected unsupported key error, got %v", err)
	}

	malformed := filepath.Join(dir, "malformed.json")
	if err := os.WriteFile(malformed, []byte(`{"db":`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--client-config", malformed, "list"); err == nil || !strings.Contains(err.Error(), "malformed client config") {
		t.Fatalf("expected malformed config error, got %v", err)
	}

	wrongType := filepath.Join(dir, "wrong-type.json")
	if err := os.WriteFile(wrongType, []byte(`{"db":123}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := run("--client-config", wrongType, "list"); err == nil || !strings.Contains(err.Error(), "client config db must be a string") {
		t.Fatalf("expected wrong type config error, got %v", err)
	}
}

func TestXDGConfigLoading(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	path := filepath.Join(dir, "todo")
	if err := os.MkdirAll(path, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(path, "config.json"), []byte(`{"format":"json"}`), 0644); err != nil {
		t.Fatal(err)
	}
	_, err := run("list")
	if err == nil || !strings.Contains(err.Error(), "list is not wired") {
		t.Fatalf("expected xdg config to parse and reach stub, got %v", err)
	}
}

type fakeClient struct{ calls []fakeCall }
type fakeCall struct {
	op   string
	args map[string]any
}

func (f *fakeClient) Call(op string, args map[string]any) (any, error) {
	f.calls = append(f.calls, fakeCall{op, args})
	return map[string]any{"id": "task-1", "title": "Write docs", "status": "todo", "attributes": map[string]any{}}, nil
}

func TestTaskCommandsUseSocketClientPayloads(t *testing.T) {
	orig := newClient
	fc := &fakeClient{}
	newClient = func(o Options) Caller { return fc }
	t.Cleanup(func() { newClient = orig })
	out, err := run("--format", "human", "add", "Write docs", "--attr", "owner=agent")
	if err != nil {
		t.Fatal(err)
	}
	if strings.TrimSpace(out) != "task-1" {
		t.Fatalf("expected generated id, got %q", out)
	}
	if out, err = run("update", "task-1", "--status", "done", "--edge", "depends-on:task-0"); err != nil || out != "" {
		t.Fatalf("update output/error = %q/%v", out, err)
	}
	if _, err = run("--format", "json", "show", "task-1"); err != nil {
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
	if _, err = run("update", "task-1", "--title", ""); err != nil {
		t.Fatal(err)
	}
	if fc.calls[3].args["title"] != "" {
		t.Fatalf("empty title flag should be sent to daemon validation: %#v", fc.calls[3])
	}
}
