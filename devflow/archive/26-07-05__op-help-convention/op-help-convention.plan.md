# Op help convention Plan

**Document ID:** `PLAN-OpHelp-001` **Feature:** `op-help-convention` **Proposal:** [proposal.md](./proposal.md) **RFC:** none **Root specs:** [repl-api.md](../../specs/repl-api.md), [daemon-runtime.md](../../specs/daemon-runtime.md), [cli.md](../../specs/cli.md) **Feature specs:** [specs/repl-api.delta.md](./specs/repl-api.delta.md), [specs/daemon-runtime.delta.md](./specs/daemon-runtime.delta.md), [specs/cli.delta.md](./specs/cli.delta.md) **Status:** Shipped **Last Updated:** 2026-07-05

## PLAN-OpHelp-001.P1 Goal and scope

Make `strand <op> help|-h|--help` work uniformly for subcommand-declaring ops, unescape CLI error details, and migrate the kanban op onto the `:subcommands` primitive so the motivating pain commands produce help or structured errors end to end. Contracts: the three deltas (SPEC-003-D005, SPEC-004-D005, SPEC-002-D006).

## PLAN-OpHelp-001.P2 Approach

- **PLAN-OpHelp-001.A1:** Reserve `help`/`-h`/`--help` in the shared `:subcommands` validator in `skein.api.cli.alpha` (accretion to `validate-subcommands!`; structured error naming the reserved token).
- **PLAN-OpHelp-001.A2:** Resolve the help alias **before lifecycle hook gating**, not inside `op!` after hooks have run: the socket invoke path runs payload/lifecycle hooks before `op!` (`src/skein/core/weaver/socket.clj` ~168-179, 260-267), so a mutating op's hooks must be skipped when the invocation is a help alias (SPEC-004-D005.C1a). Detect the alias where hook-class is decided (resolved entry + argv + payloads are all available there), returning `op-detail` of the entry; reuse the existing projection fn; no new registry state. Tests: alias hit for each token; alias miss for extra argv, payloads, flat-arg-spec op, raw-envelope op; missing/unknown subcommand still loud; a mutating-class op's hooks do NOT fire on a help-alias invocation (and still fire on real subcommand calls).
- **PLAN-OpHelp-001.A3:** Byte-faithful JSON at both Go escaping sites: `cli/internal/client/client.go` (`details=` stderr rendering) and `cli/internal/client/invoke.go` (single-result stdout marshalling) switch to `json.Encoder` + `SetEscapeHTML(false)` (trim the encoder's trailing newline). Go tests asserting `<` prints literally on both paths.
- **PLAN-OpHelp-001.A4:** Migrate `spools/kanban/src/skein/spools/kanban.clj` to a `:subcommands` arg-spec (about/add/board/card/next/promote/claim/note/finish/prime — the full current verb set including the newly-landed `prime`; flags/positionals from the current `parse-op-argv` calls); handlers dispatch on `:subcommand` from `:op/args`; delete `parse-op-argv` usage for this op and the hand-rolled usage error. `kanban board` zero-arg rule and `add`/`note` variadic title/text positionals must be expressed in the nested specs. Update `spools/kanban.md`. Kanban tests move hand-rolled-error assertions to parser-phase shapes and add a help-alias assertion.

## PLAN-OpHelp-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-OpHelp-001.AA1 | `src/skein/api/cli/alpha.clj` | reserved help tokens in validator |
| PLAN-OpHelp-001.AA2 | `src/skein/api/weaver/alpha.clj`, `src/skein/core/weaver/socket.clj` | help alias resolved pre-hook-gating |
| PLAN-OpHelp-001.AA3 | `cli/internal/client/client.go`, `cli/internal/client/invoke.go` | unescaped JSON on stderr details and stdout results |
| PLAN-OpHelp-001.AA4 | `spools/kanban/**`, `spools/kanban.md`, `test/skein/kanban_test.clj` | `:subcommands` migration |
| PLAN-OpHelp-001.AA5 | `devflow/specs/*` (at finish) | merge the three deltas |

## PLAN-OpHelp-001.P4 Contract and migration impact

- **PLAN-OpHelp-001.CM1:** Durable changes live in the three deltas. Kanban valid usage is unchanged; its missing/unknown-subcommand failures deliberately become parser-phase structured errors, and `kanban help|-h|--help` change from errors to exit-0 help — both are the point of the feature. The recently-added `kanban prime` subcommand (main @92788c8) must be carried into the migration.

## PLAN-OpHelp-001.P5 Implementation phases

### PLAN-OpHelp-001.PH1 Core convention

Outcome: reserved tokens validated; `op!` alias per SPEC-004-D005 with dispatch tests; Go escaping fixed with test.

### PLAN-OpHelp-001.PH2 Kanban migration

Outcome: kanban on `:subcommands`; kanban tests updated; `spools/kanban.md` current.

## PLAN-OpHelp-001.P6 Validation strategy

- **PLAN-OpHelp-001.V1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test`, `(cd cli && go test ./...)`, `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` all green.
- **PLAN-OpHelp-001.V2:** Disposable-workspace real-usage smoke reproducing the original pain points with the kanban spool activated: `strand kanban help`, `strand kanban -h`, bare `strand kanban`, `strand kanban bogus`, plus `strand help kanban` — correct exit codes (0 for help alias, non-zero for bare/unknown) and structured parser errors. Byte-faithfulness is proven where angle brackets actually occur post-migration: `strand help kanban` stdout must print literal `<` from positional docs (e.g. `<id>`), and the Go unit tests cover both encoder sites; do not rely on the deleted hand-rolled usage strings for this.

## PLAN-OpHelp-001.P7 Risks and open questions

- **PLAN-OpHelp-001.R1:** Kanban's variadic positionals (`add` title words, `note` text words) and `--handover` boolean must map cleanly onto nested specs; if a verb cannot be expressed, stop and surface rather than approximating (TEN-003).
- **PLAN-OpHelp-001.R2:** The kanban spool is an approved local root loaded by the canonical weaver; tests must use disposable worlds (`skein.test.alpha`/temp workspaces), never the canonical weaver.
- **PLAN-OpHelp-001.R3:** Recently landed kanban changes on main (`prime`, board reshape) may drift the migration surface — diff `spools/kanban/**` at task start, not from this plan.

## PLAN-OpHelp-001.P8 Task context

- **PLAN-OpHelp-001.TC1:** Read the three deltas first — they are the contract. Feature-1 primitives shipped at SPEC-003.C64/C65 + SPEC-004.C63d; reuse `validate-subcommands!`, `op-detail`, and the parse routing rather than adding parallel mechanisms. TEN-003/TEN-004/TEN-006 govern; CLI machine output stays JSON-only.

## PLAN-OpHelp-001.P9 Developer Notes

### PLAN-OpHelp-001.DN1 Feature shipped — 2026-07-05

Shipped via treadle AFK loop (pi-main), tasks 1–4, commits `665453e`/`a847c16`/`3705bae`. Post-loop verification: full suites green (470 tests / 2669 assertions / 0 fail; go ok; smoke ok). Task-4's two reported failures both resolved on verification: `skein.chime-test/notifier-binding-and-manual-notify` is a parallel-load flake (green in isolation; folded into the board's flake evidence), and the disposable-weaver start failure was the delegate's activation recipe — the coordinator's own disposable-workspace smoke (kanban spool activated via spools.edn local root + init.clj sync!/use!) reproduced every original pain-point command successfully: `kanban help|-h|--help` exit-0 detail, bare/bogus structured errors with available names, `kanban about` printing literal `<id>` with zero <. Deltas merged into root specs as SPEC-003.C65 (reserved tokens), SPEC-004.C63e (help alias), SPEC-002.C4/C39 (byte-faithful output + alias surface). Cut scope: agents/treadle/chime op migrations to :subcommands — future userland-paced work, no card filed.
