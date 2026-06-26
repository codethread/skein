package client

import (
	"bufio"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeMeta(t *testing.T, stateDir, sock string, pid int) {
	t.Helper()
	if err := os.MkdirAll(stateDir, 0755); err != nil {
		t.Fatal(err)
	}
	m := Metadata{ProtocolVersion: 1, PID: pid, DatabasePath: filepath.Join(filepath.Dir(stateDir), "data", "skein.sqlite"), DaemonID: "daemon-1", ConfigDir: filepath.Dir(stateDir), DataDir: filepath.Join(filepath.Dir(stateDir), "data"), SocketPath: sock, StartedAt: "now"}
	m.NREPL.Host = "127.0.0.1"
	m.NREPL.Port = 9999
	b, _ := json.Marshal(m)
	if err := os.WriteFile(filepath.Join(stateDir, "weaver.json"), b, 0644); err != nil {
		t.Fatal(err)
	}
}

func serveAt(t *testing.T, sock string, handler func(map[string]any) map[string]any) string {
	t.Helper()
	_ = os.Remove(sock)
	if err := os.MkdirAll(filepath.Dir(sock), 0755); err != nil {
		t.Fatal(err)
	}
	ln, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close(); os.Remove(sock) })
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		var req map[string]any
		_ = json.NewDecoder(bufio.NewReader(c)).Decode(&req)
		_ = json.NewEncoder(c).Encode(handler(req))
	}()
	return sock
}

func serve(t *testing.T, stateDir string, handler func(map[string]any) map[string]any) string {
	return serveAt(t, filepath.Join(stateDir, "weaver.sock"), handler)
}

func TestCallSuccessAndDaemonError(t *testing.T) {
	base, err := os.MkdirTemp("/tmp", "td-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(base) })
	stateDir := filepath.Join(base, "state")
	sock := serve(t, stateDir, func(req map[string]any) map[string]any {
		if req["protocol_version"] != float64(1) || req["operation"] != "show" || req["weaver_id"] != "daemon-1" || req["database_path"] != nil {
			t.Fatalf("bad envelope: %#v", req)
		}
		if got := req["arguments"].(map[string]any)["id"]; got != "t1" {
			t.Fatalf("bad args: %#v", req["arguments"])
		}
		if got := req["options"].(map[string]any)["format"]; got != "json" {
			t.Fatalf("bad options: %#v", req["options"])
		}
		return map[string]any{"protocol_version": 1, "request_id": req["request_id"], "ok": true, "result": map[string]any{"id": "t1"}, "error": nil}
	})
	writeMeta(t, stateDir, sock, os.Getpid())
	got, err := New(Config{StateDir: stateDir, Format: "json"}).Call("show", map[string]any{"id": "t1"})
	if err != nil {
		t.Fatal(err)
	}
	if got.(map[string]any)["id"] != "t1" {
		t.Fatalf("got %#v", got)
	}

	sock = serve(t, stateDir, func(req map[string]any) map[string]any {
		return map[string]any{"protocol_version": 1, "request_id": req["request_id"], "ok": false, "result": nil, "error": map[string]any{"type": "domain", "code": "task/not-found", "message": "Task not found", "details": map[string]any{}}}
	})
	writeMeta(t, stateDir, sock, os.Getpid())
	_, err = New(Config{StateDir: stateDir, Format: "json"}).Call("show", map[string]any{"id": "missing"})
	if err == nil || !strings.Contains(err.Error(), "task/not-found") {
		t.Fatalf("expected daemon error, got %v", err)
	}

	sock = serve(t, stateDir, func(req map[string]any) map[string]any {
		return map[string]any{"protocol_version": 1, "request_id": req["request_id"], "ok": false, "result": nil, "error": map[string]any{"type": "domain", "code": "domain/error", "message": "Query not found", "details": map[string]any{"canonical-query": "missing", "available": []any{"mine", "ready"}}}}
	})
	writeMeta(t, stateDir, sock, os.Getpid())
	_, err = New(Config{StateDir: stateDir, Format: "json"}).Call("list-query", map[string]any{"query": "missing", "params": map[string]string{}})
	if err == nil || !strings.Contains(err.Error(), "missing") || !strings.Contains(err.Error(), "mine, ready") {
		t.Fatalf("expected query details, got %v", err)
	}
}

func TestMetadataAndTransportFailures(t *testing.T) {
	stateDir := filepath.Join(t.TempDir(), "state")
	_, err := New(Config{StateDir: stateDir, Format: "json"}).Call("init", map[string]any{})
	if err == nil || !strings.Contains(err.Error(), "no running daemon") || !strings.Contains(err.Error(), "daemon start") {
		t.Fatalf("expected missing daemon startup guidance, got %v", err)
	}
	writeMeta(t, stateDir, filepath.Join(stateDir, "weaver.sock"), os.Getpid())
	_, err = New(Config{StateDir: stateDir, Format: "json"}).Call("init", map[string]any{})
	if err == nil || !strings.Contains(err.Error(), "daemon socket unreachable") {
		t.Fatalf("expected unreachable socket, got %v", err)
	}
	writeMeta(t, stateDir, filepath.Join(stateDir, "weaver.sock"), 999999)
	_, err = New(Config{StateDir: stateDir, Format: "json"}).Call("init", map[string]any{})
	if err == nil || !strings.Contains(err.Error(), "stale daemon metadata") {
		t.Fatalf("expected stale metadata, got %v", err)
	}
}

func TestMalformedMetadataAndIdentityMismatch(t *testing.T) {
	base, err := os.MkdirTemp("/tmp", "td-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(base) })
	stateDir := filepath.Join(base, "state")
	_ = os.MkdirAll(stateDir, 0755)
	metaFile := filepath.Join(stateDir, "weaver.json")
	if err := os.WriteFile(metaFile, []byte(`{"protocol_version":1}`), 0644); err != nil {
		t.Fatal(err)
	}
	_, err = New(Config{StateDir: stateDir, Format: "json"}).Call("init", map[string]any{})
	if err == nil || !strings.Contains(err.Error(), "malformed daemon metadata") {
		t.Fatalf("expected malformed metadata, got %v", err)
	}

	sock := serveAt(t, filepath.Join(base, "wrong.sock"), func(req map[string]any) map[string]any {
		return map[string]any{"protocol_version": 1, "request_id": req["request_id"], "ok": false, "result": nil, "error": map[string]any{"type": "protocol", "code": "protocol/identity-mismatch", "message": "Daemon identity mismatch", "details": map[string]any{}}}
	})
	writeMeta(t, stateDir, sock, os.Getpid())
	_, err = New(Config{StateDir: stateDir, Format: "json"}).Call("status", map[string]any{})
	if err == nil || !strings.Contains(err.Error(), "socket mismatch") {
		t.Fatalf("expected socket mismatch before connect, got %v", err)
	}
}

func TestMalformedLifecycleResults(t *testing.T) {
	base, err := os.MkdirTemp("/tmp", "td-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(base) })
	stateDir := filepath.Join(base, "state")
	sock := serve(t, stateDir, func(req map[string]any) map[string]any {
		return map[string]any{"protocol_version": 1, "request_id": req["request_id"], "ok": true, "result": map[string]any{"healthy": true}, "error": nil}
	})
	writeMeta(t, stateDir, sock, os.Getpid())
	_, err = New(Config{StateDir: stateDir, Format: "json"}).Call("status", map[string]any{})
	if err == nil || !strings.Contains(err.Error(), "invalid status result") {
		t.Fatalf("expected malformed status result, got %v", err)
	}
}
