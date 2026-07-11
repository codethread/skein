# Getting started with Skein

This guide takes you from nothing to a working Skein setup, then a little further, into the live REPL and your own custom command. You can follow it top to bottom. You do not need to know Clojure or any graph tooling to start.

It is written in two halves:

1. **The everyday CLI** — install, start a weaver, add strands, ask what is
   ready. This is all most people need day to day.
2. **The live machine** — the REPL, named queries, and building your own
   behavior. Optional, and clearly marked, so you can stop after part one and
   come back later.

The guide marks places where you can skip ahead. If a section is not what you need right now, skip it. Already sold on the live-runtime story and want to jump straight to it? Go to [The REPL: a live machine](#the-repl-a-live-machine).

## Prefer to learn by asking?

Skein's own repository is written to be read by coding agents, and it ships agent-facing docs, `prime` orientation commands, and the specs behind every contract. If you already work with a coding agent, you can point it at a Skein checkout and ask questions as you go.

- `mill skein prime` prints orientation for the Skein source, docs, and how to
  extend a `.skein` config. `mill strand prime` explains the strand
  planning-and-tracking workflow. Both run with no weaver.
- `docs/skein.md`, the `spools/` contracts, and `devflow/specs/` hold the real
  detail.

An agent that has read that surface can answer "how do I model a review step?" or "what belongs in the CLI versus the REPL?" interactively. You can follow this guide without an agent; the agent is optional.

## The mental model in one minute

A **strand** is a small record with three parts: a title, a **lifecycle `state`** (whether it is `active`, `closed`, or `replaced`), and an **open map of `attributes`**. "Open map" means the attribute names are yours to invent (`owner`, `priority`, `kind`, `kanban`, whatever your workflow needs) instead of waiting for a schema to grow.

Strands connect with **edges**: links from one strand to another. Edges are **typed**, meaning each carries a relation name like `depends-on`, and they can carry attributes of their own.

You work with strands two ways:

- The **`strand` CLI** for everyday create, update, and read. Its commands print
  JSON, so scripts and agents can consume them.
- The **weaver's REPL** for everything richer: querying, mutating, and extending
  Skein while it runs, without restarting anything. A REPL is an interactive
  Clojure prompt; more on it in part two.

The CLI stays deliberately thin. Runtime customization lives in trusted config and the REPL. See [PHILOSOPHY.md](../devflow/PHILOSOPHY.md) for why the line is drawn there.

## Before you start

You need a few things on the machine:

- **Git**, and a Git repository to track work in. Skein is repo-first and does
  not run outside Git.
- **make**, to build and install the CLIs.
- **Go**, which compiles the `strand` and `mill` command-line tools.
- **A JVM**, which runs the weaver (Skein is a Clojure program).

Install those the usual way for your platform before the steps below.

## Install

Skein installs from a cloned source checkout of this repository.

```sh
make install
```

This builds and installs the `strand` and `mill` CLIs and records this checkout as mill's source for launching weavers. It does not change anything `mill init` writes into a repo.

## Choosing a workspace

A workspace is one isolated Skein setup. By default `strand` is **repo-first**: when you do not pass `--workspace`, `mill` looks upward for the Git repository root (the "canonical" root that linked worktrees share) and uses that repo's `.skein` directory as your workspace. Two worktrees of the same repository talk to the same weaver and the same data.

If you omit `--workspace` outside a Git repository, the command fails with a remediation message instead of guessing. It will not invent a workspace from your current directory or fall back to a global one.

That `.skein` directory holds trusted config only. The runtime state (metadata, sockets, and the SQLite database) lives under Skein's own state directory, not in your repo.

Create a workspace in the repo you want to use Skein in:

```sh
mill init
```

`mill init` creates or completes `.skein` at the Git root. It writes shared, committable config files (covered later) and never overwrites ones you already have. It fails loudly outside Git, does not run `git init`, and does not create the database; the weaver prepares storage when it starts.

**Escape hatch — throwaway workspaces.** For experiments, tests, or agent work, use a disposable workspace so you never touch a real repo's config:

```sh
workspace=$(mktemp -d)
mill init --workspace "$workspace"
```

`--workspace` is not sticky, so pass the same path on **every** command that should target it, for example `mill weaver start --workspace "$workspace"`. The plain examples below leave the flag off for readability; the customization examples later use an explicit `$workspace` so you never casually reload a real repo's config.

## Start the weaver

Start mill once in a terminal you can leave open, then ask it to start the weaver for your workspace:

```sh
mill start
mill weaver start
```

There is no separate database-init step. Starting the weaver prepares storage.

## Add and inspect strands

Add a couple of strands, with a few attributes of your own choosing:

```sh
strand add "Review docs" --attr owner=ct --attr area=docs
strand add "Scratch idea" --attr temporary=true
```

Attributes are plain `key=value` strings. `--attr temporary=true` stores the string `"true"`, not a JSON boolean; richer values are a REPL job, covered later.

List everything, or just what is ready. These commands print JSON:

```sh
strand list
strand ready
```

## Dependencies and readiness

An edge named `depends-on` from `A` to `B` means "A is blocked while B is active". To add one, you need the id of the strand you depend on. `strand add` prints the new strand as JSON, so create the blocker first and read its `"id"` from the output:

```sh
strand add "Sketch the model"
```

The output includes a field like `"id": "ab12c"`. Use that generated id (yours will differ) to add a second strand that depends on it:

```sh
strand add "Build the weaver" --edge depends-on:ab12c
strand ready
```

`ready` returns active strands whose `depends-on` targets are inactive or absent (closed, replaced, or never created), so it shows "Sketch the model" but not "Build the weaver". Close the first and the second becomes ready. Use the id printed for "Sketch the model" earlier:

```sh
strand update ab12c --state closed
strand ready
```

**Scripting tip.** Once you are comfortable, you can capture an id in one line instead of copying it by hand. `jq` is the standard JSON command-line tool, and `$(...)` runs a command and keeps its output in a shell variable:

```sh
design=$(strand add "Sketch the model" | jq -r '.id')
strand add "Build the weaver" --edge depends-on:"$design"
```

`depends-on`, `parent-of`, and `supersedes` are Skein's three **declared acyclic relations**: a "relation" is an edge type, and "acyclic" means Skein rejects cycles in each of them (A cannot end up depending on itself through a chain). It also rejects an edge from a strand to itself on any relation.

## Closing and deleting

There is no special "done" command. Close a strand when it is no longer active, and record an outcome as an attribute if you want one:

```sh
strand update <strand-id> --state closed --attr outcome=done
```

On `update`, `--attr` merges into the strand's existing attributes: this call adds `outcome=done` and leaves `owner`, `area`, and anything else already there untouched. To change one attribute, name just that one.

Closed strands stay visible with `state="closed"`. Deletion is separate and explicit:

```sh
strand burn <strand-id>
```

---

That is the everyday CLI: add, relate, ask what is ready, close, and occasionally burn. If you do not need the REPL or runtime customization, you can stop here and run `mill weaver stop`. The rest of this guide covers the REPL and building your own behavior.

## The REPL: a live machine

The weaver is a running Clojure image. `mill weaver repl` attaches directly to it, so the code you type runs inside the weaver, against your real strands, with no restart between edits.

```sh
mill weaver repl
```

For editor-driven work, see the [IDE REPL setup guide](./ide-repl/) for connecting VS Code or Calva to the running weaver's nREPL.

**Reading the Clojure below.** A handful of rules cover everything in this section:

- A call puts the function name first, inside the parentheses: `(strand! "x")`
  calls `strand!` with `"x"`.
- The `!` on a name is a convention for "this changes something", not syntax.
- A word starting with a colon, like `:owner`, is a **keyword**: a plain,
  self-describing name often used as a map key.
- Curly braces make a **map** of key/value pairs: `{:owner "ct"}`.
- `def` gives a value a name you can reuse: `(def s ...)` binds `s`.

The [Clojure crash course](./clojure-crash-course.md) covers the rest. If you came for the payoff, the worked [custom command example](#example-your-own-kanban-command) is further down.

Create a strand and look it up:

```clojure
(def s (:id (strand! "My first REPL strand" {:owner "ct"}))) ; create; keep its :id in s
(strand s)                                                   ; look it up by id
```

Create several related strands in one transactional call. `:ref` values are temporary handles (stand-in names) so `:edges` can link siblings before the real ids exist; the returned `:refs` map binds each handle to its generated id:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.batch.alpha :as batch])   ; transactional graph mutations

(def refs
  (:refs
   (batch/apply! (current/runtime)
    {:strands [{:ref :design :title "Sketch the data model" :attributes {:owner "ct" :priority "high"}}
               {:ref :build  :title "Implement the weaver"  :attributes {:owner "ct"}}
               {:ref :docs   :title "Write getting-started" :attributes {:owner "agent"}}]
     :edges   [{:op :upsert :from :build :to :design :type "depends-on"}
               {:op :upsert :from :docs  :to :build  :type "depends-on"}]})))
```

Now write a small helper and use it:

```clojure
(defn brief
  "Keep just the :id and :title of each strand row."
  [rows]
  (map #(select-keys % [:id :title]) rows))

(brief (strands))   ; every strand, summarized
```

Because the weaver is live, you can improve `brief` while it runs. Redefine it, and the next call uses the new version. No restart, no lost strands:

```clojure
(require '[clojure.pprint :refer [pprint]])

(defn brief
  "Pretty-print just the :id and :title of each strand row."
  [rows]
  (pprint (map #(select-keys % [:id :title]) rows)))

(brief (strands))   ; same call, now pretty-printed
(brief (ready))     ; only strands with no active dependency
(update! s {:state "closed"})   ; close one; the row stays, state becomes "closed"
```

Skein ships graph helpers too. `graph/subgraph` walks a declared acyclic relation from a root id and returns the connected strands and edges. Fold that into an ASCII tree:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.graph.alpha :as graph]
         '[clojure.string :as str])

(defn dag-tree
  "Render the depends-on subgraph under root-id as an ASCII tree of titles."
  [root-id]
  (let [{:keys [strands edges]} (graph/subgraph (current/runtime) [root-id] {:type "depends-on"})
        title    (into {} (map (juxt :id :title)) strands)
        children (group-by :from_strand_id edges)
        lines    (fn lines [id depth]
                   (cons (str (apply str (repeat (max 0 (dec depth)) "   "))
                              (when (pos? depth) "└─ ")
                              (title id))
                         (mapcat #(lines (:to_strand_id %) (inc depth))
                                 (children id))))]
    (str/join "\n" (lines root-id 0))))

(println (dag-tree (:docs refs)))   ; walk from the docs strand
```

Produces:

```text
Write getting-started
└─ Implement the weaver
   └─ Sketch the data model
```

## Named queries: from the REPL to the CLI

A query is a data expression, here "the `owner` attribute equals `ct`". Register one by name in the REPL:

```clojure
(defquery! 'mine '[:= [:attr :owner] "ct"])   ; register a query named "mine"
(strands 'mine)                               ; run it in the REPL
```

The plain CLI can discover and run the same query for as long as this weaver keeps running:

```sh
strand query list
strand query explain mine
strand list --query mine
strand ready --query mine
```

`query list` and `query explain <name>` are read-only discovery. Applying a query stays on `list --query` and `ready --query`. See the [REPL API spec](../devflow/specs/repl-api.md) for the full predicate language.

Named queries registered this way last only for the current weaver run. To keep one across restarts, register it from startup config, covered next.

## Startup config and runtime helpers

Skip this section unless you want behavior that survives a restart or that the whole repo should share.

**What survives a restart.** Your strands do, because they live in SQLite. Anything you registered only in a live REPL (named queries, patterns, views, custom ops, event handlers) is weaver-lifetime state and disappears when the weaver stops. Move those registrations into startup config so the weaver re-installs them every time it boots. Reloading config (rather than restarting) re-runs the startup files in place and never touches your strands.

`mill init` creates missing workspace files without overwriting existing ones. For a repo workspace the layout is:

```text
.skein/
  .gitignore          # commit: ignore local and runtime artifacts
  config.json         # commit: shared alpha workspace config
  init.clj            # commit: shared trusted startup config
  spools.edn          # commit: shared approved local-root spools
  config.local.json   # gitignored: personal config overlay
  init.local.clj      # gitignored: personal startup overlay
  spools.local.edn    # gitignored: personal approved-spool overlay
```

These files are "trusted" because the weaver loads and runs them as code with weaver authority, so treat them like any committed source. Commit the shared files when you want the whole repo to get the same behavior. Keep personal, machine-specific overlays in the gitignored `*.local.*` files. Runtime metadata, sockets, and SQLite data live under Skein's state directory, not in `.skein`.

The generated `init.clj` is a small bootstrap:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

(runtime/sync! runtime)
;; batteries is the one classpath-shipped spool: require it explicitly before
;; its use! rather than approving a spools.edn coordinate for it.
(require 'skein.spools.batteries)
(runtime/use! runtime :skein/spools-batteries
  {:ns 'skein.spools.batteries
   :call 'skein.spools.batteries/activate!})
```

The weaver loads `init.clj`, then `init.local.clj`. This is where you register queries and patterns, load approved spools, and install your own conventions. To make the `mine` query above permanent, add its registration to `init.clj`:

```clojure
(require '[skein.api.graph.alpha :as graph])
(graph/register-query! (current/runtime) 'mine [:= [:attr :owner] "ct"])
```

Two kinds of code can extend the weaver:

- **Built-in `skein.api.*.alpha` namespaces** — privileged helpers shipped on
  the Skein classpath.
- **Your own trusted spools** — Clojure you approve in `spools.edn` and load
  through config or the REPL.

`spools.local.edn` overlays `spools.edn` by coordinate, so a personal fork can replace a shared entry without editing committed config:

```clojure
;; .skein/spools.edn, committed
{:spools {team/workflows {:local/root "spools/team-workflows"}}}
```

```clojure
;; .skein/spools.local.edn, gitignored
{:spools {team/workflows {:local/root "~/dev/workflows/team-workflows"}
          personal/ops   {:local/root "~/dev/workflows/personal-ops"}}}
```

Skein does not resolve floating or transitive dependencies here: it will not chase version ranges or pull a spool's own dependencies for you. A `:local/root` must already exist on disk. A shared spool can also be approved by a pinned git coordinate (`:git/url` plus a 40-character `:git/sha`); on first use `sync!` fetches that exact commit and caches it by sha. The [writing shared spools](./writing-shared-spools.md) guide covers the git-distribution path, and the [shipped reference spools](../spools/README.md) are worked examples of spool design, driven end to end by their own tests.

**Keep it governable.** Runtime programmability is easy to overuse. Try new behavior in a disposable workspace first, and promote it into committed `init.clj` or a spool only after review, with someone named to own it and its commands documented for the agents and people who depend on them. Once custom behavior grows past a helper or two, follow [writing shared spools](./writing-shared-spools.md) for the maintainable path.

### Custom operations

Every weaver command is a registered op, invoked as `strand <op> [args]`. There is no `op` sub-prefix. The built-in `help` op lists registered ops and explains one op's arguments:

```sh
strand help
strand help <op>
```

Register your own from trusted Clojure with `skein.api.weaver.alpha/register-op!`. The CLI forwards everything after the op name as string argv:

```clojure
(require '[skein.api.current.alpha :as current])
(require '[skein.api.weaver.alpha :as weaver])

(defn echo-op [{:op/keys [name argv]}]
  {:operation name :argv argv})

(weaver/register-op! (current/runtime) 'echo "Echo raw argv" 'user/echo-op)
```

```sh
strand echo --flag value
```

## Example: your own kanban command

The op surface is deliberately generic: Skein only routes argv to a trusted weaver-side handler. Workflow behavior like a kanban board belongs in your config or spool code, not in core Skein. Here is a small one, built in a disposable workspace so you can try it without touching a real repo. Promote something like this into a committed workspace only once it earns its keep and has an owner.

Append this handler to `$workspace/init.clj` (not a real repo's `.skein/init.clj`):

```clojure
(require '[clojure.string :as str])
(require '[skein.api.current.alpha :as current])
(require '[skein.api.weaver.alpha :as weaver])

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
  (->> (weaver/list rt [:= [:attr :kanban] status] {})
       (take limit)
       vec))

(defn kanban-op [{:op/keys [argv]}]
  (let [rt (current/runtime)
        max-rows (parse-max argv)
        cols [(by-kanban rt "ready" max-rows)
              (by-kanban rt "in-progress" max-rows)
              (by-kanban rt "done" max-rows)]
        row-count (apply max (map count cols))
        rows (for [i (range row-count)]
               (mapv #(title-cell (nth % i nil)) cols))]
    {:max max-rows
     :table (table rows)}))

(weaver/register-op! (current/runtime) 'kanban "Show strands grouped by :attr kanban" 'user/kanban-op)
```

Reload that disposable workspace's config so its weaver installs the handler. Only reload the workspace you mean to; do not point this at a real repo unless you intend to reload its shared config. This one-liner sends a `reload!` form to the weaver over stdin (`printf` prints the Clojure, `mill weaver repl --stdin` evaluates it):

```sh
printf '(do (require '\''[skein.api.current.alpha :as current] '\''[skein.api.runtime.alpha :as runtime]) (runtime/reload! (current/runtime)))\n' \
  | mill weaver repl --stdin --workspace "$workspace"
```

Create a few demo strands with one batch call. `cat <<'EOF' ... EOF` is a shell heredoc: it feeds the lines between the markers to the command on the left, so the Clojure block reaches the weaver's REPL on stdin:

```sh
cat <<'EOF' | mill weaver repl --stdin --workspace "$workspace"
(do
  (require '[skein.api.current.alpha :as current]
           '[skein.api.batch.alpha :as batch])
  (batch/apply! (current/runtime)
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

Now invoke your command. `--max` is optional and defaults to 5 rows:

```sh
strand --workspace "$workspace" kanban --max 15
```

`strand` keeps public output JSON, so pull out the `table` field when you want the terminal-friendly view:

```sh
strand --workspace "$workspace" kanban --max 15 | jq -r .table
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

You just added a command to Skein without recompiling or restarting it. Views and event handlers work the same way, from trusted Clojure; see [docs/skein.md](./skein.md) for those.

## Stop the weaver

Stop the weaver when you are finished:

```sh
mill weaver stop --workspace "$workspace"
```

For a repo workspace, drop the flag:

```sh
mill weaver stop
```

## Where to go next

- [Skein user reference](./skein.md) — the complete model, CLI, weaver, REPL,
  and workspace behavior, with a spec index at the end.
- [Shipped reference spools](../spools/README.md) — a workflow engine, a feature
  lifecycle, a kanban board, and more, as working code.
- [Writing shared spools](./writing-shared-spools.md) — building extensions
  other people can run.
- [Library authoring](./library-authoring.md) — testing spools against a chosen
  Skein checkout.
- [Clojure crash course](./clojure-crash-course.md) — enough Clojure to be
  comfortable in the REPL.
- [Tenets](../devflow/TENETS.md) and [philosophy](../devflow/PHILOSOPHY.md) —
  why Skein is shaped the way it is.
