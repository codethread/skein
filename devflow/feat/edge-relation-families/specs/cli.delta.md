# CLI Surface delta for edge relation families

**Document ID:** `ERF-DELTA-004`
**Root spec:** [cli.md](../../../specs/cli.md)
**Feature:** [../proposal.md](../proposal.md)
**Status:** Reviewed
**Last Updated:** 2026-06-28

## ERF-DELTA-004.P1 Summary

This delta adapts the thin CLI to the state lifecycle model, open relation names, named-query edge predicates, and core supersession operation.

## ERF-DELTA-004.P2 Contract changes

- **ERF-DELTA-004.CC1:** `strand add` uses `--state active|closed` instead of `--active true|false`. Omitted state defaults to `active`; `replaced` is produced only by the supersession command.
- **ERF-DELTA-004.CC2:** `strand update` uses `--state active|closed` instead of `--active true|false`; it cannot manually set `replaced`.
- **ERF-DELTA-004.CC3:** `strand list` filters lifecycle with `--state active|closed|replaced` instead of `--active true|false`.
- **ERF-DELTA-004.CC4:** Public strand command outputs expose `state` and do not expose old `active` or `inactive_at` fields.
- **ERF-DELTA-004.CC5:** `strand ready` remains the shipped readiness battery: strands with `state="active"` and no outgoing `depends-on` target whose state is `active`. It is not reconfigured by CLI flags.
- **ERF-DELTA-004.CC6:** `strand update --edge edge-type:to-id` accepts any valid relation-name string for `edge-type`, not a closed set of four shipped names. Valid relation names use the portable lowercase grammar `[a-z0-9][a-z0-9._/-]*`.
- **ERF-DELTA-004.CC7:** CLI edge input reserves `:` as the separator between relation name and target id. Relation names containing `:`, uppercase letters, whitespace/control characters, blank relation names, blank target ids, or otherwise invalid relation names fail before or during the weaver mutation.
- **ERF-DELTA-004.CC8:** CLI edge writes route through the weaver and inherit storage-owned validation: target existence, self-edge rejection, relation-name syntax, declared-acyclic cycle checks, and annotation-by-default behavior for undeclared valid relations.
- **ERF-DELTA-004.CC9:** The CLI exposes a simple supersession command over the weaver operation. The command accepts old and replacement strand ids, returns JSON, and performs the same transaction as the trusted REPL helper.
- **ERF-DELTA-004.CC10:** Supersession command output reports the replaced strand before/after state, the replacement id, the `supersedes` edge outcome, and any dependency rewiring outcomes.
- **ERF-DELTA-004.CC11:** `list --query` and `ready --query` can consume weaver-registered named queries that contain edge predicates. The CLI still passes only a query name and string-valued `--param` values; it does not parse EDN query expressions.
- **ERF-DELTA-004.CC12:** The public CLI does not gain commands for declaring/listing relation schema, registering query definitions, running raw graph traversals, or invoking arbitrary relation analysis. Users who need those workflows use `strand weaver repl`, `strand weaver repl --stdin`, trusted config, or activated libraries.
- **ERF-DELTA-004.CC13:** CLI docs/examples may present the annotation catalog as conventional relation names. They must distinguish operational batteries (`depends-on`, `parent-of`, `supersedes`) from annotation conventions (`related-to`, `duplicates`, `references`, `implements`, `verifies`, `tracks`, `caused-by`).

## ERF-DELTA-004.P3 Design decisions

### ERF-DELTA-004.D1 State is the CLI lifecycle contract

- **Decision:** The CLI removes `--active` rather than keeping it as an alias for `--state`.
- **Rationale:** This is an alpha breaking change and the goal is to remove the old schema everywhere. Keeping aliases would preserve confusing old vocabulary.
- **Rejected:** Compatibility flags or boolean-to-state mapping.

### ERF-DELTA-004.D2 Supersession is simple enough for the thin CLI

- **Decision:** Unlike relation declaration or raw query authoring, supersession gets a public CLI command because it is a core lifecycle operation over two ids.
- **Rationale:** The CLI already exposes core lifecycle mutation. Supersession is no longer userland graph choreography; it is a tested weaver operation with a simple JSON-shaped input.
- **Rejected:** Forcing all supersession through REPL/config while exposing lower-level `update --state replaced` publicly.

### ERF-DELTA-004.D3 Catalog names are examples, not CLI validation policy

- **Decision:** CLI help/docs can point to the annotation catalog, but `--edge` validation remains the generic relation-name grammar plus weaver-owned structural checks.
- **Rationale:** The catalog helps agents choose common names without reintroducing a CLI-side allowlist or rejecting userland relations.
- **Rejected:** CLI validation against only the blessed catalog.

## ERF-DELTA-004.P4 Open questions

- **ERF-DELTA-004.Q1:** None for contract scope. Exact supersession command spelling may be finalized in the implementation plan.
