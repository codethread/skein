# Library authoring: testing against a Skein checkout

This guide is for authors of Skein spools and libraries: normal trusted Clojure libraries approved through `spools.edn`, synced into a weaver, and activated from `init.clj` or a connected REPL. It covers repo shape, putting a selected Skein checkout on your test classpath, the three testing tiers, and weaver-world integration tests with `skein.test.alpha`.

For how to structure a spool others will run, read [writing-shared-spools](./writing-shared-spools.md). For the runtime/spool model itself, read [skein](./skein.md).

## Repo shape

A spool library is an ordinary Clojure project:

```text
my-spool/
  deps.edn
  src/
    my/spool.clj
  test/
    my/spool_test.clj
```

There is no package registry, installer, or lockfile. You publish by pushing Git commits/tags; consumers select a version by checking out your repo (or vendoring it) and approving its local root in their `spools.edn`.

## deps.edn: Skein as a local-root test dependency

Skein is not on a package repository. Put a selected checkout on your test classpath with a tools.deps `:local/root` alias:

```clojure
{:paths ["src"]
 :aliases
 {:test {:extra-paths ["test"]
         :extra-deps {io.skein/skein {:local/root "/path/to/skein-src"}}
         :jvm-opts ["--enable-native-access=ALL-UNNAMED"]}}}
```

Run tests with your preferred runner, e.g.:

```sh
clojure -M:test -m my.test-runner
```

The dependency name is arbitrary; `:local/root` is what matters. Keep the checkout path out of `src` paths — Skein is a dev/test dependency for your library code, and a runtime host for your spool.

## Testing tiers

Three tiers, cheapest first. Do not start a weaver for code that does not need one.

### 1. Pure tests

Most spool logic should be plain functions tested with ordinary `clojure.test`. No Skein dependency is needed at all for these.

### 2. Skein-namespace tests in your test JVM

With the local-root alias, your test JVM can require Skein namespaces directly — useful for exercising pure helpers like query compilation or your own code that composes `skein.api.*.alpha` functions against an explicit runtime value.

### 3. Weaver-world integration tests with `skein.test.alpha`

For behavior that only exists inside a running weaver — approved-spool sync, `use!` activation, init.clj startup behavior, event handlers, ops — use `skein.test.alpha`. It starts a real, disposable, isolated weaver world in your test JVM and routes forms through the weaver's real nREPL transport:

```clojure
(ns my.spool-test
  (:require [clojure.test :refer [deftest is]]
            [skein.test.alpha :as t]))

(deftest strands-flow-through-a-disposable-weaver
  (t/with-weaver-world [ctx {}]
    (is (= "Sketch model"
           (:title (t/repl! ctx "
             (require '[skein.api.current.alpha :as current]
                      '[skein.api.weaver.alpha :as api])
             (api/add (current/runtime) {:title \"Sketch model\"})"))))))
```

`weaver-world-fixture` provides the same lifecycle for `use-fixtures`, binding `skein.test.alpha/*weaver-world*`:

```clojure
(use-fixtures :each (t/weaver-world-fixture {:storage :sqlite-memory}))

(deftest listing-starts-empty
  (is (= [] (t/repl! t/*weaver-world* "
    (require '[skein.api.weaver.alpha :as api] '[skein.api.current.alpha :as current])
    (api/list (current/runtime))"))))
```

The context map contains orchestration facts only: `:config-dir`, `:state-dir`, `:data-dir`, `:db-path` (file storage only), `:storage`, `:source` (the Skein checkout on your classpath), `:runtime`, `:metadata`, and `:timeout-ms`. There are deliberately no strand/query wrappers, assertion helpers, or CLI subprocess helpers — exercise the real API forms.

## The classpath boundary

Two evaluation contexts exist even though the test weaver runs in your test JVM process:

- **Direct `require` in test code** uses your test JVM classpath (your library
  plus the Skein checkout). This never proves the weaver can load your spool.
- **Weaver-routed forms via `repl!`** evaluate inside the weaver runtime.
  Spool code becomes visible there only through the real workflow: approve the
  root in `spools.edn`, `skein.api.runtime.alpha/sync!`, then `use!`.

A spool that passes tier-2 tests can still fail tier 3 — missing `deps.edn` paths in the spool root, load-order problems in `install!`, or reliance on your test JVM classpath. Tier 3 exists to catch exactly that.

## Testing the real spool workflow

Write the spool fixture and approval into the generated world, sync it from `init.clj` (or from `repl!`), then activate and assert:

```clojure
(deftest spool-syncs-and-activates
  (t/with-weaver-world
    [ctx {:spools-edn {:spools {'demo/lib {:local/root "spools/demo"}}}
          :files {"spools/demo/deps.edn" "{:paths [\"src\"]}\n"
                  "spools/demo/src/demo/lib.clj"
                  "(ns demo.lib)\n(defn install! [] :ok)\n"}
          :init "(require '[skein.api.current.alpha :as current]
                          '[skein.api.runtime.alpha :as runtime-alpha])
                 (runtime-alpha/sync! (current/runtime))"}]
    (is (= :loaded
           (get-in (t/repl! ctx "
             (require '[skein.api.current.alpha :as current]
                      '[skein.api.runtime.alpha :as runtime-alpha])
             (runtime-alpha/use! (current/runtime) :demo/lib
                                 {:ns 'demo.lib :spools #{'demo/lib}})")
                   [:status])))))
```

To test your actual library instead of an inline fixture, point the approved `:local/root` at your library checkout (an absolute path works in `:spools-edn` data). When the checkout comes from the test classpath rather than a fixed local path, `skein.test.alpha/spool-checkout-root` resolves the root from one of the spool's source resources and fails loudly if that resource is absent. Its one-argument form uses `clojure.java.io/resource`; tests for the resolver can pass a resource-loader function as the second argument.

Two constraints from tools.deps to know about:

- `sync!` mutates JVM-global classpath state (`add-libs`). Synced roots and
  lib coordinates persist for the JVM lifetime. Use unique lib names per test
  world (e.g. suffix a UUID) if multiple tests sync fixture spools in one JVM.
- Do not delete a synced root while the JVM may sync again: pass an explicit
  `:root` you control (the helper then skips deletion by default), or accept
  the default temp-root cleanup only for worlds that never sync.

## Storage selection

`:storage` is explicit:

- `:sqlite-file` (default) — the canonical user path: a real
  `data/skein.sqlite` in the generated workspace. Use this when the test
  should match normal weaver-world layout, metadata, and persistence.
- `:sqlite-memory` — real Xerial SQLite held in memory for the weaver
  lifetime. Nothing is written under `data/`; stopping the world destroys the
  database. A single held connection serializes writes, which is fine at test
  scale but is not production-like pooled storage.

Both run the same schema and SQL code. Metadata/status report storage explicitly: file worlds have a `database_path`, memory worlds report `database_kind "sqlite-memory"` with a null path.

## Temp paths and Unix sockets

Each weaver world serves a Unix domain socket, and socket paths have a small platform limit (about 104 bytes on macOS). The helper generates its worlds under a short `/tmp` root for this reason. If you pass an explicit `:root`, keep it short — deeply nested `target/...` build paths can make socket creation fail.

## CI

Check out both repos and pin Skein to a commit or tag:

```yaml
steps:
  - uses: actions/checkout@v4
  - uses: actions/checkout@v4
    with:
      repository: your-org/skein-src
      ref: v0.4.0        # pin a tag or SHA
      path: skein-src
  - run: clojure -M:test -m my.test-runner
```

Have the `:local/root` in `deps.edn` reference the checkout path used in CI (a relative `:local/root "skein-src"` next to your repo keeps local and CI layouts identical). Treat Skein version bumps like any dependency bump: update the pinned ref, run the suite.

## What the helper will not do

`skein.test.alpha` orchestrates worlds and weaver-routed eval, nothing else:

- No strand/query/assertion wrappers — call real `skein.api.*.alpha` forms.
- No spool activation wrappers — use `sync!`/`use!` like real config does.
- No Go CLI subprocess helpers or binary discovery — CLI behavior is covered
  by Skein's own smoke workflow, not library tests.
- Never touches your default `~/.config/skein` (or any user-owned) workspace;
  worlds are generated and isolated by default.
