# CLI Attribute Inputs Proposal

**Document ID:** `PROP-002` **Status:** Proposed **Related RFCs:** None **Related specs:** [CLI Surface](../../specs/cli.md)

## PROP-002.P1 Problem

Large string attributes such as Markdown plans are awkward to create through `strand add --attr key=value` because shell quoting and JSON escaping make multiline content error-prone.

## PROP-002.P2 Goals

- **PROP-002.G1:** Let agents attach large per-attribute text values to newly created strands without manual escaping.
- **PROP-002.G2:** Let agents load a bulk attribute template from JSON stdin and override selected fields with explicit CLI flags.
- **PROP-002.G3:** Keep the public CLI a thin JSON control surface that validates input locally and sends one normal weaver `add` request.

## PROP-002.P3 Non-goals

- **PROP-002.N1:** Do not add rich EDN/Clojure authoring to `strand add`.
- **PROP-002.N2:** Do not change weaver persistence or strand attribute storage.
- **PROP-002.N3:** Do not add a whole-strand JSON request format in this feature.

## PROP-002.P4 Proposed scope

- **PROP-002.S1:** Add `--attr-file key=path` for file-backed string attributes.
- **PROP-002.S2:** Add `--attr-stdin key` for one stdin-backed string attribute.
- **PROP-002.S3:** Add `--attributes-stdin` for one stdin JSON object of bulk attributes.
- **PROP-002.S4:** Merge explicit per-field attributes over lower-priority bulk/template attributes, while failing loudly on malformed or ambiguous input.

## PROP-002.P5 Open questions

- **PROP-002.Q1:** None for the initial implementation.
