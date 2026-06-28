# Task 6: Expose supersession through helpers and CLI

**Document ID:** `ERF-TASK-006`

## ERF-TASK-006.P1 Scope

Type: AFK

Expose the core supersession operation through trusted Clojure helpers, JSON socket dispatch, and a thin Go CLI command.

## ERF-TASK-006.P2 Must implement exactly

- **ERF-TASK-006.MI1:** Add the supersession operation to Clojure client/weaver operation routing and JSON socket dispatch.
- **ERF-TASK-006.MI2:** Add a REPL/helper surface with unambiguous argument order. Prefer wording such as `(supersede! old-id replacement-id)` while documenting that the stored edge is `replacement --supersedes--> old`.
- **ERF-TASK-006.MI3:** Add a Go CLI command over the same operation with simple JSON-shaped input, for example `strand supersede <old-id> <replacement-id>` or an equivalent command chosen consistently in help/tests.
- **ERF-TASK-006.MI4:** CLI and helper outputs must return the normalized supersession result from the weaver, not re-implement graph logic client-side.
- **ERF-TASK-006.MI5:** Add Go command tests for help text, payload construction, invalid arg counts, and JSON output.
- **ERF-TASK-006.MI6:** Add Clojure client/repl/socket tests proving the helper and socket route to the weaver operation and preserve domain errors.
- **ERF-TASK-006.MI7:** Ensure generic `update --state replaced` and generic REPL update to `state="replaced"` fail; supersession is the only public way to produce replaced state.

## ERF-TASK-006.P3 Done when

- **ERF-TASK-006.DW1:** `(cd cli && go test ./...)` passes.
- **ERF-TASK-006.DW2:** Focused Clojure socket/repl/client supersession tests pass.
- **ERF-TASK-006.DW3:** CLI help clearly distinguishes `supersede` from raw `update --edge` usage.

## ERF-TASK-006.P4 Out of scope

- **ERF-TASK-006.OS1:** Relation declaration CLI commands.
- **ERF-TASK-006.OS2:** Supersession edge migration beyond the core transaction already implemented in Task 5.
- **ERF-TASK-006.OS3:** Public docs sweep; this task may update command help/tests only.

## ERF-TASK-006.P5 References

- **ERF-TASK-006.REF1:** `devflow/feat/edge-relation-families/specs/cli.delta.md`
- **ERF-TASK-006.REF2:** `devflow/feat/edge-relation-families/specs/repl-api.delta.md`
- **ERF-TASK-006.REF3:** `src/skein/repl.clj`
- **ERF-TASK-006.REF4:** `cli/internal/command/command.go`
