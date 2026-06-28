# Getting started with Skein

Use a disposable config-dir for learning and agent work:

```sh
go install ./cli/cmd/strand
world=$(mktemp -d)
printf '{"configFormat":"alpha","source":"%s"}\n' "$PWD" | jq . > "$world/config.json"
```

Start the weaver in a dedicated terminal:

```sh
strand --config-dir "$world" weaver start
```

In another terminal, initialize the Skein store and bootstrap missing config files:

```sh
strand --config-dir "$world" init
```

Add strands:

```sh
strand --config-dir "$world" add "Review docs" --attr owner=ct --attr area=docs
strand --config-dir "$world" add "Scratch idea" --attr temporary=true --attr example_category=scratch
```

List and inspect ready strands. The CLI always emits JSON:

```sh
strand --config-dir "$world" list
strand --config-dir "$world" ready
```

Deactivate a persistent strand:

```sh
strand --config-dir "$world" update <strand-id> --active false
```

Inactive rows remain visible with `active=false` and `inactive_at` set. Use `strand burn <id>` for explicit deletion.

## REPL workflow

Open a connected helper REPL:

```sh
strand --config-dir "$world" weaver repl
```

Useful forms:

```clojure
(init!)
(def s (:id (strand! "My first REPL strand" {:owner "ct"})))
(strand s)
(update! s {:active false})
(strands)
(ready)
```

Define and consume a named query:

```clojure
(defquery! 'mine '[:= [:attr :owner] "ct"])
(strands 'mine)
```

The CLI can consume the same weaver-memory query during that weaver lifetime:

```sh
strand --config-dir "$world" list --query mine
strand --config-dir "$world" ready --query mine
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
strand --config-dir "$world" pattern explain task
printf '{"title":"Implement feature"}\n' | strand --config-dir "$world" weave --pattern task
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
printf '(ready)\n' | strand --config-dir "$world" weaver repl --stdin
```

Stop the weaver when finished:

```sh
strand --config-dir "$world" weaver stop
```
