package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"syscall"
	"time"

	"skein-strand-cli/internal/client"
	"skein-strand-cli/internal/config"
)

func resolveLifecycleWorld(req client.MillWorldRequest) (config.World, error) {
	world, err := config.BootstrapTargetWorld(req.CWD, req.ConfigDir)
	if err != nil {
		return config.World{}, err
	}
	_, loaded, err := config.Load(world.ConfigDir)
	if err != nil {
		return config.World{}, err
	}
	return loaded, nil
}

func resolveLaunchSource(cwd string) (string, error) {
	if source := os.Getenv("SKEIN_SOURCE"); source != "" {
		return config.ValidateSource("SKEIN_SOURCE", source)
	}
	if config.InstalledSource != "" {
		return config.ValidateSource("installed Skein source", config.InstalledSource)
	}
	root, err := config.GitRoot(cwd)
	if err == nil {
		return config.ValidateSource("canonical Skein checkout cwd", root)
	}
	return "", fmt.Errorf("unable to resolve Skein source for weaver launch; set SKEIN_SOURCE to a Skein checkout, reinstall mill with a valid install-time source, or run strand weaver start from a canonical Skein checkout cwd containing deps.edn")
}

func weaverArgs(world config.World) []string {
	return []string{"-M:skein", "-m", "skein.weaver.runtime", "--config-dir", world.ConfigDir, "--state-dir", world.StateDir, "--data-dir", world.DataDir}
}

func (s *server) startWeaver(req client.MillWorldRequest) (map[string]any, error) {
	world, err := resolveLifecycleWorld(req)
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if child := s.children[world.ConfigDir]; child != nil && child.cmd.Process != nil && processAlive(child.cmd.Process.Pid) {
		status, stale := readStatus(world)
		if status != nil && !stale {
			return status, nil
		}
		if status == nil {
			status = baseStatus(world, "starting")
			status["pid"] = child.cmd.Process.Pid
		}
		return status, nil
	}
	if status, stale := readStatus(world); status != nil {
		if !stale {
			return status, nil
		}
		return nil, fmt.Errorf("stale weaver metadata for selected world: %v", status["stale_reason"])
	}
	source, err := resolveLaunchSource(req.CWD)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(world.StateDir, 0o755); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(world.DataDir, 0o755); err != nil {
		return nil, err
	}
	cmd, err := launchWeaver(source, weaverArgs(world), io.Discard, io.Discard)
	if err != nil {
		return nil, err
	}
	done := make(chan error, 1)
	s.children[world.ConfigDir] = &weaverChild{cmd: cmd, world: world, done: done}
	go func() { done <- cmd.Wait() }()
	status, err := waitForReadyStatus(world, cmd.Process.Pid, done, 15*time.Second)
	if err != nil {
		terminateProcess(cmd.Process)
		select {
		case <-done:
		default:
		}
		cleanupWorldArtifacts(world)
		delete(s.children, world.ConfigDir)
		return nil, err
	}
	millLogf("weaver started config_dir=%s state_dir=%s pid=%v", world.ConfigDir, world.StateDir, status["pid"])
	return status, nil
}

func (s *server) weaverStatus(req client.MillWorldRequest) (map[string]any, error) {
	world, err := resolveLifecycleWorld(req)
	if err != nil {
		return nil, err
	}
	return s.weaverStatusForWorld(world), nil
}

func (s *server) weaverReplContext(req client.MillWorldRequest) (map[string]any, error) {
	world, err := resolveLifecycleWorld(req)
	if err != nil {
		return nil, err
	}
	status := s.weaverStatusForWorld(world)
	if status["state"] != "running" {
		return status, nil
	}
	source, err := resolveLaunchSource(req.CWD)
	if err != nil {
		return nil, err
	}
	status["source"] = source
	return status, nil
}

func (s *server) weaverStatusForWorld(world config.World) map[string]any {
	s.mu.Lock()
	defer s.mu.Unlock()
	if status, stale := readStatus(world); status != nil {
		if stale {
			status["state"] = "stale"
		}
		return status
	}
	if child := s.children[world.ConfigDir]; child != nil {
		if child.cmd.Process != nil && processAlive(child.cmd.Process.Pid) {
			status := baseStatus(world, "starting")
			status["pid"] = child.cmd.Process.Pid
			return status
		}
		status := baseStatus(world, "stopped")
		return status
	}
	return baseStatus(world, "none")
}

func (s *server) stopWeaver(req client.MillWorldRequest) (map[string]any, error) {
	world, err := resolveLifecycleWorld(req)
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	child := s.children[world.ConfigDir]
	if child == nil || child.cmd.Process == nil || !processAlive(child.cmd.Process.Pid) {
		delete(s.children, world.ConfigDir)
		if status, stale := readStatus(world); status != nil {
			if !stale {
				return nil, fmt.Errorf("selected-world weaver is not supervised by this mill")
			}
			cleanupWorldArtifacts(world)
		}
		return baseStatus(world, "stopped"), nil
	}
	pid := child.cmd.Process.Pid
	terminateProcess(child.cmd.Process)
	select {
	case <-child.done:
	case <-time.After(5 * time.Second):
		_ = child.cmd.Process.Kill()
		<-child.done
	}
	cleanupWorldArtifacts(world)
	delete(s.children, world.ConfigDir)
	status := baseStatus(world, "stopped")
	status["pid"] = pid
	millLogf("weaver stopped config_dir=%s state_dir=%s pid=%d", world.ConfigDir, world.StateDir, pid)
	return status, nil
}

func (s *server) stopAll() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, child := range s.children {
		if child.cmd != nil && child.cmd.Process != nil && processAlive(child.cmd.Process.Pid) {
			terminateProcess(child.cmd.Process)
			select {
			case <-child.done:
			case <-time.After(5 * time.Second):
				_ = child.cmd.Process.Kill()
				<-child.done
			}
			cleanupWorldArtifacts(child.world)
		}
	}
}

func readStatus(world config.World) (map[string]any, bool) {
	metadataPath := filepath.Join(world.StateDir, "weaver.json")
	b, err := os.ReadFile(metadataPath)
	if err != nil {
		return nil, false
	}
	var m client.Metadata
	if err := json.Unmarshal(b, &m); err != nil {
		st := baseStatus(world, "stale")
		st["stale_reason"] = fmt.Sprintf("malformed weaver metadata: %v", err)
		return st, true
	}
	if staleReason := validateMetadata(world, m); staleReason != "" {
		st := baseStatus(world, "stale")
		st["stale_reason"] = staleReason
		return st, true
	}
	st := baseStatus(world, "running")
	st["pid"] = m.PID
	st["weaver_id"] = m.DaemonID
	st["socket_path"] = m.SocketPath
	st["nrepl"] = m.NREPL
	st["started_at"] = m.StartedAt
	st["database_path"] = m.DatabasePath
	return st, false
}

func validateMetadata(world config.World, m client.Metadata) string {
	if m.ProtocolVersion != 1 || m.PID == 0 || m.DatabasePath == "" || m.DaemonID == "" || m.ConfigDir == "" || m.DataDir == "" || m.SocketPath == "" || m.StartedAt == "" || m.NREPL.Host == "" || m.NREPL.Port == 0 {
		return "malformed weaver metadata: missing required fields"
	}
	if !samePath(m.ConfigDir, world.ConfigDir) || !samePath(m.DataDir, world.DataDir) || !samePath(m.DatabasePath, world.DBPath) || !samePath(m.SocketPath, filepath.Join(world.StateDir, "weaver.sock")) {
		return "weaver metadata identity mismatch"
	}
	if !processAlive(m.PID) {
		return fmt.Sprintf("pid %d is not alive", m.PID)
	}
	return ""
}

func samePath(a, b string) bool {
	if a == "" || b == "" {
		return a == b
	}
	realA, errA := filepath.EvalSymlinks(a)
	if errA != nil {
		realA = filepath.Clean(a)
	}
	realB, errB := filepath.EvalSymlinks(b)
	if errB != nil {
		realB = filepath.Clean(b)
	}
	return realA == realB
}

func waitForReadyStatus(world config.World, pid int, done <-chan error, timeout time.Duration) (map[string]any, error) {
	deadline := time.Now().Add(timeout)
	for {
		status, stale := readStatus(world)
		if status != nil && !stale {
			return status, nil
		}
		if stale {
			return nil, fmt.Errorf("weaver published stale metadata during startup: %v", status["stale_reason"])
		}
		select {
		case err := <-done:
			if err != nil {
				return nil, fmt.Errorf("weaver exited before publishing ready metadata: %w", err)
			}
			return nil, fmt.Errorf("weaver exited before publishing ready metadata")
		default:
		}
		if time.Now().After(deadline) {
			terminatePID(pid)
			return nil, fmt.Errorf("weaver did not publish ready metadata before timeout")
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func cleanupWorldArtifacts(world config.World) {
	_ = os.Remove(filepath.Join(world.StateDir, "weaver.json"))
	_ = os.Remove(filepath.Join(world.StateDir, "weaver.edn"))
	_ = os.Remove(filepath.Join(world.StateDir, "weaver.sock"))
}

func baseStatus(world config.World, state string) map[string]any {
	return map[string]any{
		"state":         state,
		"config_dir":    world.ConfigDir,
		"state_dir":     world.StateDir,
		"data_dir":      world.DataDir,
		"database_path": world.DBPath,
	}
}

func terminateProcess(p *os.Process) {
	if p == nil {
		return
	}
	terminatePID(p.Pid)
	_ = p.Signal(syscall.SIGTERM)
}

func terminatePID(pid int) {
	if pid > 0 {
		_ = syscall.Kill(-pid, syscall.SIGTERM)
		if p, err := os.FindProcess(pid); err == nil {
			_ = p.Signal(syscall.SIGTERM)
		}
	}
}

func processAlive(pid int) bool {
	if pid <= 0 {
		return false
	}
	p, err := os.FindProcess(pid)
	if err != nil {
		return false
	}
	return p.Signal(syscall.Signal(0)) == nil
}
