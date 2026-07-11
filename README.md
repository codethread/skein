# Skein 🧶 Give your agents a lisp

Skein is a local graph for the work coding agents generate: tasks, notes, dependencies, review state — whatever your workflow needs to remember. Everything lives in a SQLite file on your machine, and the everyday `strand` commands print JSON for scripts and agents.

The tagline is literal. Skein is written in Clojure, a Lisp that runs on the JVM, and the process that owns your data is a live image. You and your agents can attach REPLs to it at the same time, and every session shares that one image: define a var in one and the others see it. Redefine a function or reload your config while it keeps running, without losing a strand. The workflow is not fixed by a schema someone else chose; you build the parts you want.

A few terms up front, since the rest of this page uses them:

- A **strand** is one record in the graph: a title, a lifecycle `state`, and a
  map of `attributes` you define.
- The **weaver** is the long-lived Clojure process that owns your data and
  runs in the background.
- The **`strand` CLI** is a thin, JSON-only command surface. **mill** is the
  local supervisor: you start it once, and it routes each command to the right
  weaver.
- A **workspace** is one isolated Skein setup, picked by directory: a repo's
  `.skein`, or an explicit `--workspace` you pass.

<img src="./docs/assets/mill-weaver-strands.svg" width="560"
     alt="mill, the supervisor, routes to one weaver per workspace; a worktree of repo-a uses the same repo-a weaver; each weaver owns its own skein.sqlite of strands.">

Full documentation lives at **[codethread.github.io/skein](https://codethread.github.io/skein/)**.

## Is Skein for you?

The short version: Skein wants to be Emacs for agents — a small core held stable, a live programmable runtime, and everything else built in userland.

It was built against a few specific problems. If you recognize them, Skein is probably for you:

- **Orchestrating more than one harness.** Claude and GPT working the same board, seeing each other's strands, handing work to each other. Skein is the shared world they coordinate through; each harness stays small and focused, and the orchestration lives above them rather than inside any one of them.
- **Agent behavior as code, not prose.** Skills and instruction files drive critical behavior, yet prose can't be tested or debugged. In Skein the load-bearing behavior is Clojure you read, test, and grow one function at a time.
- **Conventions that survive a provider switch.** A repo's workflow lives in its `.skein`, shared ideas travel as spools, and none of it cares which harness runs against it — swapping providers doesn't mean rebuilding your process.
- **A foundation that holds still.** Agent tooling churns weekly. Skein's core is minimal and deliberately boring: the strand schema is meant to outlive whatever sits on top, so you can build your own workflow engine against it, or use the reference spools. Honest caveat: Skein is alpha today, so this is the destination rather than a guarantee — contracts can still change while the core settles.

The bill: a local background JVM process, Go and a JVM on the machine, and Clojure for any behavior beyond the built-in commands. There is no hosted service, web UI, or accounts — if you want a shared team tracker, Skein is not that.

A low-risk way in: one repo, one maintainer, the plain CLI. Reach for the programmable parts once they pay for themselves.

## Quick start

Skein installs from a cloned checkout of this repository.

```sh
make install   # builds and installs the `strand` and `mill` CLIs from this checkout
mill start     # start the supervisor once, in a terminal you can leave open
```

Go to a Git repo you want to track work in, create its Skein workspace, and start the weaver for it:

```sh
cd ~/some/git/repo
mill init            # writes this repo's .skein config directory
mill weaver start    # boot the weaver for this workspace
```

Add a few strands, wiring in dependencies and a `type` attribute as you go (`strand add` prints the new strand as JSON; `jq` pulls the id out):

```sh
model=$(strand add "Sketch the data model" --attr type=docs | jq -r '.id')
docs=$(strand add "Write the docs" --attr type=docs \
  --edge depends-on:"$model" | jq -r '.id')
cli=$(strand add "Build the CLI" --attr type=code \
  --edge depends-on:"$model" | jq -r '.id')
strand add "Announce the release" \
  --edge depends-on:"$docs" \
  --edge depends-on:"$cli"
```

> [!NOTE]
> `type` is not a Skein concept. Attributes are arbitrary key/values — this example invented `type=docs|code` on the spot, and inventing your own conventions is the point. See [attributes are the extension point](./docs/skein.md#attributes-are-the-extension-point).

Four commands, and you have a graph — each strand is a node carrying its attributes, and each edge points at the work it waits on:

<img src="./docs/assets/strand-graph.svg" width="640"
     alt="Four strands in a graph, each carrying its attributes map. 'Write the docs' and 'Build the CLI' depend on 'Sketch the data model'; 'Announce the release' depends on both of them. Only 'Sketch the data model' is ready.">

<details markdown>
<summary>The same graph in one REPL call</summary>

From the weaver's REPL (covered below), the whole graph is one transactional weave. `:ref` names are temporary handles, so edges can point at siblings created in the same call:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.batch.alpha :as batch])

(batch/apply! (current/runtime)
  {:strands [{:ref :model    :title "Sketch the data model" :attributes {:type "docs"}}
             {:ref :docs     :title "Write the docs"        :attributes {:type "docs"}}
             {:ref :cli      :title "Build the CLI"         :attributes {:type "code"}}
             {:ref :announce :title "Announce the release"}]
   :edges   [{:op :upsert :from :docs     :to :model :type "depends-on"}
             {:op :upsert :from :cli      :to :model :type "depends-on"}
             {:op :upsert :from :announce :to :docs  :type "depends-on"}
             {:op :upsert :from :announce :to :cli   :type "depends-on"}]})
```

</details>

Ask what exists, and what is ready to work on. Both print plain JSON rows (trimmed here):

```sh
strand list     # every strand
```

```json
[{"id":"gbkcx","title":"Write the docs","state":"active","attributes":{"type":"docs"}, …},
 {"id":"st1ca","title":"Announce the release","state":"active","attributes":{}, …},
 {"id":"vfkhw","title":"Build the CLI","state":"active","attributes":{"type":"code"}, …},
 {"id":"xhwxk","title":"Sketch the data model","state":"active","attributes":{"type":"docs"}, …}]
```

```sh
strand ready    # only strands with nothing blocking them
```

```json
[{"id":"xhwxk","title":"Sketch the data model","state":"active","attributes":{"type":"docs"}, …}]
```

Every other strand waits on "Sketch the data model", directly or through another strand. Close it and the graph moves:

```sh
strand update "$model" --state closed
strand ready    # now "Write the docs" and "Build the CLI" are ready
```

Open a live REPL when you want to look inside the weaver:

```sh
mill weaver repl
```

From the REPL you can register a named query and see it immediately from the plain CLI, while the weaver keeps running:

```clojure
(defquery! 'code '[:= [:attr :type] "code"])
```

```sh
strand list --query code    # just "Build the CLI"
mill weaver stop
```

The everyday commands are defined the same way: `add`, `list`, `ready`, and the rest come from the [batteries spool](./spools/batteries.md), activated by one line `mill init` writes into `.skein/init.clj`. Remove that line and `strand` keeps only `help`; register your own ops in its place and the CLI becomes whatever surface your workflow needs.

With no `--workspace`, `strand` finds the canonical Git repository root and uses that repo as its workspace. Outside a Git repo, commands fail loudly rather than guess. The [getting started guide](./docs/getting-started.md) walks through all of this slowly, including throwaway `--workspace` worlds for experiments.

## Learn it from an agent

Skein is built for agents, and its own repository is written for them to read. Point a coding agent at a checkout and ask questions: `mill skein prime` and `mill strand prime` print orientation, [`AGENTS.md`](./AGENTS.md) and the specs under [`devflow/specs/`](./devflow/specs/) carry the real contracts, and `mill init` seeds a pointer to the prime commands into your own repo's `AGENTS.md`.

## Where to go next

- [Docs site](https://codethread.github.io/skein/) — everything below, rendered.
- [Getting started](./docs/getting-started.md) — install to your first custom command.
- [Skein user reference](./docs/skein.md) — the data model, CLI, weaver, REPL,
  and workspace conventions.
- [Reference spools](./spools/README.md) — the shipped workflow extensions:
  a workflow engine, a feature lifecycle, a kanban board, and more, each one
  working code you can read, run, or copy.
- [Writing shared spools](./docs/writing-shared-spools.md) and
  [library authoring](./docs/library-authoring.md) — building extensions others
  can run.
- [Clojure crash course](./docs/clojure-crash-course.md) — enough Clojure to
  read the REPL examples.

## Beyond the primitives

Everything on this page is a few small primitives — `add`, `weave`, `pattern` — over one graph. Around them Skein ships shared libraries called [spools](./spools/README.md), the durable workspace config you saw `mill init` create, an event and hooks system inside the weaver, and a testing library (`skein.test.alpha`) that spins up disposable weaver worlds. They go a long way: this repository coordinates its own development (a kanban board, a feature lifecycle, delegated agent runs, and a landing workflow) entirely in userland code built from those parts.

<details markdown>
<summary>A landing workflow, condensed</summary>

A sketch of the landing discipline in this repo's [`.skein/workflows.clj`](./.skein/workflows.clj). Steps compile to strands in the same graph you have been querying: the review gate is completed by a delegated subagent run, and the merge step cannot become ready until CI is green and sign-off is decided.

```clojure
(workflow/workflow "Land a feature branch"
  (workflow/step :push-draft-pr "Push the branch and open a draft PR" :self)
  (workflow/step :ci-green "Watch CI to green at HEAD" :self
                 :depends-on [:push-draft-pr])
  (workflow/gate :review "Roster code review" :subagent
                 :depends-on [:ci-green])
  (workflow/checkpoint :signoff "Sign off the landing"
                       :depends-on [:review]
                       :kind :agent
                       :choices [{:key :approved :label "Approve"}
                                 {:key :abort :label "Abort"}])
  (workflow/step :merge-verify "Squash-merge to main and verify" :self
                 :depends-on [:signoff]))
```

</details>
