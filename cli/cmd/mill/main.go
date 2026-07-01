package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"sync"
	"syscall"
	"time"

	"github.com/spf13/cobra"
	"skein-strand-cli/internal/client"
	"skein-strand-cli/internal/config"
)

type server struct {
	meta     client.MillMetadata
	mu       sync.Mutex
	children map[string]*weaverChild
}

type weaverChild struct {
	cmd   *exec.Cmd
	world config.World
	name  string
	done  chan error
}

var millLogOut io.Writer = os.Stdout

func millLogf(format string, args ...any) {
	fmt.Fprintf(millLogOut, format+"\n", args...)
}

var launchWeaver = func(source string, args []string, out, errOut io.Writer) (*exec.Cmd, error) {
	cmd := exec.Command("clojure", args...)
	cmd.Dir = source
	cmd.Stdout = out
	cmd.Stderr = errOut
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	return cmd, nil
}

func main() {
	root := &cobra.Command{Use: "mill", Short: "Skein local router"}
	root.AddCommand(&cobra.Command{Use: "start", Short: "Start mill in the foreground", RunE: func(cmd *cobra.Command, args []string) error {
		return start()
	}})
	root.AddCommand(&cobra.Command{Use: "status", Short: "Check the active mill", RunE: func(cmd *cobra.Command, args []string) error {
		result, err := client.MillStatus()
		if err != nil {
			return err
		}
		return json.NewEncoder(os.Stdout).Encode(result)
	}})
	weaver := &cobra.Command{Use: "weaver", Short: "Inspect supervised weavers"}
	weaver.AddCommand(&cobra.Command{Use: "list", Short: "List known selected workspace weavers", RunE: func(cmd *cobra.Command, args []string) error {
		result, err := client.MillCall("weaver-list", client.MillWorldRequest{})
		if err != nil {
			return err
		}
		return json.NewEncoder(os.Stdout).Encode(result)
	}})
	root.AddCommand(weaver)
	if err := root.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func start() error {
	root, err := config.StateRoot()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(root, 0o755); err != nil {
		return err
	}
	socketPath := filepath.Join(root, config.MillSocketFileName)
	metadataPath := filepath.Join(root, config.MillMetadataFileName)
	if err := cleanupPreviousMillState(root, socketPath, metadataPath); err != nil {
		return err
	}
	listener, err := net.Listen("unix", socketPath)
	if err != nil {
		return err
	}
	defer listener.Close()
	defer os.Remove(socketPath)
	defer os.Remove(metadataPath)

	meta := client.MillMetadata{ProtocolVersion: client.MillProtocolVersion, PID: os.Getpid(), MillID: fmt.Sprintf("mill-%d-%d", os.Getpid(), time.Now().UnixNano()), StateRoot: root, SocketPath: socketPath, StartedAt: time.Now().UTC().Format(time.RFC3339Nano)}
	b, err := json.MarshalIndent(meta, "", "  ")
	if err != nil {
		return err
	}
	if err := os.WriteFile(metadataPath, append(b, '\n'), 0o644); err != nil {
		return err
	}
	millLogf("mill listening state_root=%s socket=%s pid=%d", meta.StateRoot, meta.SocketPath, meta.PID)

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	go func() { <-sig; listener.Close() }()
	s := server{meta: meta, children: map[string]*weaverChild{}}
	defer s.stopAll()
	for {
		conn, err := listener.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			return err
		}
		go s.handle(conn)
	}
}

func (s *server) handle(conn net.Conn) {
	defer conn.Close()
	var req client.MillRequest
	if err := json.NewDecoder(conn).Decode(&req); err != nil {
		_ = json.NewEncoder(conn).Encode(errorResponse("", "protocol", "mill/protocol", "malformed mill request", err.Error()))
		return
	}
	if req.ProtocolVersion != client.MillProtocolVersion || req.RequestID == "" || req.MillID != s.meta.MillID {
		_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "protocol", "mill/identity", "mill request identity mismatch", ""))
		return
	}
	switch req.Operation {
	case "status", "ping":
		_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: map[string]any{"healthy": true, "protocol_version": client.MillProtocolVersion, "pid": s.meta.PID, "mill_id": s.meta.MillID, "state_root": s.meta.StateRoot, "socket_path": s.meta.SocketPath, "started_at": s.meta.StartedAt}})
	case "init":
		world, err := config.BootstrapWorld(req.World.CWD, req.World.ConfigDir, req.World.Source)
		if err != nil {
			_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/init-failed", "strand init failed", err.Error()))
			return
		}
		_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: map[string]any{"config_dir": world.ConfigDir, "config_file": world.ConfigFile}})
	case "weaver-start":
		result, err := s.startWeaver(req.World)
		if err != nil {
			_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/weaver-start-failed", "weaver start failed", err.Error()))
			return
		}
		_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: result})
	case "weaver-status":
		result, err := s.weaverStatus(req.World)
		if err != nil {
			_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/weaver-status-failed", "weaver status failed", err.Error()))
			return
		}
		_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: result})
	case "weaver-list":
		result, err := s.weaverList()
		if err != nil {
			_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/weaver-list-failed", "weaver list failed", err.Error()))
			return
		}
		_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: result})
	case "weaver-repl-context":
		result, err := s.weaverReplContext(req.World)
		if err != nil {
			_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/weaver-repl-context-failed", "weaver repl context failed", err.Error()))
			return
		}
		_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: result})
	case "weaver-stop":
		result, err := s.stopWeaver(req.World)
		if err != nil {
			_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "domain", "mill/weaver-stop-failed", "weaver stop failed", err.Error()))
			return
		}
		_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: result})
	case "add", "update", "show", "supersede", "burn", "list", "ready", "list-query", "ready-query", "weave", "pattern-list", "pattern-explain", "query-list", "query-explain", "op", "subgraph":
		result, err := s.forwardToWeaver(req.World, req.Operation, req.Payload)
		if err != nil {
			if re, ok := err.(*client.ResponseError); ok {
				_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: false, Error: re})
				return
			}
			_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "transport", "mill/weaver-forward-failed", "weaver forwarding failed", err.Error()))
			return
		}
		_ = json.NewEncoder(conn).Encode(client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: req.RequestID, OK: true, Result: result})
	default:
		_ = json.NewEncoder(conn).Encode(errorResponse(req.RequestID, "protocol", "mill/unknown-operation", "unknown mill operation", req.Operation))
	}
}

func cleanupPreviousMillState(root, socketPath, metadataPath string) error {
	if b, err := os.ReadFile(metadataPath); err == nil {
		var meta client.MillMetadata
		if err := json.Unmarshal(b, &meta); err == nil && meta.PID != 0 && processAlive(meta.PID) {
			return fmt.Errorf("mill is already running with pid %d", meta.PID)
		}
	} else if err != nil && !os.IsNotExist(err) {
		return err
	}
	for _, pattern := range []string{filepath.Join(root, "weavers", "*", "weaver.json")} {
		matches, err := filepath.Glob(pattern)
		if err != nil {
			return err
		}
		for _, path := range matches {
			if b, err := os.ReadFile(path); err == nil {
				var meta client.Metadata
				if err := json.Unmarshal(b, &meta); err == nil && meta.PID != 0 && processAlive(meta.PID) {
					terminatePID(meta.PID)
				}
			}
			stateDir := filepath.Dir(path)
			cleanupWorldArtifacts(config.World{StateDir: stateDir})
		}
	}
	_ = os.Remove(socketPath)
	_ = os.Remove(metadataPath)
	return nil
}

func errorResponse(requestID, typ, code, message, detail string) client.MillResponse {
	return client.MillResponse{ProtocolVersion: client.MillProtocolVersion, RequestID: requestID, OK: false, Error: &client.ResponseError{Type: typ, Code: code, Message: message, Details: map[string]any{"detail": detail}}}
}
