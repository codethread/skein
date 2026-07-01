# Getting started with Skein

This guide walks through installing `strand` and `mill`, starting a mill-routed weaver, and
working with strands, the REPL, and runtime config.

## Core ideas

Skein's data model is deliberately small. A **strand** is a simple node with a
title and a lifecycle `state`; almost everything else you care about lives in
its open-ended `attributes` map. You invent the attribute names — `owner`,
`priority`, `kanban`, whatever your workflow needs — rather than waiting for the
schema to grow. Relationships between strands are edges, which are themselves
just typed, attribute-bearing links.

On top of that model you get two ways to work:

- A small, predictable **CLI** (`strand`) for everyday CRUD and safe, scriptable
  consumption of existing state. Those commands emit JSON.
- A live **Lisp machine**: the long-lived weaver owns the store and runtime
  state, and `strand weaver repl` attaches directly to that running weaver JVM
  so you can query, mutate, and _extend_ Skein at runtime — registering queries,
  patterns, views, event handlers, and custom operations without restarting anything.

The CLI stays thin on purpose; runtime customization belongs in trusted config
and the REPL. See [PHILOSOPHY.md](../devflow/PHILOSOPHY.md) for the reasoning.

Install the CLIs from the Skein checkout. `make install` records this checkout as mill's install-time source for future weaver launches and thin nREPL attach clients; it does not affect what `strand init` writes.

```sh
make install
```

## Table of contents

- [Core ideas](#core-ideas)
- [Choosing a workspace](#choosing-a-workspace)
- [Start the weaver](#start-the-weaver)
- [Add and inspect strands](#add-and-inspect-strands)
- [Close and delete strands](#close-and-delete-strands)
- [REPL workflow](#repl-workflow)
- [IDE REPL setup](./ide-repl/)
- [Startup config and runtime helpers](#startup-config-and-runtime-helpers)
  - [Custom operations (`strand op`)](#custom-operations-strand-op)
- [Example: a small userland kanban op](#example-a-small-userland-kanban-op)
- [Stop the weaver](#stop-the-weaver)

## Choosing a workspace

By default, `strand` is **repo-first**. With no `--workspace`, `mill` resolves the canonical repository root and uses that repo's `.skein` directory as your workspace; linked worktrees of the same repository share it.

That `.skein` directory holds trusted config only. The mill-owned runtime state — metadata, sockets, and SQLite data — lives under Skein's XDG state root, not in your repo.

Outside a supported Git layout, no-flag commands fail with remediation rather than guess. They won't create an accidental workspace from your current directory or fall back to a global personal workspace.

Initialize a repo workspace from the Git repo you want to use Skein in:

```sh
strand init
```

Mill resolves the Skein source checkout it uses to launch the weaver from, in order:

- `SKEIN_SOURCE`,
- the install-time source recorded by `make install`, or
- a canonical Skein checkout as the current directory.

`strand init` does not persist a source path in `.skein/config.json`.

`strand init` without `--workspace` creates or completes `.skein` at the Git
root and fails loudly outside Git. To work in a separate, disposable workspace
instead (recommended for tests and isolated agent work), create one and target it
with `--workspace`:

```sh
workspace=$(mktemp -d)
strand --workspace "$workspace" init
```

`--workspace` is not sticky: pass the same path on **every** command that should
target that workspace (for example `strand --workspace "$workspace" weaver start`). The
basic repo workspace examples below omit the flag for readability, but examples that
reload config or create demo data use an explicit disposable `$workspace` so you do
not casually mutate or reload the default repo workspace.

## Start the weaver

Start mill once in a durable terminal, then ask it to start the selected workspace's weaver. Weaver startup prepares storage; there is no separate post-start `strand init` or database init step.

```sh
mill start
strand weaver start
```

## Add and inspect strands

Add strands:

```sh
strand add "Review docs" --attr owner=ct --attr area=docs
strand add "Scratch idea" --attr temporary=true --attr example_category=scratch
```

List and inspect ready strands — these commands emit JSON:

```sh
strand list
strand ready
```

## Close and delete strands

Deactivate a persistent strand:

```sh
strand update <strand-id> --state closed
```

Closed rows remain visible with `state="closed"`. Use `strand burn <id>` for
explicit deletion.

## REPL workflow

Open a live weaver REPL:

```sh
strand weaver repl
```

For editor-driven REPL work, see the [IDE REPL setup guide](./ide-repl/) for connecting VS Code/Calva to a running weaver nREPL.

Useful forms. In Clojure the function name comes first inside the parens, so
`(strand! "x")` calls `strand!` with `"x"` (where `!` indicates mutation but is semantic, not syntax). See the
[Clojure crash course](./clojure-crash-course.md) for more:

```clojure
(require '[skein.batch.alpha :as batch])             ; batch graph mutations

(def s (:id (strand! "My first REPL strand" {:owner "ct"}))) ; create one strand; keep its :id in s
(strand s)                                           ; look up that strand by id

;; create several related strands in one transactional call.
;; :ref values are temporary handles so :edges can link siblings;
;; the returned :refs map binds each ref to its generated id.
(def refs
  (:refs
   (batch/apply!
    {:strands [{:ref :design :title "Sketch the data model" :attributes {:owner "ct" :priority "high"}}
               {:ref :build  :title "Implement the weaver"  :attributes {:owner "ct"}}
               {:ref :docs   :title "Write getting-started" :attributes {:owner "agent"}}]
     :edges   [{:op :upsert :from :build :to :design :type "depends-on"}
               {:op :upsert :from :docs  :to :build  :type "depends-on"}]})))

(defn brief
  "Keep just the :id and :title of each strand row."
  [rows]
  (map #(select-keys % [:id :title]) rows))

(brief (strands))                                    ; every strand, summarized (one dense line)
```

The weaver is a live image, so you can improve `brief` while it runs and the new
definition takes effect on the next call — no restart, no lost state:

```clojure
(require '[clojure.pprint :refer [pprint]])          ; load pretty printing

(defn brief                                          ; redefine in place
  "Pretty-print just the :id and :title of each strand row."
  [rows]
  (pprint (map #(select-keys % [:id :title]) rows)))

(brief (strands))                                    ; same call, now nicely formatted
(brief (ready))                                      ; only strands with no active dependency
(update! s {:state "closed"})                        ; close one (row stays, state becomes "closed")
```

Skein ships graph helpers too. `graph/subgraph` walks a declared acyclic
relation (`depends-on` here) from a root id and returns the connected strands and
edges; fold that into an ASCII tree:

```clojure
(require '[skein.graph.alpha :as graph]
         '[clojure.string :as str])

(defn dag-tree
  "Render the depends-on subgraph under root-id as an ASCII tree of titles."
  [root-id]
  (let [{:keys [strands edges]} (graph/subgraph [root-id] {:type "depends-on"})
        title    (into {} (map (juxt :id :title)) strands)
        children (group-by :from_strand_id edges)
        lines    (fn lines [id depth]
                   (cons (str (apply str (repeat (max 0 (dec depth)) "   "))
                              (when (pos? depth) "└─ ")
                              (title id))
                         (mapcat #(lines (:to_strand_id %) (inc depth))
                                 (children id))))]
    (str/join "\n" (lines root-id 0))))

(println (dag-tree (:docs refs)))                    ; walk from the docs strand
```

Produces:

```text
Write getting-started
└─ Implement the weaver
   └─ Sketch the data model
```

Define and consume a named query. The query is a data expression, here "the
`owner` attribute equals `ct`"; see the [REPL API spec](../devflow/specs/repl-api.md)
for the full predicate DSL:

```clojure
(defquery! 'mine '[:= [:attr :owner] "ct"]) ; register a query named "mine"
(strands 'mine)                             ; list strands matching it
```

The CLI can discover and consume the same weaver-memory query during that weaver lifetime:

```sh
strand query list
strand query explain mine
strand list --query mine
strand ready --query mine
```

`query list` / `query explain <name>` are read-only discovery commands for named queries. Applying a query stays on `list --query` and `ready --query`.

## Startup config and runtime helpers

Fresh `strand init` creates missing workspace files without overwriting existing files. For repo workspaces, the usual layout is:

```text
.skein/
  .gitignore       # commit: ignore local/runtime artifacts
  init.clj         # commit: shared trusted startup config
  spools.edn         # commit: shared approved local-root spools
  config.json      # gitignored: local alpha config marker
  init.local.clj   # gitignored: personal startup overlay
  spools.local.edn   # gitignored: personal approved-spool overlay
```

Generated `.skein/.gitignore` ignores `config.json`, `init.local.clj`,
`spools.local.edn`, and accidental `state/`, `data/`, `weaver.*`, and SQLite/runtime artifacts. Normal runtime metadata, sockets, and SQLite data live under mill-owned XDG state paths, not in `.skein`.
`init.clj` and `spools.edn` are suitable to commit when the repo wants shared
Skein behavior. The generated `init.clj` is a small resilient bootstrap:

```clojure
(require '[skein.runtime.alpha :as runtime-alpha])

(runtime-alpha/sync!)
```

Create your own config or spool files when you need runtime behavior.
`init.clj` is the place for shared repo behavior: load approved spools,
register queries, register weave patterns, register views, register event
handlers, or call your own install functions. `init.local.clj` is loaded after
`init.clj` for personal machine-specific behavior.

Built-in `skein.*.alpha` namespaces are privileged helpers shipped on the Skein classpath, not ordinary user/community spools. User/community spools are trusted Clojure roots approved in `spools.edn` / `spools.local.edn`, synced through `skein.runtime.alpha`, and experimented with from the live REPL. `spools.local.edn` overlays `spools.edn` by coordinate, so a personal workflow spool or fork can replace a shared entry without changing committed config:

```clojure
;; .skein/spools.edn, committed
{:spools {team/workflows {:local/root "spools/team-workflows"}}}
```

```clojure
;; .skein/spools.local.edn, gitignored
{:spools {team/workflows {:local/root "~/dev/workflows/team-workflows"}
        personal/ops   {:local/root "~/dev/workflows/personal-ops"}}}
```

```clojure
;; .skein/init.local.clj, gitignored
(require '[skein.runtime.alpha :as runtime-alpha])
(runtime-alpha/sync!)
(runtime-alpha/use! :personal/ops
  {:ns 'personal.ops.alpha
   :spools #{'personal/ops}
   :call 'personal.ops.alpha/install!})
```

Require `skein.batch.alpha` explicitly when you want `(batch/apply! payload)` for transactional graph mutations. `weave --pattern` and `batch/apply!` are two doors into the same transactional engine: `weave` is the CLI-safe, named, spec-checked, create-only front door; raw batch is the trusted REPL/config loading dock that can also update, burn, and upsert edges. Package management, source fetching, and install commands are outside this MVP; local roots must already exist.

Example pattern and view setup in your own startup-loaded spool:

```clojure
(ns my.workflow
  (:require [clojure.spec.alpha :as s]
            [skein.graph.alpha :as graph]
            [skein.patterns.alpha :as patterns]
            [skein.views.alpha :as views]
            [skein.weaver.api :as api]))

(s/def ::title string?)
(s/def ::task-input (s/keys :req-un [::title]))

(defn task-pattern [{:keys [input]}]
  [{:ref 'impl
    :title (:title input)
    :attributes {:owner "ct"}}
   {:ref 'review
    :title (str "Review: " (:title input))
    :attributes {:kind "review"}
    :edges [{:type "depends-on" :to 'impl}]}])

(defn owned-view [{:keys [params]}]
  (let [ids (graph/query-ids! 'owned params)]
    {:ids ids
     :strands (graph/strands-by-ids ids)}))

(defn install! []
  (api/register-query! 'owned [:= [:attr :owner] "ct"])
  (patterns/register-pattern! 'task 'my.workflow/task-pattern ::task-input)
  (views/register-view! 'owned-view 'my.workflow/owned-view))
```

Lower-privilege CLI callers can discover, inspect, and invoke registered patterns with JSON stdin:

```sh
strand pattern list
strand pattern explain task
printf '{"title":"Implement feature"}\n' | strand weave --pattern task
```

Pattern discovery mirrors query discovery (`query list` / `query explain <name>`), while application follows the definition type: queries apply through `list --query` / `ready --query`, and patterns apply through `weave --pattern`.

### Custom operations (`strand op`)

`strand op` is the generic custom-operation portal. The built-in help operation explains the contract:

```sh
strand op help
```

Register custom handlers from trusted Clojure with `skein.weaver.api/register-op!`. The Go CLI forwards everything after the operation name as string argv:

```clojure
(require '[skein.weaver.api :as api])

(defn echo-op [{:op/keys [name argv]}]
  {:operation name :argv argv})

(api/register-op! 'echo "Echo raw argv" 'my.workflow/echo-op)
```

```sh
strand op echo --flag value
```

## Example: a small userland kanban op

The `op` surface is intentionally generic: Skein only routes argv to a trusted
weaver-side handler. Workflow behavior such as a kanban board belongs in your
config or spool code, not in core Skein.

For a safe demo, use a disposable workspace and append this handler to
`$workspace/init.clj` rather than the default repo `.skein/init.clj`:

```clojure
(require '[clojure.string :as str])
(require '[skein.weaver.api :as api])

(defn parse-max [argv]
  (loop [remaining argv]
    (case (first remaining)
      nil 5
      "--max" (let [value (second remaining)]
                (when-not value
                  (throw (ex-info "--max requires a value" {:argv argv})))
                (let [n (parse-long value)]
                  (when-not (pos-int? n)
                    (throw (ex-info "--max must be a positive integer" {:value value})))
                  n))
      (throw (ex-info "Unknown kanban argument" {:argument (first remaining)
                                                  :argv argv})))))

(defn title-cell [strand]
  (or (:title strand) ""))

(defn pad [s width]
  (let [s (subs (str s) 0 (min width (count (str s))))]
    (str s (apply str (repeat (- width (count s)) " ")))))

(defn table [rows]
  (let [width 24
        line (str "+" (str/join "+" (repeat 3 (apply str (repeat (+ width 2) "-")))) "+")
        render-row (fn [[a b c]]
                     (str "| " (pad a width) " | " (pad b width) " | " (pad c width) " |"))]
    (str/join "\n" (concat [line
                              (render-row ["Ready" "In progress" "Done"])
                              line]
                            (map render-row rows)
                            [line]))))

(defn by-kanban [rt status limit]
  (->> (api/list rt [:= [:attr :kanban] status] {})
       (take limit)
       vec))

(defn kanban-op [{:op/keys [argv]}]
  (let [rt @skein.weaver.runtime/current-runtime
        max-rows (parse-max argv)
        cols [(by-kanban rt "ready" max-rows)
              (by-kanban rt "in-progress" max-rows)
              (by-kanban rt "done" max-rows)]
        row-count (apply max (map count cols))
        rows (for [i (range row-count)]
               (mapv #(title-cell (nth % i nil)) cols))]
    {:max max-rows
     :table (table rows)}))

(api/register-op! 'kanban "Show strands grouped by :attr kanban" 'user/kanban-op)
```

Reload that disposable workspace's config so its running weaver installs the
handler. Do not run reload examples against the default repo workspace unless you
intend to reload its shared `.skein` config:

```sh
printf '(do (require '\''[skein.runtime.alpha :as runtime-alpha]) (runtime-alpha/reload!))\n' \
  | strand --workspace "$workspace" weaver repl --stdin
```

Create a few demo strands with one batch call. Batch entries use temporary
`:ref` values so the result can report generated ids:

```sh
cat <<'EOF' | strand --workspace "$workspace" weaver repl --stdin
(do
  (require '[skein.batch.alpha :as batch])
  (batch/apply!
    {:strands [{:ref :ready-1
                :title "Sketch CLI op guide"
                :attributes {:kanban "ready"}}
               {:ref :ready-2
                :title "Review examples"
                :attributes {:kanban "ready"}}
               {:ref :progress-1
                :title "Wire kanban handler"
                :attributes {:kanban "in-progress"}}
               {:ref :done-1
                :title "Ship op mechanism"
                :state "closed"
                :attributes {:kanban "done"}}
               {:ref :done-2
                :title "Document smoke path"
                :state "closed"
                :attributes {:kanban "done"}}]}))
EOF
```

Invoke the custom operation in that disposable workspace. `--max` is optional and
defaults to 5 rows:

```sh
strand --workspace "$workspace" op kanban --max 15
```

`strand` keeps public command output JSON-only, so render the table field when
you want terminal-friendly ASCII:

```sh
strand --workspace "$workspace" op kanban --max 15 | jq -r .table
```

Produces something like:

```text
+--------------------------+--------------------------+--------------------------+
| Ready                    | In progress              | Done                     |
+--------------------------+--------------------------+--------------------------+
| Review examples          | Wire kanban handler      | Ship op mechanism        |
| Sketch CLI op guide      |                          | Document smoke path      |
+--------------------------+--------------------------+--------------------------+
```

Call registered views from trusted Clojure, not from a public `strand view` CLI command:

```clojure
(require '[skein.views.alpha :as views])
(views/view! 'owned-view {})
```

Register event handlers from trusted config or weaver-loadable spools when you want asynchronous reactions to strand mutations. Event helpers are not public CLI commands:

```clojure
(ns my.workflow
  (:require [skein.events.alpha :as events]))

(defn record-add! [event]
  (println "added" (:strand/id event)))

(events/register! :example/record-add #{:strand/added} 'my.workflow/record-add!)
(events/handlers)
(events/recent-failures)
```

Hot-reload the selected workspace `init.clj` from the live weaver REPL:

```clojure
(require '[skein.runtime.alpha :as runtime-alpha])
(runtime-alpha/reload!)
```

Reload clears weaver-lifetime spool sync state, module-use state, named queries, views, patterns, custom ops, lifecycle hooks, event handlers, queued events, and recent event failures, then re-runs `init.clj` followed by `init.local.clj`.

Use the live stdin REPL for scripts. Include `--workspace` when scripting
against a disposable or test workspace:

```sh
printf '@skein.weaver.runtime/current-runtime\n' | strand --workspace "$workspace" weaver repl --stdin
```

## Stop the weaver

Stop the weaver when finished:

```sh
strand --workspace "$workspace" weaver stop
```
