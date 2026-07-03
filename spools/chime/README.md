# Skein Chime Spool

## Overview

`skein.spools.chime` is a local human-attention notification bridge for Skein
workflows. It watches graph mutations and runs small rules that decide when a
human should notice: HITL checkpoints becoming ready, shuttle agent failures,
and treadle gate errors.

Chime spawns a user-configured local notifier process with the user's authority,
so it is an approved local-root spool like shuttle rather than a shipped
classpath spool. It owns only weaver-lifetime state: notifier binding, rules,
deduplication, and recent failures.

## Loading

Approve the local root from the selected workspace's `spools.edn`:

```clojure
{:spools {skein.spools/chime {:local/root "../spools/chime"}}}
```

Activate it from trusted startup config after syncing approved roots:

```clojure
(require '[skein.api.runtime.alpha :as runtime-alpha])

(runtime-alpha/sync!)
(runtime-alpha/use! :chime
  {:ns 'skein.spools.chime
   :spools ['skein.spools/chime]
   :call 'skein.spools.chime/install!
   :required? true})
```

Install ordering matters when startup config also binds the notifier: load and
install chime before calling `skein.spools.chime/set-notifier!`. Chime does not
require shuttle or treadle to be installed; it only reads their public attribute
vocabulary when those spools write it.

## Notifier binding

Bind the notifier with plain data:

```clojure
(require '[skein.spools.chime :as chime])

(chime/set-notifier! {:argv ["cc-notify"]})
(chime/notifier)
```

For each notification, chime appends the title as the final argv element and
writes the body to stdin. The intended production command shape is:

```sh
cc-notify <title>
# body on stdin
```

Chime does not hardcode `cc-notify`; tests and workspaces can bind any local
command. Missing notifier bindings are loud: a fired rule records a failure in
`(chime/failures)` instead of silently dropping the event.

Manual sends use the same path:

```clojure
(chime/notify! {:title "Needs review" :body "Checkpoint abc is ready."})
```

## Rules

Rule functions receive a context map containing at least `:event` and `:strand`.
Chime scans current strands after graph mutation events so readiness changes caused by
another strand closing can be observed. Rule functions return nil for no notification
or `{:title "..." :body "..."}` to send one.

```clojure
(chime/defrule! :my-rule 'my.workspace/rule-fn)
(chime/rules)
(chime/remove-rule! :my-rule)
```

`install!` registers three removable defaults:

- `:hitl-checkpoint-ready` — an active `workflow/role "checkpoint"` strand with
  truthy `workflow/hitl` that is currently ready.
- `:agent-failure` — a strand whose `shuttle/phase` is `"failed"` or
  `"exhausted"`; the body includes `shuttle/error` when present.
- `:treadle-error` — a strand carrying `treadle/error`.

Chime deduplicates notifications per `[rule strand]` for the weaver lifetime.
Use `(chime/reset-seen!)` from tests or config to clear that memory.

Rule, notifier, and process failures are recorded by `(chime/failures)` and event
handler failures remain visible through the Skein event failure surface.

## Attribute-or-state vocabulary

Chime observes only existing strand state and attributes:

| Vocabulary | Meaning |
|---|---|
| `state "active"` | Candidate work still participates in readiness. |
| `depends-on` edge | Blocks HITL checkpoint readiness while the target is active. |
| `workflow/role "checkpoint"` | Marks a workflow checkpoint. |
| `workflow/hitl true` or `"true"` | Marks a human-in-the-loop checkpoint. |
| `shuttle/phase "failed"` / `"exhausted"` | Marks a failed agent run. |
| `shuttle/error` | Failure detail included in agent-failure notification body. |
| `treadle/error` | Gate bridge error detail included in treadle-error notification body. |

## See also

- [`../README.md`](../README.md) — shipped and approved local-root spool index.
- [`../shuttle/README.md`](../shuttle/README.md) — local-root layout and loading pattern.
- [`../shuttle/treadle.md`](../shuttle/treadle.md) — gate bridge install-ordering pattern.
- [`../../docs/skein.md#authoring-your-own-spool-code`](../../docs/skein.md#authoring-your-own-spool-code) — authoring and loading local spools.
