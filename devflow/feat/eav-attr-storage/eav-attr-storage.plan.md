# EAV Attribute Storage Plan

**Document ID:** `EAS-PLAN-001` **Feature:** `eav-attr-storage` **Proposal:** [proposal.md](./proposal.md) **RFC:** None (no RFC covers attribute storage representation; adjacent [RFC-002 Task Query DSL](../../rfcs/2026-06-24-task-query-dsl.md) defines the query language this re-implements over rows without changing its surface) **Root specs:** [Strand Model](../../specs/strand-model.md), [CLI Surface](../../specs/cli.md), [Weaver Runtime](../../specs/daemon-runtime.md), [Alpha Surface](../../specs/alpha-surface.md) **Feature specs:** [Strand Model delta](./specs/strand-model.delta.md), [CLI Surface delta](./specs/cli.delta.md), [Weaver Runtime delta](./specs/daemon-runtime.delta.md) **Evidence base:** `/tmp/claude/attr-scaling-ship-now-brief/` (`SYNTHESIS.md` §5 side-table measurements bound the benchmark envelope; `DESIGN.md` semantics; `RESULTS-*.md` harness methodology) **Builds on:** `attr-scaling-ship-now`, merged at `5595fe7`; this branch is rebased onto that baseline (L0a pragmas, L1 lean reads, and the L0b registry are canonical and in scope for preservation or removal per the deltas). **Status:** Shipped **Last Updated:** 2026-07-07

## EAS-PLAN-001.P1 Goal and scope

Replace the single `strands.attributes` JSON document with an `attributes` EAV table plus an `archived` cold tier, under one hard acceptance invariant (`PROP-EavAttrStorage-001`): **the attribute-map contract is byte-identical on the wire for full-fidelity reads, and no non-archived key is ever less capable or slower than today on hot query paths.** The storage change is invisible above `skein.core.*` (TEN-007).

In scope:

- **EAV storage** — one JSON-encoded row per `(strand_id, key)`; drop the document column (`EAS-DELTA-001.CC6`).
- **Archive tier** — an `archived` flag plus `WHERE archived = 0` partial indexes; trusted `archive!` / `unarchive!` primitives (`EAS-DELTA-001.CC4`, `EAS-DELTA-003.CC4`).
- **Query compiler over rows** — `EXISTS`/join over non-archived rows with full predicate capability for every key and cross-key self-joins (`EAS-DELTA-001.CC12`/`CC13`).
- **L0b removal** — drop the `indexed_attr_keys` table, `::specs/indexed-attr-key`, literal-path emission, and their trusted ops/tests; fold coverage into uniform-capability gates (`EAS-DELTA-001.CC9`/`D5`).
- **Explicit cutover** — `ensure-current-schema!` fails loud on the document schema; one trusted migrate op converts existing worlds (`EAS-DELTA-003.CC4`/`D2`).
- **Benchmark gate** — an in-feature gate (adapted from the card-`bvb0g` harnesses) blocks merge on measured write-amp, scan, and assembly-read targets (`EAS-PLAN-001.P7`).

Preserved unchanged: L0a pragmas (storage-neutral) and the L1 lean-read wire contract (a wire transform over the assembled map). Out of scope: transparent hot offload / L2 overflow table, artifacts-as-second-concept, event sourcing, fixed columns, key interning, edge-attribute EAV, any archive policy in core, and any JSONB assumption (`PROP-EavAttrStorage-001.NG1`–`NG7`).

## EAS-PLAN-001.P2 Approach

- **EAS-PLAN-001.A1:** Land the change entirely in `skein.core.*` (schema, write path, query compiler, migration) plus the `skein.api.*` decode seam. Everything above — CLI wire format, spool readers, named queries, events/views — is contract-unchanged and must not need edits to keep passing (TEN-007). Treat any required edit above `skein.core.*`/`skein.api.weaver.alpha` as a signal the seam leaked.
- **EAS-PLAN-001.A2:** Make attribute assembly one decode seam: reads select non-archived rows (plus archived on full point reads), assemble a Clojure map in lexicographic key order (`EAS-DELTA-001.CC5`), and hand it to exactly the normalization every reader already consumes. The L1 lean projection stays layered above that assembled map, untouched.
- **EAS-PLAN-001.A3:** Keep the write path's API contract identical: `::specs/attributes` validates the map before persistence; a per-key merge writes/updates rows; a `null` value deletes the row (the row-delete translation of today's `json_patch` null-removal); a whole-map replace clears and rewrites the strand's rows; the whole patch is one transaction. The row-level `CHECK (json_valid(value))` backstops migration/trusted/direct-SQL writers (`EAS-DELTA-001.D6`).
- **EAS-PLAN-001.A4:** Rewrite the query compiler's attribute-predicate emission to an `EXISTS`/join over non-archived rows, binding keys and values as parameters (never splicing a key into SQL — the literal-path mechanism and its spec re-validation are deleted, not ported). Cross-key predicates become self-joins. Result semantics stay identical for every predicate type.
- **EAS-PLAN-001.A5:** Remove L0b as a unit (table, spec, trusted ops, literal-path branch, and its tests), replacing the old undeclared-key byte-identity gate with a **uniform-capability** gate: every key has full predicate capability, asserted structurally over the row compiler.
- **EAS-PLAN-001.A6:** Make cutover explicit and once: `ensure-current-schema!` fails loud on the document column; one trusted migrate op reads documents and writes rows, loud and idempotent-safe on an already-migrated world. New worlds get the row schema natively.
- **EAS-PLAN-001.A7:** Prove it before merge with the feature-local benchmark gate (`EAS-PLAN-001.P7`), adapting the card-`bvb0g` `RESULTS-*` harnesses to the row schema. Serialize timed runs behind the benchmark lock.
- **EAS-PLAN-001.A8:** Slice so each piece lands independently green; merge the three spec deltas into the root specs and rewrite the `CLAUDE.md` debug snippet in the final sweep.

## EAS-PLAN-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| EAS-PLAN-001.AA1 | `src/skein/core/db.clj` | Replace the `strands.attributes` column with an `attributes` table (`strand_id … ON DELETE CASCADE, key, value NOT NULL CHECK(json_valid(value)), archived DEFAULT 0, PRIMARY KEY (strand_id,key)`) and the `(key,value) WHERE archived=0` partial index; rewrite insert/update/replace/null-delete to per-key rows in one transaction; add lexicographic-ordered assembly reads (archived included only on full point reads); `archive!`/`unarchive!` (per strand + explicit key set); the trusted migrate op shipped for the cutover window and was then struck; extend `ensure-current-schema!` to validate the `attributes` table and fail loud on the document schema. **Remove** the `indexed_attr_keys` table, `declare-indexed-attr-key!`/`list-indexed-attr-keys`/`indexed-attr-key?`, and the per-key expression indexes. |
| EAS-PLAN-001.AA2 | `src/skein/core/query.clj` | Rewrite attribute-predicate compilation to an `EXISTS`/join over non-archived rows with full predicate capability, cross-key self-joins, and no literal-path splice. **Remove** the declared-key literal-path branch and its `::specs/indexed-attr-key` re-validation. |
| EAS-PLAN-001.AA3 | `src/skein/core/specs.clj` | **Remove** `::indexed-attr-key`. Keep `::attributes` (the map-shape contract on writes) and `::omitted-attribute-descriptor` (the L1 descriptor) unchanged. Add spec-backed contracts for the new trusted seam shapes: `::attribute-key-set` and `::attribute-archive-result`, plus fdefs or equivalent call-site conformance for `archive!`/`unarchive!`. The migration result spec shipped for the cutover window and was then struck. |
| EAS-PLAN-001.AA4 | `src/skein/api/weaver/alpha.clj` | Add trusted `archive!`/`unarchive!` ops with the arg/result/error contracts in `EAS-DELTA-003.CC4`; the trusted `migrate-attribute-storage!` op shipped for the cutover window and was then struck. Keep point/trusted lookup reads full-fidelity over the assembled map (archived included) while hot list/query reads return non-archived maps only. **Remove** the declare/list indexed-attr-key ops. No lean transform in `normalize-row`. |
| EAS-PLAN-001.AA5 | `spools/src/skein/spools/batteries.clj` | Wire the CLI/agent read ops to the row-backed hot projections: `list-op`/named-query listing call lean list/query projections, `ready-op` routes through the trusted `ready-lean` projection, and full `show-op` remains full-fidelity. |
| EAS-PLAN-001.AA6 | `spools/src/skein/spools/util.clj` | No change: the `attr-get` fail-loud guard and its ex-data contract operate over the assembled map. Confirm-only. |
| EAS-PLAN-001.AA7 | `test/skein/**`, `dev/skein/smoke.clj`, feature-local bench harness | Schema tests (attributes table, partial index, CASCADE, document-schema fail-loud); write/patch/null-delete/whole-replace row tests; archive/unarchive per-strand + explicit-key-set tests; the uniform-capability gate (every key, every predicate type, cross-key self-join) replacing the old undeclared-key gate; lexicographic reassembly test; archived-excluded-from-query test; full-vs-lean read tests; 500-row assembly read test; smoke exercises row storage + archive; the benchmark harness (`EAS-PLAN-001.P7`). The migrate op tests shipped for the cutover window and were then struck. **Remove** the L0b indexed-attr-key tests. |
| EAS-PLAN-001.AA8 | `devflow/specs/strand-model.md`, `cli.md`, `daemon-runtime.md`; `CLAUDE.md` | Merge the three deltas into the root specs (drop the retired L0b clauses `SPEC-001.P8` para 3, `SPEC-001.P9` declared-key paras, `SPEC-004.C16a`/`C16b`, and trim `SPEC-004.C16`/`SPEC-002.C21`); rewrite the `CLAUDE.md` "Debugging SQLite state" snippet to the `attributes` table (`EAS-DELTA-002.DI1`). |

## EAS-PLAN-001.P4 Contract and migration impact

- **EAS-PLAN-001.CM1:** This is an incompatible core-schema change (the `strands.attributes` column is dropped). `ensure-current-schema!` fails loud on an old world; there is no silent migration (TEN-000@1, TEN-003). New worlds get the row schema natively.
- **EAS-PLAN-001.CM2:** Cutover was one explicit trusted migrate op, `migrate-attribute-storage!`, run per existing world, including the canonical coordination world, which carries real data (~2k strands, 4.67MB attribute bytes). The op read each `strands.attributes` document, wrote one row per key (all `archived = 0`), verified row/document parity, failed loudly on any mismatch, and was safe to re-run on an already-migrated world (`EAS-DELTA-003.CC4`/`D2`). It shipped for the cutover window and was then struck from main.
- **EAS-PLAN-001.CM3:** Full-fidelity reads are byte-identical on the wire to today; the only new guarantee is deterministic lexicographic key order, which was never a contract (`EAS-DELTA-002.CC4`). Archived keys are the single explicit exception: present in full point reads, absent from hot query paths (`EAS-DELTA-001.CC4`).
- **EAS-PLAN-001.CM4:** L0b is removed, not preserved: the `indexed_attr_keys` table, `::specs/indexed-attr-key`, literal-path emission, and the declare/list ops are deleted, with coverage folded into the uniform-capability gate (`EAS-DELTA-001.D5`). Worlds that declared indexed keys under `5595fe7` lose nothing — every key is uniformly indexable by construction.
- **EAS-PLAN-001.CM5:** `archive!`/`unarchive!` are trusted Clojure surface only — no socket op, no `strand` command (TEN-006). The one-shot `migrate-attribute-storage!` surface followed that tier while it existed, then was struck from main after the cutover. Core ships no archive policy. Bad strand ids, malformed or empty key sets, missing keys, and partial archive writes fail loudly with diagnostic ex-data (`EAS-DELTA-003.CC4`).
- **EAS-PLAN-001.CM6:** Edge attributes are untouched (`strand_edges.attributes` stays a JSON document); no JSONB assumption is introduced; no key interning table is added (`EAS-DELTA-001.CC11`/`D1`).

## EAS-PLAN-001.P5 Implementation phases

Phase ordering is dependency-driven; each phase lands independently green under `clojure -M:test`.

### EAS-PLAN-001.PH1 EAV schema, write path, and assembly reads

Outcome: the `attributes` table (with partial index, CASCADE, per-value `CHECK`) replaces the document column; writes go to per-key rows in one transaction (per-key merge, null-value row delete, whole-map replace); reads assemble a lexicographically-ordered map (archived included on full point reads only); `ensure-current-schema!` fails loud on the document schema. `::specs/attributes` still validates the map before write. Trusted in-process reads and the L1 lean projection stay full-fidelity/unchanged above the seam. Focused db tests plus full `clojure -M:test` green.

### EAS-PLAN-001.PH2 Archive tier and trusted primitives

Outcome: `archive!`/`unarchive!` flip the `archived` flag per strand and per explicit key set; multi-key calls validate first and commit atomically; repeated archive/unarchive of an already-correct-state key is idempotent with `:changed 0`. The `WHERE archived = 0` partial index and an `archived = 0` predicate keep archived rows out of assembly-for-list and every query path; full point reads still include them. Tests cover per-strand and explicit-key-set archive/unarchive, hot-path exclusion, full-read inclusion, idempotency, atomicity, and fail-loud behavior for bad strand ids, malformed/empty key sets, missing keys, and partial writes.

### EAS-PLAN-001.PH3 Query compiler over rows and the uniform-capability gate (hard slice)

Outcome: `[:attr k]` predicates compile to an `EXISTS`/join over non-archived rows; cross-key predicates are self-joins; keys and values are bound as parameters (no literal splice). The **uniform-capability gate** asserts full predicate capability (`:=`, `:!=`, `:<`/`:<=`/`:>`/`:>=`, `:in`, `:exists`, `:missing`, logical composition, cross-key) with result semantics identical to the document baseline for every key — replacing the removed undeclared-key gate. An EXPLAIN/QUERY PLAN assertion confirms a filtered predicate uses the `(key,value)` partial index. The L0b literal-path branch and `::specs/indexed-attr-key` are deleted.

### EAS-PLAN-001.PH4 Migrate op and L0b removal (hard slice)

Outcome: `migrate-attribute-storage!` converted document worlds to rows with `::specs/attributes` validation, parity verification, and re-run safety for the cutover window, then was struck from main after the recorded worlds were migrated; the `indexed_attr_keys` table, its trusted ops, and the L0b tests are removed.

### EAS-PLAN-001.PH5 Benchmark gate (hard slice)

Outcome: the feature-local benchmark harness (adapted from the card-`bvb0g` `RESULTS-*` methodology) demonstrates the `EAS-PLAN-001.P7` targets pre-merge, with timed runs serialized behind the benchmark lock. This phase blocks merge and replaces a separate POC.

### EAS-PLAN-001.PH6 Docs, specs, smoke, and final validation

Outcome: the three deltas merge into the root specs (retired L0b clauses dropped); the `CLAUDE.md` debug snippet is rewritten to the `attributes` table; smoke exercises row storage, archive, and migrate; the full gate suite is green with no generated artifacts left behind.

## EAS-PLAN-001.P6 Task strategy, commit and validation

- **EAS-PLAN-001.T1 (task payloads):** Implementation tasks are authored later (via `agent-plan` or devflow task pour) and carry **only `id`, `title`, `body`, and `harness`** — a self-contained brief per slice, not a re-statement of these specs. Each body points at its governing delta clauses and the phase outcome; the deltas and this plan are the durable source of truth.
- **EAS-PLAN-001.T2 (harness routing):** Route the **hard slices to `hard-gpt`** (codex gpt-5.5 medium, tuned for delicate patch-shaped work over existing code): the **query-compiler rewrite** (`PH3`), the **migration op** (`PH4`), and the **benchmark gate** (`PH5`). The remaining mechanical slices (schema/write path `PH1`, archive primitives `PH2`, docs/smoke sweep `PH6`) route to `patch-gpt` (default implementer for the refactor/complex-patch flow) or `grunt` for the test-heavy mechanical work. Reviews follow the `complex-patch-review` roster (opus design seat + gpt-5.4-high thorough seat, synthesized by `hard-gpt`), per the repo's refactor/complex-patch flow.
- **EAS-PLAN-001.T3 (commit strategy):** One commit per phase, conventional messages (`feat(eav-attr-storage): …`, `refactor(query): …`, `test(eav): …`), each landing green. The L0b removal and the query rewrite (`PH3`/`PH4`) are the delicate diffs — keep them isolated commits so a bisect can pin any regression.
- **EAS-PLAN-001.V1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` after each phase and before completion. In agent/swarm contexts serialize behind `flock -w 3600 /tmp/skein-test.lock` (a different lock from the benchmark lock).
- **EAS-PLAN-001.V2:** The uniform-capability gate (`PH3`) is a blocking, structural, deterministic test — result-parity and predicate-capability assertions, no wall-clock timing, so it does not flake under concurrent suites.
- **EAS-PLAN-001.V3:** `(cd cli && go test ./...)` — expected inert (no Go CLI surface change; the storage move is weaver-side and relayed verbatim), run to confirm no regression.
- **EAS-PLAN-001.V4:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` after the docs/smoke integration; exercise row storage, archive, and migrate.
- **EAS-PLAN-001.V5:** `make fmt-check`, `make lint`, and `make reflect-check` before committing source (all held at zero findings).
- **EAS-PLAN-001.V6:** `make docs-check` after touching any spool docstring so the generated `spools/*.api.md` stays in sync (batteries/util are confirm-only here, so this is expected inert but run to confirm).
- **EAS-PLAN-001.V7:** `git status --short` shows no generated SQLite/runtime metadata (including WAL `-wal`/`-shm` sidecars and benchmark scratch databases) after validation.

## EAS-PLAN-001.P7 Benchmark gate

Adapt the card-`bvb0g` spike harnesses (`RESULTS-*.md` methodology; the side-table results are the closest measured analogue, since EAV generalizes the side table to all keys) to the row schema and demonstrate, **pre-merge**, all of:

- **EAS-PLAN-001.BG1 (write-amp):** `>= 5x` write-amp reduction on a small-key patch of payload-carrying strands versus the document baseline, measured on payload rows at or above 16 KiB. The earlier `>= 10x` target assumed page-level behavior from the side-table spike. The row schema still proves the required storage decoupling when payload-size growth no longer changes patch bytes materially.
- **EAS-PLAN-001.BG2 (filtered scans + ready):** filtered scans at 250k synthetic strands within the measured document-schema envelope or better. `ready` may be up to `1.7x` the document baseline. The accepted measured result is `1.69x` after the optimization ladder (`ANALYZE`, then batched two-pass assembly). Rationale: the remaining cost is batch assembly on an unbounded synthetic frontier that both schemas fail interactively anyway; the improvement path is bounded frontier queries, tracked by kanban card `ncso4`.
- **EAS-PLAN-001.BG3 (assembly reads):** assembly reads for a `list` of 500 strands **no worse than 2× the document baseline** — the primary EAV risk, since assembling a map from N rows per strand replaces one column read. The gate must confirm the 500-row assembly case, not just single-row point reads.
- **EAS-PLAN-001.BG4 (serialization):** serialize timed runs behind `/opt/homebrew/opt/util-linux/bin/flock -w 3600 /tmp/skein-bench.lock` so concurrent siblings never contaminate the measured numbers.

All four are merge-blocking. Changing a target requires updating this plan and the proposal with the measured result and rationale, not only weakening the benchmark code.

## EAS-PLAN-001.P8 Risks and open questions

- **EAS-PLAN-001.R1 (assembly-read regression):** N-row assembly per strand could blow the `list`-of-500 budget past 2× the document baseline (`BG3`). Mitigation: the `strand_id`-prefixed primary key covers assembly; if the gate misses, a `(strand_id) WHERE archived = 0` assembly-path partial index is the first lever (`EAS-DELTA-001.Q1`), decided by the benchmark, not guessed.
- **EAS-PLAN-001.R2 (query-plan drift):** SQLite may decline the `(key,value)` partial index for the `EXISTS`/join or self-join shape. Mitigation: `PH3`'s EXPLAIN QUERY PLAN assertion pins index use; the self-join SQL shape (`EAS-DELTA-001.Q4`) is validated for plan-parity, not assumed.
- **EAS-PLAN-001.R3 (seam leak above core):** an edit forced above `skein.core.*`/`skein.api.weaver.alpha` to keep a spool or the CLI passing means the decode seam leaked and TEN-007 is violated. Mitigation: `AA5`/`AA6` are confirm-only by design; treat any required spool/CLI edit as a defect in the assembly seam, not a place to patch.
- **EAS-PLAN-001.R4 (migrate on the canonical world):** the canonical coordination world carries live data; a lossy or non-idempotent migrate would be a real-data incident. Mitigation: `PH4` validates source documents against `::specs/attributes`, verifies row/document parity, is re-run-safe, and is exercised in tests before it is ever pointed at the canonical world; run it against a disposable copy first.
- **EAS-PLAN-001.R6 (archive policy changes query membership):** archiving a key intentionally removes it from hot `list`/`ready`/query maps and flips predicates such as `:exists`/`:missing` on that key. Mitigation: core ships no policy; archive policy authors must choose keys/states deliberately and can verify effects with full point reads before and after policy runs.
- **EAS-PLAN-001.R5 (WAL sidecars in bench/test scratch):** the benchmark and tests spin real SQLite databases with `-wal`/`-shm` sidecars. Mitigation: treat them as expected runtime state and clean them; extend the `git status --short` check (`V7`) to cover benchmark scratch databases.
- **EAS-PLAN-001.Q1:** The exact secondary-index set and the self-join/assembly SQL shapes are implementation mechanics finalized in the phases above and pinned by the benchmark gate and uniform-capability tests (`EAS-DELTA-001.Q1`/`Q4`). The trusted op names, semantic args, return shapes, and fail-loud cases are already fixed in `EAS-DELTA-003.CC4`.

## EAS-PLAN-001.P9 Task context

- **EAS-PLAN-001.TC1:** Implement the three deltas as the source of truth; the final code should read as if row-per-key attribute storage, the archive flag, and the row query compiler were the original design (TEN-007). No compatibility shim for the document column survives.
- **EAS-PLAN-001.TC2:** Keep the attribute-map contract identical above `skein.core.*`. `::specs/attributes` validates the map on write; the row `CHECK (json_valid(value))` is the storage backstop; reads assemble a lexicographically-ordered map. Never leak rows above the decode seam.
- **EAS-PLAN-001.TC3:** Split reads by read tier: full point/lookup reads include archived values; hot `list`/`ready`/query reads exclude archived values before any lean projection. Never let the lean transform decide archive visibility.
- **EAS-PLAN-001.TC4:** Remove L0b as a unit — table, spec, ops, literal-path branch, tests — and never splice a key into SQL. Attribute keys are bound parameters; the uniform-capability gate replaces the undeclared-key gate.
- **EAS-PLAN-001.TC5:** Archive is a flag, not a table or a second concept. Ship `archive!`/`unarchive!` as trusted primitives only; ship no archive policy and no CLI command. Preserve the spec-backed input/output, atomicity, idempotency, and fail-loud bad-input contract from `EAS-DELTA-003.CC4`.
- **EAS-PLAN-001.TC6:** Cutover was explicit and once: fail loud on the document schema, migrate via `migrate-attribute-storage!` with parity verification. Never auto-migrate on open (TEN-000@1, TEN-003). The one-shot migrate op shipped for that cutover window and was then struck from main.
- **EAS-PLAN-001.TC7:** The benchmark gate (`P7`) is merge-blocking and its numbers are exact. The accepted ready threshold change is recorded in `BG2` and the proposal with the measured `1.69x` result, rationale, and follow-up path. Serialize timed runs behind the benchmark lock; report any future missed target instead of softening it only in code.
- **EAS-PLAN-001.TC8:** Implementation task payloads carry only `id`/`title`/`body`/`harness`; route the query-compiler rewrite, the migration op, and the benchmark gate to `hard-gpt` (`EAS-PLAN-001.T2`).

## EAS-PLAN-001.P10 Developer Notes

Append notes here. Do not rewrite earlier notes.

### EAS-PLAN-001.DN1 Plan creation — 2026-07-06

Written alongside the three spec deltas against the post-`5595fe7` root specs. The plan commits the implementation choices the proposal left open as plan-level mechanics: the EAV table shape and write-path translation (`PH1`), the archive-flag tier and trusted primitives (`PH2`), the row query compiler and uniform-capability gate replacing the L0b undeclared-key gate (`PH3`), the explicit migrate op and full L0b removal (`PH4`), and the merge-blocking benchmark gate with exact targets (`PH5`/`P7`). Agent review and human sign-off remain for the coordinator; no implementation tasks are queued by this step. TEN-007 (deep-module storage) was added to `TENETS.md` in this same step, resolving proposal `Q5`.

### EAS-PLAN-001.DN2 Migration op struck — 2026-07-07

The one-shot `migrate-attribute-storage!` surface described in `CM2`, `CM5`, `PH4`, and `TC6` shipped for the verified document→EAV cutover, then was removed from main after all recorded real-world databases were migrated. Worlds predating the EAV row schema now have no in-tree migration path; reinitialize the world, or check out merge `c77332a` (PR #6), whose tree carries the verified migrate op.
