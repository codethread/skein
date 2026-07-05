# Task 2: Byte-faithful JSON output in Go CLI

**Document ID:** `TASK-OpHelp-002`

## TASK-OpHelp-002.P1 Scope

Type: AFK

Stop HTML-escaping JSON in the strand CLI per SPEC-002-D006.C2 (devflow/feat/op-help-convention/specs/cli.delta.md).

## TASK-OpHelp-002.P2 Must implement exactly

- **TASK-OpHelp-002.MI1:** `cli/internal/client/client.go` (`details=` rendering, ~line 70) and `cli/internal/client/invoke.go` (single-result stdout marshalling, ~line 100): replace `json.Marshal` with `json.Encoder` + `SetEscapeHTML(false)` (trim the encoder's trailing newline where the current code expects no newline). Audit the client package for any other `json.Marshal` feeding user-visible output (stream relay path relays weaver bytes as received — verify and leave untouched if so).
- **TASK-OpHelp-002.MI2:** Go tests: details string containing `<usage>` prints literally in the error message; a result payload containing `<` marshals to stdout without `<`.

## TASK-OpHelp-002.P3 Done when

- **TASK-OpHelp-002.DW1:** `(cd cli && go test ./...)` green.

## TASK-OpHelp-002.P4 Out of scope

- **TASK-OpHelp-002.OS1:** Any weaver-side change; dispatcher flag surface; mill output paths (JSON there comes from weaver metadata passthrough — out of scope unless a test proves user-visible escaping).

## TASK-OpHelp-002.P5 References

- **TASK-OpHelp-002.REF1:** specs/cli.delta.md SPEC-002-D006.C2, plan A3.
