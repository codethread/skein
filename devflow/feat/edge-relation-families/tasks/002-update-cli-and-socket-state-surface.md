# Task 2: Update CLI and socket state surface

**Document ID:** `ERF-TASK-002`

## ERF-TASK-002.P1 Scope

Type: AFK

Update the public JSON socket and Go CLI lifecycle contract from old boolean lifecycle flags/fields to `state`.

## ERF-TASK-002.P2 Must implement exactly

- **ERF-TASK-002.MI1:** Update `src/skein/weaver/socket.clj` request validation/dispatch so `add` and `update` accept `state` values `active|closed`, `list` accepts optional `state active|closed|replaced`, and old `active` args fail.
- **ERF-TASK-002.MI2:** Update socket list filtering to compile `[:= :state <value>]`.
- **ERF-TASK-002.MI3:** In `cli/internal/command/command.go`, replace legacy boolean lifecycle flag with `--state` for `add`, `update`, and `list`. `add`/`update` accept `active|closed`; `list` accepts `active|closed|replaced`.
- **ERF-TASK-002.MI4:** Remove or replace the Go `boolFlag` lifecycle path with explicit state validation. Do not keep legacy boolean lifecycle flag as a hidden alias.
- **ERF-TASK-002.MI5:** Update Go unit tests and integration tests for help text, payload construction, validation errors, and JSON output expectations.
- **ERF-TASK-002.MI6:** Update any Clojure socket tests that still send/expect old lifecycle fields.

## ERF-TASK-002.P3 Done when

- **ERF-TASK-002.DW1:** `(cd cli && go test ./...)` passes.
- **ERF-TASK-002.DW2:** Relevant Clojure socket/weaver tests pass.
- **ERF-TASK-002.DW3:** `rg -- 'legacy boolean lifecycle flag' cli src test` returns no public CLI/socket compatibility paths.

## ERF-TASK-002.P4 Out of scope

- **ERF-TASK-002.OS1:** Supersession CLI command.
- **ERF-TASK-002.OS2:** Public docs and root spec promotion.

## ERF-TASK-002.P5 References

- **ERF-TASK-002.REF1:** `devflow/feat/edge-relation-families/specs/cli.delta.md`
- **ERF-TASK-002.REF2:** `devflow/feat/edge-relation-families/specs/daemon-runtime.delta.md`
- **ERF-TASK-002.REF3:** `cli/internal/command/command.go`
