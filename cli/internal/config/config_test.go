package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadNoConfigFile(t *testing.T) {
	d := t.TempDir()
	c, _, err := Load(d)
	if err != nil {
		t.Fatalf("expected no error, got %v", err)
	}
	if c.ConfigFormat != "" || c.Source != "" {
		t.Fatalf("unexpected config: %#v", c)
	}
}

func TestLoadMalformedJSON(t *testing.T) {
	d := t.TempDir()
	if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(`{"configFormat":`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := Load(d); err == nil || !strings.Contains(err.Error(), "malformed client config") {
		t.Fatalf("expected malformed error, got %v", err)
	}
}

func TestLoadRequiresConfigFormat(t *testing.T) {
	d := t.TempDir()
	if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(`{"source":"/tmp/source"}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := Load(d); err == nil || !strings.Contains(err.Error(), "configFormat is required") {
		t.Fatalf("expected configFormat required error, got %v", err)
	}
}

func TestLoadRejectsUnsupportedKeysAndValues(t *testing.T) {
	d := t.TempDir()
	if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(`{"configFormat":"alpha","where":"x"}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := Load(d); err == nil || !strings.Contains(err.Error(), "unsupported client config key: where") {
		t.Fatalf("expected unsupported key error, got %v", err)
	}

	if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(`{"configFormat":"old"}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := Load(d); err == nil || !strings.Contains(err.Error(), "unsupported client config configFormat") {
		t.Fatalf("expected configFormat value error, got %v", err)
	}

	if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(`{"configFormat":123}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := Load(d); err == nil || !strings.Contains(err.Error(), "client config configFormat must be a string") {
		t.Fatalf("expected configFormat type error, got %v", err)
	}
}

func TestLoadAcceptsValidAlphaConfig(t *testing.T) {
	d := t.TempDir()
	if err := os.WriteFile(filepath.Join(d, ConfigFileName), []byte(`{"configFormat":"alpha","source":"/tmp/source"}`), 0644); err != nil {
		t.Fatal(err)
	}
	c, world, err := Load(d)
	if err != nil {
		t.Fatalf("expected no error, got %v", err)
	}
	if c.ConfigFormat != "alpha" || c.Source != "/tmp/source" {
		t.Fatalf("unexpected config: %#v", c)
	}
	tDir, err := filepath.EvalSymlinks(d)
	if err != nil {
		t.Fatal(err)
	}
	if world.ConfigDir != tDir {
		t.Fatalf("unexpected world config dir: %#v", world)
	}
}

func TestResolveSourceSupportsLeadingHomeExpansion(t *testing.T) {
	home := t.TempDir()
	homeSource := filepath.Join(home, "skein")
	if err := os.MkdirAll(homeSource, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(home, "deps.edn"), []byte(`{}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(homeSource, "deps.edn"), []byte(`{}`), 0o644); err != nil {
		t.Fatal(err)
	}
	t.Setenv("HOME", home)

	resolved, err := ResolveSource("~")
	if err != nil {
		t.Fatalf("expected leading ~ to resolve, got %v", err)
	}
	if resolved != home {
		t.Fatalf("unexpected resolved source: %q", resolved)
	}

	resolved, err = ResolveSource("~/skein")
	if err != nil {
		t.Fatalf("expected leading ~/ to resolve, got %v", err)
	}
	if resolved != homeSource {
		t.Fatalf("unexpected resolved source: %q", resolved)
	}

	resolved, err = ResolveSource("~" + string(filepath.Separator) + "skein")
	if err != nil {
		t.Fatalf("expected leading ~/ path to resolve, got %v", err)
	}
	if resolved != homeSource {
		t.Fatalf("unexpected resolved source: %q", resolved)
	}
}

func TestResolveSourceRejectsRelativePath(t *testing.T) {
	if _, err := ResolveSource("relative"); err == nil || !strings.Contains(err.Error(), "source must be an absolute path") {
		t.Fatalf("expected absolute path error, got %v", err)
	}
}
