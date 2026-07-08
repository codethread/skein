# Emit Strand Mutation Events

**Document ID:** `TASK-002` **Status:** Pending **Plan:** [Weaver Event System Plan](../weaver-event-system.plan.md)

## TASK-002.P1 Scope

Type: AFK

Emit semantic events from current weaver strand mutation operations after their database mutations succeed.

## TASK-002.P2 Required context

- Depends on `TASK-001`.
- Inspect `src/skein/weaver/api.clj`, especially `add`, `update`, `burn-by-id`, and `burn-by-ids`.
- Inspect `src/skein/db.clj` only to understand available before/after row data; keep semantic emission at the weaver boundary unless a small persistence seam is unavoidable.

## TASK-002.P3 Implementation notes

- Add event emission for add, update, and burn-by-id/burn-by-ids.
- Use event types such as `:strand/added`, `:strand/updated`, and `:strand/burned`.
- Do not add batch event types in this MVP; there is no blessed weaver batch mutation API yet.
- Include event metadata: `:event/type`, `:event/id`, `:event/at`, `:event/source`.
- For update events, include id, submitted patch/delta, before row, and after row. If the update operation writes edges, include the submitted `:edges` in the patch/delta and report them through `:strand/updated` for the MVP.
- For burn events, capture normalized rows before deletion and include them with requested and burned ids.
- Enqueue events only after the DB mutation has succeeded.

## TASK-002.P4 Done when

- Tests can register a handler, perform each mutation, and observe the expected event payload.
- Update event tests prove before/after values are correct for active/inactive changes and attributes.
- Burn event tests prove requested/burned ids and pre-delete rows are included and no deleted rows are required to be re-read after deletion.
- Existing mutation return values remain unchanged.
- `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` passes.
