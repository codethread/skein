# CLI Surface Delta: Runtime Spools

**Document ID:** `LTS-SPEC-002-DELTA-001` **Status:** Merged **Target root spec:** `devflow/specs/cli.md`

## Delta

- `strand init` bootstraps `spools/` and `spools.edn` with `{:spools {}}` instead of the legacy library workspace names.
- Repo `.skein/.gitignore` ignores `spools.local.edn` for local spool overlays.
- `strand init` fails loudly if `libs.edn` or `libs.local.edn` is present in the selected config-dir, with guidance to rename those files and the top-level `:libs` key manually.
- CLI prose names runtime extension workflows as spool workflows. There is still no public `strand spool` command.
