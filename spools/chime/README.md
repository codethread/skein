# Skein Chime Spool

## Overview

`skein.spools.chime` is a local notification engine for Skein graph events.
It watches strand mutations, evaluates small user-registered rules, and sends
matching notices through a user-bound local notifier command.

Chime knows nothing about any particular workflow or attribute vocabulary: it
ships **no rules and no notifier**. A workspace's trusted config decides what
deserves attention (rules) and each developer decides how to be told
(notifier). It owns only runtime-local weaver-lifetime state: the notifier
binding, rules, deduplication memory, batch scan memory, and recent failures are
kept on the active runtime and isolated from other runtimes in the same JVM.

Chime spawns a user-configured local process with the user's authority, so it
is an approved local-root spool like shuttle rather than a shipped classpath
spool.

## Loading

Approve the local root from the selected workspace's `spools.edn`:

```clojure
{:spools {skein.spools/chime {:local/root "../spools/chime"}}}
```

Activate it from trusted startup config after syncing approved roots:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime-alpha])

(def runtime (current/runtime))
(runtime-alpha/sync! runtime)
(runtime-alpha/use! runtime :chime
  {:ns 'skein.spools.chime
   :spools ['skein.spools/chime]
   :call 'skein.spools.chime/install!
   :required? true})
```

`install!` registers only the graph-event handler. A useful setup then layers
two things on top:

- shared config (`init.clj` / a workspace spool) registers the workspace's
  rules with `defrule!`, so the repo decides what needs attention;
- each developer binds their notifier in gitignored `init.local.clj` with
  `set-notifier!`, so how you get told (a notification daemon, `osascript`,
  a bell) stays a personal choice.

## Notifier binding

Bind the notifier with plain data:

```clojure
(require '[skein.spools.chime :as chime])

(chime/set-notifier! {:argv ["my-notify"]})
(chime/notifier)
```

For each notification, chime spawns `:argv` with the title appended as the
final argument and the body written to stdin — any command with the shape
`my-notify <title>` (body on stdin) works, including a small wrapper script
around whatever your platform uses.

Notifier state is weaver-lifetime: re-bind on every startup/reload (which
`init.local.clj` does naturally). With no notifier bound, a fired rule records
a loud failure in `(chime/failures)` instead of silently dropping the event.

Manual sends use the same path:

```clojure
(chime/notify! {:title "Build finished" :body "All strands under plan abc are closed."})
```

## Rules

A rule is a named function registered by fully qualified symbol. On every
graph mutation chime scans current strands and calls each rule with a context
map containing at least:

- `:strand` — the candidate strand being evaluated;
- `:event` — the triggering graph event;
- `:ready-ids` — the set of ready strand ids, computed once per scan, so
  readiness rules need no extra queries.

The rule returns nil for no notification or `{:title "..." :body "..."}` to
send one. Scans are whole-graph on purpose — the strand worth notifying about
is often not the strand that changed (closing one strand is what makes another
ready). Batch events and their per-strand fanout share a `:batch/id` and
trigger only one scan.

Worked example — notify when a strand that parents other work is closed:

```clojure
(ns my.rules
  "Workspace notification rules."
  (:require [skein.repl :as repl]))

(defn parent-completed
  "Notify when a strand with parent-of children reaches closed."
  [{:keys [strand]}]
  (when (and (= "closed" (:state strand))
             (seq (repl/query [:edge/in "parent-of" [:= :id (:id strand)]])))
    {:title (str "Plan complete: " (:title strand))
     :body (str "Strand " (:id strand) " and the work it parents are finished.")}))
```

```clojure
(chime/defrule! :parent-completed 'my.rules/parent-completed)
(chime/rules)
(chime/remove-rule! :parent-completed)
```

Chime deduplicates notifications per `[rule strand]` while the rule keeps
matching: a strand is marked seen only after the notifier process starts, so a
missing or failing notifier does not swallow the alert, and the mark clears
when the rule stops matching so a recurrence notifies again. Use
`(chime/reset-seen!)` from tests or config to clear that memory.

Rule, notifier, and process failures are recorded by `(chime/failures)` and
event handler failures remain visible through the Skein event failure surface.

## See also

- [`../README.md`](../README.md) — shipped and approved local-root spool index.
- [`../shuttle/README.md`](../shuttle/README.md) — local-root layout and loading pattern.
- [`../../docs/skein.md#authoring-your-own-spool-code`](../../docs/skein.md#authoring-your-own-spool-code) — authoring and loading local spools.
