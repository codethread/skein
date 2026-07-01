package main

import (
	"bufio"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"skein-strand-cli/internal/client"
	"skein-strand-cli/internal/config"
)

func TestForwardStrandOperationsThroughWeaverSocket(t *testing.T) {
	world, cfg := forwardWorld(t)
	received := []string{}
	serveFakeWeaver(t, world, func(req map[string]any) map[string]any {
		received = append(received, req["operation"].(string))
		if req["cwd"] != nil || req["config_dir"] != nil {
			t.Fatalf("routing fields leaked into weaver request: %#v", req)
		}
		return map[string]any{"protocol_version": 1, "request_id": req["request_id"], "ok": true, "result": map[string]any{"operation": req["operation"]}}
	})
	writeWeaverMetadata(t, world, os.Getpid(), "weaver-forward")

	s := server{children: map[string]*weaverChild{}}
	for _, op := range []string{"add", "list", "ready", "pattern-list", "query-list", "query-explain", "op", "subgraph"} {
		result, err := s.forwardToWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}, op, map[string]any{"name": "help"})
		if err != nil {
			t.Fatalf("forward %s: %v", op, err)
		}
		if result.(map[string]any)["operation"] != op {
			t.Fatalf("unexpected result for %s: %#v", op, result)
		}
	}
	if strings.Join(received, ",") != "add,list,ready,pattern-list,query-list,query-explain,op,subgraph" {
		t.Fatalf("received operations %v", received)
	}
}

func TestForwardReportsNoSelectedWorldWeaver(t *testing.T) {
	_, cfg := forwardWorld(t)
	_, err := (&server{}).forwardToWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}, "list", nil)
	if err == nil || !strings.Contains(err.Error(), "strand weaver start") {
		t.Fatalf("expected start remediation, got %v", err)
	}
}

func TestForwardReportsStaleSelectedWorldWeaver(t *testing.T) {
	world, cfg := forwardWorld(t)
	if err := os.WriteFile(filepath.Join(world.StateDir, "weaver.json"), []byte(`{bad`), 0o644); err != nil {
		t.Fatal(err)
	}
	_, err := (&server{}).forwardToWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}, "list", nil)
	re, ok := err.(*client.ResponseError)
	if !ok || re.Code != "mill/stale-selected-weaver" || re.Details["stale_reason"] == nil {
		t.Fatalf("expected stale selected-world error, got %#v", err)
	}
}

func TestForwardPropagatesWeaverDomainError(t *testing.T) {
	world, cfg := forwardWorld(t)
	serveFakeWeaver(t, world, func(req map[string]any) map[string]any {
		return map[string]any{"protocol_version": 1, "request_id": req["request_id"], "ok": false, "error": map[string]any{"type": "domain", "code": "strand/not-found", "message": "missing", "details": map[string]any{"id": "nope"}}}
	})
	writeWeaverMetadata(t, world, os.Getpid(), "weaver-forward")

	_, err := (&server{}).forwardToWeaver(client.MillWorldRequest{CWD: t.TempDir(), ConfigDir: cfg}, "show", map[string]any{"id": "nope"})
	re, ok := err.(*client.ResponseError)
	if !ok || re.Code != "strand/not-found" || re.Type != "domain" {
		t.Fatalf("expected propagated domain error, got %#v", err)
	}
}

func forwardWorld(t *testing.T) (config.World, string) {
	t.Helper()
	xdg, err := os.MkdirTemp("/tmp", "mill-forward-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(xdg) })
	t.Setenv("XDG_STATE_HOME", xdg)
	cfg := t.TempDir()
	if err := os.WriteFile(filepath.Join(cfg, "config.json"), []byte(`{"configFormat":"alpha"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	world, err := config.RuntimeWorld(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(world.StateDir, 0o755); err != nil {
		t.Fatal(err)
	}
	return world, cfg
}

func serveFakeWeaver(t *testing.T, world config.World, handler func(map[string]any) map[string]any) {
	t.Helper()
	socket := filepath.Join(world.StateDir, "weaver.sock")
	_ = os.Remove(socket)
	ln, err := net.Listen("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close(); _ = os.Remove(socket) })
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				_ = c.SetDeadline(time.Now().Add(time.Second))
				var req map[string]any
				if err := json.NewDecoder(bufio.NewReader(c)).Decode(&req); err != nil {
					return
				}
				_ = json.NewEncoder(c).Encode(handler(req))
			}(conn)
		}
	}()
}
