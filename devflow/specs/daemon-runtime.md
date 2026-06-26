# Weaver Runtime

**Document ID:** `SPEC-004`
**Status:** Implemented
**Last Updated:** 2026-06-26
**Related RFCs:** [RFC-002 Task Query DSL](../rfcs/2026-06-24-task-query-dsl.md), [RFC-003 Fast JSON Socket CLI](../archive/26-06-25__go-cli-migration/rfcs/2026-06-25-fast-json-socket-cli.md), [RFC-004 Go CLI Migration](../archive/26-06-25__go-cli-migration/rfcs/2026-06-25-go-cli-migration.md)
**Code:** `src/skein/weaver`, `src/skein/client.clj`, `cli/`

## SPEC-004.P1 Purpose

The weaver runtime is the long-lived local Clojure process that owns strand storage access, query execution, and runtime extension state for CLI and REPL clients.

## SPEC-004.P2 Runtime model

- **SPEC-004.C1:** A weaver owns exactly one active SQLite datasource, one in-memory named-query registry, one in-memory read-only view registry, one in-memory approved-library sync state, and one in-memory module-use registry for its lifetime.
- **SPEC-004.C2:** A weaver exposes two local transports: nREPL for Clojure REPL/client workflows and a JSON Unix domain socket for the public Go CLI.
- **SPEC-004.C3:** Transports are local-only by default: nREPL binds to loopback, and the JSON CLI transport uses a Unix domain socket under the selected runtime state directory.
- **SPEC-004.C4:** A weaver world is selected by config-dir. The default config-dir is `$XDG_CONFIG_HOME/skein` or `~/.config/skein`; an explicit config-dir override selects a separate world.
- **SPEC-004.C5:** The default world uses `$XDG_STATE_HOME/skein` or `~/.local/state/skein` for runtime state and `$XDG_DATA_HOME/skein` or `~/.local/share/skein` for weaver-owned data.
- **SPEC-004.C6:** An explicit config-dir selects a self-contained experimental world: config files live in the selected directory, runtime state lives in `state`, and weaver data lives in `data`.
- **SPEC-004.C7:** The weaver owns storage selection. By default it uses `skein.sqlite` under the selected data world. Public clients do not choose storage paths.
- **SPEC-004.C8:** A selected config-dir may have at most one running weaver. Starting another weaver for the same selected config-dir fails loudly unless stale socket/metadata can be proven dead and cleaned.
- **SPEC-004.C9:** Multi-weaver use is explicit world selection: different config-dir overrides create separate weaver/socket/metadata/data/init worlds. No client command implicitly switches worlds based on cwd or storage path.

## SPEC-004.P3 Runtime metadata and discovery

- **SPEC-004.C10:** The JSON socket path is fixed within the selected state world as `weaver.sock`.
- **SPEC-004.C11:** Runtime metadata is published beside the fixed socket as `weaver.json` for Go clients and `weaver.edn` for Clojure clients.
- **SPEC-004.C12:** Runtime metadata records enough identity for clients to connect and verify intent: pid, weaver id, protocol version, selected config-dir, selected state dir, selected data dir, weaver-owned database path for status/debugging, nREPL endpoint data, JSON socket path, and startup time.
- **SPEC-004.C13:** Clients discover the weaver by selected config-dir/state world, then verify weaver identity/protocol over the socket. They do not need a database path to locate metadata, and they do not send or compare database path as a request identity field.
- **SPEC-004.C14:** Stale, missing, mismatched, malformed, or unreachable runtime metadata is an error; clients must fail loudly rather than silently opening SQLite directly. Clients discover only `weaver.*` artifacts and do not fall back to old `daemon.*` metadata.

## SPEC-004.P4 API boundary

- **SPEC-004.C15:** `skein.weaver.api` is the semantic boundary used by clients. Transport-specific eval strings, JSON envelopes, nREPL messages, and wire details are not the durable product API.
- **SPEC-004.C16:** Weaver API operations cover the current stripped strand surface and trusted runtime state helpers: initialize storage, add strand, update strand, show strand, list strands, ready strands, register one named query, load named queries, list registered query definitions, resolve a named query, execute list/ready through a named query, read approved library config, sync approved libraries, inspect approved-library sync state, activate one module with `use!`, inspect module-use state, execute set-oriented runtime transformation primitives (`query-ids`, `strands-by-ids`, `ancestor-root-ids`, and `subgraph`), and register/list/invoke weaver-memory views.
- **SPEC-004.C17:** Weaver API return values are Clojure data with JSON-bearing database columns normalized before transport-specific formatting. Strand rows use `active`, `ephemeral`, and `inactive_at`; they do not expose core `status` or `final_at`.
- **SPEC-004.C18:** Weaver API failures preserve domain error information well enough for clients to exit non-zero with useful messages.

## SPEC-004.P5 nREPL transport

- **SPEC-004.C19:** nREPL remains available for Clojure/JVM process, client, editor, and REPL tooling.
- **SPEC-004.C20:** Clojure clients call only fixed weaver API forms generated by trusted client code; user CLI input is passed as data arguments, not interpolated as executable code.
- **SPEC-004.C21:** nREPL is not the public fast CLI transport; it remains the rich Clojure-native surface for live development and REPL helpers.

## SPEC-004.P6 JSON Unix socket transport

- **SPEC-004.C22:** The public Go CLI uses a local JSON Unix domain socket advertised in Go-readable runtime metadata.
- **SPEC-004.C23:** JSON socket requests are one request per connection and include protocol version, request id, weaver id, operation name, operation arguments, and simple options such as output format.
- **SPEC-004.C24:** JSON socket responses include protocol version, request id, success flag, result on success, and a structured error envelope on failure. Error types distinguish domain, protocol, and transport failures.
- **SPEC-004.C25:** The JSON transport dispatches to weaver semantic operations rather than duplicating SQL or query logic in transport handlers.
- **SPEC-004.C26:** The JSON socket operation allowlist is limited to public CLI behavior: `init`, `add`, `update`, `show`, `list`, `ready`, `list-query`, `ready-query`, `status`, and `stop`.
- **SPEC-004.C27:** Query registry mutation/listing/inspection and view registry operations are intentionally excluded from the JSON socket allowlist; those remain REPL/trusted config workflows.
- **SPEC-004.C28:** `status` validates the matched weaver over the socket and reports weaver health, pid, selected config/data paths, weaver-owned database path, weaver identity, socket path, and nREPL endpoint. `stop` stops only the matched weaver and removes `weaver.edn`, `weaver.json`, and `weaver.sock` artifacts.

## SPEC-004.P7 Configuration and user code

- **SPEC-004.C29:** Core weaver startup works without a user init file.
- **SPEC-004.C30:** The weaver loads `init.clj` from the selected config-dir by default when present. Load errors fail startup loudly and publish no ready metadata.
- **SPEC-004.C31:** Trusted init code runs after weaver memory/socket state exists and before runtime metadata is published, so it may initialize weaver-owned runtime state through startup helpers such as `skein.weaver.api/register-query!`.
- **SPEC-004.C32:** Sandboxing, SCI execution, runtime reload commands, untrusted plugin isolation, remote weaver access, authentication, multi-user authorization, and daemon startup hooks for storage customization are outside the current runtime contract.

## SPEC-004.P8 Named query registry

- **SPEC-004.C33:** Named queries are data-first EDN DSL definitions stored in weaver memory, not executable user functions.
- **SPEC-004.C34:** Registry names are simple unqualified names; symbol and keyword forms of the same name resolve to one entry.
- **SPEC-004.C35:** Registry contents are not durable across weaver restarts. Users reload runtime query definitions through trusted startup config or REPL helpers.
- **SPEC-004.C36:** Missing named-query resolution fails loudly with a clear domain error.

## SPEC-004.P9 Runtime library workspace model

- **SPEC-004.C37:** Runtime extensions are normal trusted Clojure libraries/modules made available to the weaver through selected config-dir startup (`init.clj`) or connected REPL workflows.
- **SPEC-004.C38:** Extension code runs with weaver process authority. Sandboxing, untrusted execution, remote authorization, and capability restriction are outside this contract.
- **SPEC-004.C39:** Skein ships the blessed source-visible `skein.libs.alpha` runtime library for library-workspace workflows.
- **SPEC-004.C40:** Blessed alpha libraries are documented, tested, and used by examples. They are recommended maintenance paths, not enforcement boundaries; trusted code may require lower-level namespaces or use raw SQLite schema when it accepts compatibility cost.
- **SPEC-004.C41:** The selected config-dir is a trusted alpha library workspace root. User-owned config may include `config.json` with `"configFormat":"alpha"`, `init.clj`, `libs.edn`, local source directories, and user code. The CLI bootstrap path may initialize missing workspace files/directories and a Git repository, but must not overwrite existing user files.
- **SPEC-004.C42:** `libs.edn`, when present, declares approved weaver-wide local roots. Relative `:local/root` entries resolve against selected config-dir; absolute roots are accepted as explicit user-approved paths; leading `~` and `~/` expand to the user home directory. Malformed config fails loudly.
- **SPEC-004.C43:** Missing `libs.edn` yields an empty approved config unless user code explicitly requires libraries to exist. Per-library missing, unreadable, or add-libs-failed roots are sync outcomes rather than structural config errors.
- **SPEC-004.C44:** `skein.libs.alpha/sync!` uses Clojure runtime dependency tooling to add approved local roots to the weaver runtime and records per-library loaded, already-available, or failed outcomes in weaver memory.
- **SPEC-004.C45:** Runtime library availability and runtime activation are distinct. Making a root available allows weaver-side `require`; activation remains explicit trusted Clojure such as `use!`, direct function calls, or selected-config-dir-relative `load-file`.
- **SPEC-004.C46:** `skein.libs.alpha/use!` records module-use attempts and loaded, skipped, or failed outcomes for weaver-lifetime introspection. This state is not durable package metadata.
- **SPEC-004.C47:** Runtime dependency loading is weaver-wide. There is no per-module version isolation or unloading guarantee; clean replacement may require weaver restart.
- **SPEC-004.C48:** Runtime source acquisition is outside normal weaver boot. Skein must not silently clone repositories, add Git submodules, or fetch source as part of module activation.
- **SPEC-004.C49:** The MVP supports approved local roots first. Maven/remote dependency downloads, package registries, git fetching, dependency solving, lockfiles, and CLI package commands are outside this contract.
- **SPEC-004.C50:** Blessed `skein.*.alpha` namespaces are loaded from the selected world's configured Skein source checkout/classpath. Startup or REPL use fails loudly if those namespaces are unavailable.

## SPEC-004.P10 Runtime transformation primitives

- **SPEC-004.C51:** Skein ships blessed source-visible `skein.graph.alpha` and `skein.views.alpha` namespaces for trusted runtime transformation workflows. They build on the runtime library workspace model but are built-in namespaces, not user/community libraries requiring `libs.edn` approval.
- **SPEC-004.C52:** `query-ids` returns a vector of strand ids for an ad hoc query definition or registered query name, ordered by the same stable strand ordering as `list` query results.
- **SPEC-004.C53:** `strands-by-ids` accepts a collection of strand ids, collapses duplicate ids by first occurrence, returns normalized strand rows in that first-occurrence input order, returns `[]` for empty input, and fails loudly if any requested id is missing.
- **SPEC-004.C54:** `ancestor-root-ids` traverses upward over `parent-of` edges where parent `from_strand_id` points to child `to_strand_id`. Seed ids are depth-zero candidates. With `:where`, it returns topmost matching ancestors on every path; without `:where`, it returns graph roots with no `parent-of` parent. Results are deduplicated and stable-sorted by id. Empty seed input returns `[]`; any missing seed id fails loudly. The MVP has no edge-type option.
- **SPEC-004.C55:** `subgraph` expands downward over `parent-of` from root ids and returns `{:root-ids [...] :strands [...] :edges [...]}`. `:root-ids` preserves first-occurrence input order with duplicates collapsed. `:strands` contains normalized rows for roots and descendants, ordered by stable strand id. `:edges` contains only internal `parent-of` edges connecting included strands, ordered by `from_strand_id`, `to_strand_id`, then edge type. Empty root input returns `{:root-ids [] :strands [] :edges []}`; any missing root id fails loudly. The MVP has no edge-type option.
- **SPEC-004.C56:** View registry entries are named by simple unqualified names and point to fully qualified Clojure function symbols resolvable in the weaver JVM. Duplicate registration replaces the prior entry for reload workflows.
- **SPEC-004.C57:** View invocation resolves the registered function symbol in the weaver JVM and calls it with one context map containing at least `:params`. View functions are read-only transformations in this feature; mutating workflows require a separate contract.
- **SPEC-004.C58:** View registry contents are weaver-lifetime runtime state and are not durable across restarts. Registry introspection returns serializable entries, not function objects.
- **SPEC-004.C59:** View registry weaver operation names are `:register-view!`, `:view!`, and `:views`.

## SPEC-004.P11 Removed compatibility

The weaver runtime and clients do not support compatibility lookup of old default `atom` worlds, `tasks.sqlite`, `daemon.*` metadata, or `todo.*` / `atom.*` namespaces.
