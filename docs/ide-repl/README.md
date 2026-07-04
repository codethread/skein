# IDE REPL setup

Skein exposes each running weaver as an nREPL server. Editor integrations can connect to that nREPL directly, which lets you evaluate Clojure forms against the live weaver runtime.

This guide covers VS Code with [Calva](https://calva.io/), a popular Clojure extension. Unlike the Neovim integration in `integrations/neovim`, this is just a manual connection workflow; there is no Skein-specific VS Code plugin.

## Prerequisites

1. Install the Skein CLIs from the Skein checkout:

   ```sh
   make install
   ```

2. Install VS Code and the Calva extension.
3. Ensure `mill` and `strand` are on your shell `$PATH`.

## Start a weaver

Start mill in a durable terminal:

```sh
mill start
```

From the repository or Skein workspace you want to work with, start its weaver:

```sh
mill weaver start
```

If you are using an explicit workspace, pass the same workspace you use for all other commands:

```sh
mill weaver start --workspace "$workspace"
```

## Find the nREPL port

List running weavers through mill:

```sh
mill weaver list
```

The output is JSON. Find the row for your workspace and use its `nrepl.host` and `nrepl.port` fields, for example:

```json
[
  {
    "name": "my-repo",
    "config_dir": "/path/to/my-repo/.skein",
    "state": "running",
    "nrepl": {"host": "127.0.0.1", "port": 51234}
  }
]
```

With `jq`, you can print just the endpoints:

```sh
mill weaver list | jq -r '.[] | select(.state == "running") | "\(.name)\t\(.config_dir)\t\(.nrepl.host):\(.nrepl.port)"'
```

## Connect from VS Code / Calva

1. Open VS Code.
2. Install or enable the **Calva: Clojure & ClojureScript Interactive Programming** extension.
3. Run the command palette action **Calva: Connect to a Running REPL Server in the Project**.
4. Choose **Clojure CLI** or **Generic nREPL** when prompted for the REPL type.
5. Enter the host and port from `mill weaver list`.

After Calva connects, evaluate this form once to enter Skein's convenience namespace:

```clojure
(do (require 'skein.repl) (in-ns 'skein.repl))
```

You can now evaluate forms such as:

```clojure
(ready)
(strands)
(def s (:id (strand! "Try VS Code REPL" {:owner "me"})))
(strand s)
(update! s {:state "closed"})
```

## Evaluating from files

When evaluating forms from a file, the file's namespace still matters. For scratch notes or `.skein/init.clj` experiments, prefer an explicit alias:

```clojure
(require '[skein.repl :as repl])

(comment
  (repl/ready)
  (def s (:id (repl/strand! "Try editor eval" {:owner "me"})))
  (repl/update! s {:state "closed"}))
```

Stop the weaver when you are finished:

```sh
mill weaver stop
```
