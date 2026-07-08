# Weaver Runtime delta for weaver lifecycle hooks

**Document ID:** `WLH-DELTA-001` **Root spec:** [daemon-runtime.md](../../../specs/daemon-runtime.md) **Feature:** [../proposal.md](../proposal.md) **Status:** Merged **Last Updated:** 2026-06-29

## WLH-DELTA-001.P1 Summary

This delta adds synchronous trusted lifecycle hooks to the weaver runtime. Hooks are weaver-lifetime runtime state, run through the same trusted library classloader as other extension points, and may reject or explicitly transform data before blessed mutation APIs commit storage changes. Existing asynchronous post-commit events remain unchanged notification machinery.

## WLH-DELTA-001.P2 Contract changes

- **WLH-DELTA-001.CC1:** A weaver owns one in-memory lifecycle hook registry for its lifetime, alongside the named-query, view, pattern, operation, event handler, approved-library sync, and module-use registries.
- **WLH-DELTA-001.CC2:** Hook registry entries contain a stable key, a non-empty set of hook type keywords, a fully qualified function symbol, resolved callable state for invocation, an integer `:order` defaulting to `0`, and data-first metadata. Registry introspection returns data-first entries and never exposes callable values.
- **WLH-DELTA-001.CC3:** Duplicate hook registration by key replaces the prior entry for reload workflows. Hook execution order is deterministic: ascending `:order`, then stable key `pr-str` order.
- **WLH-DELTA-001.CC4:** Runtime config reload clears hook registry state with other weaver-lifetime registries, then reloads selected config-dir `init.clj` so trusted config may reinstall hooks. Reload does not persist, replay, or unload hook code.
- **WLH-DELTA-001.CC5:** Hook functions are invoked through the runtime library classloader and receive exactly one context map. They are trusted code with weaver process authority.
- **WLH-DELTA-001.CC6:** Validation hook return values are ignored. A validation hook succeeds only by returning normally and rejects the operation by throwing.
- **WLH-DELTA-001.CC7:** Transform hook families are explicit. A transform hook must return `{:hook/value replacement}`. Returning `nil`, returning a non-wrapper value, or returning a replacement that fails the next core shape check fails loudly.
- **WLH-DELTA-001.CC8:** The first transform hook family is `:attributes/normalize`. It receives a JSON-compatible strand attribute map in `:hook/value` plus request and mutation context, and returns a replacement JSON-compatible attribute map before candidate validation commits.
- **WLH-DELTA-001.CC9:** Attribute normalization runs per strand attribute map, not over an aggregate payload. It applies to attributes supplied by add, update patches, transactional batch created/updated strand entries, and pattern-produced create-only strand entries. It does not normalize edge attributes in this feature.
- **WLH-DELTA-001.CC10:** Attribute-normalization context includes at least `:hook/type`, `:hook/value`, `:request/source`, `:request/operation`, `:mutation/operation`, and, when known, strand-specific keys such as `:strand/id`, `:strand/ref`, `:strand/before`, `:strand/patch`, `:batch/ref`, and `:pattern/name`. A no-op transform still returns `{:hook/value current-attributes}`.
- **WLH-DELTA-001.CC11:** Received-payload hooks use hook type `:payload/received`. They run only for JSON socket requests that may mutate strand graph data or invoke trusted userland behavior: `add`, `update`, `supersede`, `burn`, `weave`, and `op`. They do not gate setup, administrative, or read-only socket operations such as `init`, `status`, `stop`, `show`, `list`, `ready`, or `pattern-explain`.
- **WLH-DELTA-001.CC12:** Received-payload hooks run after JSON decode, protocol validation, weaver identity verification, operation allowlist resolution, and operation-specific argument shape validation, but before semantic dispatch. They may reject but may not transform operation names, allowlist membership, or request arguments in this feature.
- **WLH-DELTA-001.CC13:** Received-payload hook context includes at least `:hook/type`, `:request/source`, `:request/operation`, `:request/id`, `:request/args`, and `:request/options`. For JSON socket requests, `:request/source` is `:json-socket`, `:request/operation` is the allowlisted operation keyword, `:request/args` is the decoded string-keyed argument map before socket dispatch reshapes it into Clojure API arguments, and `:request/options` is the decoded options map. Payload hooks see pre-normalization values and should not enforce post-normalization attribute type policy.
- **WLH-DELTA-001.CC14:** Pre-commit mutation hook families include `:strand/add-before-commit`, `:strand/update-before-commit`, `:strand/supersede-before-commit`, `:strand/burn-before-commit`, and `:batch/apply-before-commit`.
- **WLH-DELTA-001.CC15:** Pre-commit hook context always includes `:hook/type`, `:request/source`, `:request/operation`, and `:mutation/operation`. Add hooks receive candidate created strand data with no before row. Update hooks receive strand id, patch, normalized before row, normalized after candidate row, and candidate edge operations requested by the update patch. Supersession hooks receive old id, replacement id, old before/after candidate rows, supersedes edge candidate, and dependency rewiring candidate data. Burn hooks receive requested ids and normalized before rows.
- **WLH-DELTA-001.CC16:** Batch hooks receive one common schema for graph batch and pattern-created batch entry points: `:batch/source`, `:batch/payload`, `:batch/refs`, `:batch/created`, `:batch/updated`, `:batch/burned`, and `:batch/edge-ops`. Entries that do not apply to one source are present as empty vectors or maps rather than omitted.
- **WLH-DELTA-001.CC17:** For transactional graph batch mutation, `:batch/source` is `:apply`, `:batch/payload` is the submitted payload, and the remaining batch keys describe final refs, created candidates, updated before/after candidates, burned before rows, and candidate edge operations. For pattern-created batches, `:batch/source` is `:weave`, `:batch/payload` is the pattern-produced create-only batch in normalized batch shape, `:batch/updated` and `:batch/burned` are empty vectors, and context also includes `:request/operation :weave`, `:pattern/name`, and `:pattern/input`. Pattern-created batches therefore pass through batch policy once, avoiding duplicate pre-commit validation.
- **WLH-DELTA-001.CC18:** Batch and pattern-created batch hook rejection rejects the entire batch transaction. Partial acceptance of valid entries from a rejected batch is outside this feature.
- **WLH-DELTA-001.CC19:** Pre-commit hooks run synchronously before durable mutation commit at a point where throwing aborts the mutation. Hook-rejected mutations commit no partial graph change and enqueue no post-commit mutation events.
- **WLH-DELTA-001.CC20:** Successful hook-approved mutations preserve existing event behavior. Events are still emitted only after storage mutation succeeds and event handler exceptions still do not fail the already-committed mutation.
- **WLH-DELTA-001.CC21:** Blessed weaver API mutation paths invoke the relevant attribute-normalization and pre-commit hooks. This includes add, update, supersede, burn, transactional batch graph mutation, and pattern-created batch mutation. Trusted code that bypasses the weaver API and calls lower-level persistence namespaces directly opts out of the blessed hook contract.
- **WLH-DELTA-001.CC22:** Hook invocation failures propagate to the originating caller as `ex-info` with top-level code `hook/failed`, including hook type, hook key, hook function symbol, original exception message, original exception class, original `ex-info` data when present, and original `:code` as `:hook/cause-code` when present. The original throwable remains the cause for in-process Clojure callers.
- **WLH-DELTA-001.CC23:** The JSON socket error envelope continues to classify hook rejections as domain failures. The error code is `hook/failed`; user hook details, including `:hook/cause-code`, are carried in structured error details.
- **WLH-DELTA-001.CC24:** The JSON socket operation allowlist remains authoritative. Hooks may reject an allowed request but cannot make a non-allowlisted operation dispatchable.
- **WLH-DELTA-001.CC25:** Hook registry mutation and introspection are weaver API operations for trusted config and REPL workflows only. They are not public JSON socket operations.

## WLH-DELTA-001.P3 Design decisions

### WLH-DELTA-001.D1 Hooks are lifecycle gates, events are notifications

- **Decision:** Synchronous lifecycle hooks are a separate registry and invocation path from asynchronous post-commit events.
- **Rationale:** Blocking policy needs a before/after candidate view and failure propagation to the original caller. Events intentionally observe committed facts and isolate handler failures.
- **Rejected:** Making existing event handlers synchronous, rollback-capable, or responsible for validation.

### WLH-DELTA-001.D2 Received payload hooks validate only

- **Decision:** `:payload/received` hooks may reject decoded socket requests for strand-graph mutation or userland-invoking operations but cannot transform request arguments in this feature.
- **Rationale:** Allowing whole-request transformation would blur the JSON socket protocol boundary and make public CLI behavior harder to reason about. Narrow transform hook families cover intended coercion without thickening the CLI. Excluding setup, read-only, and administrative socket operations keeps initialization, health checks, and shutdown debuggable even when policy hooks are broken.
- **Rejected:** Letting payload hooks rewrite operation names or decoded arguments before dispatch.

### WLH-DELTA-001.D3 Attribute normalization is the explicit first transform point

- **Decision:** Attribute coercion lives in `:attributes/normalize` hooks with a strict `{:hook/value ...}` replacement contract.
- **Rationale:** CLI `--attr` values are intentionally strings, while trusted weaver code may want numeric/date/boolean attribute shapes. A narrow transform phase keeps the CLI thin and makes transformation points auditable.
- **Rejected:** Inferring types in the Go CLI or making all hooks implicitly transform by return value.

### WLH-DELTA-001.D4 Hook failures get a stable top-level code

- **Decision:** Hook invocation failures are wrapped with `hook/failed` and preserve the original exception data under hook failure details.
- **Rationale:** Transports and agent callers need a predictable failure class, while users still need the hook key, type, original message, and original data to debug policy.
- **Rejected:** Passing arbitrary user `:code` values through as the top-level transport code with no hook metadata.

### WLH-DELTA-001.D5 The blessed path is policy-gated, not all trusted code

- **Decision:** Public mutation transports, connected helpers, patterns, and blessed alpha APIs use hook-gated weaver API mutation operations, but trusted direct calls to lower-level persistence namespaces are outside the hook guarantee.
- **Rationale:** Skein treats trusted agents and config as powerful. The blessed path should be safe and consistent without pretending to sandbox or constrain all possible in-process Clojure code.
- **Rejected:** Trying to prevent trusted code from bypassing hooks or adding defensive wrappers around every internal persistence function.

## WLH-DELTA-001.P4 Open questions

- **WLH-DELTA-001.Q1:** None for contract scope. Implementation planning should give batch candidate planning an explicit task because it is the highest-risk integration point.
