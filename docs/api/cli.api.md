
-----
# <a name="skein.api.cli.alpha">skein.api.cli.alpha</a>


Blessed declarative argv parser for weaver ops (SPEC-003-D003.C1/C2).

  An arg-spec is minimal EDN data (no functions) describing an op's flags and
  positionals. `parse` turns an envelope's argv plus attached payloads into a
  data map of keyword arg names to parsed values, or throws a loud structured
  `ex-info`. `explain` renders the same arg-spec as JSON-safe help data for the
  `help <op>` projection. The namespace is pure: no registry, socket, or runtime
  coupling and no module-level state.

  Arg-spec shape:

    {:op        <keyword-or-string>   ; optional, echoed into errors and help
     :doc       <string>              ; optional op summary
     :flags     {<name-kw> <flag-spec>}
     :positionals [<positional-spec> ...]}  ; ordered, trailing may be variadic

  Multi-verb ops may instead declare one level of subcommands:

    {:op <keyword-or-string>
     :doc <string>
     :subcommands {<name-string> {:doc <string>
                                  :flags {<name-kw> <flag-spec>}
                                  :positionals [<positional-spec> ...]}}}

  Subcommand arg-specs route on the first argv token and return the nested
  parsed args merged with `:subcommand` set to the matched subcommand name.
  `:subcommand` is reserved and may not be declared as a nested flag or
  positional name.

  A flag-spec is a map with:

    :type      :string | :int | :boolean | :boolean-token | :map   ; default :string
                 - :string/:int/:boolean-token consume the following token as a typed value
                 - :boolean is a presence form (`--flag` -> true, no value)
                 - :boolean-token consumes `true` or `false` as a boolean value
                 - :map accumulates `--flag key=value` tokens into a {k v} map
    :repeat?   truthy -> repeatable, values collect into a vector (:string/:int)
    :required? truthy -> must appear
    :parse     :json | :jsonl   ; parse the resolved string value
    :doc       <string>

  A positional-spec is a map with :name (keyword), :type, :required?,
  :variadic? (trailing only, collects remaining tokens into a vector), :parse,
  and :doc.

  Payload references (SPEC-003-D003.C2): after argv parsing, any whole string
  value equal to `:stdin` or `:payload/<name>` resolves to the matching entry in
  the envelope payloads map. Matching is whole-value only (a value that merely
  contains `:stdin` as a substring is untouched). A reference with no matching
  payload throws; an attached payload that no reference consumed throws.




## <a name="skein.api.cli.alpha/explain">`explain`</a>
``` clojure
(explain arg-spec)
```
Function.

Render `arg-spec` as JSON-safe help data (args, types, docs, required flags,
  subcommands, and payload-parse declarations) for the `help <op>` projection.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/cli/alpha.clj#L477-L485">Source</a></sub></p>

## <a name="skein.api.cli.alpha/parse">`parse`</a>
``` clojure
(parse arg-spec argv)
(parse arg-spec argv payloads)
```
Function.

Parse `argv` against `arg-spec`, resolving payload references from `payloads`.

  Returns a map of keyword arg names to parsed values. For subcommand arg-specs,
  the first argv token selects the nested spec and the result includes the
  matched `:subcommand` string. Throws a structured `ex-info` (ex-data carries
  `:reason` plus the offending token/flag and the op) on any violation: unknown
  flags, missing required args, type violations, duplicate non-repeat flags,
  malformed key=value tokens, trailing unconsumed tokens, missing/unknown
  subcommands, dangling or unused payload references, and malformed
  :json/:jsonl payloads.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/cli/alpha.clj#L429-L445">Source</a></sub></p>

## <a name="skein.api.cli.alpha/reserved-subcommand-names">`reserved-subcommand-names`</a>




Subcommand names reserved for dispatch-level help aliases.

  The single source of truth: registration/parse/explain validation here and
  the weaver's dispatch-time help alias must agree on this set.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/cli/alpha.clj#L64-L69">Source</a></sub></p>

## <a name="skein.api.cli.alpha/validate!">`validate!`</a>
``` clojure
(validate! arg-spec)
```
Function.

Validate any parser arg-spec shape, returning it unchanged on success.

  Flat arg-specs validate their top-level flags and positionals. Subcommand
  arg-specs additionally enforce the one-level subcommand contract and reserved
  `:subcommand` result key. Throws structured `ex-info` on malformed specs so
  op registration fails before help or invocation can drift from the contract.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/cli/alpha.clj#L251-L268">Source</a></sub></p>
