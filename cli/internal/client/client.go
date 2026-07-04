package client

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"syscall"
)

const protocolVersion = 1

// ProtocolVersion is the JSON socket protocol version the bin speaks; exported
// for the dispatcher's --version and dry-run frame identity.
const ProtocolVersion = protocolVersion

type Metadata struct {
	ProtocolVersion int    `json:"protocol_version"`
	PID             int    `json:"pid"`
	DatabaseKind    string `json:"database_kind"`
	DatabaseLabel   string `json:"database_label"`
	// Pointer so a JSON null (required for sqlite-memory) is distinguishable
	// from an accidental empty string.
	DatabasePath *string `json:"database_path"`
	DaemonID     string  `json:"weaver_id"`
	ConfigDir    string  `json:"config_dir"`
	StateDir     string  `json:"state_dir"`
	DataDir      string  `json:"data_dir"`
	Name         string  `json:"name"`
	SocketPath   string  `json:"socket_path"`
	StartedAt    string  `json:"started_at"`
	NREPL        struct {
		Host string `json:"host"`
		Port int    `json:"port"`
	} `json:"nrepl"`
}

type ResponseError struct {
	Type    string         `json:"type"`
	Code    string         `json:"code"`
	Message string         `json:"message"`
	Details map[string]any `json:"details"`
}

func (e *ResponseError) Error() string {
	if e == nil {
		return "weaver error"
	}
	message := e.Message
	if query, ok := e.Details["canonical-query"].(string); ok && query != "" {
		message = fmt.Sprintf("%s: %s", message, query)
	}
	if available, ok := e.Details["available"].([]any); ok && len(available) > 0 {
		names := []string{}
		for _, v := range available {
			if s, ok := v.(string); ok {
				names = append(names, s)
			}
		}
		if len(names) > 0 {
			message = fmt.Sprintf("%s (available: %s)", message, strings.Join(names, ", "))
		}
	}
	if e.Code == "database/not-initialized" {
		return message
	}
	// ex-data details are the machine-readable half of a fail-loudly error;
	// agents scripting the CLI need them, so append them as compact JSON
	if len(e.Details) > 0 {
		if encoded, err := json.Marshal(e.Details); err == nil {
			message = fmt.Sprintf("%s details=%s", message, encoded)
		}
	}
	if e.Code != "" {
		return fmt.Sprintf("weaver %s error (%s): %s", e.Type, e.Code, message)
	}
	return fmt.Sprintf("weaver %s error: %s", e.Type, message)
}

func validResponseError(e *ResponseError) bool {
	if e.Code == "" || e.Message == "" || e.Details == nil {
		return false
	}
	switch e.Type {
	case "domain", "protocol", "transport":
		return true
	default:
		return false
	}
}

// DatabasePathString returns the file-backed database path, or "" when the
// metadata carries no path (sqlite-memory publishes an explicit null).
func (m Metadata) DatabasePathString() string {
	if m.DatabasePath == nil {
		return ""
	}
	return *m.DatabasePath
}

// ValidateStorageIdentity fails unless database kind, label, and path are
// mutually consistent: sqlite-file requires a non-blank label == path;
// sqlite-memory requires a non-blank label and a null path.
func ValidateStorageIdentity(m Metadata) error {
	if strings.TrimSpace(m.DatabaseLabel) == "" {
		return fmt.Errorf("blank weaver storage label for kind %q", m.DatabaseKind)
	}
	switch m.DatabaseKind {
	case "sqlite-file":
		if m.DatabasePath == nil || strings.TrimSpace(*m.DatabasePath) == "" || m.DatabaseLabel != *m.DatabasePath {
			return fmt.Errorf("inconsistent sqlite-file storage metadata: label %q path %q", m.DatabaseLabel, m.DatabasePathString())
		}
	case "sqlite-memory":
		if m.DatabasePath != nil {
			return fmt.Errorf("inconsistent sqlite-memory storage metadata: label %q path %q", m.DatabaseLabel, *m.DatabasePath)
		}
	default:
		return fmt.Errorf("unknown weaver storage kind %q", m.DatabaseKind)
	}
	return nil
}

func pidAlive(pid int) bool {
	p, err := os.FindProcess(pid)
	if err != nil {
		return false
	}
	return p.Signal(syscall.Signal(0)) == nil
}
