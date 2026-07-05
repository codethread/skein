# Strand workflow

Use the `strand` CLI to plan and track multi-step work as a small explicit DAG
of strands. Run `mill skein prime` first for the wider Skein orientation and the
paths to the full docs.

## When to use strands

- Skip strands for one small direct action.
- Create a strand plan for multi-step work, dependencies, validation, review, or
  when the user explicitly asks to use/manage strands.
- Keep plans small: usually 2-6 strands. Do not create a strand for every tiny
  sub-step.

## Start from a feature worktree

For feature work, create or switch to a dedicated worktree before creating the
strand plan. Skip only for tiny local edits or when the user explicitly says to
work in the current tree.

```sh
wktree add --branch <feature-slug> --json
wktree path --branch <feature-slug>
```

If `wktree add --json` returns a `post_create_script_path`, run that script with
`bash` before treating the worktree as ready.

Once the branch exists, make the work discoverable: exactly one active root
strand carries `branch` (plus `owner`, and `worktree` when one exists) and all
execution strands hang beneath it via `parent-of`. `strand kanban claim` does
the stamping for kanban cards; stamp ad hoc roots with
`strand update <root-id> --attr branch=<branch> --attr owner=<name>`. Check
in-flight branch work with `strand branches [branch]`.

## Discover patterns first

Before creating a multi-step plan, inspect available weaver patterns and prefer a
self-descriptive pattern over hand-authored `add`/`update` commands:

```sh
strand pattern list
strand pattern explain <pattern-name>
```

Use raw strand commands only when no registered pattern fits or when editing an
existing graph.

## Body attribute convention

Use `body` for useful issue-style context: problem, scope, acceptance criteria,
constraints, relevant files, and validation expectations.

- Titles alone are acceptable for personal/ephemeral to-do tracking.
- Any strand delegated to another agent must include a clear descriptive `body`.
- For multi-line bodies, attach the text as a payload and reference it:
  `strand --payload body=<path> add "<title>" --attr body=:payload/body` (from a
  file) or `printf '%s' "<text>" | strand --stdin add "<title>" --attr body=:stdin`.

## Standard loop

1. Create or switch to a feature worktree for non-trivial feature work.
2. Inspect registered patterns; use `strand pattern explain <name>` for the live contract.
3. Create or update the plan graph with the best fitting pattern or minimal raw commands.
4. Add `body` context to any strand that may be delegated.
5. Run `strand ready` and pick from ready work, not the full list. Where a repo
   registers a curated ready query (e.g. `work`), prefer
   `strand ready --query work` so workflow plumbing stays hidden while steps and
   checkpoints remain visible.
6. If a ready strand has `hitl=true`, stop and ask the user before doing it.
7. Complete one ready strand or one tightly related pair.
8. Run relevant validation.
9. If new work is discovered, add/wire it before closing the current loop.
10. Mark completed strands inactive (`strand update <id> --state closed`).
11. Repeat `strand ready` until done or blocked.
12. Before finishing a feature, run the repo's validation/smoke suite.
13. After validation passes, squash merge the feature worktree/branch back to the
    trunk and clean it up.

## Choice commands

Check the current CLI surface:

```sh
strand -h
strand pattern explain agent-plan
strand ready
```

Mark done:

```sh
strand update <id> --state closed
```

Select a workspace explicitly for disposable or non-default worlds:

```sh
strand --workspace <dir> ready
strand --workspace <dir> update <id> --state closed
```

One-shot REPL helpers when the CLI is awkward:

```sh
printf '(ready)\n' | mill weaver repl --stdin
printf '(strands)\n' | mill weaver repl --stdin
```

Hot-reload selected config after config/library edits (never restart the weaver
for this):

```sh
printf "(do (require '[skein.api.runtime.alpha :as runtime]) (runtime/reload!))\n" \
  | mill weaver repl --stdin
```

Register a temporary named query for this weaver lifetime:

```sh
printf "(defquery! 'agent-owned '[:= [:attr :owner] \"agent\"])\n" \
  | mill weaver repl --stdin
strand list --query agent-owned
strand ready --query agent-owned
```

## Delegation

When a repo ships the agents spool, the full delegated-worker contract (read
your strand and notes first, record progress, set `status=implemented` only when
validation is green, never close your own strand, never mutate siblings/parents
unless told, commit only if told) ships in-band and is injected into every
delegated run's preamble. Read the live manual rather than hand-rolling JSON:

```sh
strand agent about
strand agent delegate <task-id> --prompt "Extra implementation constraints"
```

## Validation and finish

Before reporting success:

- `strand ready` matches the expected next work, or is empty because all planned
  work is inactive.
- Completed strands are inactive; newly discovered work is represented by active
  strands; dependencies reflect actual blocking relationships.
- Relevant checks pass.

Repo-specific runtime surface (curated ready queries, the kanban board, the
devflow lifecycle, delegation, branch visibility) is documented in that repo's
`AGENTS.md`; see `mill skein prime` for the path to the source docs under
`{{.Source}}`.
