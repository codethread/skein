# Brief: run-usage — cost, tokens, wall-time on agent-run records

Kanban: card `2ms8c`, under epic `kaans` (agent-layer redesign). Source: 2026-07-05 coordinator retrospective
(strand `i9rab`, retro/core-friction + what-good-looks-like, top ask) plus the coordinator sequencing note
`pc2xl` on the card. F1–F4 are landed and live (F4 vocab registry at `d9bc478`); this feature was deliberately
sequenced after F4 so its new keys become the first post-seed vocabulary addition.

## Problem

No machine-readable cost or duration exists on agent-run records — spend was only visible inside error JSON.
Budget-aware coordination and any spend query are impossible; the retro ranked harness seats partly by
cost/incident with no data to back it.

## Scope (the contract)

1. **First-class usage data on every agent-run record**: cost, tokens, wall time — captured at run completion,
   as `agent-run/*` attributes.
2. **Capture per harness output format** (per note `pc2xl`):
   - `:pi-json` — pi's event stream already emits usage maps `{input, output, cacheRead, cacheWrite,
     totalTokens, cost}` per assistant message; extend `parse-pi-json` (seam recently reworked by the g28j2
     fix, commit `c3c1092`, already on main at branch point).
   - `:claude-json` — the result JSON carries cost fields; capture them.
   - `:raw` (codex) — no structured usage; wall-time only, derived from the existing
     `agent-run/started-at` / `agent-run/finished-at`.
3. **Spend/usage query** over the recorded data (e.g. a registered query or CLI surface) so coordinators can
   see spend by run/harness/period.
4. **Vocabulary discipline**: declare any new `agent-run/*` keys through the F4 registry — this is the first
   post-registry vocabulary addition and should dogfood it.

## Deliberately not built

- No budget enforcement or routing logic — this records the data that makes budget-aware routing possible.
- No backfill of historical runs: records are additive from capture-time forward.

## Migration

None. Purely additive — no cutover, no restart, no data rewrite; live pickup via targeted loads + `reload!`.

## Related

- F5 (`2mp13`) runs in parallel (downstream devflow.spool + notes-workspace updates); declared parallel-safe
  in note `pc2xl`.
