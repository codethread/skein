# Task 1: Registry metadata, loud collision, replace-op!, envelope context

**Document ID:** `TASK-Ooc-001`

## TASK-Ooc-001.P1 Scope

Type: AFK

Accrete the weaver op registry in `src/skein/api/weaver/alpha.clj` per SPEC-004-D003.C6/C6a: op metadata, loud collision, explicit `replace-op!`, registry-recorded provenance, and an envelope-bearing handler context. Purely additive — the old CLI, socket dispatch, and existing `register-op!` call sites keep working.

## TASK-Ooc-001.P2 Must implement exactly

- **TASK-Ooc-001.MI1:** `register-op!` accepts an op metadata map (new arity or options map, your choice, but keep the existing `(register-op! runtime name doc fn-sym)` arity working for current call sites): keys `:doc`, `:arg-spec` (opaque data at this layer; validated by task 2's parser), `:stream?` (boolean, default false), `:deadline-class` (`:standard` | `:unbounded`, default `:standard`; `:stream? true` defaults to `:unbounded`), `:hook-class` (`:read` | `:mutating`, default `:mutating` — preserves today's behavior where all `op` invocations are hook-gated). Unknown metadata keys fail loudly.
- **TASK-Ooc-001.MI2:** Registration collision: `register-op!` on an already-registered name throws `ex-info` naming both the existing entry's provenance and the attempted registrant. The current silent-replace docstring behavior is removed. New `replace-op!` (same signature) requires the name to already exist (loud error if absent) and replaces it. `unregister-op!` is not required.
- **TASK-Ooc-001.MI3:** Provenance: the registry records, on each entry, the registering namespace (derive from the handler `fn-sym` namespace) as `:provenance`. It is registry-recorded, never caller-supplied; a caller-supplied `:provenance` key fails loudly.
- **TASK-Ooc-001.MI4:** `op!` accretes an optional envelope argument: `(op! runtime op-name argv)` keeps working (empty envelope); a new arity accepts an envelope map `{:payloads {string string}, :cwd string, :worktree-root string?, :git-common-dir string?, :timeout number?}` and threads it into handler context as `:op/payloads`, `:op/cwd`, `:op/worktree-root`, `:op/git-common-dir`, `:op/timeout` alongside the existing `:op/name`/`:op/argv`/`:op/runtime`/`:op/runtime-metadata`. Wire-level `client{pid,version}` is deliberately **not** part of this envelope or handler context — it stays socket-level diagnostic data (SPEC-004-D003.C1).
- **TASK-Ooc-001.MI5:** `ops` returns entries including the metadata and provenance. Update the `register-op!` docstring to the new contract. Verify `reload!` still works: registry clear + init re-run must not trip the collision check (add a test).
- **TASK-Ooc-001.MI6:** Tests in the existing Clojure test tree covering: metadata defaults and validation, collision throw content, `replace-op!` on missing/present names, provenance recording and caller-supplied rejection, envelope threading into handler context, reload-safety.

## TASK-Ooc-001.P3 Done when

- **TASK-Ooc-001.DW1:** `PATH="/opt/homebrew/opt/openjdk/bin:$PATH" clojure -M:test` green.
- **TASK-Ooc-001.DW2:** Existing callers (`register-built-in-ops!`, spool `register-op!` call sites under `spools/`, `.skein` config) load without change or are mechanically updated in this task if the chosen signature requires it — grep `register-op!` across the repo and prove no caller breaks.
- **TASK-Ooc-001.DW3:** `git status --short` shows no runtime artifacts.

## TASK-Ooc-001.P4 Out of scope

- **TASK-Ooc-001.OS1:** Parser (task 2), help projection (task 3), socket/protocol changes (task 6), any Go code.

## TASK-Ooc-001.P5 References

- **TASK-Ooc-001.REF1:** `devflow/feat/op-only-cli/specs/daemon-runtime.delta.md` SPEC-004-D003.C6/C6a; plan `devflow/feat/op-only-cli/op-only-cli.plan.md` A2, TC1/TC2; RFC `devflow/rfcs/2026-07-04-op-only-cli.md` D6/D7.
- **TASK-Ooc-001.REF2:** Current registry code: `src/skein/api/weaver/alpha.clj` (`register-op!`, `ops`, `resolve-op`, `op!`, `op-help-handler`, `register-built-in-ops!`). Note the repo rule: never destructure `:fn` into a local named `fn`.
