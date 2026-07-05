package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRenderPrimeInterpolatesSource(t *testing.T) {
	src := t.TempDir()
	if err := os.WriteFile(filepath.Join(src, "deps.edn"), []byte("{}\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	t.Setenv("SKEIN_SOURCE", src)

	for _, tc := range []struct {
		topic string
		tmpl  string
	}{
		{"skein", primeSkein},
		{"strand", primeStrand},
	} {
		out, err := renderPrime(tc.topic, tc.tmpl)
		if err != nil {
			t.Fatalf("%s: %v", tc.topic, err)
		}
		if !strings.Contains(out, src) {
			t.Fatalf("%s prime did not interpolate source %q:\n%s", tc.topic, src, out)
		}
		if strings.Contains(out, "{{") {
			t.Fatalf("%s prime left an unrendered template action:\n%s", tc.topic, out)
		}
	}
}

func TestRenderPrimeFailsWhenSourceUnresolvable(t *testing.T) {
	t.Setenv("SKEIN_SOURCE", filepath.Join(t.TempDir(), "does-not-exist"))
	if _, err := renderPrime("skein", primeSkein); err == nil {
		t.Fatal("expected error when SKEIN_SOURCE points at a missing directory")
	}
}
