# Quality gates inventory

Baseline generated on 2026-07-05 from the `quality-gates` worktree before any fixes landed; per-tool sections are updated as fix waves drive gates to zero.

## Summary

| Tool | Invocation | Result | Count |
|---|---|---:|---:|
| cljfmt 0.13.1 | `clojure -M:format` | fails | 66 incorrectly formatted files |
| gofumpt v0.8.0 | `cd cli && go run mvdan.cc/gofumpt@v0.8.0 -l .` | fails | 6 files |
| clj-kondo 2025.06.05 | `clojure -M:lint/clj-kondo` | fails | 18 errors, 105 warnings |
| golangci-lint v2.1.6 | `make lint-go` | fails | 31 issues |
| splint 1.21.0 | `clojure -M:lint/splint` | fails | 646 warnings |
| reflection gate | `clojure -M:reflect-check` | blocking, clean | 0 reflection warnings |
| antq 2.11.1276 | `clojure -M:deps/antq` | report-only | 35 outdated entries |
| govulncheck v1.1.4 | `cd cli && go run golang.org/x/vuln/cmd/govulncheck@v1.1.4 ./...` | clean | 0 vulnerabilities |
| clj-watson v6.1.0 | `clojure -M:security/clj-watson` | report-only | NVD update hit HTTP 429 without API key; non-blocking |

## Per-tool notes

### Formatting

- `cljfmt`: 66 files would be changed. This includes the explicitly requested roots `src`, `spools/src`, `test`, `dev`, `scripts`, and only practical top-level repo config files under `.skein` (`init.clj`, `config.clj`, `reviewers.clj`), not nested `.skein/spools` material.
- `gofumpt`: 6 Go files in `cli/` would be changed: `cmd/mill/lifecycle_test.go`, `integration_test.go`, `internal/config/bootstrap.go`, `internal/config/config.go`, `internal/config/config_test.go`, and `internal/config/runtime.go`.

### clj-kondo

Totals: 18 errors, 105 warnings.

Top categories:

- 9 `project/ns-docstring` errors from the custom hook: namespace forms without immediate docstrings.
- 9 existing unresolved-symbol / namespace-name errors from test files and fixture naming.
- 55 `unused binding rt` warnings.
- 12 `unused binding db-file` warnings.
- 2 unresolved `clojure.string` namespace warnings.
- The custom hook layer is active and uses `:project/ns-docstring`, `:project/no-spool-module-atom`, and `:project/no-fn-keys-destructure` finding types.

### golangci-lint

Totals: 31 issues.

- `errcheck`: 30 unchecked return values, mostly deferred `Close`/`Flush`/`Remove` and output writes.
- `staticcheck`: 1 `ST1005` capitalized error string in `internal/config/bootstrap.go`.

### splint

Totals: 646 warnings.

Top categories:

- 499 prefer uniform `Class/member` interop syntax.
- 47 use `clojure.string` function instead of interop.
- 33 broad `Throwable` catches.
- 21 adjacent identical branches.
- 10 use `zero?` instead of recreating it.
- 7 use `not-any?` instead of recreating it.
- 5 use `clojure.string/join` instead of recreating it.

### Reflection

Totals: 0 reflection warnings. Every warning is fixed with a type hint at the definition site, so the gate is a blocking CI check that fails on any reflected interop. The gate compiles all of `src` (every `skein.*` namespace) plus every spool source root — `spools/src`, `spools/shuttle/src`, `spools/agents/src`, `spools/chime/src`, and `spools/kanban/src` — so no shipped namespace escapes it. Repeated compile/reload warnings are preserved rather than deduplicated so the gate stays compiler-faithful.

The clusters hinted to reach zero:

- `skein.core.client` / `skein.repl`: `.close` on nREPL transports (`^java.io.Closeable`).
- `skein.core.db`: `.nextInt` on `^SecureRandom`.
- `skein.core.weaver.metadata`, `runtime`, and `socket`: file/socket/channel interop hinted at the file-returning helpers, queue, and socket-channel definition sites.
- `skein.api.weaver.alpha` and `skein.api.peers.alpha`: spool checkout `File`, `ProcessBuilder`/`Process`, and event-queue interop.
- `skein.test.alpha`: workspace-fixture `File` interop.
- `skein.spools.carder`/`util`/`agents`/`chime`/`shuttle`: date, thread, `File`, executor, and `ProcessHandle` interop.

### Dependency reports

- `antq`: 35 outdated entries, including GitHub Actions versions introduced by this workflow, `clj-kondo`, `cljfmt`, `splint`, and existing project dependencies (`clojure`, `nrepl`, `next.jdbc`, `sqlite-jdbc`, etc.).
- `govulncheck`: no vulnerabilities found for `cli/`.
- `clj-watson`: wired as a report-only job using the pinned git coordinate from upstream docs. It is intentionally non-blocking because the first run downloads NVD data and is sensitive to NVD API-key availability; this run reached the NVD service and failed with HTTP 429 rate limiting.

## Config decisions

- Tool versions are pinned at invocation points: deps aliases for Clojure tools, `go run module@version` for Go tools, and explicit GitHub Action versions.
- `.clj-kondo/` is globally ignored by the existing repository `.gitignore`; these config/hook files must be force-added to preserve the chosen layout without changing ignore policy.
- The clj-kondo hook implementation uses rewrite-clj nodes via `clj-kondo.hooks-api` and registers findings with custom project linter types:
  - `ns` forms must have an immediate docstring.
  - top-level `def`/`defonce` of `atom`/`volatile!` in shipped spool source paths is forbidden.
  - any analyzed form containing `{:keys [... :fn ...]}` is forbidden.
- Generated/vendored exclusions were not added beyond avoiding recursive `.skein/spools` formatting. Existing lint findings are recorded, not fixed.
