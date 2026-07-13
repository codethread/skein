package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// primeManifestPath is the one frozen path in the prime contract: every Skein
// checkout ships a manifest here naming its prime topics, so an already
// installed mill renders current orientation text from a newer checkout
// instead of shipping stale embedded prose. The path itself must never move;
// the manifest indirection exists so everything it points at still can.
const primeManifestPath = "docs/prime/index.json"

// primeManifestVersion is the manifest schema this mill understands. A future
// checkout needing richer rendering bumps the manifest version, and this guard
// turns that skew into a loud upgrade instruction instead of garbled output.
const primeManifestVersion = 1

type primeManifest struct {
	Version int               `json:"version"`
	Topics  map[string]string `json:"topics"`
}

// renderPrime resolves the Skein source, looks the topic up in the checkout's
// prime manifest, and renders the file the manifest names. The rendering
// contract is a single token: every {{.Source}} occurrence becomes the
// resolved checkout path, and no other template construct is interpreted, so
// any future implementation can reproduce it with a string substitution.
func renderPrime(topic string) (string, error) {
	source, err := resolveLaunchSource("")
	if err != nil {
		return "", fmt.Errorf("mill %s prime cannot resolve the Skein source that hosts the docs: %w", topic, err)
	}
	manifestPath := filepath.Join(source, filepath.FromSlash(primeManifestPath))
	raw, err := os.ReadFile(manifestPath)
	if err != nil {
		return "", fmt.Errorf("mill %s prime: reading prime manifest: %w", topic, err)
	}
	var manifest primeManifest
	if err := json.Unmarshal(raw, &manifest); err != nil {
		return "", fmt.Errorf("mill %s prime: parsing prime manifest %s: %w", topic, manifestPath, err)
	}
	if manifest.Version != primeManifestVersion {
		return "", fmt.Errorf("mill %s prime: manifest %s is version %d but this mill supports version %d; upgrade mill", topic, manifestPath, manifest.Version, primeManifestVersion)
	}
	rel, ok := manifest.Topics[topic]
	if !ok {
		return "", fmt.Errorf("mill %s prime: manifest %s declares no %q topic", topic, manifestPath, topic)
	}
	body, err := os.ReadFile(filepath.Join(source, filepath.FromSlash(rel)))
	if err != nil {
		return "", fmt.Errorf("mill %s prime: reading manifest topic file %s: %w", topic, rel, err)
	}
	return strings.ReplaceAll(string(body), "{{.Source}}", source), nil
}

func runPrime(topic string) error {
	out, err := renderPrime(topic)
	if err != nil {
		return err
	}
	if _, err := fmt.Fprint(os.Stdout, out); err != nil {
		return fmt.Errorf("writing prime output: %w", err)
	}
	return nil
}
