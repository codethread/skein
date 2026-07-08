# CLI Surface delta for batch task refs

**Document ID:** `DELTA-001` **Root spec:** [cli.md](../../../specs/cli.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-24 **Configuration identification:** `DELTA-001` is the CLI delta for this feature. Every nested point ID is prefixed with `DELTA-001`.

## DELTA-001.P1 Summary

The CLI gains a minimal `batch` mutation command that reads one EDN vector from standard input and creates tasks plus generic edges atomically. Batch-local refs let edge declarations target tasks created earlier or later in the same input without persisting aliases as durable identity.

## DELTA-001.P2 Contract changes

- **DELTA-001.CC1:** Add `batch` to the CLI command vocabulary as a mutation command: `clojure -M:todo [--db <path>] [--format human|edn|json] batch`.
- **DELTA-001.CC2:** `batch` takes no positional arguments and no command-specific options; it reads exactly one EDN value from standard input. Any trailing non-whitespace after that EDN value fails before writes.
- **DELTA-001.CC3:** The stdin EDN value must be a vector of task maps. Empty vectors are invalid because they usually indicate a caller mistake.
- **DELTA-001.CC4:** A batch task map requires non-blank string `:title` and may include only `:attributes`, `:ref`, and `:edges` as additional top-level keys. Unknown task keys fail loudly.
- **DELTA-001.CC5:** `:ref`, when present, must be an EDN symbol and must be unique within the batch. Refs are batch-local only and are not persisted as task fields or task attributes.
- **DELTA-001.CC6:** `:edges`, when present, must be a vector of edge maps. Each edge map requires non-blank string `:type` and target `:to`, and may include only `:attributes` as an additional key. Unknown edge keys fail loudly.
- **DELTA-001.CC7:** Edge target resolution is type-based: symbol `:to` values resolve against batch-local refs, and string `:to` values resolve against existing durable task ids. Any other `:to` value fails the command.
- **DELTA-001.CC8:** A symbolic target that is not declared as a batch-local ref fails loudly. The error should make clear that symbolic targets only resolve to batch refs and durable ids must be strings.
- **DELTA-001.CC9:** A string target that does not name an existing task fails loudly.
- **DELTA-001.CC10:** Task and edge `:attributes` must be nil or EDN maps that encode to JSON objects under the existing task-model contract. Missing or nil attributes normalize to `{}`; non-map roots or non-JSON-compatible EDN values fail before writes rather than being coerced.
- **DELTA-001.CC11:** Batch creation is all-or-nothing. Any malformed input, duplicate ref, unresolved ref, nonexistent durable target, invalid task, invalid edge, or write failure rolls back all tasks and edges from the batch.
- **DELTA-001.CC12:** Human output for a successful `batch` prints the generated ids in input order. EDN and JSON output return one value containing `:created` task rows and `:refs` mapping string ref names to generated id strings, for example `{:refs {"design" "abc12"}}`. Rows normalize JSON-bearing columns following existing output rules.
- **DELTA-001.CC13:** `batch` does not add a file input flag. Callers that want file-backed input should use shell redirection or piping into stdin.

## DELTA-001.P3 Design decisions

### DELTA-001.D1 Stdin-only EDN batch input

- **Decision:** Read a single EDN vector from standard input and omit file-specific flags.
- **Rationale:** Agents can emit heredocs, pipes, or redirected files without increasing the CLI surface.
- **Rejected:** `--file` and positional file arguments are rejected for the initial alpha API as avoidable surface area.

### DELTA-001.D2 Generic edge declarations

- **Decision:** Use `:edges [{:type "depends-on" :to some-ref}]` rather than dependency-specific keys.
- **Rationale:** The task model already supports generic typed edges, and this keeps batch creation aligned with `link` rather than growing per-edge-type syntax.
- **Rejected:** Special `:depends-on` syntax is rejected because it solves only one edge type and would need expansion later.

### DELTA-001.D3 Type-based target resolution

- **Decision:** Symbols are batch-local refs; strings are durable ids.
- **Rationale:** EDN types give agents an unambiguous way to express unresolved local targets and already-existing persistent targets.
- **Rejected:** Inferring target kind from string content is rejected because generated ids and readable aliases can overlap.

### DELTA-001.D4 Atomic batch mutation

- **Decision:** Treat one batch input as one transaction.
- **Rationale:** A partially-created DAG is surprising and hard for agents to recover from. Failing loudly should leave no partial batch state.
- **Rejected:** Best-effort or partial-success batch creation is rejected for this feature.

## DELTA-001.P4 Open questions

- **DELTA-001.Q1:** None.
