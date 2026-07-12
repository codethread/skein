package client

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"time"

	"skein-strand-cli/internal/config"
)

const MillProtocolVersion = 2

const defaultWeaverReadyTimeout = 5 * time.Minute

type MillMetadata struct {
	ProtocolVersion int    `json:"protocol_version"`
	PID             int    `json:"pid"`
	MillID          string `json:"mill_id"`
	StateRoot       string `json:"state_root"`
	SocketPath      string `json:"socket_path"`
	StartedAt       string `json:"started_at"`
}

type MillRequest struct {
	ProtocolVersion int              `json:"protocol_version"`
	RequestID       string           `json:"request_id"`
	MillID          string           `json:"mill_id"`
	Operation       string           `json:"operation"`
	World           MillWorldRequest `json:"world,omitempty"`
	Payload         map[string]any   `json:"payload"`
}

type MillWorldRequest struct {
	CWD            string `json:"cwd,omitempty"`
	ConfigDir      string `json:"config_dir,omitempty"`
	Source         string `json:"source,omitempty"`
	Name           string `json:"name,omitempty"`
	ReadyTimeoutMs int64  `json:"ready_timeout_ms,omitempty"`
}

type MillResponse struct {
	ProtocolVersion int            `json:"protocol_version"`
	RequestID       string         `json:"request_id"`
	OK              bool           `json:"ok"`
	Result          any            `json:"result"`
	Error           *ResponseError `json:"error"`
}

func MillStatus() (any, error) { return MillCall("status", MillWorldRequest{}) }

func MillCall(operation string, world MillWorldRequest) (any, error) {
	return MillCallPayload(operation, world, nil)
}

func MillCallPayload(operation string, world MillWorldRequest, payload map[string]any) (any, error) {
	if payload == nil {
		payload = map[string]any{}
	}
	meta, err := ReadMillMetadata()
	if err != nil {
		return nil, err
	}
	requestID := fmt.Sprintf("%d", time.Now().UnixNano())
	req := MillRequest{ProtocolVersion: MillProtocolVersion, RequestID: requestID, MillID: meta.MillID, Operation: operation, World: world, Payload: payload}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	conn, err := (&net.Dialer{}).DialContext(ctx, "unix", meta.SocketPath)
	if err != nil {
		return nil, fmt.Errorf("mill socket unreachable; start one with: mill start: %w", err)
	}
	defer func() { _ = conn.Close() }()
	deadline := 5 * time.Second
	switch operation {
	case "weaver-start":
		// Weaver startup waits for the child process to publish ready metadata.
		// The client protocol deadline must cover the mill-side ready wait plus
		// a small cushion for response delivery.
		if world.ReadyTimeoutMs > 0 {
			deadline = time.Duration(world.ReadyTimeoutMs)*time.Millisecond + 5*time.Second
		} else {
			deadline = defaultWeaverReadyTimeout + 5*time.Second
		}
	case "weaver-stop":
		// Stop signals the weaver and waits for its shutdown hook to clean up
		// runtime metadata (supervised child or metadata-discovered pid), which
		// can outlast the short default protocol deadline.
		deadline = 15 * time.Second
	}
	_ = conn.SetDeadline(time.Now().Add(deadline))
	if err := json.NewEncoder(conn).Encode(req); err != nil {
		return nil, fmt.Errorf("mill socket write failed: %w", err)
	}
	var resp MillResponse
	if err := json.NewDecoder(bufio.NewReader(conn)).Decode(&resp); err != nil {
		return nil, fmt.Errorf("malformed mill response: %w", err)
	}
	if resp.ProtocolVersion != MillProtocolVersion || resp.RequestID != requestID {
		return nil, errors.New("malformed mill response: protocol version or request id mismatch")
	}
	if !resp.OK {
		if resp.Error == nil || !validResponseError(resp.Error) || resp.Result != nil {
			return nil, errors.New("malformed mill response: error envelope does not match protocol")
		}
		return nil, resp.Error
	}
	if resp.Error != nil {
		return nil, errors.New("malformed mill response: success envelope includes error")
	}
	return resp.Result, nil
}

func ReadMillMetadata() (MillMetadata, error) {
	metadata, err := readMillMetadata()
	if err != nil {
		return MillMetadata{}, fmt.Errorf("%w; start one with: mill start", err)
	}
	return metadata, nil
}

func samePath(a, b string) bool {
	ca, errA := filepath.EvalSymlinks(filepath.Clean(a))
	if errA != nil {
		ca = filepath.Clean(a)
	}
	cb, errB := filepath.EvalSymlinks(filepath.Clean(b))
	if errB != nil {
		cb = filepath.Clean(b)
	}
	return ca == cb
}

func readMillMetadata() (MillMetadata, error) {
	file, err := config.MillMetadataPath()
	if err != nil {
		return MillMetadata{}, err
	}
	b, err := os.ReadFile(file)
	if os.IsNotExist(err) {
		return MillMetadata{}, errors.New("no running mill")
	}
	if err != nil {
		return MillMetadata{}, err
	}
	var m MillMetadata
	if err := json.Unmarshal(b, &m); err != nil {
		return MillMetadata{}, fmt.Errorf("malformed mill metadata: %w", err)
	}
	root, err := config.StateRoot()
	if err != nil {
		return MillMetadata{}, err
	}
	if m.ProtocolVersion != MillProtocolVersion || m.PID == 0 || m.MillID == "" || m.StateRoot == "" || m.SocketPath == "" || m.StartedAt == "" {
		return MillMetadata{}, errors.New("malformed mill metadata: missing required fields")
	}
	if !samePath(m.StateRoot, root) {
		return MillMetadata{}, fmt.Errorf("mill metadata state root mismatch: %s", m.StateRoot)
	}
	if !samePath(m.SocketPath, filepath.Join(root, config.MillSocketFileName)) {
		return MillMetadata{}, fmt.Errorf("mill metadata socket mismatch: %s", m.SocketPath)
	}
	if !pidAlive(m.PID) {
		return MillMetadata{}, fmt.Errorf("stale mill metadata: pid %d is not alive", m.PID)
	}
	return m, nil
}
