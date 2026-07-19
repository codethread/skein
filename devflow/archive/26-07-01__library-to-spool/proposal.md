# Library to Spool Proposal

**Document ID:** `LTS-PROP-001` **Last Updated:** 2026-07-01 **Related RFCs:** None (direction accepted from in-session naming review; no blocking alternatives remain) **Related root specs:** [REPL API](../../specs/repl-api.md), [Weaver Runtime](../../specs/daemon-runtime.md), [CLI Surface](../../specs/cli.md)

## LTS-PROP-001.P1 Problem

Skein's runtime extension surface has taken enough shape that its remaining `library` vocabulary is now doing two different jobs. In the generic Clojure sense, `libs.edn` currently lists library coordinates with `:local/root` paths and the specs discuss Maven/remote dependency coordinates as deferred Clojure dependency mechanics. In the Skein product sense, the same word names a trusted code unit that a selected world approves, a weaver makes available with `sync!`, and trusted config activates with `use!`.

That double use invites assumptions Skein explicitly does not provide: package registries, remote fetch, dependency solving, version isolation, or a public package CLI. The model is more specific than "a library exists on a classpath": it is an owner-approved local source root that feeds trusted behavior into one weaver runtime.

The textile metaphor covers that narrower concept without renaming the whole runtime model. A **spool** is a prepared supply of thread ready for a weaver to use. In Skein terms, a spool is a trusted local Clojure source root approved by a selected world; `sync!` makes approved spools available to the weaver, and `use!` activates one module from those spools or from selected config code.

There is also no lightweight filesystem convention for recognisable spool roots today. A future discovery or authoring tool can benefit from a visible marker convention now, before config workspace examples harden further.

This scope is deliberately narrow: the in-session naming review kept `Skein`, `strand`, `mill`, `weaver`, `world`, `query`, `batch`, `pattern`, and `weave`. `library` → `spool` is the one rename in this pass because it removes a standing ambiguity rather than laterally relabelling a working term.

## LTS-PROP-001.P2 Goals

- **LTS-PROP-001.G1:** Rename the user-facing Skein-specific trusted-code-unit concept from "library" to "spool" across README, docs, CLI help text, and spec prose. Keep "Clojure library" where text genuinely means the generic Clojure/deps concept.
- **LTS-PROP-001.G2:** Rename the approved-config surface: `libs.edn`/`libs.local.edn` → `spools.edn`/`spools.local.edn`, top-level `:libs` → `:spools`, and the default workspace directory `libs/` → `spools/`, while keeping each entry's Clojure coordinate shape (`{coord {:local/root path}}`) unchanged.
- **LTS-PROP-001.G3:** Rename data-bearing runtime surfaces that expose the approved-code-unit concept from libs/libraries to spools: return maps such as `{:libs ...}` become `{:spools ...}`, `use!` dependency gates use `:spools` instead of `:libs`, and weaver API/internal operation names should be updated where they are not stable public CLI commands.
- **LTS-PROP-001.G4:** Keep generic runtime verbs and the loader namespace unchanged: `skein.runtime.alpha` still exposes `approved`, `sync!`, `syncs`, `use!`, `uses`, and `reload!`. The rename is a noun/data-shape rename, not a verb or namespace-root rename.
- **LTS-PROP-001.G5:** Introduce a recommended, non-enforced `.spool` directory-suffix convention for spool roots (for example, `spools/my-module.spool`) as a discovery marker for future authoring/discovery tools.
- **LTS-PROP-001.G6:** Preserve the runtime model and behavior: shared/local overlay, local-root resolution, runtime dependency loading, sync/use/reload lifecycle, trusted execution, and fail-loud malformed config semantics are unchanged except for renamed keys/files.
- **LTS-PROP-001.G7:** Drop old names outright under TEN-000@1: no `libs.edn`/`:libs` fallback, alias, dual-read path, or compatibility namespace for the renamed approved-spool surface.

## LTS-PROP-001.P3 Non-goals

- **LTS-PROP-001.NG1:** No rename of `weaver`, `world`, `query`, `batch`, `pattern`, `weave`, or `mill`. The naming review kept these: `weaver` is the accepted active-engine noun (RFC-006), `world` remains plumbing for selected config/runtime identity, `query`/`batch` stay generic, and `pattern`/`weave` already read well.
- **LTS-PROP-001.NG2:** No package registry, remote/Maven fetch, dependency solver, lockfile, source installer, or `strand spool` CLI command. The `.spool` convention is discovery groundwork only.
- **LTS-PROP-001.NG3:** No enforcement or validation of the `.spool` suffix. Unsuffixed spool roots keep working; the suffix is a recommended convention.
- **LTS-PROP-001.NG4:** No change to runtime extension semantics: sync/use/reload behavior, overlay precedence, local-root resolution, classloader behavior, and trusted execution authority are unchanged.
- **LTS-PROP-001.NG5:** No rename of the generic Clojure per-entry `:local/root` key, which mirrors Clojure deps coordinate conventions.
- **LTS-PROP-001.NG6:** No rename of `skein.runtime.alpha`; it remains the privileged runtime loader/config namespace, not an ordinary spool namespace.
- **LTS-PROP-001.NG7:** No formal RFC for this pass. The proposal records the accepted naming decision, and the remaining work is spec/plan/implementation detail rather than unresolved product direction.

## LTS-PROP-001.P4 Proposed scope

- **LTS-PROP-001.S1:** Replace `library`/`libraries` with `spool`/`spools` where text names Skein's trusted local code-unit concept in README, `docs/skein.md`, `docs/getting-started.md`, CLI help, and root spec prose. Keep `Clojure library`, `library coordinate`, and other generic Clojure wording where that meaning is intended.
- **LTS-PROP-001.S2:** Rename the config surface: `libs.edn` → `spools.edn`, `libs.local.edn` → `spools.local.edn`, top-level `:libs` → `:spools`. `strand init` bootstrap creates `spools.edn` and a `spools/` directory, and repo `.gitignore` ignores `spools.local.edn`.
- **LTS-PROP-001.S3:** Keep `strand init` fail-loud and idempotent for old worlds: if legacy `libs.edn` or `libs.local.edn` is present, `strand init` fails before creating new spool files and tells the user to rename the file(s) and top-level key manually. Existing arbitrary directories named `libs/` are not rejected, because `:local/root` may point at any user-approved path.
- **LTS-PROP-001.S4:** Rename the runtime data shapes and gates exposed through trusted Clojure APIs: `(runtime-alpha/approved)` and `(runtime-alpha/syncs)` return `{:spools ...}`, `(runtime-alpha/sync!)` returns `{:spools ...}`, and `(runtime-alpha/use! key opts)` accepts `:spools` instead of `:libs` for approved-root dependencies.
- **LTS-PROP-001.S5:** Rename non-public/internal approved-library identifiers where practical so code, tests, errors, and docs do not preserve stale concept names: approved-lib sync state, weaver API operation keywords, helper names, test namespaces, and messages should move to spool vocabulary.
- **LTS-PROP-001.S6:** Move the reserved authorable/example namespace family from `skein.libs.*` to `skein.spools.*`. The existing `skein.libs.*` namespace family is removed rather than retained as a compatibility alias.
- **LTS-PROP-001.S7:** Preserve `skein.runtime.alpha` and existing verb names (`approved`, `sync!`, `syncs`, `use!`, `uses`, `reload!`). Generated `init.clj` continues to require `skein.runtime.alpha` and call `(runtime-alpha/sync!)`.
- **LTS-PROP-001.S8:** Add fail-loud legacy detection for present `libs.edn` or `libs.local.edn` in a selected config-dir when approved spool config is read. The error should tell users to rename the files and top-level key; it must not silently ignore old config or read both surfaces.
- **LTS-PROP-001.S9:** Document the `.spool` naming convention: spool roots may be named `<name>.spool/` and `:local/root` entries reference that directory directly, but the suffix is not required for loading.
- **LTS-PROP-001.S10:** Update affected root specs through feature-local deltas before planning: CLI init bootstrap (SPEC-002), REPL API runtime spool workspace helpers (SPEC-003.P5/P6), Weaver Runtime runtime spool workspace model (SPEC-004.P9 plus any runtime-state references), and namespace-family guidance that currently reserves `skein.libs.*`.
- **LTS-PROP-001.S11:** Sequence this rename before active `library-author-testing-support` implementation work resumes, then rebase that feature's proposal/spec deltas/plan/tasks vocabulary from library/libs to spool/spools where it refers to the approved trusted-code-unit surface. Public docs introduced by that feature should use spool vocabulary and a spool-oriented filename/title (for example `docs/spool-authoring.md`), while the existing feature folder name may remain as historical devflow bookkeeping.

## LTS-PROP-001.P5 Decisions

- **LTS-PROP-001.D1:** The rename depth includes config files/keys, trusted Clojure data shapes, `use!` gate keys, internal approved-code-unit identifiers, tests, docs, examples, and the reserved authorable/example namespace family. It excludes `skein.runtime.alpha`, generic Clojure `:local/root`, and unrelated runtime terms.
- **LTS-PROP-001.D2:** The `.spool` convention is a directory suffix (`my-module.spool/`), not a marker file. It is recommended documentation only and is not validated by the loader.
- **LTS-PROP-001.D3:** No RFC is required for this feature. The naming choice and out-of-scope terms are settled enough for spec deltas and planning.
- **LTS-PROP-001.D4:** This feature should land before `library-author-testing-support` executes implementation tasks, because that feature's docs/specs/tasks currently refer to the surface this feature renames. That feature's public documentation should be rebased to spool vocabulary and should not ship a new `docs/library-authoring.md` path after this rename.
- **LTS-PROP-001.D5:** `strand init` should fail immediately when legacy `libs.edn` or `libs.local.edn` files are present; runtime spool config reads should fail the same way. Skein should not silently ignore old approved-code config, create parallel `spools.edn` beside it, or dual-read both surfaces.

## LTS-PROP-001.P6 Open questions

- **LTS-PROP-001.Q1:** None before spec deltas and planning.
