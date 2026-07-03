# Spike: Library Author Classpath and Dependency Setup

**Spike ID:** `SPIKE-2026-06-26-004`
**Status:** Open
**Date:** 2026-06-26
**Related RFC:** [`RFC-005 Library Author Testing Support`](../rfcs/2026-06-26-library-author-testing.md)

## Question

What should external Atom library authors put in `deps.edn` so their tests can require Atom namespaces, use `atom.test.alpha`, and test against a specific Atom checkout without misunderstanding daemon vs test JVM classpaths?

## Context

Library authors are expected to write normal Clojure libraries. During development they may depend on Atom via a local checkout, commonly using tools.deps `:local/root`.

There are at least two classpaths to explain:

- the author's test JVM classpath
- the daemon JVM/library classpath mutated by `atom.libs.alpha/sync!`

Direct `require` in the test JVM is not the same as daemon-side activation through `libs.edn`, `sync!`, and `use!`.

## Scope

Produce clear dependency/classpath guidance and validate it with a tiny external-style fixture library.

Evaluate:

- `deps.edn` aliases for pure tests
- `deps.edn` aliases that add Atom via `:local/root`
- whether `atom.test.alpha` should live under `src` or another path
- whether external authors need Go or only Clojure for daemon-world tests
- how CI should clone/pin Atom source
- how docs should explain helper REPL vs daemon JVM boundaries

## Non-goals

- No package registry design.
- No published Maven artifact design.
- No dependency solver or lockfile.
- No dynamic source fetching.
- No CLI helper API.

## Suggested experiment

1. Create or sketch an external-style library repo layout.
2. Add Atom as a local/root dev/test dependency.
3. Run pure tests requiring only library code.
4. Run tests requiring Atom namespaces.
5. Run a daemon-world test that loads the library through `libs.edn`.
6. Record exact `deps.edn` and command examples for docs.

## Acceptance evidence

- Minimal external library `deps.edn` example.
- Explanation of classpath boundaries in author-friendly language.
- CI/pinning recommendation for testing against a chosen Atom checkout.
- Recommendation for where `atom.test.alpha` should live.

## Output

Write findings back into this file under `## Findings`, with docs-ready examples.

## Findings

### Recommendation

External library authors should treat their project as a normal tools.deps library and add the selected Atom checkout only on dev/test aliases. The library's runtime `deps.edn` should not depend on the user's daemon config-dir; daemon-world tests should separately approve the library through a disposable world's `libs.edn` and call `atom.libs.alpha/sync!`/`use!` inside that daemon world.

`atom.test.alpha` should live under Atom `src` (`src/atom/test/alpha.clj`) so authors get it by putting the chosen Atom checkout on the author-side test JVM classpath. That matches RFC-005's classification of `atom.test.alpha` as an author-side test helper, not a daemon activation API. Keeping it under `src` also avoids a separate published test artifact while Atom is source-checkout/local-root based.

### Minimal external library layout

```text
my-atom-lib/
|-- deps.edn
|-- src/my_atom_lib/core.clj
`-- test/my_atom_lib/core_test.clj
```

```clojure
;; deps.edn
{:paths ["src"]
 :aliases
 {:test
  {:extra-paths ["test"]
   :extra-deps {atom/atom {:local/root "../atom"}}
   :jvm-opts ["--enable-native-access=ALL-UNNAMED"]
   :main-opts ["-m" "my-atom-lib.test-runner"]}}}
```

Notes:

- `atom/atom` is only a local coordinate chosen by the author for tools.deps; there is no published Maven coordinate implied.
- `:local/root "../atom"` should point at the exact Atom source checkout under test. CI should clone or checkout Atom at the desired commit and pass that path into `deps.edn` generation or use an alias checked in with the CI workspace layout.
- The SQLite JDBC native access option is needed for Atom-backed tests on current JDKs, matching Atom's own test aliases.

### Test tiers and classpath boundaries

1. **Pure library tests** require only the author's namespaces and should run with ordinary `clojure.test`.
2. **Author-side Atom namespace tests** run in the test JVM and can directly require namespaces that are on the test JVM classpath through `atom/atom {:local/root "../atom"}`, e.g. `atom.libs.alpha`, `atom.graph.alpha`, and future `atom.test.alpha`.
3. **Daemon-world integration tests** must use a disposable config-dir and exercise the real runtime-library path: write `config.json`/`libs.edn`/`init.clj` as needed, start a daemon world, then run daemon-routed forms that call `(libs/sync!)` and `(libs/use! ...)`.

Important boundary for docs: direct `require` in the author's test JVM proves that the test JVM can see Atom and the library. It does **not** prove that the daemon JVM has synced the library. Conversely, `libs/sync!` mutates the daemon runtime's library classloader; it does not add the library to an already-running helper/test JVM classpath.

### Docs-ready daemon-world example

A small integration test can approve the author library by absolute local root from the disposable daemon world:

```clojure
(ns my-atom-lib.integration-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [atom.libs.alpha :as libs]
            [todo.daemon.config :as daemon-config]
            [todo.daemon.runtime :as runtime]))

(def library-root (.getCanonicalPath (io/file ".")))

(defn temp-config-dir []
  (.toFile
   (java.nio.file.Files/createTempDirectory
    (.toPath (io/file "/tmp"))
    "atom-world"
    (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest loads-library-through-real-libs-workflow
  (let [config-dir (temp-config-dir)
        db-file (io/file config-dir "data" "tasks.sqlite")]
    (spit (io/file config-dir "libs.edn")
          (pr-str {:libs {'my/lib {:local/root library-root}}}))
    (let [rt (runtime/start! (.getCanonicalPath db-file)
                             {:world (daemon-config/world
                                      (.getCanonicalPath config-dir))})]
      (try
        (is (= :loaded (get-in (libs/sync!) [:libs 'my/lib :status])))
        (is (= :loaded
               (:status (libs/use! :my/lib
                                    {:ns 'my-atom-lib.core
                                     :libs ['my/lib]}))))
        (finally
          (runtime/stop! rt))))))
```

When `atom.test.alpha` exists, docs should replace this boilerplate with the helper, but keep the same conceptual steps visible: disposable config-dir, explicit approved local root, daemon start, `sync!`, `use!`, stop/cleanup.

### Validation performed

Created a temporary external-style fixture under `/tmp` with:

- `deps.edn` using `:extra-deps {atom/atom {:local/root "/Users/ct/dev/projects/atom"}}`
- a pure library namespace and test
- a test requiring `atom.libs.alpha` from the author-side test JVM
- an in-process daemon-world test writing disposable `libs.edn`, starting `todo.daemon.runtime/start!`, calling `libs/sync!` and `libs/use!`, and resolving the library function

Command:

```sh
(cd /tmp/af.KJbV && PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test)
```

Result: `Ran 3 tests containing 5 assertions. 0 failures, 0 errors.`

One experiment with Java's default long temporary directory prefix failed before library sync with `java.net.SocketException: Unix domain path too long`. Docs and `atom.test.alpha` should prefer short temporary config-dir paths on macOS/Unix socket platforms, or make socket-path diagnostics explicit. This is not a classpath problem, but authors are likely to hit it if helper-generated worlds use long names.

### CI/pinning guidance

CI should pin Atom by source checkout, not by an implicit user install:

```sh
git clone https://github.com/<owner>/atom.git .ci/atom
git -C .ci/atom checkout <commit-or-tag>
# then run the author library tests with deps.edn pointing at .ci/atom
PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test
```

For GitHub Actions or similar, use a separate checkout step/path for Atom and either keep a stable relative path in `deps.edn` (for example `.ci/atom`) or generate a CI-only alias with the checked-out absolute path. Avoid using `~/.config/atom`, the user's default daemon, or a globally installed `todo` binary as the source of truth for library tests.
