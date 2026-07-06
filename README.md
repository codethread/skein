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
- The **`strand` CLI** is a thin, JSON-only command surface for scripts and
  agents. **mill** is the local supervisor: you start it once, and it routes
  each command to the right weaver.
- A **REPL** is an interactive Clojure prompt. Attaching one to the weaver lets
  you query, change, and extend Skein while it keeps running.
- A **workspace** is one isolated Skein setup, picked by directory: a repo's
  `.skein`, or an explicit `--workspace` you pass.

The weaver is the part worth knowing about. You can attach a REPL to it, look at
what it is doing, redefine a function, or reload your whole config, all while it
keeps running and without losing a strand. The workflow is not fixed by a schema
someone else chose; you build the parts you want.

## Is Skein for you?

Skein may fit if:

- you run coding agents and want them to record structured work you can query,
  instead of prose you have to re-read;
- you have outgrown a flat todo list but do not want a hosted tracker or a
  server to keep alive;
- you like a tool you can reshape from the inside rather than file a feature
  request against.

If you want a shared team tracker with a web UI and accounts, Skein is not that.
It is a local, single-machine graph that you program.

## What adopting it costs

Skein is honest about its shape, so here is the bill before you try it:

- A local background process (the weaver) runs while you use it. There is no
  hosted service, no web UI, and no accounts.
- You need a JVM (for the weaver) and Go (for the CLIs) on the machine.
- Your data is a local SQLite file under Skein's own state directory. Your
  shared config is a small `.skein/` directory you commit to the repo.
- Behavior you add beyond the built-in commands is Clojure. A team that wants
  custom workflow needs someone willing to own that Clojure.

A low-risk way in: start with one repo and one maintainer, use the plain CLI,
and reach for the programmable parts only once they pay for themselves.

## Quick start

Skein installs from a cloned checkout of this repository. You need Go (for the
CLIs) and a JVM (for the weaver).

```sh
make install   # builds and installs the `strand` and `mill` CLIs from this checkout
mill start     # start the supervisor once, in a terminal you can leave open
```

`make install` compiles the two command-line tools and records this checkout as
the source mill uses to launch weavers. `mill` is the supervisor you just
started; it stays running and hands each command to the right weaver.

Go to a Git repo you want to track work in, create its Skein workspace, and
start the weaver for it:

```sh
cd ~/some/git/repo
mill init            # writes this repo's .skein config directory
mill weaver start    # boot the weaver for this workspace
```

`mill init` creates the `.skein/` directory at the repo root. That directory
holds only config (the files you commit to share behavior); your strands live in
SQLite under Skein's state directory, not in the repo.

Add a few strands, including one that depends on another. `strand add` prints the
new strand as JSON, and `jq` (the standard JSON command-line tool) pulls the
generated id out of it:

```sh
strand add "Sketch the data model"
id=$(strand add "Write the docs" | jq -r '.id')   # capture the new strand's id
strand add "Announce the release" --edge depends-on:"$id"
```

Ask what exists, and what is ready to work on:

```sh
strand list     # every strand
strand ready    # only strands with nothing blocking them
```

`ready` leaves out "Announce the release" because it depends on "Write the docs",
which is still active. Close a strand when it is done and the graph moves:

```sh
strand update "$id" --state closed
strand ready    # now "Announce the release" shows up
```

Open a live REPL when you want to look inside the weaver:

```sh
mill weaver repl
```

From the REPL you can register a named query and see it immediately from the
plain CLI:

```clojure
(defquery! 'mine '[:= [:attr :owner] "ct"])   ; register a named query, live
```

```sh
strand list --query mine   # the CLI can run it too, while this weaver runs
mill weaver stop           # stop the weaver when you are done
```

With no `--workspace`, `strand` finds the canonical Git repository root and uses
that repo as its workspace, so linked worktrees of one repository share a
workspace. Outside a Git repo, commands fail loudly rather than guess. The
[getting started guide](./docs/getting-started.md) walks through all of this
slowly, and shows how to use a throwaway `--workspace` for experiments.

## When something goes wrong

Skein is built to stay inspectable, so recovery is boring by design:

- Your strands are a local SQLite file under Skein's state directory. `.skein/`
  in the repo is config only, so deleting or resetting a workspace never loses
  committed config, and vice versa.
- Every command emits JSON and fails loudly instead of guessing, so errors are
  visible and scriptable.
- A disposable `--workspace` is the safe test lane: try config and custom
  behavior there first, and you cannot corrupt a real repo's state.
- The restart and reload boundaries (what survives, what does not) are spelled
  out in [docs/skein.md](./docs/skein.md).

Because the data is SQLite, the config is plain repo files, and every command
speaks JSON, nothing about Skein is opaque. You can export, inspect, or leave it
without prying data out of a proprietary store.

## Let a coding agent show you around

Skein is built for agents, and its own repository is written for them to read.
One practical way to learn it is to point your coding agent at a Skein checkout
and ask questions.

- `mill skein prime` prints orientation for the Skein source, docs, and how to
  extend a `.skein/` config. `mill strand prime` explains the strand
  planning-and-tracking workflow. Both run without a weaver.
- The repo's `AGENTS.md` / `CLAUDE.md` and the specs under
  [`devflow/specs/`](./devflow/specs/) describe the real contracts.
- When you run `mill init` in your own repo, it seeds a short section into that
  repo's `AGENTS.md`/`CLAUDE.md` pointing agents at the prime commands.

An agent that has read that surface can answer "how do I model X?"
interactively, which is often quicker than reading the reference docs front to
back.

## The data model

Skein stores:

- **strands** — a generated text id, a title, a lifecycle `state`, timestamps,
  and JSON attributes;
- **edges** — a relation name, a direction, and JSON attributes;
- three **declared acyclic relations** for structure: `depends-on`, `parent-of`,
  and `supersedes`.

`state` is the only built-in lifecycle field, with values `active`, `closed`,
and `replaced`. Active strands take part in readiness; closed and replaced ones
stay in the graph; deletion is explicit, with `burn`.

Superseding is a first-class move. `strand supersede <old-id> <replacement-id>`
records `replacement --supersedes--> old`, marks the old strand `replaced`, and
rewires its dependents onto the replacement.

Everything else (priority, ownership, outcomes, board columns, "kind") lives in
attributes your workspace decides on, not in built-in fields. That is the point:
you model your concepts as open JSON, and Skein does not make you wait for a
schema to grow.

## The good bits

Two parts are worth calling out.

**A live system you reshape.** The weaver is a real Clojure image, the whole
language included. Attach a REPL, inspect the runtime, redefine a helper, and the
next call picks it up. Reload your entire config without a restart and your
strands stay put. The runtime stays visible and editable while it is running.

```clojure
(defquery! 'mine '[:= [:attr :owner] "ct"])   ; register a named query, live
(strands 'mine)                               ; run it
```

That query is now visible to the plain CLI too, for as long as the weaver runs:

```sh
strand list --query mine
```

**Workflows you build.** Skein ships primitives (named queries, weave patterns,
views, event handlers, and custom `strand <op>` commands), and you assemble the
workflow you actually want from them, in trusted Clojure. The
[reference spools](./spools/README.md) show this in practice: a workflow engine,
a feature lifecycle, a kanban board, an ephemeral-strand helper, and more, each
one working code you can read, run, or copy.

That programmability is also a maintenance choice, so keep it deliberate. Keep
the shared CLI path boring, try new ideas in a disposable workspace first, and
move behavior into committed Clojure only when someone is willing to own it. Once
custom behavior grows past a helper or two, treat it like the rest of your repo's
code and follow [writing shared spools](./docs/writing-shared-spools.md).

The [getting started guide](./docs/getting-started.md) builds up to both,
starting from the plain CLI.

## Where to go next

- [Getting started](./docs/getting-started.md) — a walkthrough from install to
  your first custom command.
- [Skein user reference](./docs/skein.md) — the full model, CLI, weaver, REPL,
  and workspace conventions.
- [Shipped reference spools](./spools/README.md) — the workflow extensions that
  ship with Skein.
- [Writing shared spools](./docs/writing-shared-spools.md) — how to build
  extensions others can run.
- [Library authoring](./docs/library-authoring.md) — testing spools against a
  chosen Skein checkout.
- [Clojure crash course](./docs/clojure-crash-course.md) — enough Clojure to
  read the REPL examples, if it is new to you.
- [Devflow specs](./devflow/specs/) — the exact source contracts behind the
  alpha surface.
