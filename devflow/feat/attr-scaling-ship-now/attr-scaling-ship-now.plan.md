# Attr-Scaling Ship-Now Plan

**Document ID:** `ASSN-PLAN-001` **Feature:** `attr-scaling-ship-now` **Proposal:** [proposal.md](./proposal.md) **RFC:** None (no RFC covers attribute storage/query indexing; adjacent [RFC-002 Task Query DSL](../../rfcs/2026-06-24-task-query-dsl.md) defines the query language this touches without conflict) **Root specs:** [Strand Model](../../specs/strand-model.md), [CLI Surface](../../specs/cli.md), [Weaver Runtime](../../specs/daemon-runtime.md) **Feature specs:** [Strand Model delta](./specs/strand-model.delta.md), [CLI Surface delta](./specs/cli.delta.md), [Weaver Runtime delta](./specs/daemon-runtime.delta.md) **Evidence base:** `/tmp/claude/attr-scaling-ship-now-brief/` (`SYNTHESIS.md` §8 adopted; `DESIGN.md` semantics; `FACTCHECK.md` corrections) **Status:** Draft **Last Updated:** 2026-07-06

## ASSN-PLAN-001.P1 Goal and scope

Ship the three value-per-risk layers the proposal fixes to, under one hard invariant — **an undeclared attribute key is never slower or less capable than today**:

- **L0a** — WAL/mmap/cache pragmas on the weaver datasource (free, no contract change).
- **L0b** — a durable declared hot-key registry (`indexed_attr_keys`, parallel to `acyclic_relations`) that makes the query compiler emit literal JSON paths for declared keys so expression indexes can eliminate rows; undeclared keys keep today's bound-parameter form and full capability.
- **L1** — lean-by-default CLI/agent list-style reads that replace large attribute values with a typed, storage-neutral omission descriptor above a fixed floor; point reads and every trusted in-process read stay full-fidelity.

Out of scope: the declared-key overflow storage table (L2), any size-threshold or naming-convention offload, dedup, per-world config, and any hydration lever (`PROP-AttrScalingShipNow-001.NG1`–`NG7`, `ASSN-DELTA-001.D3`). Nothing built here may preclude L2.

## ASSN-PLAN-001.P2 Approach

- **ASSN-PLAN-001.A1:** Treat leanness as a **read-surface transform at the CLI/agent op boundary**, not a core change. `skein.spools.batteries/list-op`, `ready-op`, and the named-query path wrap the full-fidelity `weaver/list`/`weaver/ready` result; `show-op` and every direct trusted `weaver/*` read stay full. This is the "split by caller" the design requires and keeps one small seam.
- **ASSN-PLAN-001.A2:** Own the omission descriptor as **one** `skein.core.specs` clojure.spec (`::omitted-attribute-descriptor`) and construct/discriminate only through it. The lean emitter conforms to it; the trusted reader guard rejects against it.
- **ASSN-PLAN-001.A3:** Make the declaration registry a faithful reuse of the `acyclic_relations` idioms in `skein.core.db` — durable single-column PK table, idempotent `declare-*!`, `list-*`, and spec-backed key-syntax validation (`::specs/indexed-attr-key`, mirroring how `require-valid-relation-name!` gates on `::specs/edge-type`) — and honestly omit the late-declaration guard, which protects no invariant here.
- **ASSN-PLAN-001.A4:** Have the query compiler consult the durable registry to choose literal-path vs bound-parameter compilation, re-validating a declared key against `::specs/indexed-attr-key` before literal SQL emission so the one place a key is spliced (not bound) can never carry a metacharacter, changing result semantics for no key.
- **ASSN-PLAN-001.A5:** Enforce the undeclared-key invariant with a **blocking, structural** regression gate (compiled-SQL byte-identity + full predicate-type capability) in `clojure -M:test`, not a timing benchmark.
- **ASSN-PLAN-001.A6:** Keep L0a pragmas an isolated open-time datasource change with no API or read-shape effect.
- **ASSN-PLAN-001.A7:** Slice so each layer lands independently green; merge the three spec deltas into the root specs and land the per-op wording in `spools/batteries.md` in the final sweep.

## ASSN-PLAN-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| ASSN-PLAN-001.AA1 | `src/skein/core/db.clj` | `datasource` opens WAL + `mmap_size` + `cache_size`; add `indexed_attr_keys` table to `schema-sql`; `declare-indexed-attr-key!`/`list-indexed-attr-keys`/`indexed-attr-key?` mirroring the `acyclic_relations` helpers (no late-declaration guard); `declare-indexed-attr-key!` validates the key against `::specs/indexed-attr-key` and rejects fail-loud with the canonical ex-data `{:key <value> :spec ::specs/indexed-attr-key :allowed-pattern <regex-source>}` (`ASSN-DELTA-001.D5` — a newly owned contract, deliberately richer than `require-valid-relation-name!`'s bare `{:relation relation}`); `CREATE INDEX IF NOT EXISTS` per declared key. |
| ASSN-PLAN-001.AA2 | `src/skein/core/specs.clj` | `:skein/omitted` (`#{true}`), `::bytes` (`nat-int?`), `::omitted-attribute-descriptor` (`s/keys :req [:skein/omitted] :req-un [::bytes]`), and an `omitted-attribute-descriptor?` predicate; plus `::indexed-attr-key` — a single owned spec pinning the declared-key character class to the existing safe `relation-name-pattern` class (`#"[a-z0-9][a-z0-9._/-]*"`, structurally excluding quote/backslash/whitespace/control chars) so a declared key is safe to embed literally into SQL. |
| ASSN-PLAN-001.AA3 | `src/skein/core/query.clj` | `compile-field` consults the declared-key registry and emits a literal JSON path for declared keys, the existing bound-parameter form otherwise; before literal emission it re-validates the key against `::specs/indexed-attr-key` (the same spec `declare-indexed-attr-key!` uses), failing loud rather than splicing an unvalidated key. |
| ASSN-PLAN-001.AA4 | `src/skein/api/weaver/alpha.clj` | Trusted declare/list indexed-attr-key ops (parallel to the acyclic-relation ops); keep in-process reads full-fidelity. The lean-projection helper stays internal (`skein.core.*` or private in batteries) — not on the accretion-locked alpha surface while batteries is its only consumer. No lean transform in `normalize-row`. |
| ASSN-PLAN-001.AA5 | `spools/src/skein/spools/batteries.clj` | `list-op`/`ready-op`/named-query path apply the lean projection above the floor; `show-op` unchanged (full). No `--hydrate` flag. |
| ASSN-PLAN-001.AA6 | `spools/src/skein/spools/util.clj` | `attr-get` fail-loud guard: reject a value conforming to `::omitted-attribute-descriptor` where a raw value is expected, throwing `ex-info` whose ex-data carries the offending key and the `show <id>` recovery path (a newly owned contract in the fail-loud spirit of `require-valid-relation-name!`, deliberately richer than that precedent's bare `{:relation relation}`). |
| ASSN-PLAN-001.AA7 | `test/skein/**`, `dev/skein/smoke.clj` | Descriptor discrimination + guard tests (including the guard's ex-data — offending key + recovery path); the `::indexed-attr-key` spec's rejection of metacharacter-bearing keys with the canonical ex-data shape (`{:key … :spec … :allowed-pattern …}`, `ASSN-DELTA-001.D5`), asserted at both `declare-indexed-attr-key!` and `compile-field`; the blocking undeclared-key invariant gate; declared-key literal-path/index tests; lean-vs-full read tests; smoke exercises lean list + full show. |
| ASSN-PLAN-001.AA8 | `devflow/specs/strand-model.md`, `cli.md`, `daemon-runtime.md`; `spools/batteries.md` | Merge the three deltas into root specs and land the per-op lean-read wording in the batteries contract at the final sweep. |

## ASSN-PLAN-001.P4 Contract and migration impact

- **ASSN-PLAN-001.CM1:** Additive, no forced rebuild (`ASSN-DELTA-001` / `ASSN-DELTA-003`): pragmas apply on open; `indexed_attr_keys` and expression indexes use `IF NOT EXISTS`; the read change is output-shape only. Existing worlds open unchanged. `ensure-current-schema!` still validates only `strands`/`strand_edges` and fails loud only on genuinely incompatible core layouts (fail-loud rejection per TEN-003; no forced migration per TEN-000).
- **ASSN-PLAN-001.CM2:** CLI/agent behavior change: `list`/`ready`/query-backed listing return the omission descriptor for values above the fixed 1 KiB floor by default; `show` stays full. No new flag.
- **ASSN-PLAN-001.CM3:** Declaration is trusted Clojure config/REPL surface only — no public JSON socket op, no `strand` command (TEN-006). It stays separate from any future L2 offload declaration; no combined per-key capability system is built (`PROP-AttrScalingShipNow-001.Q4`).
- **ASSN-PLAN-001.CM4:** No L2 overflow table, no size/naming offload, no dedup, no per-world floor config, no generated columns (NG1–NG6). The descriptor's storage-neutral wording is what keeps L2 an unforced future option.

## ASSN-PLAN-001.P5 Implementation phases

### ASSN-PLAN-001.PH1 L0a storage pragmas

Outcome: the datasource opens every world with `journal_mode=WAL`, `mmap_size`, and an enlarged `cache_size`; file and `sqlite-memory` storage share the open path; no schema/contract/read change. Focused db tests plus full `clojure -M:test` green.

### ASSN-PLAN-001.PH2 L1 lean reads, descriptor spec, and boundary guard

Outcome: `::omitted-attribute-descriptor` exists as the single-source spec; `list`/`ready`/named-query return the descriptor above the 1 KiB floor while small keys and `show` pass through full; trusted `weaver/*` in-process reads stay full; `attr-get` fails loud on a descriptor value with the offending key and `show <id>` recovery path in ex-data. Tests cover descriptor discrimination (never a plain string), lean-vs-full split, floor boundary, and the guard's loud rejection (including its ex-data contract).

### ASSN-PLAN-001.PH3 L0b declared hot-key registry, literal-path compilation, and the blocking invariant gate

Outcome: `indexed_attr_keys` registry + `declare-indexed-attr-key!`/`list` trusted ops (acyclic-relations idioms, no late-declaration guard); the shared `::specs/indexed-attr-key` spec pins the safe character class and both `declare-indexed-attr-key!` and `compile-field` gate on it (a metacharacter-bearing key is rejected fail-loud with offending value + allowed pattern in ex-data at both sites); declared keys compile to literal paths and get `CREATE INDEX IF NOT EXISTS` expression indexes; undeclared keys compile byte-identically to today. The **blocking** undeclared-key gate asserts the byte-identical compiled SQL and full predicate-type capability (`:=`,`:!=`,`:<`/`:<=`/`:>`/`:>=`,`:in`,`:exists`,`:missing`, logical composition). An EXPLAIN/QUERY PLAN assertion confirms a declared-key predicate uses its expression index (the literal-path fix's whole point).

### ASSN-PLAN-001.PH4 Docs, specs, smoke, and final validation

Outcome: the three deltas are merged into the root specs, the lean-read per-op wording lands in `spools/batteries.md`, smoke exercises lean `list` + full `show`, and the full gate suite is green with no generated artifacts left behind.

## ASSN-PLAN-001.P6 Validation strategy

- **ASSN-PLAN-001.V1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` after each phase and before completion. In agent/swarm contexts serialize behind `flock -w 3600 /tmp/skein-test.lock`.
- **ASSN-PLAN-001.V2:** The undeclared-key invariant gate (PH3) is a blocking test in `:test`, structural and deterministic — no wall-clock timing, so it does not flake under concurrent suites.
- **ASSN-PLAN-001.V3:** `(cd cli && go test ./...)` — expected inert (no Go CLI surface change; the lean transform is weaver-side and the dispatcher relays it verbatim), run to confirm no regression.
- **ASSN-PLAN-001.V4:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` after the docs/smoke integration; exercise lean list + full show.
- **ASSN-PLAN-001.V5:** `make fmt-check`, `make lint`, and `make reflect-check` before committing source (all held at zero findings).
- **ASSN-PLAN-001.V6:** `make docs-check` after touching batteries docstrings, so the generated `spools/*.api.md` stays in sync.
- **ASSN-PLAN-001.V7:** `git status --short` shows no generated SQLite/runtime metadata after validation.

## ASSN-PLAN-001.P7 Risks and open questions

- **ASSN-PLAN-001.R1:** Misplacing the lean transform in core `normalize-row` instead of the op boundary would hand trusted spools descriptors (silent wrongness). Mitigation: the transform lives only in the batteries list/ready/query handlers, and the `attr-get` guard (PH2) fails loud if that discipline is ever broken.
- **ASSN-PLAN-001.R2:** SQLite may still decline an expression index for a declared key if the literal-path spelling drifts from the index's expression. Mitigation: PH3's EXPLAIN QUERY PLAN assertion pins index use; generated columns remain a noted fallback (`PROP-AttrScalingShipNow-001.Q1`) but are not the committed mechanism.
- **ASSN-PLAN-001.R3:** WAL introduces `-wal`/`-shm` sidecar files; smoke/tests must treat them as expected runtime state and clean them. Mitigation: extend the generated-artifact cleanup/`git status` check (V7) to cover them.
- **ASSN-PLAN-001.R4:** The 1 KiB floor could clip a value an agent routinely needs inline. Mitigation: it is conservative (payload keys are tens of KB; metadata keys are far below 1 KiB), fixed by design (NG5), and full fidelity is one `show <id>` away.
- **ASSN-PLAN-001.Q1:** L2 re-evaluation is not owned here. Recorded triggers (worlds approaching ~50k strands, operationally visible WAL churn, payload profiles trending past ~64KB) and the cheap decisive prep (re-run the W4b large-value-replace benchmark on plain-ROWID fixtures) stay context for a future L2 decision (`PROP-AttrScalingShipNow-001.Q6`).

## ASSN-PLAN-001.P8 Task context

- **ASSN-PLAN-001.TC1:** Implement the three deltas as the source of truth; the final code should read as if lean reads, declared indexing, and WAL storage were the original design.
- **ASSN-PLAN-001.TC2:** The omission descriptor is `{:skein/omitted true :bytes N}`, defined once as `::specs/omitted-attribute-descriptor`; construct and discriminate only through the spec, never with ad hoc map-shape checks.
- **ASSN-PLAN-001.TC3:** Split reads by caller: lean at the CLI/agent op boundary, full for `show` and every trusted in-process `weaver/*` read. Never lean-project in `normalize-row`.
- **ASSN-PLAN-001.TC4:** The declared-key registry reuses the `acyclic_relations` table shape and declaration idioms but carries no late-declaration guard (it protects no invariant here). Its entry is a bare string, but its shape **is** a shared spec (`::specs/indexed-attr-key`, `ASSN-DELTA-001.D5`), because a declared key is embedded literally into SQL and must be spec-gated against SQL metacharacters — the real parity with the spec-backed `acyclic_relations` precedent, not the hand-rolled `query.clj` `fail!` path. Both `declare-indexed-attr-key!` and `compile-field`'s literal emission consult that one spec.
- **ASSN-PLAN-001.TC5:** Ship no hydration lever. Full fidelity is lean-list-then-`show <id>` composition.
- **ASSN-PLAN-001.TC6:** Keep declaration a trusted Clojure surface; add no public CLI/socket declaration command (TEN-006), and keep it separate from any future L2 offload declaration.
- **ASSN-PLAN-001.TC7:** The undeclared-key invariant gate is blocking and structural. Do not encode it as a timing benchmark.

## ASSN-PLAN-001.P9 Developer Notes

Append notes here. Do not rewrite earlier notes.

### ASSN-PLAN-001.DN1 Plan creation — 2026-07-06

- Written after the three spec deltas (commit staged alongside) resolved the four binding proposal-round review findings. The plan commits the implementation choices the proposal left open: literal-path expression indexes (Q1), the `{:skein/omitted true :bytes N}` descriptor with a 1 KiB fixed floor and read-time byte count (Q2/Q3), a trusted-only `indexed_attr_keys` declaration surface kept separate from L2 (Q4), and additive cutover (Q5). Agent review and human sign-off remain for the coordinator; no tasks are queued by this step.

### ASSN-PLAN-001.DN2 Revision — resolving change-review-b203341d — 2026-07-06

Amended the deltas and this plan for the three findings from roster review `change-review-b203341d`:

- **HIGH — declared-key validation is now a real shared spec.** `ASSN-DELTA-001.D5` was rewritten: the false "reused exactly as `acyclic_relations`" framing (relation-name validation is spec-backed; the `query.clj` key path is hand-rolled `fail!`) is gone. The declared key now has one owned `::specs/indexed-attr-key` spec pinning the safe `relation-name-pattern` character class (structurally excludes quote/backslash/whitespace/control chars), rejecting fail-loud with the offending value + allowed pattern in ex-data, consulted by **both** `declare-indexed-attr-key!` (`CC3`/`CC9`) and `compile-field`'s literal emission (`CC12`/daemon `CC5`). Plan `AA1`/`AA2`/`AA3`/`A3`/`A4`/`TC4`/`PH3`/`AA7` updated to match.
- **MED — attr-get guard ex-data pinned.** `CC6` (strand-model) and `CC7` (daemon) now commit the guard's `ex-info` to carry the offending key + the `show <id>` recovery path — a newly owned contract, richer than the `require-valid-relation-name!` precedent's bare `{:relation relation}`. Plan `AA6`/`PH2`/`AA7` updated.
- **LOW — phantom `get` op fixed.** `CC5` (strand-model) and `CC6` (daemon) now read `show` (the real `skein.api.weaver.alpha`/batteries point read), not `get`, before verbatim root-spec merge.
