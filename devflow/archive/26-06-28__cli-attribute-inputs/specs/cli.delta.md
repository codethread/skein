# CLI Surface Delta: Attribute Input Sources

**Document ID:** `DELTA-004` **Status:** Merged **Target spec:** [CLI Surface](../../../specs/cli.md) **Feature:** [CLI Attribute Inputs](../proposal.md)

## DELTA-004.P1 Summary

`strand add` gains explicit attribute input sources for large text and bulk JSON templates while preserving the thin JSON control-surface model.

## DELTA-004.P2 Interface delta

Replace the current `add` command shape with:

```text
add <title> [--active true|false] [--attr key=value ...] [--attr-file key=path ...] [--attr-stdin key] [--attributes-stdin]
```

## DELTA-004.P3 Contract deltas

- **DELTA-004.C1:** `--attr key=value` keeps existing behavior and writes a string-valued attribute.
- **DELTA-004.C2:** `--attr-file key=path` reads the exact file contents and writes that string as attribute `key`; it may be repeated.
- **DELTA-004.C3:** `--attr-stdin key` reads all stdin and writes that string as attribute `key`; it may appear at most once.
- **DELTA-004.C4:** `--attributes-stdin` reads exactly one JSON object from stdin and merges its properties into the attributes map.
- **DELTA-004.C5:** `--attr-stdin` and `--attributes-stdin` are mutually exclusive because both consume stdin.
- **DELTA-004.C6:** Attribute merge precedence is `--attr` highest, then per-attribute stream/file sources, then `--attributes-stdin` lowest. Cross-priority duplicates are allowed and resolved by precedence.
- **DELTA-004.C7:** Duplicate keys within the same source priority fail loudly, including repeated `--attr`, repeated `--attr-file`, and a repeated key across `--attr-file` plus `--attr-stdin`.
- **DELTA-004.C8:** Malformed key/value flags, blank keys, unreadable files, empty or malformed JSON stdin, non-object `--attributes-stdin` values, trailing JSON stdin values, and incompatible stdin consumers fail before mutation.
- **DELTA-004.C9:** `--attributes-stdin` preserves JSON attribute value types from the input object; string-valued flags remain string overrides.

## DELTA-004.P4 Non-goals

- **DELTA-004.N1:** No change to `update` in this feature.
- **DELTA-004.N2:** No whole-strand JSON input format.
