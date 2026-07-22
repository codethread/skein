# Brief: SQLite schema upgrade story — decide before release

Kanban: card `r85t4`, from the `ffh75` pre-alpha decision sweep. Blocks the release-identity card `rapv5`
(the alpha release itself). User direction (2026-07-12): work this card to completion, delegating as needed.

## Problem

The on-disk `data/skein.sqlite` carries no version stamp — no `PRAGMA user_version`/`application_id`, no
`schema_version` table — and there is no migration machinery. `skein.core.db/init!` runs
`CREATE TABLE IF NOT EXISTS` (src/skein/core/db.clj:257, schema-sql :156) then `ensure-current-schema!`
(:236), which THROWS on any structural mismatch (:238-253) with no forward path.

Consequence: the first release that ships a structural schema change bricks every existing coordination
world (kanban/devflow/delegation/notes) while `api.*` stays nominally "stable". A new db opened by an old
weaver can also misfire. Attribute-level evolution is safe — attributes are generic JSON `TEXT` rows —
which is why this has not bitten yet.

## Decision required (pick one)

- **(a)** Stamp `PRAGMA user_version` at `init!` plus a migration ladder keyed off it.
- **(b)** Formally freeze table structure and force all evolution through the attribute bag — cheapest,
  matches current reality and TEN-007 deep-module discipline; needs writing down in SPEC-001.P8 plus a guard.
- **(c)** Explicitly declare workspaces disposable across schema-breaking releases.

Today the codebase is an implicit (b) with a throw and no stamp.

## Acceptance

- The decision is recorded in the shipped contract (`devflow/specs/strand-model.md` P8 and/or the relevant
  root spec section) — not just in a card note.
- Whatever guard/stamp the decision implies exists in code with test coverage.
- The story covers both skew directions: new weaver on an old db, and old weaver on a new db.
- `rapv5` (release identity) can cite this decision when it stamps the release.

## Constraints

- This is primarily a decision card; keep the implementation as small as the decision allows (TEN-004).
- SQL and shared persistence behavior stays in `skein.core.db`; attributes stay JSON `TEXT` (no JSONB).
- Changing shipped behavior means updating the relevant root spec (`devflow/specs/`).
