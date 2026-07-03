# Skein Guild Spool

## Overview

`skein.spools.guild` is a small reference spool for publishing a weaver's
trusted operation API to sibling weavers. It does not add a new protocol,
server operation, package manager, or permission system. Guild ops are ordinary
weaver `op` registry entries with a documented naming/versioning convention and
a built-in `guild.describe` operation for discovery.

Use it when a repo wants other local weavers to call stable, intentional entry
points such as `gate.status.v1` or `release.request.v1` instead of reaching into
repo-private REPL helpers. The agreement surface is userland: a repo opts in by
registering ops from trusted config, usually its checked-in `.skein/init.clj`.
For a peering repo, that checked-in `init.clj` is effectively a published API
file. Treat its guild declarations like public contract code.

## Loading

Because `skein.spools.guild` ships on the weaver classpath, no `spools.edn`
approval is needed:

```clojure
(require '[skein.spools.guild :as guild])

(guild/install!)
```

`install!` registers the built-in `guild.describe` op and resets the spool's
runtime-local weaver-lifetime declaration state for reload-friendly startup. The
declaration state is isolated from other runtimes in the same JVM. It may also take
a non-blank fallback guild name for contexts without runtime metadata:

```clojure
(guild/install! "backend")
```

At invocation time, `guild.describe` prefers the runtime metadata name published
by the running weaver.

## Operation declaration surface

### `defop!`

```clojure
(guild/defop! 'gate.status.v1
  {:doc "Return whether the named gate is satisfied."
   :spec ::gate-status-input}
  'my.repo.guild/gate-status)
```

`defop!` registers a public guild op in the existing weaver op registry.

| Argument | Meaning |
|---|---|
| `name` | Simple unqualified symbol or keyword. By convention use a dotted, version-suffixed handle such as `gate.status.v1`. |
| `opts` | Map supporting `:doc` and optional `:spec`. Unknown keys fail loudly. |
| `handler-fn-sym` | Fully qualified symbol resolving to the handler function in the weaver JVM. |

Guild op invocation accepts zero arguments or one JSON input argument. The spool
parses that JSON to `:guild/input`, validates it against `:spec` when supplied,
and then calls the resolved handler with the ordinary op context plus
`:guild/input`.

### `deprecate!`

```clojure
(guild/deprecate! 'gate.status.v1
  {:replacement "gate.status.v2"
   :since "2026-07-02"})
```

`deprecate!` replaces a registered guild op with a stub that always fails
loudly. A deprecated stub may explain, redirect, or refuse — it must never
pretend to succeed. The thrown data includes `{:code :op/deprecated}` plus the
op name and replacement guidance.

### `install!`

```clojure
(guild/install!)
(guild/install! "frontend")
```

`install!` registers `guild.describe`, clears previous guild declarations in the
current weaver JVM, and records an optional fallback name. Re-run it during
trusted config reload before re-declaring ops.

### `guild.describe`

Peers call `guild.describe` through the ordinary `op` socket operation. It
returns JSON-safe metadata:

```clojure
{:guild "backend"
 :active [{:name "gate.status.v1" :doc "Return whether the named gate is satisfied." :spec ":backend/gate-status-input"}]
 :deprecated [{:name "gate.old.v1" :replacement "gate.status.v1" :since "2026-07-02"}]}
```

## Naming and evolution conventions

- Registry names are simple unqualified handles; do not use namespace-qualified
  symbols or keywords.
- Use dotted names with explicit version suffixes: `domain.action.v1`,
  `gate.close.v1`, `gate.close.v2`.
- Evolve additively. Add a new version when inputs or semantics change in a way
  an existing caller cannot safely assume.
- Keep old versions registered while callers migrate, or deprecate them with a
  loud structured stub via `deprecate!`.
- Never install a noop compatibility stub for a coordination operation. A false
  success can corrupt manager state; a loud failure leaves the workflow visibly
  stalled and fixable.

## Worked two-repo example

Assume two repos, `frontend` and `backend`, each with a checked-in portable
weaver name in `.skein/config.json`:

```json
{
  "configFormat": "alpha",
  "name": "backend"
}
```

A machine with two clones can disambiguate locally with `.skein/config.local.json`
when needed; the local overlay is not committed.

In the backend repo's checked-in `.skein/init.clj`, publish a guild API:

```clojure
(ns user
  (:require [clojure.spec.alpha :as s]
            [skein.spools.guild :as guild]))

(s/def ::gate-name string?)
(s/def ::gate-status-input (s/keys :req-un [::gate-name]))

(defn gate-status [{:guild/keys [input]}]
  ;; Repo-private implementation. Keep the public contract at this fn boundary.
  {:gate (:gate-name input)
   :satisfied false})

(guild/install!)
(guild/defop! 'gate.status.v1
  {:doc "Return whether a backend gate is satisfied."
   :spec ::gate-status-input}
  'user/gate-status)
```

From the frontend weaver (or a manager weaver), discover the backend by its
portable name with the blessed alpha peering helpers and call the guild op over
the existing JSON socket protocol:

```clojure
(require '[clojure.data.json :as json]
         '[skein.api.peers.alpha :as peers])

(def backend (peers/peer "backend"))

(peers/call! backend "op"
  {"name" "guild.describe"
   "args" []})
;; => {"guild" "backend", "active" [...], "deprecated" [...]}

(peers/call! backend "op"
  {"name" "gate.status.v1"
   "args" [(json/write-str {:gate-name "api-ready"})]})
;; => {"gate" "api-ready", "satisfied" false}
```

`skein.api.peers.alpha` is explicit-require userland API: `(peers/peers)`
enumerates running sibling weavers from mill metadata, `(peers/peer
name-or-workspace)` resolves exactly one running peer, and `(peers/call! peer op
args)` invokes one allowlisted public JSON socket operation. It does not
auto-start peers or add retries; unavailable peers fail loudly.

## See also

- [`spools/README.md`](./README.md) — shipped spools index and loading notes.
- [`skein.spools.workflow`](./workflow.md) — workflow gates are the durable wait points guild ops often inspect or complete.
- [`skein.spools.treadle`](./shuttle/treadle.md) — local-root gate adapter shape that guild-backed adapters can mirror.
- [Weaver Runtime spec](../devflow/specs/daemon-runtime.md) — local weaver peering contract (SPEC-004.P10c).
- [REPL API spec](../devflow/specs/repl-api.md) — blessed `skein.api.peers.alpha` helper listing.
