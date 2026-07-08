# CLI Surface delta for batch graph upsert

**Document ID:** `BGU-DELTA-004` **Root spec:** [cli.md](../../specs/cli.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-28

## BGU-DELTA-004.P1 Summary

This delta records that batch graph upsert intentionally does not add a public CLI command in its initial scope. The CLI remains a thin JSON control surface for common operations and named daemon-owned behavior.

## BGU-DELTA-004.P2 Contract changes

- **BGU-DELTA-004.CC1:** No `strand batch`, arbitrary JSON graph patch, or batch mutation command is added to the public CLI surface in the initial batch graph upsert scope.
- **BGU-DELTA-004.CC2:** Existing CLI commands continue to expose single-strand add/update/burn, list/ready read operations, pattern-backed create batches through `weave`, and weaver REPL access for trusted Clojure workflows.
- **BGU-DELTA-004.CC3:** Users and agents that need batch graph mutation use `strand weaver repl`, `strand weaver repl --stdin`, trusted config, activated libraries, patterns for validated creation workflows, or event handlers for trusted post-commit side effects as appropriate.
- **BGU-DELTA-004.CC4:** A future CLI batch command requires a separate explicit JSON contract and JSON socket allowlist change.

## BGU-DELTA-004.P3 Design decisions

### BGU-DELTA-004.D1 Do not freeze a JSON batch CLI yet

- **Decision:** Initial batch graph mutation remains outside the public CLI.
- **Rationale:** The batch payload is rich, extensible, and still contains open naming/event/edge-operation questions. Freezing it as a CLI JSON API now would work against the thin-CLI/rich-REPL boundary.
- **Rejected:** Adding a broad `strand batch apply` command that accepts arbitrary payloads over stdin in the first feature cut.

## BGU-DELTA-004.P4 Open questions

- **BGU-DELTA-004.Q1:** If the batch contract stabilizes, should the CLI eventually expose a named-pattern-like batch command, a raw JSON payload command, or only continue to route through REPL/config workflows?
