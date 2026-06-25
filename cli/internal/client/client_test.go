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

func writeMeta(t *testing.T, db, sock string, pid int) {
	t.Helper()
	canon, err := canonicalPath(db)
	if err != nil {
		t.Fatal(err)
	}
	dir := filepath.Join(os.TempDir(), "todo-daemon")
	if err := os.MkdirAll(dir, 0755); err != nil {
		t.Fatal(err)
	}
	m := Metadata{ProtocolVersion: 1, PID: pid, DatabasePath: canon, DaemonID: "daemon-1", SocketPath: sock}
	b, _ := json.Marshal(m)
	if err := os.WriteFile(filepath.Join(dir, stableHash(canon)+".json"), b, 0644); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.Remove(filepath.Join(dir, stableHash(canon)+".json")) })
}

func serve(t *testing.T, handler func(map[string]any) map[string]any) string {
	t.Helper()
	sock := filepath.Join(t.TempDir(), "s.sock")
	ln, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
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

func TestCallSuccessAndDaemonError(t *testing.T) {
	db := filepath.Join(t.TempDir(), "todo.sqlite")
	canon, _ := canonicalPath(db)
	sock := serve(t, func(req map[string]any) map[string]any {
		if req["protocol_version"] != float64(1) || req["operation"] != "show" || req["daemon_id"] != "daemon-1" || req["database_path"] != canon {
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
	writeMeta(t, db, sock, os.Getpid())
	got, err := New(Config{DB: db, Format: "json"}).Call("show", map[string]any{"id": "t1"})
	if err != nil {
		t.Fatal(err)
	}
	if got.(map[string]any)["id"] != "t1" {
		t.Fatalf("got %#v", got)
	}

	sock = serve(t, func(req map[string]any) map[string]any {
		return map[string]any{"protocol_version": 1, "request_id": req["request_id"], "ok": false, "result": nil, "error": map[string]any{"type": "domain", "code": "task/not-found", "message": "Task not found", "details": map[string]any{}}}
	})
	writeMeta(t, db, sock, os.Getpid())
	_, err = New(Config{DB: db, Format: "json"}).Call("show", map[string]any{"id": "missing"})
	if err == nil || !strings.Contains(err.Error(), "task/not-found") {
		t.Fatalf("expected daemon error, got %v", err)
	}
}

func TestMetadataAndTransportFailures(t *testing.T) {
	db := filepath.Join(t.TempDir(), "missing.sqlite")
	_, err := New(Config{DB: db, Format: "json"}).Call("init", map[string]any{})
	if err == nil || !strings.Contains(err.Error(), "missing daemon metadata") {
		t.Fatalf("expected missing metadata, got %v", err)
	}

	writeMeta(t, db, filepath.Join(t.TempDir(), "no.sock"), os.Getpid())
	_, err = New(Config{DB: db, Format: "json"}).Call("init", map[string]any{})
	if err == nil || !strings.Contains(err.Error(), "daemon socket unreachable") {
		t.Fatalf("expected unreachable socket, got %v", err)
	}

	writeMeta(t, db, filepath.Join(t.TempDir(), "no.sock"), 999999)
	_, err = New(Config{DB: db, Format: "json"}).Call("init", map[string]any{})
	if err == nil || !strings.Contains(err.Error(), "stale daemon metadata") {
		t.Fatalf("expected stale metadata, got %v", err)
	}
}

func TestMalformedResponse(t *testing.T) {
	db := filepath.Join(t.TempDir(), "todo.sqlite")
	sock := filepath.Join(t.TempDir(), "s.sock")
	ln, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		_, _ = c.Write([]byte("not-json\n"))
	}()
	writeMeta(t, db, sock, os.Getpid())
	_, err = New(Config{DB: db, Format: "json"}).Call("init", map[string]any{})
	if err == nil || !strings.Contains(err.Error(), "malformed daemon response") {
		t.Fatalf("expected malformed response, got %v", err)
	}
}

func TestMalformedErrorEnvelope(t *testing.T) {
	db := filepath.Join(t.TempDir(), "todo.sqlite")
	sock := serve(t, func(req map[string]any) map[string]any {
		return map[string]any{"protocol_version": 1, "request_id": req["request_id"], "ok": false, "result": nil, "error": map[string]any{"type": "domain"}}
	})
	writeMeta(t, db, sock, os.Getpid())
	_, err := New(Config{DB: db, Format: "json"}).Call("init", map[string]any{})
	if err == nil || !strings.Contains(err.Error(), "malformed daemon response") {
		t.Fatalf("expected malformed envelope, got %v", err)
	}
}
