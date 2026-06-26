# Docs and Smoke Workflow

**Document ID:** `RLW-TASK-006`
**Status:** Pending
**Plan:** [../runtime-library-workspace.plan.md](../runtime-library-workspace.plan.md)

## RLW-TASK-006.P1 Scope

Type: AFK

Update user/agent documentation and smoke coverage for the config-dir library workspace workflow: `libs.edn`, local roots, daemon-side `sync!`, layered `use!`, fix-forward introspection, and no plugin/package CLI.

## RLW-TASK-006.P2 Required work

- **RLW-TASK-006.W1:** Update `README.md` and relevant agent/devflow docs to show config-dir as a user-owned workspace that may use Git/submodules/manual source acquisition outside Atom runtime.
- **RLW-TASK-006.W2:** Document the recommended setup: keep Atom source wherever `config.json :source` points, use `libs.edn` for additional local roots, and allow symlinks/absolute paths via canonical root normalization.
- **RLW-TASK-006.W3:** Replace plugin-loader examples with `atom.libs.alpha/sync!`, `use!`, `uses`, and layered user-code examples.
- **RLW-TASK-006.W4:** Document helper REPL boundary: direct connected-REPL `require` uses helper JVM classpath; daemon-side activation goes through helpers.
- **RLW-TASK-006.W5:** Update smoke tests to create a disposable config-dir with `libs.edn`, a local module root, an `init.clj` that syncs and uses layered modules, and connected REPL/stdin introspection after startup.
- **RLW-TASK-006.W6:** Ensure smoke verifies optional failure does not brick daemon startup where practical.

## RLW-TASK-006.P3 Done when

- **RLW-TASK-006.D1:** Docs describe the new library workspace path and no longer recommend `load-plugin!`.
- **RLW-TASK-006.D2:** Smoke covers successful local-root use and at least one resilient skip/fix-forward introspection path.
- **RLW-TASK-006.D3:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:smoke` passes, or failures are fixed before task completion.
