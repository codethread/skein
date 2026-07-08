# Skein 🧶 Give your agents a lisp

Skein is a local graph for tracking the work that coding agents and the people
working with them generate: tasks, notes, dependencies, review state, whatever
your workflow needs to remember. It runs on your own machine and stores
everything in a SQLite file. The everyday `strand` commands print JSON, so
scripts and agents can drive it without scraping text out of a pretty table.

A few terms up front, since the rest of this page uses them:

- A **strand** is one record in the graph: a title, a lifecycle `state`, and a
  map of `attributes` you define.
- Skein is written in **Clojure**, a Lisp that runs on the JVM. The **weaver**
  is a long-lived Clojure process that owns your data and runs in the background.
- The **`strand` CLI** is a thin, JSON-only command surface. **mill** is the
  local supervisor: you start it once, and it routes each command to the right
  weaver.
- A **workspace** is one isolated Skein setup, picked by directory: a repo's
  `.skein`, or an explicit `--workspace` you pass.

The weaver is the part worth knowing about. You can attach a REPL to it, look
at what it is doing, redefine a function, or reload your whole config, all while
it keeps running and without losing a strand. The workflow is not fixed by a
schema someone else chose; you build the parts you want.

Full documentation lives at **[codethread.github.io/skein](https://codethread.github.io/skein/)**.

## Is Skein for you?

Skein may fit if you run coding agents and want them to record structured work
you can query instead of prose you have to re-read, and you like a tool you can
reshape from the inside rather than file a feature request against. The bill:
a local background JVM process, Go and a JVM on the machine, and Clojure for
any behavior beyond the built-in commands. There is no hosted service, web UI,
or accounts — if you want a shared team tracker, Skein is not that.

A low-risk way in: one repo, one maintainer, the plain CLI. Reach for the
programmable parts once they pay for themselves.

## Quick start

Skein installs from a cloned checkout of this repository.

```sh
make install   # builds and installs the `strand` and `mill` CLIs from this checkout
mill start     # start the supervisor once, in a terminal you can leave open
```

Go to a Git repo you want to track work in, create its Skein workspace, and
start the weaver for it:

```sh
cd ~/some/git/repo
mill init            # writes this repo's .skein config directory
mill weaver start    # boot the weaver for this workspace
```

Add a few strands, including one that depends on another (`strand add` prints
the new strand as JSON; `jq` pulls the id out):

```sh
strand add "Sketch the data model"
id=$(strand add "Write the docs" | jq -r '.id')
strand add "Announce the release" --edge depends-on:"$id"
```

Ask what exists, and what is ready to work on:

```sh
strand list     # every strand
strand ready    # only strands with nothing blocking them
```

`ready` leaves out "Announce the release" because it depends on "Write the
docs", which is still active. Close a strand and the graph moves:

```sh
strand update "$id" --state closed
strand ready    # now "Announce the release" shows up
```

Open a live REPL when you want to look inside the weaver:

```sh
mill weaver repl
```

From the REPL you can register a named query and see it immediately from the
plain CLI, while the weaver keeps running:

```clojure
(defquery! 'mine '[:= [:attr :owner] "ct"])
```

```sh
strand list --query mine
mill weaver stop
```

With no `--workspace`, `strand` finds the canonical Git repository root and
uses that repo as its workspace. Outside a Git repo, commands fail loudly
rather than guess. The [getting started guide](./docs/getting-started.md) walks
through all of this slowly, including throwaway `--workspace` worlds for
experiments.

## Learn it from an agent

Skein is built for agents, and its own repository is written for them to read.
Point a coding agent at a checkout and ask questions: `mill skein prime` and
`mill strand prime` print orientation, [`AGENTS.md`](./AGENTS.md) and the specs
under [`devflow/specs/`](./devflow/specs/) carry the real contracts, and
`mill init` seeds a pointer to the prime commands into your own repo's
`AGENTS.md`.

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
