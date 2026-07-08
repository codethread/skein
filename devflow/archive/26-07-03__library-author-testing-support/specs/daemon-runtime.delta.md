# Weaver Runtime delta for library author testing support

**Document ID:** `LAT-DELTA-001` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-07-03

## LAT-DELTA-001.P1 Summary

Weaver runtime gains an explicit storage model that supports the existing file-backed SQLite world and a real Xerial SQLite in-memory mode for trusted tests. Runtime metadata/status stop treating every database identity as a canonical filesystem path and instead report storage kind, diagnostic label, and file path only when storage is file-backed.

## LAT-DELTA-001.P2 Contract changes

- **LAT-DELTA-001.CC1:** A weaver runtime owns one storage handle for its lifetime. The handle includes a next.jdbc-compatible connectable, storage kind, diagnostic label, optional filesystem path, and close behavior.
- **LAT-DELTA-001.CC2:** `:sqlite-file` remains the default storage kind for normal weaver worlds. It uses the selected world's `data/skein.sqlite` path unless trusted runtime construction supplies another file path.
- **LAT-DELTA-001.CC3:** `:sqlite-memory` is supported for trusted test/runtime construction. It uses real Xerial SQLite JDBC with a weaver-owned held `java.sql.Connection`; it is not fake persistence and must exercise the same `skein.core.db` schema/query code.
- **LAT-DELTA-001.CC4:** Runtime stop closes storage resources that the weaver owns. Closing an in-memory connection destroys the in-memory database; later use of a closed storage handle fails loudly.
- **LAT-DELTA-001.CC5:** Runtime metadata reports storage identity explicitly. EDN metadata uses `:storage-kind`, `:storage-label`, and `:canonical-db-path`; JSON metadata/status uses `database_kind`, `database_label`, and `database_path`.
- **LAT-DELTA-001.CC6:** For file-backed SQLite, `:storage-kind` is `:sqlite-file`, `:storage-label` is the canonical database path, `:canonical-db-path` is the canonical database path, `database_kind` is `"sqlite-file"`, `database_label` is the canonical database path, and `database_path` is the canonical database path.
- **LAT-DELTA-001.CC6a:** For in-memory SQLite, `:storage-kind` is `:sqlite-memory`, `:storage-label` is a stable diagnostic label for the weaver lifetime, `:canonical-db-path` is nil, `database_kind` is `"sqlite-memory"`, `database_label` is the diagnostic label, and `database_path` is explicitly null.
- **LAT-DELTA-001.CC6b:** Metadata/status must not publish a fake filesystem path for in-memory storage.
- **LAT-DELTA-001.CC7:** Config-dir/world clients discover weavers by selected state world and weaver identity, not by database path. Legacy db-file-oriented Clojure helpers remain file-storage-only and fail loudly if used against non-file storage.
- **LAT-DELTA-001.CC8:** The public CLI does not select storage mode in this feature. In-memory storage is intended for trusted tests/helpers, not as a general user CLI mode.

## LAT-DELTA-001.P3 Design decisions

### LAT-DELTA-001.D1 Storage handle instead of path-only runtime

- **Decision:** Runtime startup should normalize storage into a storage handle before DB initialization and metadata publication.
- **Rationale:** File paths and held in-memory connections need different lifecycle and metadata behavior. One handle keeps DB call sites simple while avoiding filesystem assumptions in the test API.
- **Rejected:** Treating `:memory:` as a special path string; this would leak fake path semantics into metadata and cleanup.

### LAT-DELTA-001.D2 Real Xerial in-memory only

- **Decision:** In-memory mode uses the same Xerial SQLite JDBC engine as file mode and keeps the DB alive with a held connection.
- **Rationale:** The spike proved existing schema/queries work on a held connection. This preserves correctness while improving test isolation.
- **Rejected:** WASM SQLite, map-backed fake DBs, and other engines because they diverge from shipped behavior.

## LAT-DELTA-001.P4 Open questions

- **LAT-DELTA-001.Q1:** Concurrency behavior for a single held in-memory connection must be validated enough for test workloads and documented if serialized semantics are assumed.
