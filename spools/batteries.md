# Skein Batteries Spool

> This is the **contract** doc: the per-op behavior guarantees for the shipped
> `strand <op>` surface. Its two companions are
> [`batteries.cookbook.md`](./batteries.cookbook.md) — worked scripting recipes
> (how you compose the ops in a shell or pipeline) — and
> [`batteries.api.md`](./batteries.api.md) — the generated op/arg-spec reference.
> Reach for the cookbook when you want a runnable pipeline, the API doc when you
> want an exact flag list, and this doc for what each op promises.

## 1. Overview

`skein.spools.batteries` is the shipped *core strand command surface*, expressed as registered weaver ops. It registers the everyday strand operations — `add`, `update`, `show`, `supersede`, `burn`, `list`, `ready`, `subgraph`, the create-only `weave` op, and the read-only registry-introspection ops `query` and `pattern` — as `register-op!` ops whose `:arg-spec` is parsed by the blessed argv parser `skein.api.cli.alpha` (see [cli.md](../devflow/specs/cli.md) and [repl-api.md](../devflow/specs/repl-api.md)).

Each op delegates to exactly the `skein.api.*.alpha` call the old JSON socket dispatch used — strand lifecycle in `skein.api.weaver.alpha`, queries and traversal in `skein.api.graph.alpha`, weave in `skein.api.patterns.alpha` — and returns the same JSON-safe shape, so the ops are drop-in reachable through `strand <op> …` (RFC-019). The namespace owns no module-level state: handlers read the runtime from their invocation context (`:op/runtime`) and never touch the published ambient singleton.

This doc is the standing contract. It is written against the old public-CLI clauses in [`devflow/specs/cli.md`](../devflow/specs/cli.md) §SPEC-002.C6–C13 so behavior equivalences and deliberate differences are explicit; §5 is the clause-by-clause map. Stable ids here use the `BAT-` prefix.

Because batteries ships on the weaver classpath (under `spools/src`), it needs no `spools.edn` approval — `require` it and call `activate!`:

```clojure
(require '[skein.spools.batteries :as batteries])
(batteries/activate!)          ; into the active runtime (use!-style)
(batteries/activate! runtime)  ; explicit runtime, for tests/trusted callers
```

`activate!` registers every op below and returns `{:installed true :namespace 'skein.spools.batteries :ops [<register-op! result> ...]}`. Each op carries `{:doc … :arg-spec … :hook-class …}` metadata; re-running `activate!` against a live runtime collides loudly under the accretion registry (use `reload!`, which clears registries first).

## 2. Invocation and payloads

- **BAT-C1:** Every op is invoked as `strand <op> [args…]`; argv after the op
  name is parsed by the op's declared `:arg-spec` (`skein.api.cli.alpha`).
  Missing required flags/positionals, unknown flags, and type violations fail
  loudly in the parser before any handler runs.
- **BAT-C2 (payload references):** Wherever an argument value is a *payload
  reference* — the whole token `:stdin` or `:payload/<name>` — the parser
  resolves it against the invocation envelope's named payloads (`--stdin` /
  `--payload name=path` on the bin, `{:payloads {…}}` under `op!`). This
  replaces the old file/stdin attribute sources:
  - `--attr key=:payload/x` (or `--attr key=:stdin`) replaces old
    `--attr-file key=path` / `--attr-stdin key`: the payload string becomes the
    attribute value.
  - `--attributes :stdin` (or `:payload/<name>`) replaces old
    `--attributes-stdin`: the referenced payload is parsed as one JSON object
    of typed bulk attributes.
  - `weave --input :stdin` replaces reading raw stdin for `weave`.
Loud rules (SPEC-003-D003.C2): a reference naming no attached payload fails `:missing-payload`; an attached payload that no reference consumed fails `:unused-payloads`.
- **BAT-C3 (hook classes):** Each op declares a `:hook-class` used for
  metadata-driven gating (SPEC-004-D003): `:mutating` for `add`, `update`,
  `supersede`, `burn`, `weave`; `:read` for `show`, `list`, `ready`,
  `subgraph`, `query`, `pattern`. Mutating ops pass a request context
  `{:request/source :json-socket :request/operation <op-kw>}` so hooks and
  events observe the same data the old socket dispatch supplied.
- **BAT-C4 (result shapes):** Handlers return JSON-safe data (strings,
  numbers, booleans, nil, vectors, string/keyword-keyed maps). `attributes`
  and `state` are normalized; the old lifecycle fields `active` / `inactive_at`
  are never emitted (old C9).

## 3. Op reference

### 3.1 Mutations

#### `add` — BAT-C5

```
strand add <title> [--state active|closed] [--attr key=value]… \
  [--attributes <json-object-ref>] [--edge edge-type:to-id]…
```

Creates a strand with generated id, lifecycle state, timestamps, and merged attributes. Precedence: `--attr` (highest, repeatable string map) over `--attributes` (lowest, a JSON object of typed values). `--state` defaults to `active` and accepts `active|closed` (`replaced` is reserved for supersession and rejected). `--edge edge-type:to-id` adds an outgoing edge; repeatable. Duplicate keys **within** `--attr` fail loudly (old C6e); a blank `--attributes` key fails loudly; a malformed `--edge` (no/edge-terminal `:`) fails loudly. Returns the normalized strand `{:id :title :state :attributes …}`.

#### `update` — BAT-C6

```
strand update <id> [--title t] [--state active|closed] [--attr key=value]… \
  [--edge edge-type:to-id]…
```

Patches title, lifecycle state, attributes, and outgoing edges of one existing strand. `--attr` **merges** into the existing attribute map — it does not replace it. The weaver applies the patch with SQLite `json_patch` (`skein.core.db/update-strand!`), so keys you pass are added or overwritten and keys you omit are left untouched. Because `--attr` values are always strings, `update` has no way to *remove* an attribute key: `--attr key=null` stores the literal string `"null"`, and `update` accepts no `--attributes` flag to carry a typed JSON `null` (the merge-patch value that would delete a key). Removing a key is a trusted-path operation — `skein.api.weaver.alpha/update` with `{:attributes {"key" nil}}`. Duplicate keys within one `--attr` set fail loudly, as on `add`. `--attributes` is not accepted here (it is `add`-only, old C7). Accepts `active|closed`; cannot set `replaced`. Returns the normalized strand.

#### `supersede` — BAT-C7

```
strand supersede <old-id> <replacement-id>
```

Delegates to the weaver supersession transaction: stores `replacement --supersedes--> old`, marks the old strand `replaced`, rewires incoming `depends-on` edges, and returns the normalized supersession result (old C9a).

#### `burn` — BAT-C8

```
strand burn <id>
```

Physically deletes one strand and its incident edges. Returns `{:burned [<id>] :count 1}`-shaped JSON (old C9b).

#### `weave` — BAT-C9

```
strand weave --pattern <name> --input <json-value-ref>
```

Applies an already-registered create-only weaver pattern to exactly one JSON input value and returns the pattern-created batch (`{:created [row…] :refs {…}}`, old C13a). Both flags are required. `--input` resolves a payload reference (or inline literal JSON) and is parsed **strictly** in the handler: empty, malformed, and trailing-value input all fail loudly with `{:code "pattern/input-invalid"}` before any mutation. A missing/blank `--pattern` fails in the parser; an unknown pattern fails `Pattern not found` carrying available names. The parsed object is keywordized before dispatch, matching the socket `weave` case.

> **Divergence (recorded):** old C13a specifies `weave --input` as `:parse
> :json`, but the parser's `:parse :json` uses `clojure.data.json/read-str`,
> which silently returns the first value and ignores trailing input — it cannot
> enforce "exactly one JSON value". So `--input` is declared `:type :string`
> and the handler parses it strictly (`read-single-json`) to preserve the loud
> empty/malformed/trailing behavior. Behavior equivalent; mechanism moved
> handler-side.

### 3.2 Reads

#### `show` — BAT-C10

```
strand show <id>
```

Returns one normalized strand by id, or JSON `null` when absent. `show` is the full-fidelity point read: every attribute value is returned verbatim, including values larger than the lean-read floor.

#### `list` — BAT-C11

```
strand list [--state active|closed|replaced] [--query name [--param key=value]…] [--limit N]
```

Lists strands. Optional `--state` filters lifecycle (`active|closed|replaced`; callers who care must pass it explicitly — old C11). Optional `--query` resolves a weaver-registered named query with repeatable string-valued `--param key=value`; `--state` overlays the query as an additional `[:= :state …]` clause. `--param` without `--query`, a blank `--query`, and unknown query params all fail loudly. Returns a JSON array of normalized strands. The result uses the lean read tier by default: any attribute value whose JSON-encoded UTF-8 length is above the fixed 1 KiB floor is replaced with `{"skein/omitted": true, "bytes": N}`; values at or below the floor pass through unchanged.

`list` is result-capped before attribute assembly. The default cap is 500 rows; trusted workspace config may set another cap with `skein.spools.batteries/set-read-limit!`, and one call may override it with `--limit N`. If more rows match, `list` fails with `read-limit-exceeded`, naming the total, the cap, and the remedies: narrow with `--query`/`--param`/`--state`, or pass explicit `--limit N`. Set `--limit` above the reported total for an intentional full read. Successful results are never truncated, and batteries has no pagination surface. There is no hydration flag; use `show <id>` to fetch a full row.

#### `ready` — BAT-C12

```
strand ready [--query name [--param key=value]…] [--limit N]
```

Returns strands with `state="active"` and no active `depends-on` blocker (old C10), optionally scoped to a named query's result set exactly as `list`. `ready` takes no `--state`. Like `list`, `ready` uses the lean read tier by default for large attribute values above the fixed 1 KiB floor and has no hydration flag; use `show <id>` for full fidelity. It uses the same default cap, trusted config override, `--limit N` call override, and loud `read-limit-exceeded` behavior as `list`.

#### `subgraph` — BAT-C13

```
strand subgraph <root-id> [--relation type]
```

Returns a relation-scoped graph traversed downward from the root over the declared acyclic relation named by `--relation` (weaver default `parent-of` when omitted — old C11a). Result is the string-keyed `{"root_ids" […] "strands" […] "edges" […]}` shape verbatim from the socket op.

#### `query` — BAT-C14 (registry introspection)

```
strand query list
strand query explain <name>
```

Read-only introspection of registered named queries (old C13aa/C13ab), moved from the deleted builtin to a batteries op (SPEC-002-D004.C12). `query` declares `list` and `explain` as parser-owned subcommands, so `strand help query` renders both verbs and missing/unknown subcommands fail in the parser with the available names before the handler runs. `list` takes no arguments and returns a JSON array of metadata entries (`name`, `params`, `referenced-params`) ordered by canonical name. `explain <name>` returns caller guidance for one query (`name`, `params`, `referenced-params`, `where`, `definition`, `where-form`, `definition-form`, `summary`). Both are projected JSON-safe (`json-safe-value`: keywords → names, symbols → strings, sets → sorted vectors), matching the old `query-list`/`query-explain` payloads. A missing/blank name on `explain` and unknown query names fail loudly.

#### `pattern` — BAT-C15 (registry introspection)

```
strand pattern list
strand pattern explain <name>
```

Read-only introspection of registered weave patterns (old C13b). `pattern` declares `list` and `explain` as parser-owned subcommands, so help rendering and missing/unknown-subcommand failures are handled by the blessed arg-spec parser. `list` takes no arguments and returns registered pattern metadata ordered by name. `explain <name>` returns input-spec guidance (`name`, `fn`, `input-spec`, `spec-form`, `summary`, and expanded `required`/`optional` key specs for a `clojure.spec.alpha/keys` input spec, plus optional `doc`). Registry names are canonical strings (e.g. `"task"`). A missing/blank name on `explain` and unknown pattern names fail loudly. Pattern *registration* stays a trusted config/REPL workflow — never exposed here.

## 4. Attribute and edge flag semantics

Reproducing old SPEC-002.C6–C8 (see SPEC-002-D004.R2):

- **BAT-C16:** `--attr key=value` — repeatable, highest-precedence string map.
  Values may be payload references. Duplicate keys within a single op's `--attr`
  set fail loudly (old C6e), enforced in the handler by recovering flag keys
  from the raw argv (the parser's `:map` type silently collapses duplicates).
- **BAT-C17:** `--attributes <ref>` — a payload reference to one JSON object of
  typed bulk attributes, lowest precedence, `add`-only. Cross-priority duplicate
  keys resolve by precedence (`--attr` wins); JSON value types are preserved.
- **BAT-C18:** `--edge edge-type:to-id` — repeatable outgoing edge on `add` /
  `update`; malformed specs fail loudly.
- **BAT-C19:** `--param key=value` — repeatable named-query parameter on
  `list` / `ready`; last-wins collapse (matching the old CLI's non-dedup
  `parseKV`), restricted to the query's declared param names.

## 5. Equivalence with the old public CLI

| Old clause (cli.md) | Batteries | Equivalence / difference |
|---|---|---|
| SPEC-002.C6 `add` | BAT-C5 | Equivalent. |
| SPEC-002.C6a `--attr` | BAT-C16 | Equivalent. |
| SPEC-002.C6b `--attr-file` | BAT-C2/C16 | Replaced by `--attr key=:payload/x`. |
| SPEC-002.C6c `--attr-stdin` | BAT-C2/C16 | Replaced by `--attr key=:stdin`. |
| SPEC-002.C6d `--attributes-stdin` | BAT-C2/C17 | Replaced by `--attributes :stdin` (JSON-object parse). |
| SPEC-002.C6e precedence + dup loudness | BAT-C16/C17 | Equivalent; the mutual-exclusion of two stdin sources dissolves — payloads are named, not a single stdin. |
| SPEC-002.C7 `update` | BAT-C6 | Equivalent; `--attributes` stays add-only. |
| SPEC-002.C8 `--edge` | BAT-C18 | Equivalent. |
| SPEC-002.C9 normalized JSON | BAT-C4 | Equivalent. |
| SPEC-002.C9a `supersede` | BAT-C7 | Equivalent. |
| SPEC-002.C9b `burn` | BAT-C8 | Equivalent. |
| SPEC-002.C10 `ready` | BAT-C12 | Equivalent. |
| SPEC-002.C11 `list`/`ready` queries + `--state` | BAT-C11/C12/C19 | Equivalent. |
| SPEC-002.C11a `graph subgraph` | BAT-C13 | Equivalent; the `graph` command group is gone — it is the root op `subgraph`. |
| SPEC-002.C13a `weave` | BAT-C9 | Behavior-equivalent; strict single-JSON parse moved handler-side (see divergence note). |
| SPEC-002.C13aa `query list` | BAT-C14 | Equivalent payload; now a registered read op, not a builtin. |
| SPEC-002.C13ab `query explain` | BAT-C14 | Equivalent payload; unknown-name loudness handler-side. |
| SPEC-002.C13b `pattern list`/`explain` | BAT-C15 | Equivalent payload; now a registered read op. |

Not part of batteries (out of scope here): `op help` → the core `help` op (SPEC-002-D004.C12), and `init` / `weaver *` → mill (SPEC-002-D004.C9).

## 6. See also

- [cli.md](../devflow/specs/cli.md) — CLI surface spec (old C6–C13 clauses).
- [repl-api.md](../devflow/specs/repl-api.md) — op registry and blessed parser.
- [workflow.md](./workflow.md) — the workflow spool whose patterns `weave`
  drives.
