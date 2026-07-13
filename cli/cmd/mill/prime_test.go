package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// writeSourceFixture lays out a minimal Skein checkout: deps.edn (so source
// resolution accepts it) plus a prime manifest and topic files.
func writeSourceFixture(t *testing.T, manifest string, topics map[string]string) string {
	t.Helper()
	src := t.TempDir()
	if err := os.WriteFile(filepath.Join(src, "deps.edn"), []byte("{}\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if manifest != "" {
		if err := os.MkdirAll(filepath.Join(src, "docs", "prime"), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(src, primeManifestPath), []byte(manifest), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	for rel, body := range topics {
		path := filepath.Join(src, filepath.FromSlash(rel))
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return src
}

func TestRenderPrimeReadsManifestTopicsAndInterpolatesSource(t *testing.T) {
	src := writeSourceFixture(t,
		`{"version": 1, "topics": {"skein": "docs/prime/skein.md", "strand": "docs/prime/strand.md"}}`,
		map[string]string{
			"docs/prime/skein.md":  "Source lives at {{.Source}} and again {{.Source}}.\n",
			"docs/prime/strand.md": "No token here.\n",
		})
	t.Setenv("SKEIN_SOURCE", src)

	out, err := renderPrime("skein")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Count(out, src) != 2 {
		t.Fatalf("expected both {{.Source}} tokens replaced with %q:\n%s", src, out)
	}
	if strings.Contains(out, "{{") {
		t.Fatalf("skein prime left an unrendered token:\n%s", out)
	}

	out, err = renderPrime("strand")
	if err != nil {
		t.Fatal(err)
	}
	if out != "No token here.\n" {
		t.Fatalf("strand prime altered a token-free file: %q", out)
	}
}

func TestRenderPrimeFailsWhenSourceUnresolvable(t *testing.T) {
	t.Setenv("SKEIN_SOURCE", filepath.Join(t.TempDir(), "does-not-exist"))
	if _, err := renderPrime("skein"); err == nil {
		t.Fatal("expected error when SKEIN_SOURCE points at a missing directory")
	}
}

func TestRenderPrimeFailsWithoutManifest(t *testing.T) {
	src := writeSourceFixture(t, "", nil)
	t.Setenv("SKEIN_SOURCE", src)
	if _, err := renderPrime("skein"); err == nil {
		t.Fatal("expected error when the checkout ships no prime manifest")
	}
}

func TestRenderPrimeRejectsUnsupportedManifestVersion(t *testing.T) {
	src := writeSourceFixture(t,
		`{"version": 2, "topics": {"skein": "docs/prime/skein.md"}}`,
		map[string]string{"docs/prime/skein.md": "future format\n"})
	t.Setenv("SKEIN_SOURCE", src)
	_, err := renderPrime("skein")
	if err == nil || !strings.Contains(err.Error(), "upgrade mill") {
		t.Fatalf("expected loud version-mismatch error instructing an upgrade, got: %v", err)
	}
}

func TestRenderPrimeFailsOnUnknownTopic(t *testing.T) {
	src := writeSourceFixture(t,
		`{"version": 1, "topics": {"skein": "docs/prime/skein.md"}}`,
		map[string]string{"docs/prime/skein.md": "ok\n"})
	t.Setenv("SKEIN_SOURCE", src)
	if _, err := renderPrime("nope"); err == nil {
		t.Fatal("expected error for a topic the manifest does not declare")
	}
}

// TestRepoPrimeManifestPointsAtRealFiles guards the shipped contract: the
// repo's own manifest parses at the supported version, every topic file
// exists, and no topic file uses a template construct beyond the one
// {{.Source}} token renderPrime substitutes.
func TestRepoPrimeManifestPointsAtRealFiles(t *testing.T) {
	repoRoot, err := filepath.Abs(filepath.Join("..", "..", ".."))
	if err != nil {
		t.Fatal(err)
	}
	t.Setenv("SKEIN_SOURCE", repoRoot)

	raw, err := os.ReadFile(filepath.Join(repoRoot, primeManifestPath))
	if err != nil {
		t.Fatalf("repo prime manifest missing: %v", err)
	}
	var manifest primeManifest
	if err := json.Unmarshal(raw, &manifest); err != nil {
		t.Fatalf("repo prime manifest does not parse: %v", err)
	}
	if manifest.Version != primeManifestVersion {
		t.Fatalf("repo prime manifest version %d != supported %d", manifest.Version, primeManifestVersion)
	}
	if len(manifest.Topics) == 0 {
		t.Fatal("repo prime manifest declares no topics")
	}
	tokenRe := regexp.MustCompile(`\{\{[^}]*\}\}`)
	for topic, rel := range manifest.Topics {
		body, err := os.ReadFile(filepath.Join(repoRoot, filepath.FromSlash(rel)))
		if err != nil {
			t.Fatalf("topic %q points at unreadable file %s: %v", topic, rel, err)
		}
		for _, m := range tokenRe.FindAllString(string(body), -1) {
			if m != "{{.Source}}" {
				t.Fatalf("topic %q file %s uses unsupported template construct %q", topic, rel, m)
			}
		}
		if out, err := renderPrime(topic); err != nil {
			t.Fatalf("rendering repo topic %q failed: %v", topic, err)
		} else if strings.Contains(out, "{{") {
			t.Fatalf("rendering repo topic %q left an unrendered token", topic)
		}
	}
}
