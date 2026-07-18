(ns skein.api.cli.alpha
  "Blessed declarative argv parser for weaver ops (SPEC-003-D003.C1/C2).

  An arg-spec is minimal EDN data (no functions) describing an op's flags and
  positionals. `parse` turns an envelope's argv plus attached payloads into a
  data map of keyword arg names to parsed values, or throws a loud structured
  `ex-info`. `explain` renders the same arg-spec as JSON-safe help data for the
  `help <op>` projection. The namespace is pure: no registry, socket, or runtime
  coupling and no module-level state.

  Arg-spec shape:

    {:op <keyword-or-string> ; optional, echoed into errors and help
     :doc <string>           ; optional op summary
     :flags {<name-kw> <flag-spec>}
     :positionals [<positional-spec> ...]} ; trailing may be variadic

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

    :type      :string | :int | :boolean | :boolean-token | :map
               - :string/:int/:boolean-token consume a typed value
               - :boolean is a presence form (`--flag` -> true, no value)
               - :map accumulates `--flag key=value` tokens into a map
    :repeat?   truthy -> repeatable, values collect into a vector
    :required? truthy -> must appear
    :parse     :json | :jsonl ; parse the resolved string value
    :doc       <string>

  A positional-spec is a map with :name (keyword), :type, :required?,
  :variadic? (trailing only, collects remaining tokens into a vector), :parse,
  and :doc.

  Payload references (SPEC-003-D003.C2): after argv parsing, any whole string
  value equal to `:stdin` or `:payload/<name>` resolves to the matching entry in
  the envelope payloads map. Matching is whole-value only (a value that merely
  contains `:stdin` as a substring is untouched). A reference with no matching
  payload throws; an attached payload that no reference consumed throws."
  (:require [skein.api.cli.internal.help :as help]
            [skein.api.cli.internal.parsing :as parsing]
            [skein.api.cli.internal.shared :as shared]
            [skein.api.cli.internal.validation :as validation]))

(def reserved-subcommand-names
  "Subcommand names reserved for dispatch-level help aliases.

  The single source of truth: registration/parse/explain validation here and
  the weaver's dispatch-time help alias must agree on this set."
  #{"help" "-h" "--help"})

(defn validate!
  "Validate any parser arg-spec shape, returning it unchanged on success.

  Flat arg-specs validate their top-level flags and positionals. Subcommand
  arg-specs additionally enforce the one-level subcommand contract and reserved
  `:subcommand` result key. Throws structured `ex-info` on malformed specs so
  op registration fails before help or invocation can drift from the contract."
  [arg-spec]
  (when-not (map? arg-spec)
    (shared/fail! :invalid-arg-spec
                  "Arg-spec must be a map"
                  {:value arg-spec}))
  (if (contains? arg-spec :subcommands)
    (validation/validate-subcommands! arg-spec reserved-subcommand-names)
    (let [op (:op arg-spec)]
      (validation/validate-flags! op nil (:flags arg-spec))
      (validation/validate-positionals! op nil (:positionals arg-spec))
      arg-spec)))

(defn parse
  "Parse `argv` against `arg-spec`, resolving payload references from `payloads`.

  Returns a map of keyword arg names to parsed values. For subcommand arg-specs,
  the first argv token selects the nested spec and the result includes the
  matched `:subcommand` string. Throws a structured `ex-info` (ex-data carries
  `:reason` plus the offending token/flag and the op) on any violation: unknown
  flags, missing required args, type violations, duplicate non-repeat flags,
  malformed key=value tokens, trailing unconsumed tokens, missing/unknown
  subcommands, dangling or unused payload references, and malformed
  :json/:jsonl payloads."
  ([arg-spec argv]
   (parse arg-spec argv {}))
  ([arg-spec argv payloads]
   (let [arg-spec (validate! arg-spec)
         argv (vec argv)]
     (if (contains? arg-spec :subcommands)
       (let [op (:op arg-spec)
             subcommands (:subcommands arg-spec)
             available (vec (sort (keys subcommands)))
             subcommand (first argv)]
         (when-not subcommand
           (shared/fail! :missing-subcommand
                         "Missing subcommand"
                         {:op op :available-subcommands available}))
         (when-not (contains? subcommands subcommand)
           (shared/fail! :unknown-subcommand
                         (str "Unknown subcommand " (pr-str subcommand))
                         {:op op
                          :token subcommand
                          :available-subcommands available}))
         (assoc (parsing/parse-flat (assoc (get subcommands subcommand) :op op)
                                    (subvec argv 1)
                                    payloads)
                :subcommand subcommand))
       (parsing/parse-flat arg-spec argv payloads)))))

(defn explain
  "Render `arg-spec` as JSON-safe help data.

  Includes arguments, types, docs, required flags, subcommands, and payload-parse
  declarations for the `help <op>` projection."
  [arg-spec]
  (let [arg-spec (validate! arg-spec)]
    (if (contains? arg-spec :subcommands)
      (assoc (help/explain-flat (dissoc arg-spec :subcommands))
             :subcommands
             (mapv help/render-subcommand
                   (sort-by key (:subcommands arg-spec))))
      (help/explain-flat arg-spec))))
