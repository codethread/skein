# Getting started with Skein

Use a disposable config-dir for learning and agent work:

```sh
go install ./cli/cmd/strand
world=$(mktemp -d)
printf '{"configFormat":"alpha","source":"%s","format":"human"}\n' "$PWD" | jq . > "$world/config.json"
```

Start the weaver in a dedicated terminal:

```sh
strand --config-dir "$world" weaver start
```

In another terminal, initialize the Skein store:

```sh
strand --config-dir "$world" init
```

Add strands:

```sh
strand --config-dir "$world" add "Review docs" --attr owner=ct --attr area=docs
strand --config-dir "$world" add "Scratch idea" --ephemeral true --attr example_category=scratch
```

List and inspect ready strands:

```sh
strand --config-dir "$world" list
strand --config-dir "$world" --format json ready
```

Deactivate a persistent strand:

```sh
strand --config-dir "$world" update <strand-id> --active false
```

Persistent inactive rows remain visible with `active=false` and `inactive_at` set. Ephemeral active strands are deleted when deactivated.

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

Fresh `strand init` creates missing workspace files without overwriting existing files. The generated `init.clj` imports:

```clojure
(require '[skein.libs.alpha :as libs]
         '[skein.graph.alpha :as graph]
         '[skein.views.alpha :as views])
(libs/sync!)
```

Built-in `skein.graph.alpha` and `skein.views.alpha` come from the configured Skein checkout. User/community libraries are approved separately in `libs.edn` and synced through `skein.libs.alpha`.

Example view setup in `init.clj`:

```clojure
(ns my.skein.init
  (:require [skein.libs.alpha :as libs]
            [skein.graph.alpha :as graph]
            [skein.views.alpha :as views]
            [skein.weaver.api :as api]))

(libs/sync!)
(api/register-query! 'owned [:= [:attr :owner] "ct"])

(defn owned-view [{:keys [params]}]
  (let [ids (graph/query-ids! 'owned params)]
    {:ids ids
     :strands (graph/strands-by-ids ids)}))

(views/register-view! 'owned-view 'my.skein.init/owned-view)
```

Use the connected stdin REPL for scripts:

```sh
printf '(ready)\n' | strand --config-dir "$world" weaver repl --stdin
```

Stop the weaver when finished:

```sh
strand --config-dir "$world" weaver stop
```
