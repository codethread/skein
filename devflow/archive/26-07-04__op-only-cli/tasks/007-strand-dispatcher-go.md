# Task 7: Go strand dispatcher rewrite

**Document ID:** `TASK-Ooc-007`

## TASK-Ooc-007.P1 Scope

Type: AFK

Rewrite the `strand` binary as the pure invoke-envelope dispatcher per SPEC-002-D004.C1–C8: dispatcher flags, envelope assembly, payloads, `--dry-run`, NDJSON relay. The builtin Cobra command tree for strand commands is deleted. `mill` subcommand absorption and full integration-test green are task 8; this task's gate is the strand binary itself plus unit tests for the packages it owns.

## TASK-Ooc-007.P2 Must implement exactly

- **TASK-Ooc-007.MI1:** `strand [flags] <op-name> [args...]`: flag parsing stops at the first non-flag token; everything after ships verbatim as `argv`. Flags exactly: `--workspace`, `--cwd`, `--worktree-root`, `--git-common-dir`, `--stdin`, `--payload name=path` (repeatable), `--timeout <dur>`, `--dry-run`, `--version`, `--help`. No other flags; no subcommands. Cobra may stay for the root command's flag/help plumbing or go entirely — implementer's choice; the strand command tree in `cli/internal/command` is removed either way.
- **TASK-Ooc-007.MI2:** Context resolution per SPEC-002-D004.C2 precedence: `--workspace` wins; else git derivation from effective cwd (`--cwd` or process cwd) yielding worktree root + git common dir, explicit flags overriding each; derivation failure with nothing pinned fails loudly with remediation. Reuse the existing workspace-selection code paths.
- **TASK-Ooc-007.MI3:** Payloads: `--stdin` reads stdin to EOF into slot `stdin` (no auto-read without the flag; empty stdin with the flag is an empty-string payload, not an error); `--payload name=path` reads the file client-side; duplicate slot names (including `stdin`) fail loudly before transport.
- **TASK-Ooc-007.MI4:** Envelope assembly per SPEC-002-D004.C6 and transport via the existing mill routing path (`cli/internal/client`): frame identity fields as today, `invoke` operation, envelope arguments including `client{pid,version}`. `--timeout` parses Go-duration style and rides in the envelope (seconds or ms — match what task 6 implemented; check the Clojure side and mirror it exactly).
- **TASK-Ooc-007.MI5:** Response relay: single-frame responses print the result as one JSON line (errors to stderr, non-zero exit, preserving today's error-envelope surfacing); stream responses print each emitted line as received (flush per line) and exit by the terminator's success flag. Interrupt (SIGINT) during a stream exits non-zero cleanly.
- **TASK-Ooc-007.MI6:** `--dry-run`: assemble and print the envelope JSON (frame identity placeholders per SPEC-002-D004.C6) without contacting mill; works with no mill/weaver running. `--version`: bin + protocol version JSON. `--help`/bare `strand`: static text covering flags, envelope contract, `strand help` pointer, `mill start` remediation.
- **TASK-Ooc-007.MI7:** Unit tests (Go) for: flag-stop-at-op-name, precedence rules, payload slot collision, dry-run output shape, stream relay (mock socket), error exit codes. Delete now-dead strand command-tree tests.

## TASK-Ooc-007.P3 Done when

- **TASK-Ooc-007.DW1:** `(cd cli && go build ./... && go test ./cmd/strand/... ./internal/...)` green for the packages this task owns; `cli/integration_test.go` and mill lifecycle tests may still be red (task 8 owns them) — state exactly which tests remain red in your completion notes.
- **TASK-Ooc-007.DW2:** Weaverless checks with the locally built strand binary: `strand --help`, `strand --version`, `strand --dry-run add 'x' --attr k=v` (envelope shape correct), `echo body | strand --stdin --dry-run add 'y' --attr body=:stdin` (payload attached), plus the no-mill failure path (loud remediation, non-zero). Live end-to-end against a running weaver is deliberately deferred to task 8 (no supported CLI path to start a weaver exists between tasks 7 and 8); stream relay is unit-tested against a mock socket here.

## TASK-Ooc-007.P4 Out of scope

- **TASK-Ooc-007.OS1:** Mill subcommands (`init`, `weaver *`), mill forward streaming, integration-test rewrite (task 8); spool/doc updates.

## TASK-Ooc-007.P5 References

- **TASK-Ooc-007.REF1:** cli delta SPEC-002-D004.C1–C8/C11; RFC-019.D2/D3; plan A5, R2 (relay points), TC1 (local `go build` to `/tmp/claude/ooc-bin/`, never `make install`); `cli/internal/command`, `cli/internal/client`, `cli/cmd/strand/main.go`.
