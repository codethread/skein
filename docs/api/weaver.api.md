
-----
# <a name="skein.api.weaver.alpha">skein.api.weaver.alpha</a>


Explicit-runtime API for the strand lifecycle, schema init, and the op
  registry.

  This namespace owns the primitives no domain namespace does: strand
  create/read/update (`add!`, `update!`, `supersede!`,
  `archive-attributes!`/`unarchive-attributes!`, `show`,
  `list`/`list-lean`/`list-query`, and `ready`/`ready-lean`), database schema
  `init`, acyclic-relation declaration
  (`declare-acyclic-relation!`/`acyclic-relations`), and the CLI op registry
  (`register-op!`, `replace-op!`, `ops`, `resolve-op`, `op!`). Domain surfaces
  (events, hooks, graph queries, batch, patterns, scheduler, runtime config)
  each own their own alpha namespace.

  The module reads in that order. The mutating writes lead — each shows its own
  transaction/hook/event sequencing at the top level — followed by the acyclic
  relations, attribute archival, the read surface, and the op registry, whose
  `op!` is the dispatch entry point for a root-level `strand <name>` invoke.
  Registration validation and entry construction are plumbing in
  `skein.api.weaver.internal.op-entry`; the built-in `help` op and the
  help-alias projection live in `skein.core.weaver.help`, which both `op!` and
  the JSON socket consume.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument to every function here.




## <a name="skein.api.weaver.alpha/acyclic-relations">`acyclic-relations`</a>
``` clojure
(acyclic-relations runtime)
```
Function.

Return declared acyclic edge relation names.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L215-L218">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/add!">`add!`</a>
``` clojure
(add! runtime strand)
(add! runtime strand req-ctx)
```
Function.

Create a strand, enqueue a creation event, and return the normalized strand.

  The transaction normalizes attributes through the `:attributes/normalize`
  transform hooks, inserts the strand, applies its edges, and runs the
  `:strand/add-before-commit` validation hooks before committing; the
  `:strand/added` event is enqueued only after the commit succeeds.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L66-L102">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/archive-attributes!">`archive-attributes!`</a>
``` clojure
(archive-attributes! runtime strand-id)
(archive-attributes! runtime strand-id keys)
```
Function.

Archive all attributes, or an explicit non-empty key set, for one strand.

  Archived keys drop out of hot-tier reads (`list`, `ready`, and query
  execution) but stay visible to full point reads. A later write to an
  archived key makes that key hot again; untouched archived keys remain
  archived. Archiving a registered immutable key is rejected — it would hide
  write-once history.

  The strand id and key set are validated by the storage layer against
  `:skein.core.specs/attribute-key-set`, failing loudly on malformed or
  missing input; the result is checked here against
  `:skein.core.specs/attribute-archive-result`.

  This is a trusted in-process primitive only; it has no socket or CLI
  surface, runs no lifecycle hooks, and enqueues no event.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L226-L245">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/declare-acyclic-relation!">`declare-acyclic-relation!`</a>
``` clojure
(declare-acyclic-relation! runtime relation)
```
Function.

Declare an edge relation as acyclic for future graph writes.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L206-L209">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/init">`init`</a>
``` clojure
(init runtime)
```
Function.

Initialize the runtime database schema.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L54-L58">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/list">`list`</a>
``` clojure
(list runtime)
(list runtime query-def params)
```
Function.

Return strands visible to `runtime`, optionally filtered by a query definition.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L290-L295">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/list-lean">`list-lean`</a>
``` clojure
(list-lean runtime lean-byte-floor)
(list-lean runtime lean-byte-floor query-def params)
(list-lean runtime lean-byte-floor query-def params limit)
```
Function.

Return strands with oversized attributes replaced by descriptors.

  The optional limit arity is for the CLI/wire read surface; the trusted
  in-process arities remain unbounded by default.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L302-L314">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/list-query">`list-query`</a>
``` clojure
(list-query runtime query-name params)
```
Function.

Return strands matching a registered query definition.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L324-L327">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/op!">`op!`</a>
``` clojure
(op! runtime op-name argv)
(op! runtime op-name argv envelope)
```
Function.

Invoke a registered CLI operation with raw string argv from a root-level
  `strand <name>` invoke.

  The handler receives a context map with `:op/name`, `:op/argv`, `:op/runtime`,
  `:op/runtime-metadata`, and `:op/payloads` (defaulting to `{}`). The envelope
  arity threads any present `:cwd`, `:worktree-root`, `:git-common-dir`, and
  `:timeout` fields into `:op/cwd`, `:op/worktree-root`, `:op/git-common-dir`,
  and `:op/timeout`, and an envelope `:emit!` fn (supplied by the streaming
  socket transport for `:stream? true` ops) into `:op/emit!`. When the resolved
  op declares an `:arg-spec`, `:op/argv` and the attached payloads are parsed
  through `skein.api.cli.alpha/parse` and the result is supplied as `:op/args`;
  a parse failure throws before the handler runs. A clean trailing `--help`/`-h`
  flag (the final argv token, no other flags, no payloads) is rewritten to the
  op's help projection instead of running the handler, for every op class — the
  op detail, or a verb's sliced node when a verb token precedes the flag; retired
  `<op> help`/`about`/`prime` sugar and malformed `--help` shapes redirect loudly
  (DELTA-Dtf-002.CC3). Subcommand map results receive a
  canonical `:operation` label containing the registered op name and full
  resolved path, including a nested `:action`. A handler-supplied `:operation`
  equal to the derived label is preserved; any other value, including explicit
  nil, fails loudly with the expected and actual labels. Raw-envelope ops (no
  `:arg-spec`) receive the context unchanged, still carrying the raw
  `:op/payloads` map.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L457-L516">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/ops">`ops`</a>
``` clojure
(ops runtime)
```
Function.

Return registered CLI operation entries for the current weaver runtime.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L434-L437">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/ready">`ready`</a>
``` clojure
(ready runtime)
(ready runtime query-def params)
```
Function.

Return ready strands for `runtime`, optionally filtered by a query definition.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L333-L338">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/ready-lean">`ready-lean`</a>
``` clojure
(ready-lean runtime lean-byte-floor)
(ready-lean runtime lean-byte-floor query-def params)
(ready-lean runtime lean-byte-floor query-def params limit)
```
Function.

Return ready strands with oversized attributes replaced by descriptors.

  The optional limit arity is for the CLI/wire read surface; the trusted
  in-process arities remain unbounded by default.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L345-L357">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/register-op!">`register-op!`</a>
``` clojure
(register-op! runtime op-name fn-sym)
(register-op! runtime op-name opts fn-sym)
```
Function.

Register a trusted weaver-side CLI operation.

  Registered operations are invoked at the CLI root as `strand <name>
  [args...]`. The handler symbol must resolve to a function that accepts one
  context map (see `op!` for the context keys) and returns JSON-compatible data.
  The third positional argument is either a doc string or an op metadata map
  with keys `:doc`, `:arg-spec` (parser spec, structurally validated at
  registration), `:returns` (validated return-shape declaration), `:stream?`
  (default false), `:deadline-class` (`:standard`/`:unbounded`, defaulting to
  `:unbounded` for stream ops), and `:hook-class` (`:read`/`:mutating`, default
  `:mutating`); unknown keys fail loudly. Provenance (the registering namespace)
  is recorded from the handler symbol and must never be caller-supplied.

  Registering an already-registered name fails loudly, naming both the existing
  entry's provenance and the attempted registrant; use `replace-op!` to override
  deliberately. Registry contents live only for the current weaver lifetime and
  are normally installed from init.clj or a live REPL; `reload!` clears the
  registry before re-running init, so re-registration is collision-free.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L369-L401">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/replace-op!">`replace-op!`</a>
``` clojure
(replace-op! runtime op-name fn-sym)
(replace-op! runtime op-name opts fn-sym)
```
Function.

Replace an already-registered op, failing loudly when the name is absent.

  Same signature as `register-op!`. This is the deliberate override for a name
  that already exists; unlike `register-op!` it requires the name to be present.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L409-L426">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/resolve-op">`resolve-op`</a>
``` clojure
(resolve-op runtime op-name)
```
Function.

Return the registered CLI operation entry for `op-name`, or fail loudly.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L443-L451">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/show">`show`</a>
``` clojure
(show runtime id)
```
Function.

Return one normalized strand by id, or nil when absent.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L281-L284">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/supersede!">`supersede!`</a>
``` clojure
(supersede! runtime old-id replacement-id)
(supersede! runtime old-id replacement-id req-ctx)
```
Function.

Replace one strand with another and enqueue a supersession event.

  The transaction performs the supersession and runs the
  `:strand/supersede-before-commit` validation hooks with the supersession
  context; the `:strand/superseded` event is enqueued only after the commit
  succeeds.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L173-L195">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/unarchive-attributes!">`unarchive-attributes!`</a>
``` clojure
(unarchive-attributes! runtime strand-id)
(unarchive-attributes! runtime strand-id keys)
```
Function.

Mark all attributes, or an explicit non-empty key set, hot again for one
  strand.

  Restores hot-tier visibility without changing any value. Untouched archived
  keys remain archived. Unarchiving a registered immutable key is legal — it
  is the recovery path for immutable rows archived before enforcement existed.

  The strand id and key set are validated by the storage layer against
  `:skein.core.specs/attribute-key-set`, failing loudly on malformed or
  missing input; the result is checked here against
  `:skein.core.specs/attribute-archive-result`.

  This is a trusted in-process primitive only; it has no socket or CLI
  surface, runs no lifecycle hooks, and enqueues no event.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L253-L271">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/update!">`update!`</a>
``` clojure
(update! runtime id patch)
(update! runtime id patch req-ctx)
```
Function.

Update a strand and/or add edges atomically, then enqueue an update event.

  Rejects unknown patch fields up front. The transaction reads the current
  strand (failing loudly when absent), normalizes any supplied attributes
  through the `:attributes/normalize` transform hooks, applies edges, writes the
  changed columns, and runs the `:strand/update-before-commit` validation hooks;
  the `:strand/updated` event is enqueued only after the commit succeeds.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L110-L165">Source</a></sub></p>
