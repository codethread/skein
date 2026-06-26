# Rename weaver runtime namespaces

**Document ID:** `TASK-002`

## TASK-002.P1 Scope

Type: AFK

Move the Clojure runtime identity from `todo.*` / daemon wording to `skein.*` / weaver wording after the strand model compiles and passes focused tests.

## TASK-002.P2 Must implement exactly

- **TASK-002.MI1:** Move internal Clojure namespaces from `src/todo` to `src/skein` and daemon namespaces from `todo.daemon.*` to `skein.weaver.*`, including `skein.weaver.api`, runtime, socket, config, and metadata namespaces.
- **TASK-002.MI2:** Update nREPL/client eval forms and trusted API references from `todo.daemon.api` / `todo.daemon.runtime` to `skein.weaver.api` / `skein.weaver.runtime`.
- **TASK-002.MI3:** Rename default worlds and storage artifacts: default XDG segment `atom` to `skein`, default database `tasks.sqlite` to `skein.sqlite`, metadata/socket files `daemon.json` / `daemon.edn` / `daemon.sock` to `weaver.json` / `weaver.edn` / `weaver.sock`.
- **TASK-002.MI4:** Ensure clients discover only `weaver.*` metadata for the selected world and fail loudly on stale/missing/malformed metadata without falling back to old artifact names.
- **TASK-002.MI5:** Update Clojure tests for runtime startup/stop/status, metadata discovery, selected config-dir worlds, and socket identity to use Skein/weaver names.

## TASK-002.P3 Done when

- **TASK-002.DW1:** Clojure namespaces compile under `skein.*` without public `todo.*` compatibility namespaces.
- **TASK-002.DW2:** Daemon/weaver runtime tests use `skein` worlds and `weaver.*` runtime files.
- **TASK-002.DW3:** Clojure client calls route through `skein.weaver.api` and still exercise add/update/show/list/ready behavior from task 1.
- **TASK-002.DW4:** Relevant Clojure daemon/client tests pass.

## TASK-002.P4 Out of scope

- **TASK-002.OS1:** Do not rename blessed `atom.*.alpha` libraries or REPL helper names in this task; those are task 3.
- **TASK-002.OS2:** Do not update Go CLI command names in this task; that is task 4.
- **TASK-002.OS3:** Do not add old-world discovery or migration.

## TASK-002.P5 References

- **TASK-002.REF1:** [Daemon runtime delta](../specs/daemon-runtime.delta.md)
- **TASK-002.REF2:** [Plan](../skein-rename.plan.md) `SR-PLAN-001.PH2`
- **TASK-002.REF3:** Current anchors from scout: `src/todo/client.clj`, `src/todo/daemon/config.clj`, `src/todo/daemon/metadata.clj`, `src/todo/daemon/runtime.clj`, and `src/todo/daemon/socket.clj`.
