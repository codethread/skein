# Weaver Runtime Delta: Runtime Spool Workspace

**Document ID:** `LTS-SPEC-004-DELTA-001` **Status:** Merged **Target root spec:** `devflow/specs/daemon-runtime.md`

## Delta

- Weaver runtime state is described as approved-spool sync state rather than approved-library sync state.
- The selected config-dir is a trusted alpha spool workspace. Shared/local approved roots live in `spools.edn` and `spools.local.edn`.
- Effective approved-spool config overlays `spools.edn` followed by `spools.local.edn`; local entries replace shared entries for the same coordinate.
- Malformed present spool config fails loudly. Present legacy `libs.edn` or `libs.local.edn` also fails loudly with guidance to rename files and top-level `:libs` to `:spools`.
- Runtime dependency loading, `sync!`, `use!`, `reload!`, classloader behavior, and trusted execution semantics are unchanged except for spool vocabulary and data keys.
- `skein.spools.*` is the reserved namespace family for authorable spool libraries and examples; `skein.libs.*` is removed.
