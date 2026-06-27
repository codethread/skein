# Patterned Weave Proposal

**Document ID:** `PROP-001`
**Feature:** `patterned-weave`
**Status:** Proposed
**Last Updated:** 2026-06-27
**Related RFCs:** None
**Related root specs:** [Strand Model](../../specs/strand-model.md), [CLI Surface](../../specs/cli.md), [REPL API](../../specs/repl-api.md), [Weaver Runtime](../../specs/daemon-runtime.md)

## PROP-001.P1 Problem

Skein has a low-level atomic batch strand primitive with transient refs, but no current public workflow for agents to submit a higher-level work request and let the selected weaver world shape the resulting strand graph. Agents creating several related strands through the CLI must manually create strands, capture ids, and add edges. More importantly, owners cannot enforce their preferred workflow shape when an agent adds work: the agent decides the graph directly instead of submitting intent to trusted user config.

## PROP-001.P2 Goals

- **PROP-001.G1:** Let trusted config define named weave patterns that transform input data into one or more strand creations and edges.
- **PROP-001.G2:** Let the public CLI invoke a named pattern with JSON from stdin: `strand weave --pattern <name>`.
- **PROP-001.G3:** Preserve the existing DB-owned id and batch-local ref model; refs are still transient and creation remains atomic.
- **PROP-001.G4:** Allow owners to enforce workflow shape, such as inserting review or validation strands, while agents submit simpler work-intent payloads.
- **PROP-001.G5:** Keep the CLI thin: it reads JSON stdin, names a pattern, sends data to the weaver, and prints JSON.
- **PROP-001.G6:** Let CLI callers inspect a registered pattern's expected input shape before invoking it, without receiving executable code or REPL access.

## PROP-001.P3 Non-goals

- **PROP-001.NG1:** Do not let the CLI upload Clojure, EDN pattern definitions, spec forms, or arbitrary executable code.
- **PROP-001.NG2:** Do not define durable aliases, user-controlled ids, or persistent refs.
- **PROP-001.NG3:** Do not implement general upsert semantics in the MVP; create-only batch weaving is enough unless a trusted pattern explicitly links to existing durable ids.
- **PROP-001.NG4:** Do not add file input flags; stdin remains the sole public payload mechanism.
- **PROP-001.NG5:** Do not expose pattern mutation over the JSON socket outside the specific `weave` invocation path.

## PROP-001.P4 Proposed scope

- **PROP-001.S1:** Add a weaver-lifetime pattern registry analogous to named queries/views, managed through trusted config and REPL helpers.
- **PROP-001.S2:** Register patterns as simple names pointing to fully qualified Clojure function symbols resolvable in the weaver JVM.
- **PROP-001.S3:** Add a weaver operation that resolves a pattern, calls it with input data, expects a batch strand vector, and passes that vector to `skein.db/add-strand-batch!`.
- **PROP-001.S4:** Add `strand weave --pattern <name>` that reads one JSON value from stdin, invokes the named pattern, and emits the batch result as JSON.
- **PROP-001.S5:** Register patterns with a Clojure spec input contract owned by trusted config. Invocation validates `:input` before calling the pattern function.
- **PROP-001.S6:** Add a read-only pattern explanation operation that exposes a normalized, JSON-shaped description of the registered input spec to lower-privilege callers.
- **PROP-001.S7:** Add REPL helpers for `defpattern!`, `patterns`, `pattern`, `pattern-explain`, and `weave!` so trusted users and agents can register, inspect, explain, and invoke patterns interactively.
- **PROP-001.S8:** Preserve transactionality and fail-loud behavior from the existing batch creation primitive.

## PROP-001.P5 Open questions

- **PROP-001.Q1:** Should the pattern function receive only the input payload, or a context map containing `:input` plus future metadata? The plan should choose the smallest shape that does not block future extension.
- **PROP-001.Q2:** What exact normalized subset of Clojure spec should be exposed over JSON so agents get useful caller guidance without promising a full spec-to-schema conversion?
