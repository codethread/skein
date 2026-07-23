---
name: groom
description: scan and groom the kanban backlog - board dumps, jq recipes, and the grooming ops
argument-hint: <grooming guidance>
disable-model-invocation: true
---

# Groom the kanban backlog

Groom the board: scan it with the read surface below, then act (or report) per the guidance at the bottom. Run `strand kanban prime` first if you have not this session — it carries the board discipline.

## Knowledge

### Read surface (scanning)

All output is JSON — pipe to `jq`.

- `strand kanban board` — the primary dump: one snapshot grouped by lane (`pending`, `claimed`, `in_review`, `epics`, `needs-review`; closed collapsed to a count). Titles, ids, priorities, epic parents — no bodies.
- `strand kanban-tree [--all true]` — full epic → feature → task hierarchy **including card bodies** and derived task status. Use when grooming needs the card text, not just titles. `--all true` includes closed.
- `strand kanban card <id>` — one card's resume view (body, tasks, notes). Depth on demand.
- `strand kanban next` — highest-priority (p1 first) oldest pending feature.
- `strand kanban-export <id>` — a card's parent-of subtree with internal depends-on edges; for untangling epics.
- Named queries: `strand ready --query kanban-pending` (unblocked pending cards) and `strand list --query kanban-cards --limit 500` (everything, incl. closed — the read cap demands an explicit `--limit`).

### Scan recipes

```sh
# backlog at a glance, priority-sorted
strand kanban board | jq -r '.pending[] | [.priority, .id, (.epic // "-"), .title] | @tsv' | sort

# refinement lane (cards not yet promoted) — same shape
strand kanban board | jq -r '.refinement[]? | [.priority, .id, .title] | @tsv'

# grep bodies across the whole open board
strand kanban-tree | jq -r '.cards[] | "\(.id)\t\(.attributes["kanban/lane"])\t\(.title)\n\(.attributes.body // "")"' | grep -i <term>
```

### Grooming ops (mutations)

- `strand kanban priority <id> <p1..p4>` — re-rank (p1 immediate blocker … p4 someday).
- `strand kanban promote <id>` — refinement → pending.
- `strand kanban finish <id> --outcome abandoned` — close stale/dead cards (reversible for epics via `reopen`).
- `strand kanban note <id> ...` — annotate a card with grooming rationale.
- `strand kanban add ... --epic <id>` — file gaps discovered while grooming.

Exact flags: `strand help kanban`.

## Procedures

1. `strand kanban board` — orient: lane sizes, priority spread, orphan features (no `epic`).
2. Scan `pending` (and `refinement`) with the recipes above; pull `strand kanban card <id>` only where a verdict needs the body.
3. For each card form a verdict: keep as-is, re-prioritise, promote, merge/duplicate-of (note both, abandon one), or abandon.
4. Apply verdicts with the grooming ops, leaving a `note` on any card whose priority changed or that was abandoned, stating why.
5. Report: a short table of changes made (id, action, reason) plus anything flagged but left for the user.

## Constraints

- Grooming never claims, reviews, or finishes-as-done cards — lane movement beyond `promote` belongs to whoever works the card.
- Mutate only through `strand kanban ...` ops, never raw `strand update` against card strands.
- If the guidance below is report-only (e.g. "just tell me what's stale"), make no mutations.

## Guidance

$ARGUMENTS
