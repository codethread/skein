# Task 2: Fixed daemon socket protocol

**Document ID:** `TASK-002`
**Configuration identification:** `TASK-002` is the second task for `user-daemon-home`. Prefix every nested point ID with `TASK-002`.

## TASK-002.P1 Scope

Type: AFK

Move daemon socket and metadata discovery from database-path-hashed runtime files to the selected world's fixed state files, and remove database path from JSON request identity.

## TASK-002.P2 Must implement exactly

- **TASK-002.MI1:** Change daemon metadata paths so the selected state world contains `daemon.sock`, `daemon.json`, and `daemon.edn`.
- **TASK-002.MI2:** Ensure metadata includes pid, daemon id, protocol version, selected config-dir, selected data dir, daemon-owned database path for status/debugging, nREPL endpoint, socket path, and started-at.
- **TASK-002.MI3:** Update Go client discovery to read the selected world's fixed `daemon.json` and connect to `daemon.sock`.
- **TASK-002.MI4:** Update Clojure client discovery to read the selected world's fixed `daemon.edn` for nREPL workflows.
- **TASK-002.MI5:** Remove `database_path` from required JSON request envelope generation and daemon validation.
- **TASK-002.MI6:** Preserve daemon-id/protocol/socket identity checks; clients must fail loudly on missing, stale, malformed, unreachable, or mismatched daemon state.
- **TASK-002.MI7:** Update status/stop paths to use selected-world socket discovery rather than DB path.
- **TASK-002.MI8:** Add/update tests for fixed artifact names, request envelope without database path, stale metadata/socket failures, and status response metadata.

## TASK-002.P3 Done when

- **TASK-002.DW1:** Go tests prove normal clients do not need or send a database path.
- **TASK-002.DW2:** Clojure tests prove metadata read/write/delete operate on fixed state-world files.
- **TASK-002.DW3:** Existing DB-hashed metadata path usage is removed from the public client/daemon connection path.

## TASK-002.P4 Out of scope

- **TASK-002.OS1:** Do not implement default database selection or `init.clj` loading here except where metadata shape needs fields.
- **TASK-002.OS2:** Do not add new JSON socket operations beyond adapting existing status/stop/task/query operations.
- **TASK-002.OS3:** Do not preserve database-path mismatch checks as compatibility behavior.

## TASK-002.P5 References

- **TASK-002.REF1:** `UDH-PLAN-001.PH2`, `UDH-PLAN-001.A3`, `UDH-PLAN-001.A4`.
- **TASK-002.REF2:** `UDH-DELTA-001.C4` through `UDH-DELTA-001.C6`, `UDH-DELTA-001.R4`.
- **TASK-002.REF3:** Current daemon metadata/socket code: `src/todo/daemon/metadata.clj`, `src/todo/daemon/socket.clj`, `cli/internal/client/client.go`.
