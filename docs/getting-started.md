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
strand --config-dir "$world" add "Scratch idea" --attr ephemeral=true --attr example_category=scratch
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

Fresh `strand init` creates missing workspace files without overwriting existing files. The generated `init.clj` is a small resilient bootstrap:

```clojure
(require '[skein.libs.alpha :as libs])

(libs/sync!)
(libs/use! :user/config
  {:file "config.clj"
   :call 'user.config/install!})
```

The generated `config.clj` is where most user config should live:

```clojure
(ns user.config
  (:require [skein.graph.alpha :as graph]
            [skein.views.alpha :as views]))

(defn install!
  "Install this world's Skein runtime config."
  []
  {:installed true})
```

Edit `config.clj` to register queries, views, and other runtime behavior. `use!` records optional config failures without killing the weaver by default; use raw `require` or `:required? true` for strict fail-fast config.

Built-in `skein.graph.alpha` and `skein.views.alpha` come from the configured Skein checkout. User/community libraries are approved separately in `libs.edn` and synced through `skein.libs.alpha`.

Example `config.clj` view setup:

```clojure
(ns user.config
  (:require [skein.graph.alpha :as graph]
            [skein.views.alpha :as views]
            [skein.weaver.api :as api]))

(defn owned-view [{:keys [params]}]
  (let [ids (graph/query-ids! 'owned params)]
    {:ids ids
     :strands (graph/strands-by-ids ids)}))

(defn install! []
  (api/register-query! 'owned [:= [:attr :owner] "ct"])
  (views/register-view! 'owned-view 'user.config/owned-view))
```

Hot-reload the selected config-dir `init.clj` from a connected REPL:

```clojure
(require '[skein.libs.alpha :as libs])
(libs/reload!)
```

Use the connected stdin REPL for scripts:

```sh
printf '(ready)\n' | strand --config-dir "$world" weaver repl --stdin
```

Stop the weaver when finished:

```sh
strand --config-dir "$world" weaver stop
```
