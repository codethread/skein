# Getting started with Skein

This guide walks through installing the `strand` CLI, starting the weaver, and
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
  consumption of existing state. It always emits JSON.
- A live **Lisp machine**: the long-lived weaver owns the store and runtime
  state, and a connected Clojure REPL lets you query, mutate, and _extend_
  Skein at runtime — registering queries, patterns, views, event handlers, and
  custom operations without restarting anything.

The CLI stays thin on purpose; runtime customization belongs in trusted config
and the REPL. See [PHILOSOPHY.md](../devflow/PHILOSOPHY.md) for the reasoning.

Install the CLI:

```sh
go install ./cli/cmd/strand
```

## Table of contents

- [Core ideas](#core-ideas)
- [Choosing a world](#choosing-a-world)
- [Start the weaver](#start-the-weaver)
- [Initialize the store](#initialize-the-store)
- [Add and inspect strands](#add-and-inspect-strands)
- [Close and delete strands](#close-and-delete-strands)
- [REPL workflow](#repl-workflow)
- [Startup config and runtime helpers](#startup-config-and-runtime-helpers)
  - [Custom operations (`strand op`)](#custom-operations-strand-op)
- [Example: a small userland kanban op](#example-a-small-userland-kanban-op)
- [Stop the weaver](#stop-the-weaver)

## Choosing a world

By default `strand` reads and writes your standard config, data, and state
world. The rest of this guide assumes that default world.

To work in a separate, disposable world instead (recommended for learning and
agent work), create one and target it with `--config-dir`:

```sh
world=$(mktemp -d)
printf '{"configFormat":"alpha","source":"%s"}\n' "$PWD" | jq . > "$world/config.json"
```

`--config-dir` is not sticky: pass the same path on **every** command that should
target that world (for example `strand --config-dir "$world" weaver start`). The
examples below omit the flag; add it back when targeting a custom world.

## Start the weaver

Start the weaver in a dedicated terminal:

```sh
strand weaver start
```

## Initialize the store

In another terminal, initialize the Skein store and bootstrap missing config
files:

```sh
strand init
```

## Add and inspect strands

Add strands:

```sh
strand add "Review docs" --attr owner=ct --attr area=docs
strand add "Scratch idea" --attr temporary=true --attr example_category=scratch
```

List and inspect ready strands. The CLI always emits JSON:

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

Open a connected helper REPL:

```sh
strand weaver repl
```

Useful forms. In Clojure the function name comes first inside the parens, so
`(strand! "x")` calls `strand!` with `"x"` (where `!` indicates mutation but is semantic, not syntax). See the
[Clojure crash course](./clojure-crash-course.md) for more:

```clojure
(init!)                                              ; prepare storage for this world
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

The CLI can consume the same weaver-memory query during that weaver lifetime:

```sh
strand list --query mine
strand ready --query mine
```

## Startup config and runtime helpers

Fresh `strand init` creates missing workspace files without overwriting existing files: `config.json` if absent, `libs/`, `libs.edn`, `init.clj`, and `.git` if absent. It then asks the running weaver to initialize storage. The generated `init.clj` is a small resilient bootstrap:

```clojure
(require '[skein.libs.alpha :as libs])

(libs/sync!)
```

Create your own config or library files when you need runtime behavior. `init.clj` is the place to load approved libraries, register queries, register weave patterns, register views, register event handlers, or call your own install functions.

Built-in `skein.graph.alpha`, `skein.patterns.alpha`, `skein.views.alpha`, `skein.events.alpha`, and `skein.batch.alpha` come from the configured Skein checkout. User/community libraries are approved separately in `libs.edn` and synced through `skein.libs.alpha`. Require `skein.batch.alpha` explicitly when you want `(batch/apply! payload)` for transactional graph mutations.

Example pattern and view setup in your own startup-loaded library:

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

Lower-privilege CLI callers can inspect and invoke registered patterns with JSON stdin:

```sh
strand pattern explain task
printf '{"title":"Implement feature"}\n' | strand weave --pattern task
```

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
config or library code, not in core Skein.

Append this demo handler to the selected world's `init.clj`:

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

Reload the config so the running weaver installs the handler:

```sh
printf '(do (require '\''[skein.libs.alpha :as libs]) (libs/reload!))\n' \
  | strand weaver repl --stdin
```

Create a few demo strands with one batch call. Batch entries use temporary
`:ref` values so the result can report generated ids:

```sh
cat <<'EOF' | strand weaver repl --stdin
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

Invoke the custom operation. `--max` is optional and defaults to 5 rows:

```sh
strand op kanban --max 15
```

`strand` keeps public command output JSON-only, so render the table field when
you want terminal-friendly ASCII:

```sh
strand op kanban --max 15 | jq -r .table
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

Register event handlers from trusted config or weaver-loadable libraries when you want asynchronous reactions to strand mutations. Event helpers are not public CLI commands:

```clojure
(ns my.workflow
  (:require [skein.events.alpha :as events]))

(defn record-add! [event]
  (println "added" (:strand/id event)))

(events/register! :example/record-add #{:strand/added} 'my.workflow/record-add!)
(events/handlers)
(events/recent-failures)
```

Hot-reload the selected config-dir `init.clj` from a connected REPL:

```clojure
(require '[skein.libs.alpha :as libs])
(libs/reload!)
```

Reload clears weaver-lifetime library sync state, module-use state, named queries, views, patterns, event handlers, queued events, and recent event failures, then re-runs `init.clj`.

Use the connected stdin REPL for scripts:

```sh
printf '(ready)\n' | strand weaver repl --stdin
```

## Stop the weaver

Stop the weaver when finished:

```sh
strand weaver stop
```
