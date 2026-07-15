
-----
# <a name="skein.api.weaver.alpha">skein.api.weaver.alpha</a>


Explicit-runtime API for the strand lifecycle, schema init, and the op registry.

  This namespace owns the primitives no domain namespace does: strand
  create/read/update (`add`, `update`, `supersede`, `archive!`/`unarchive!`,
  `show`, `list`/`list-lean`/`list-query`, `ready`/`ready-lean`/`ready-query`),
  database schema `init`, acyclic-relation declaration
  (`declare-acyclic-relation!`/`acyclic-relations`), and the CLI op registry
  (`register-op!`, `replace-op!`, `ops`, `resolve-op`, `op!`,
  `op-help-handler`, `help-alias-result`, `register-built-in-ops!`). Domain
  surfaces (events, views, hooks, graph queries, batch, patterns, scheduler,
  runtime config) each own their own alpha namespace.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument to every function here.




## <a name="skein.api.weaver.alpha/acyclic-relations">`acyclic-relations`</a>
``` clojure
(acyclic-relations runtime)
```
Function.

Return declared acyclic edge relation names.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L156-L159">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/add">`add`</a>
``` clojure
(add runtime strand)
(add runtime strand req-ctx)
```
Function.

Create a strand, enqueue a creation event, and return the normalized strand.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L39-L68">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/archive!">`archive!`</a>
``` clojure
(archive! runtime strand-id)
(archive! runtime strand-id keys)
```
Function.

Archive all attributes, or an explicit non-empty key set, for one strand.

  A later write to an archived key makes that key hot again. Untouched archived
  keys remain archived.

  This is a trusted in-process primitive only; it has no socket or CLI surface.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L182-L192">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/declare-acyclic-relation!">`declare-acyclic-relation!`</a>
``` clojure
(declare-acyclic-relation! runtime relation)
```
Function.

Declare an edge relation as acyclic for future graph writes.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L151-L154">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/help-alias-result">`help-alias-result`</a>
``` clojure
(help-alias-result entry argv envelope)
```
Function.

Return an op detail projection when argv/envelope form a help alias.

  The alias applies only to ops whose arg-spec declares `:subcommands`, argv is
  exactly one reserved help token, and the envelope carries no payloads. Returns
  nil when the invocation must flow through normal parsing and handler dispatch.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L463-L476">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/init">`init`</a>
``` clojure
(init runtime)
```
Function.

Initialize the runtime database schema.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L33-L37">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/list">`list`</a>
``` clojure
(list runtime)
(list runtime query-def params)
```
Function.

Return strands visible to `runtime`, optionally filtered by a query definition.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L211-L216">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L218-L228">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/list-query">`list-query`</a>
``` clojure
(list-query runtime query-name params)
```
Function.

Return strands matching a registered query definition.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L230-L233">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/op!">`op!`</a>
``` clojure
(op! runtime op-name argv)
(op! runtime op-name argv envelope)
```
Function.

Invoke a registered CLI operation with raw string argv from a root-level `strand <name>` invoke.

  The handler receives a context map with `:op/name`, `:op/argv`, `:op/runtime`,
  `:op/runtime-metadata`, and `:op/payloads` (defaulting to `{}`). The envelope
  arity threads any present `:cwd`, `:worktree-root`, `:git-common-dir`, and
  `:timeout` fields into `:op/cwd`, `:op/worktree-root`, `:op/git-common-dir`,
  and `:op/timeout`, and an envelope `:emit!` fn (supplied by the streaming
  socket transport for `:stream? true` ops) into `:op/emit!`. When the resolved
  op declares an `:arg-spec`, `:op/argv` and
  the attached payloads are parsed through `skein.api.cli.alpha/parse` and the
  result is supplied as `:op/args`; a parse failure throws before the handler
  runs. For subcommand ops, sole-token `help`, `-h`, or `--help` invocations
  with no payloads return the op's help detail instead of running the handler.
  Raw-envelope ops (no `:arg-spec`) receive the context unchanged, still
  carrying the raw `:op/payloads` map.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L478-L515">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/op-help-handler">`op-help-handler`</a>
``` clojure
(op-help-handler ctx)
```
Function.

Project the op registry as help.

  With no positional op name, return every registered op's summary (name, doc,
  provenance, stream?, deadline-class, hook-class) sorted by name. With one op
  name, return that op's full detail including the parser `explain` of its
  arg-spec (or a raw-envelope marker) and a JSON-safe explanation of any
  declared return shape. Unknown names fail loudly through `resolve-op`, which
  carries the available names.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L552-L568">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/ops">`ops`</a>
``` clojure
(ops runtime)
```
Function.

Return registered CLI operation entries for the current weaver runtime.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L444-L447">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/ready">`ready`</a>
``` clojure
(ready runtime)
(ready runtime query-def params)
```
Function.

Return ready strands for `runtime`, optionally filtered by a query definition.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L235-L240">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L242-L252">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/ready-query">`ready-query`</a>
``` clojure
(ready-query runtime query-name params)
```
Function.

Return ready strands from the result set of a registered query definition.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L254-L257">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/register-built-in-ops!">`register-built-in-ops!`</a>
``` clojure
(register-built-in-ops! runtime)
```
Function.

Install Skein-provided CLI operations into the runtime op registry.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L570-L577">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/register-op!">`register-op!`</a>
``` clojure
(register-op! runtime op-name fn-sym)
(register-op! runtime op-name opts fn-sym)
```
Function.

Register a trusted weaver-side CLI operation.

  Registered operations are invoked at the CLI root as `strand <name> [args...]`. The handler
  symbol must resolve to a function that accepts one context map (see `op!` for
  the context keys) and returns JSON-compatible data. The third positional
  argument is either a doc string or an op metadata map with keys `:doc`,
  `:arg-spec` (parser spec, structurally validated at registration), `:returns`
  (validated return-shape declaration),
  `:stream?` (default false), `:deadline-class`
  (`:standard`/`:unbounded`, defaulting to `:unbounded` for stream ops), and
  `:hook-class` (`:read`/`:mutating`, default `:mutating`); unknown keys fail
  loudly. Provenance (the registering namespace) is recorded from the handler
  symbol and must never be caller-supplied.

  Registering an already-registered name fails loudly, naming both the existing
  entry's provenance and the attempted registrant; use `replace-op!` to override
  deliberately. Registry contents live only for the current weaver lifetime and
  are normally installed from init.clj or a live REPL; `reload!` clears the
  registry before re-running init, so re-registration is collision-free.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L392-L424">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/replace-op!">`replace-op!`</a>
``` clojure
(replace-op! runtime op-name fn-sym)
(replace-op! runtime op-name opts fn-sym)
```
Function.

Replace an already-registered op, failing loudly when the name is absent.

  Same signature as `register-op!`. This is the deliberate override for a name
  that already exists; unlike `register-op!` it requires the name to be present.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L426-L442">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/resolve-op">`resolve-op`</a>
``` clojure
(resolve-op runtime op-name)
```
Function.

Return the registered CLI operation entry for `op-name`, or fail loudly.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L449-L456">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/show">`show`</a>
``` clojure
(show runtime id)
```
Function.

Return one normalized strand by id, or nil when absent.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L206-L209">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/supersede">`supersede`</a>
``` clojure
(supersede runtime old-id replacement-id)
(supersede runtime old-id replacement-id req-ctx)
```
Function.

Replace one strand with another and enqueue a supersession event.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L134-L149">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/unarchive!">`unarchive!`</a>
``` clojure
(unarchive! runtime strand-id)
(unarchive! runtime strand-id keys)
```
Function.

Unarchive all attributes, or an explicit non-empty key set, for one strand.

  A later write to an archived key has the same hot-data result for that key.
  Untouched archived keys remain archived.

  This is a trusted in-process primitive only; it has no socket or CLI surface.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L194-L204">Source</a></sub></p>

## <a name="skein.api.weaver.alpha/update">`update`</a>
``` clojure
(update runtime id patch)
(update runtime id patch req-ctx)
```
Function.

Update a strand and/or add edges atomically, then enqueue an update event.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/weaver/alpha.clj#L82-L123">Source</a></sub></p>
