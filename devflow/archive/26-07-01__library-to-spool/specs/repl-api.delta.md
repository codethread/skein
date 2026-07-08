# REPL API Delta: Runtime Spool Helpers

**Document ID:** `LTS-SPEC-003-DELTA-001` **Status:** Merged **Target root spec:** `devflow/specs/repl-api.md`

## Delta

- `skein.runtime.alpha` remains the blessed loader/config namespace and keeps verb names `approved`, `sync!`, `syncs`, `use!`, `uses`, and `reload!`.
- Approved config files are `spools.edn` and `spools.local.edn`; each present file must contain exactly top-level `:spools` with coordinate entries shaped as `{coord {:local/root path}}`.
- `(runtime-alpha/approved)`, `(runtime-alpha/sync!)`, and `(runtime-alpha/syncs)` return `{:spools ...}`.
- `(runtime-alpha/use! key opts)` accepts `:spools` as the approved-root dependency gate; `:libs` is not accepted.
- Loader/config helpers do not live under `skein.spools.*`; that namespace family is reserved for authorable spool code and examples.
- Spool roots may use the recommended non-enforced directory suffix `<name>.spool/`.
