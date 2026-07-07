# Alpha Surface

**Document ID:** `SPEC-005`
**Status:** Implemented
**Last Updated:** 2026-07-05
**Related:** [Strand Model](./strand-model.md), [CLI Surface](./cli.md), [REPL API](./repl-api.md), [Weaver Runtime](./daemon-runtime.md), [Spools index](../../spools/README.md), [Writing shared spools](../../docs/writing-shared-spools.md)

## SPEC-005.P1 Purpose

This spec draws the line around what Skein ships as "alpha": which surface is in-contract (and where each contract lives), and which surface is explicitly internal. It is a contract index — it adds no behavior. The rule is exclusionary: surface not reachable through the in-contract tiers below is internal, regardless of Clojure var visibility or OS observability. TEN-000 still applies to everything: in-contract alpha surface evolves by accretion within a tier's own compatibility discipline, and internal surface may change without notice.

## SPEC-005.P2 In-contract surface

- **SPEC-005.C1:** The four root specs are the behavior contracts for shipped engine surface: strand model and storage semantics (SPEC-001), the public `strand`/`mill` CLI (SPEC-002), the trusted Clojure/REPL surface (SPEC-003), and the weaver runtime, transports, and registries (SPEC-004).
- **SPEC-005.C2:** The blessed spool-facing API is every `skein.api.*.alpha` namespace — currently `batch`, `cli`, `current`, `events`, `graph`, `hooks`, `patterns`, `peers`, `relations`, `runtime`, `scheduler`, `views`, `weaver` — plus `skein.test.alpha`, `skein.userland.alpha`, and the human-facing `skein.repl` helpers. Each is specified in SPEC-003/SPEC-004 (relations in SPEC-001.P5) and follows accretion-based compatibility within its subnamespace.
- **SPEC-005.C3:** Classpath-shipped reference spools are in-contract through their spool docs: `batteries`, `bobbin`, `carder`, `ephemeral`, `guild`, `roster`, `selvage`, and `workflow` at [`spools/*.md`](../../spools/README.md). The spool-authoring helper namespaces `skein.spools.util` (`fail!`, `reject-unknown-keys!`, `require-valid!`, `attr-key->str`, `attr-get`, `poll-until-deadline!`) and `skein.spools.format` (`fill`, `reflow`) are in-contract only to the extent documented in [writing shared spools](../../docs/writing-shared-spools.md); their undocumented remainder is internal until documented.
- **SPEC-005.C4:** Repo-local approved spools in this repository (the `spools/shuttle` root — which also hosts treadle — plus `spools/agents`, `spools/chime`, and `spools/kanban`) and externally distributed spools (devflow) are userland, not shipped alpha surface. Their READMEs/docs are their own contracts with their own cadence, outside this line.

## SPEC-005.P3 Explicitly internal

- **SPEC-005.C5:** `skein.core.*` is internal and may change freely (public vars included); trusted code that requires it accepts the compatibility cost (SPEC-004.C40).
- **SPEC-005.C6:** The mill JSON socket protocol is internal transport glue between the shipped Go binaries: its frame shapes, operation set, `mill/*` error codes, protocol version, and the `mill.json` field set carry no contract. The contract is the CLI command surface (SPEC-002) plus the published artifact locations (SPEC-004.C9a). Nothing but the shipped binaries should dial `mill.sock`.
- **SPEC-005.C7:** On the weaver socket, the error `type` taxonomy and envelope shape are contract (SPEC-004.C24); concrete error `code` strings are contract only where individually specified (`operation/deadline-exceeded` SPEC-004.C26b, `query/not-found` SPEC-004.C36b, `hook/failed` SPEC-004.C84). Other code strings, and all human-readable message text, may change.
- **SPEC-005.C8:** Also internal: exact generated-file contents beyond their specified behavior (SPEC-002.C14a), client-side transport timeout values, storage mechanics beyond declared semantics (PRAGMAs, cascade mechanics, index choices), and the stderr rendering format of error frames (its byte-faithful `details=` JSON payload stays contract per SPEC-002.C4).

## SPEC-005.P4 Change discipline

- **SPEC-005.C9:** Moving surface across the line — promoting internal surface into contract, or removing/reshaping in-contract surface — updates the owning root spec or spool doc, and this index when the tier membership itself changes. Internal-only changes need no spec update.
