# Customising your workspace

Skein's core is deliberately small; most of what your workspace *means* lives in trusted Clojure code the
weaver loads for you — named queries, weave patterns, views, event handlers, ops. This page is the ladder for
that code. You start with a few lines in `init.clj`, promote them to a local spool when the config grows, and
only when a spool leaves your machine do the authoring rules change. Everything up to that last step lives here.

If you have not met the weaver, workspaces, or the strand model yet, read the [tutorial](../tutorial.md) first;
the [reference](../reference.md) covers the full command and runtime surface. Per-function API detail is
deliberately absent from this page: the generated [alpha API reference](../api/README.md) documents every
`skein.api.*.alpha` function, and this page only shows how the pieces compose.

## The files mill init gives you

`mill init` bootstraps missing workspace files without overwriting existing ones. It does not initialize
database storage; weaver startup prepares storage for the selected workspace. The full layout of an ordinary
repo-local `.skein` workspace:

```text
.skein/
  config.json        -> shared alpha workspace config (the low-privilege format marker)
  config.local.json  -> personal config overlay
  init.clj           -> shared trusted startup code loaded by the weaver
  init.local.clj     -> personal startup overlay loaded after init.clj
  spools.edn         -> shared approved local spool roots
  spools.local.edn   -> personal approved-spool overlay
  spools/            -> optional local spools
```

When absent, `mill init` creates the shared half: `config.json` with the alpha format marker, `spools.edn` as
`{:spools {}}`, `init.clj` with the default below, and the `spools/` directory. It also adds a `.gitignore`
ignoring the personal overlays (`config.local.json`, `init.local.clj`, `spools.local.edn`). The overlays are yours to add
when you want them: each shared file has a gitignored personal counterpart, so shared config is committed and
reviewed while personal config stays on your machine. Explicit `--workspace` bootstrap works the same way on
the selected directory, preserving whatever already exists.

## A private repo-local workspace

Run `mill init --stealth` when you want Skein in a repository without committing its config. The
workspace remains a physical `.skein` directory at the Git root, so agents, `rg`, Make, Clojure,
and weaver calls see normal repo-local paths. Mill adds its paths to `.git/info/exclude`, which is
private to that clone, and reports each file it created, updated, skipped, or left unchanged.

Stealth init does not write shared `AGENTS.md` or `CLAUDE.md`. It creates or updates an untracked
`CLAUDE.local.md` when safe and prints the instruction Codex users may add to their own guidance.
If `.skein` is already tracked or a mill-owned marker block was edited, it refuses before changing
anything.

Keep the generated startup small. Put personal activation in `init.local.clj`, approvals in
`spools.local.edn`, and substantive code in a local spool. If that code needs history, keep the
spool in its own Git repository and approve its external root from `spools.local.edn`; `.skein`
then remains only the repo-local entry point.

## Startup files

The weaver loads startup files in order: `init.clj`, then `init.local.clj`. Missing files are skipped; present
failing files fail loudly with file context. The generated `init.clj` is intentionally small:

```clojure
;; init.clj
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

(runtime/sync! runtime)
;; batteries ships on the classpath (:paths), so require it before its use!.
(require 'skein.spools.batteries)
(runtime/use! runtime :skein/spools-batteries
  {:ns 'skein.spools.batteries
   :call 'skein.spools.batteries/install!})
```

`skein.api.runtime.alpha` is a privileged built-in runtime loader/config helper namespace shipped with Skein —
not an ordinary user spool, which is why loader/config helpers do not live under `skein.spools.*`.

Startup files matter because runtime registries are weaver-lifetime state: named queries, weave patterns,
views, and event handlers registered from a live REPL vanish with the process. Anything you want after every
restart belongs in startup-loaded code. A first customisation is often a single named query, registered
directly by appending two lines to the generated `init.clj`:

```clojure
(require '[skein.api.graph.alpha :as graph])

(graph/register-query! runtime 'mine [:= [:attr :owner] "ct"])
```

Now `strand list --query mine` works from the CLI, in this weaver generation and every one after. Simple
workspaces can keep going exactly like this: shared registrations directly in `init.clj`, personal ones in
gitignored `init.local.clj`. When the file starts accumulating real behavior rather than a handful of
registrations, keep `init.clj` minimal and move the behavior into a local spool — that promotion is the
[second half of this page](#promoting-config-to-a-local-spool).

## Trying config changes in a disposable world

Config runs with weaver authority, and startup failures fail loudly — so try changes somewhere disposable
before they reach the workspace you rely on. A throwaway world costs one `mktemp`:

```sh
ws="$(mktemp -d)"
mill init --workspace "${ws:?}"
# copy or write your candidate init.clj / spools.edn into "$ws", then:
mill weaver start --workspace "${ws:?}"
mill weaver stop --workspace "${ws:?}"
```

The `${ws:?}` guard makes an empty variable fail the command instead of silently resolving to your real
workspace. If the candidate config is wrong, startup tells you with file context, and you throw the directory
away.

Once a customisation is worth keeping, it is worth automated coverage: [`skein.test.alpha`](./testing.md)
weaver worlds take `:init`, `:spools-edn`, and `:files` fixtures, so a test exercises exactly the artifacts
this page has you writing.

## Reloading a live weaver

Use reload during development instead of restarting the weaver:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])
(runtime/reload! (current/runtime))
```

Reload clears weaver-lifetime spool sync state, module-use state, named queries, weave patterns, views, custom
ops, lifecycle hooks, event handlers, queued events, and recent event failures, then reloads `init.clj`
followed by `init.local.clj`. Missing files are skipped; present failures fail loudly.

Two blind spots to know about. `reload!` re-runs the startup files but does not unload namespaces or vars it
already loaded, and a bare `(require ns :reload)` is classloader-blind to per-spool synced roots — so neither
picks up updated code from an already-synced opt-in spool. `reload-spool!` covers that gap. It takes a
root-lib symbol from the family's effective `:roots` map and reloads that root's namespaces in dependency order:

```clojure
(runtime/reload-spool! (current/runtime) 'skein.spools/kanban)
```

The two verbs are complementary halves of a hot bump. `reload-spool!` reloads spool *code*; `reload!` re-runs
the startup files so `install!` re-registers ops, queries, and handlers. So the code-bump sequence
is `reload-spool! root-lib` to make the code live, then a targeted re-`use!` of the spool's activation to
re-register — or a full `reload!` when the bump changes registrations across the config.

Some changes cannot load into a running weaver at all: removing an already-loaded root, repointing one at
different source, or bumping a loaded Maven coordinate's version. `sync!` refuses those in-JVM and records a
pending generation that takes effect at the next weaver restart. That restart is not free: replacing a weaver
ends every agent run it is supervising, and restarting the canonical weaver requires explicit user sign-off.
Treat a pending generation as a deliberate step, not a reflex; see the [reference](../reference.md) on weaver
generations for the classification and the cutover semantics.

## REPL hygiene in a shared weaver

Much of this iteration happens from `mill weaver repl`. The REPL (and `mill weaver repl --stdin`) evaluates
inside the live weaver JVM, usually in the shared `skein.repl` namespace. Exploratory requires and scratch defs mutate that namespace for every other session attached to the
same weaver, so use names that are easy to identify and clean up: prefer `:as` aliases over `:refer`, prefix
aliases and scratch vars with an owner or session prefix (`ct-`, `agent-abc-`, a task slug), and avoid
unprefixed scratch vars like `result`, `x`, or `data`. Clean aliases with `ns-unalias` and scratch vars with
`ns-unmap` when done:

```clojure
(require '[clojure.pprint :as ct-pprint])
(def ct-config-publics (keys (ns-publics 'config)))

(ct-pprint/pprint ct-config-publics)

(ns-unalias *ns* 'ct-pprint)
(ns-unmap *ns* 'ct-config-publics)
```

For stronger isolation, create an agent-local namespace and call Skein helpers through an alias:

```clojure
(create-ns 'agent.ct)
(in-ns 'agent.ct)
(clojure.core/refer 'clojure.core)
(require '[skein.repl :as repl]
         '[clojure.pprint :as ct-pprint])

(ct-pprint/pprint (repl/ready))

(remove-ns 'agent.ct)
```

## Promoting config to a local spool

When `init.clj` outgrows a handful of registrations, move the behavior into a local spool and leave activation
behind. Skein treats runtime extensions as trusted Clojure code, and the
[reference spools](../../spools/README.md) — including the workflow engine and the external,
git-distributed devflow lifecycle — double as worked examples of spool design. All of them load the
same opt-in way yours will. A common layout:

```text
workspace/
  config.json
  init.clj
  spools.edn
  spools/
    my-workflow/
      deps.edn
      src/my/workflow.clj
```

Approve the local spool root in `spools.edn`:

```clojure
{:spools {my/workflow {:local/root "spools/my-workflow"}}}
```

Relative `:local/root` values resolve against the selected workspace. Absolute paths are accepted as explicit
user-approved paths, and `~` expands to your home directory. Create a minimal `deps.edn` in the spool root (if
`:paths` is omitted, Skein's namespace loading defaults to `["src"]`):

```clojure
{:paths ["src"]}
```

Then implement the spool. The query from earlier now moves into an `install!` function, so reload and startup
install everything from one place:

```clojure
(ns my.workflow
  (:require [skein.api.current.alpha :as current]
            [skein.api.graph.alpha :as graph]))

(defn install! []
  (graph/register-query! (current/runtime) 'mine [:= [:attr :owner] "ct"])
  {:my.workflow/installed true})
```

Activate it from `init.clj`, after the `sync!` and batteries activation already there:

```clojure
(runtime/use! runtime :my/workflow
  {:ns 'my.workflow
   :spools #{'my/workflow}
   :call 'my.workflow/install!})
```

Each piece has one job. `spools.edn` is approval: it says which local roots the weaver may load.
`runtime/sync!` makes approved roots available to the weaver. `runtime/use!` activates one module and records
whether it loaded, skipped, or failed; its `:call` must name a fully qualified zero-argument function. A
direct `require` from `mill weaver repl` evaluates in the weaver JVM and is useful for trusted
experimentation, but for repeatable module activation and reload introspection, go through `runtime/use!` or
`runtime/reload!` from startup config or the live REPL.

Extension code runs with weaver authority, so only load trusted code. And there is no per-module isolation or
unload guarantee: restart the weaver when you need a clean runtime.

## Your own CLI command

Every `strand` command is a registered op, and ops register the same way queries do, so a local
spool can add commands to the CLI without recompiling anything. `strand help` lists registered ops;
`strand help <op>` explains one. Register your own from trusted Clojure with
`skein.api.weaver.alpha/register-op!` — the CLI forwards everything after the op name to your
handler as string argv:

```clojure
(ns my.workflow
  (:require [skein.api.current.alpha :as current]
            [skein.api.weaver.alpha :as weaver]))

(defn echo-op [{:op/keys [name argv]}]
  {:operation name :argv argv})

(defn install! []
  (weaver/register-op! (current/runtime) 'echo "Echo raw argv" 'my.workflow/echo-op)
  {:my.workflow/installed true})
```

```sh
strand echo --flag value
```

Op handlers return data; the CLI prints it as JSON. Like every registration on this page, ops are
weaver-lifetime state — keep them in startup-loaded code, and reload with the verbs above while
iterating. The shipped [kanban board spool](../../spools/kanban.md) is a complete example of this
pattern: a whole board surface built from ops, queries, and attributes, worth reading once your own
command grows past a helper.

Name an op by what it exposes. When your command fronts another spool's surface, keep that spool's
verbs, nouns, and attribute keys — the op is your entry point to the primitive, not a new language
over it ([the vocabulary
rule](./writing-shared-spools.md#the-rules-for-shared-spools)). Kanban earns its own vocabulary
(cards, lanes, claim) because a board is a concept the engine has no word for; a command that
starts, advances, or lists an existing primitive speaks that primitive's terms.

## Terse daily driving

Explicit-runtime code threads a `runtime` argument through every call, which is the right discipline for
durable config and a chore at the REPL. In your **own** workspace config you may trade that explicitness for
terseness: capture the runtime once with `skein.userland.alpha`, then call both the blessed API and any
explicit-runtime spool through terse wrappers:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.userland.alpha :as u]
         '[acme.priority.alpha :as priority])

;; Bind the active in-process runtime once for terse daily calls.
(u/bind! (current/runtime))

;; Terse core calls — the runtime is resolved for you.
(u/strand! "Ship the release" {:owner "me"})
(u/ready)

;; An explicit-runtime spool still takes the runtime as its first
;; argument; hand it (u/runtime).
(priority/promote! (u/runtime) some-id)
```

Here `acme/priority` stands for any approved spool written to the explicit-runtime discipline. The ergonomics
live entirely on *your* side of the boundary: the spool never learns that `skein.userland.alpha` exists. That
asymmetry is the whole point: users may trade explicitness for terseness in their own config; shared code may
not, so `skein.userland.alpha` is userland-only, forever, and no spool you distribute may require it or call
`bind!`.

## When a spool leaves your workspace

Everything above assumes the code is yours alone, running in your weaver, free to resolve the ambient runtime
and stay informally structured. Even here, runtime-owned state is the easier default — it survives reloads
that a module-level atom loses — so the liberties are ambient resolution and loose structure, not unmanaged
state. The moment other people run your spool, those liberties become bugs: a shared
spool must work in any weaver runtime, including unpublished runtimes that coexist with others in a single
JVM, so it takes the runtime explicitly as the first argument of every public function, keeps its state
runtime-owned, registers behavior by symbol rather than closure, and never touches the ergonomics layer. Those
rules, the helper namespaces that support them, and the git publishing and pinning story live in
[writing shared spools](./writing-shared-spools.md) — the one step of the ladder this page does not cover.
