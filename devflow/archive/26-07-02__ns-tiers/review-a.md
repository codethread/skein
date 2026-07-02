# Review segment A — spools to root

## Findings

- **blocking** — `.skein/config.clj:581`, `.skein/config.clj:584`, `.skein/config.clj:587`: `devflow-conventions-op` still returns doc paths under `src/skein/spools/*.md`, but segment A removes `src/skein/spools/` entirely. These are relative doc references surfaced by `strand op devflow-conventions`; they now point at deleted files. Update them to `spools/workflow.md`, `spools/devflow.md`, and `spools/ephemeral.md` so every relative doc link/path resolves after the move.
- **should-fix** — `devflow/feat/weaver-guild/proposal.md:7`: this newly added working-tree document links to `../../../src/skein/spools/workflow.md`, which no longer exists after segment A. Because it is part of the current uncommitted diff and the review gate requires relative doc links to resolve, update it to `../../../spools/workflow.md` or keep the unrelated file out of this change.

## Checks performed

- Confirmed `src/skein/spools/` is absent from the filesystem; shipped spool sources now live under `spools/src/skein/spools/`.
- Confirmed moved Clojure spool files are `R100` renames and their `ns` forms were not edited.
- Checked `deps.edn`: root `:paths` includes `"spools/src"`; aliases inherit that runtime classpath, and `:test` still adds `"spools/shuttle/src"` for consent-tier shuttle/treadle tests.
- Spot-checked changed Markdown links in `AGENTS.md`, `README.md`, `docs/skein.md`, `spools/README.md`, `spools/ephemeral.md`, `spools/workflow.md`, `spools/shuttle/treadle.md`, and `.skein/AGENTS.md`; those changed links resolve. The unresolved items are listed above.

## Validation

- `PATH=/opt/homebrew/opt/openjdk/bin:$PATH clojure -M:test` — PASS. Ran 284 tests, 1642 assertions, 0 failures, 0 errors. The run printed background shuttle thread exceptions, but the test runner completed green.
- `(cd cli && go test ./...)` — PASS. Packages: `skein-strand-cli`, `cmd/mill`, `cmd/strand` (no tests), `internal/client`, `internal/command`, `internal/config`.
- `git status --short` after validation showed only source/doc working-tree changes; no generated SQLite or runtime metadata artifacts were created.

## Verdict

request-changes

## Fixes applied

- **blocking** — Updated `.skein/config.clj` `devflow-conventions-op` spool doc paths from deleted `src/skein/spools/*.md` locations to `spools/workflow.md`, `spools/devflow.md`, and `spools/ephemeral.md`.
- **should-fix** — Re-checked `devflow/feat/weaver-guild/proposal.md`; its workflow spool link already points at `../../../spools/workflow.md`, so no further edit was required.
- **validation** — Re-ran full segment gate validation: `PATH=/opt/homebrew/opt/openjdk/bin:$PATH clojure -M:test` passed (284 tests, 1642 assertions, 0 failures, 0 errors; background shuttle thread exceptions printed as in the original review); `(cd cli && go test ./...)` passed; `git status --short` showed only source/doc working-tree changes and no generated SQLite/runtime artifacts.
