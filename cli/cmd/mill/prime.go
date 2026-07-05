package main

import (
	"bytes"
	_ "embed"
	"fmt"
	"os"
	"text/template"
)

//go:embed prime/skein.md
var primeSkein string

//go:embed prime/strand.md
var primeStrand string

// primeData is the interpolation context for prime templates. Source is the
// Skein source checkout resolved at runtime (never hardcoded) so the printed
// doc paths point at the real installed sources.
type primeData struct {
	Source string
}

// renderPrime resolves the Skein source and interpolates it into the topic's
// embedded template. It is client-side and needs no running weaver.
func renderPrime(topic, tmpl string) (string, error) {
	source, err := resolveLaunchSource("")
	if err != nil {
		return "", fmt.Errorf("mill %s prime cannot resolve the Skein source that hosts the docs: %w", topic, err)
	}
	t, err := template.New(topic).Parse(tmpl)
	if err != nil {
		return "", err
	}
	var buf bytes.Buffer
	if err := t.Execute(&buf, primeData{Source: source}); err != nil {
		return "", err
	}
	return buf.String(), nil
}

func runPrime(topic, tmpl string) error {
	out, err := renderPrime(topic, tmpl)
	if err != nil {
		return err
	}
	fmt.Fprint(os.Stdout, out)
	return nil
}
