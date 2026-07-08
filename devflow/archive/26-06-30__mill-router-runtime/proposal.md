# Mill Router Runtime Proposal

**Document ID:** `PROP-MillRouterRuntime-001` **Related RFCs:** None **Root specs:** [CLI Surface](../../specs/cli.md), [Weaver Runtime](../../specs/daemon-runtime.md), [REPL API](../../specs/repl-api.md) **Status:** Shipped **Last Updated:** 2026-06-30

## PROP-MillRouterRuntime-001.P1 Problem

Skein's current direct `strand -> per-world weaver socket` model makes users remember and manage one foreground weaver for every repository. That becomes noisy as soon as a session touches multiple projects. The current `strand init` also mixes repo config bootstrap with weaver-backed database initialization, creating startup ordering friction.

## PROP-MillRouterRuntime-001.P2 Goals

- **PROP-MillRouterRuntime-001.G1:** Introduce a single user-started Go `mill` process that owns routing and weaver child lifecycle for local Skein use.
- **PROP-MillRouterRuntime-001.G2:** Keep users thinking in terms of `strand -> weaver`; `mill` is an implementation router/supervisor, not a semantic runtime.
- **PROP-MillRouterRuntime-001.G3:** Make `strand init` repo bootstrap only: it creates or completes repo-local `.skein` config files and never requires a running weaver.
- **PROP-MillRouterRuntime-001.G4:** Move runtime state, weaver sockets, and SQLite data out of the repository into XDG state paths managed by `mill`.
- **PROP-MillRouterRuntime-001.G5:** Keep one weaver per selected repo/config world and keep the weaver as the owner of strand storage, runtime registries, trusted config, and REPL semantics.

## PROP-MillRouterRuntime-001.P3 Non-goals

- **PROP-MillRouterRuntime-001.NG1:** No implicit global personal world fallback for no-flag `strand` commands in this feature.
- **PROP-MillRouterRuntime-001.NG2:** No semantic payload handling in `mill`; it does not inspect strand mutation/query bodies beyond routing metadata.
- **PROP-MillRouterRuntime-001.NG3:** No automatic ordinary-command weaver autostart. If a selected repo has no running weaver, ordinary strand commands fail with remediation to run `strand weaver start`.
- **PROP-MillRouterRuntime-001.NG4:** No cross-repo query, aggregate dashboard, or active-weaver listing requirement for MVP.
- **PROP-MillRouterRuntime-001.NG5:** No backwards compatibility for existing repo-local `.skein/state` or `.skein/data` runtime artifacts under TEN-000.

## PROP-MillRouterRuntime-001.P4 Proposed scope

- **PROP-MillRouterRuntime-001.S1:** Add a Go `mill` command installed by `make install`; `mill start` creates a known XDG state entrypoint and listens for local `strand` requests.
- **PROP-MillRouterRuntime-001.S2:** Change `strand` so all public commands contact `mill`; if `mill` is unavailable, commands fail loudly with remediation to start it.
- **PROP-MillRouterRuntime-001.S3:** Require implicit repo worlds to be Git worktrees. `strand init` outside Git fails; inside Git it initializes the Git root's `.skein` config files.
- **PROP-MillRouterRuntime-001.S4:** Have `strand weaver start` ask `mill` to start the selected repo's weaver as a child process, using XDG state/data paths derived from the canonical repo/config identity.
- **PROP-MillRouterRuntime-001.S5:** Make weaver startup initialize or validate an empty SQLite store itself. CLI DB initialization is removed; REPL `init!` remains as an explicit trusted helper if needed.
- **PROP-MillRouterRuntime-001.S6:** Update connected REPL launch so Go asks `mill` to resolve/ensure the selected weaver and then launches the existing Clojure helper with enough runtime metadata to attach directly.

## PROP-MillRouterRuntime-001.P5 Open questions

- **PROP-MillRouterRuntime-001.Q1:** Exact XDG directory names and hash format should be fixed in the spec delta before implementation.
- **PROP-MillRouterRuntime-001.Q2:** Whether `mill start` should run foreground only or support background daemonization is outside MVP unless implementation needs a minimal helper for tests.
