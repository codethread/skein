# CLI Surface delta for library author testing support

**Document ID:** `LAT-DELTA-003`
**Root spec:** [cli.md](../../../specs/cli.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Draft
**Last Updated:** 2026-06-26

## LAT-DELTA-003.P1 Summary

The CLI remains thin and gains no library-testing commands. `weaver status` output must reflect the weaver runtime's explicit storage metadata once storage kind/label are added, rather than assuming every weaver has a file-backed database path.

## LAT-DELTA-003.P2 Contract changes

- **LAT-DELTA-003.CC1:** No public CLI command or flag is added for library testing, package installation, storage selection, or `skein.test.alpha` usage.
- **LAT-DELTA-003.CC2:** The Go CLI continues to discover weavers by selected config-dir/state metadata and verify weaver identity over the socket. It does not open SQLite directly and does not route requests by database path.
- **LAT-DELTA-003.CC3:** `strand weaver status` reports `database_kind` and `database_label` for all weaver storage kinds.
- **LAT-DELTA-003.CC4:** `database_path` remains a file-backed SQLite diagnostic field. It is a canonical path string for `:sqlite-file`; it is explicitly `null` for `:sqlite-memory`. The field is not omitted.
- **LAT-DELTA-003.CC5:** CLI metadata/status validation compares `database_kind` and `database_label` for all storage kinds, and compares `database_path` only for `sqlite-file`. It must fail loudly on malformed or inconsistent metadata rather than silently assuming file storage.

## LAT-DELTA-003.P3 Design decisions

### LAT-DELTA-003.D1 Status reflects weaver storage, CLI does not choose it

- **Decision:** The CLI status surface learns to display/validate storage metadata, but the CLI does not gain storage-selection flags.
- **Rationale:** Storage mode for this feature exists to support trusted test helpers and weaver runtime construction. The public CLI should stay a small control surface.
- **Rejected:** `strand weaver start --in-memory`, `strand test`, package test commands, or CLI-level library activation helpers.

## LAT-DELTA-003.P4 Open questions

- **LAT-DELTA-003.Q1:** None for MVP. Field names are `database_kind`, `database_label`, and `database_path`; `database_path` is null for memory storage.
