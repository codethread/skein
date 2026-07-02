---
name: strand
description: >
  Use `strand` cli for planning and tracking multi-step coding work. Trigger when
  the user asks to create strands, use strands, track work in Skein, build a task
  graph, inspect ready work, mark strands done, or when a non-trivial coding task
  would benefit from a small explicit DAG of work.
---

# Strand workflow

## When to use strands

- Skip strands for one small direct action.
- Create a strand plan for multi-step work, dependencies, validation, review, or
  when the user explicitly asks to use/manage strands.
- Keep plans small: usually 2-6 strands.
- Do not create a strand for every tiny sub-step.

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
- Prefer `--attr-file body=<path>` or `--attr-stdin body` for multi-line bodies.

## Standard loop

1. Create or switch to a feature worktree for non-trivial feature work.
2. Inspect registered patterns; use `strand pattern explain <name>` for the live contract.
3. Create or update the plan graph with the best fitting pattern or minimal raw commands.
4. Add `body` context to any strand that may be delegated.
5. Run `strand ready` and pick from ready work, not the full list. In this
   repository's default `.skein` workspace, prefer `strand ready --query work`
   so workflow molecule/procedure/digest plumbing stays hidden while steps and
   checkpoints remain visible.
6. If a ready strand has `hitl=true`, stop and ask the user before doing it.
7. Complete one ready strand or one tightly related pair.
8. Run relevant validation.
9. If new work is discovered, add/wire it before closing the current loop.
10. Mark completed strands inactive.
11. Repeat `strand ready` until done or blocked.
12. Before finishing a feature, run the repo smoke test.
13. After smoke passes, squash merge the feature worktree/branch back to main and clean it up.

## Choice commands

Check the current CLI surface:

```sh
strand -h
```

Create a small plan with a registered pattern when available:

```sh
strand pattern explain agent-plan
printf '%s' '{"feature":"<slug>","title":"Feature: <name>","body":"Problem, scope, and acceptance criteria.","tasks":[{"key":"impl","title":"Implement <outcome>","body":"Implementation context for an offloaded agent."},{"key":"validate","title":"Validate <outcome>","kind":"review","body":"Validation expectations.","depends_on":["impl"]}]}' \
  | strand weave --pattern agent-plan
strand ready
# In skein-src's repo-local .skein workspace, prefer:
strand ready --query work
```

Mark done:

```sh
strand update <id> --state closed
```

Use a config dir explicitly for disposable or non-default worlds:

```sh
strand --config-dir <dir> ready
strand --config-dir <dir> update <id> --state closed
```

One-shot REPL helpers when CLI is awkward:

```sh
printf '(ready)\n' | strand weaver repl --stdin
printf '(strands)\n' | strand weaver repl --stdin
```

Hot-reload selected config after config/library edits:

```sh
printf "(do (require '[skein.runtime.alpha :as runtime]) (runtime/reload!))\n" \
  | strand weaver repl --stdin
```

Register a temporary named query for this weaver lifetime:

```sh
printf "(defquery! 'agent-owned '[:= [:attr :owner] \"agent\"])\n" \
  | strand weaver repl --stdin
strand list --query agent-owned
strand ready --query agent-owned
```

## Delegated-agent contract in skein-src

When working from a delegated strand in this repository, read the assigned
strand first, record progress with `strand update <id> --attr progress=...`, and
set `--attr status=implemented` when the scoped implementation is ready for the
coordinator to verify. Do not close your assigned strand, and do not mutate
sibling or parent strands unless the assignment explicitly says so.

## Validation and finish

Before reporting success:

- `strand ready` matches the expected next work, or is empty because all planned
  work is inactive.
- Completed strands are inactive.
- Newly discovered work is represented by active strands.
- Dependencies reflect actual blocking relationships.
- Relevant checks pass.

Before merging a completed feature:

```sh
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke
```

After smoke passes, squash merge the feature branch/worktree back to `main` and
remove the feature worktree/branch.
