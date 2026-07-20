(ns skein.core.weaver.help
  "Built-in `help` op wiring and the canonical help-envelope projection.

  This surface is core, not alpha: it owns the reserved help-alias token check
  (`help-alias-result`), the canonical help projection (`op-envelope`,
  `verb-envelope`, `op-catalog`), the built-in `help` op handler
  (`op-help-handler`), and its registrar (`register-built-in-ops!`). It lives in
  core because both `skein.api.weaver.alpha` (the alias check inside `op!`) and
  `skein.core.weaver.socket` (invoke-path dispatch) consume `help-alias-result`,
  and core must not require an alpha namespace.

  Help is not hand-authored: it is one declared, versioned schema, uniformly
  projected (SPEC-002.C39, DELTA-Dtf-001.D1). Every response is the response
  envelope `{schema-version, operation, source, glossary, node}` (the no-arg
  catalog is the same schema family, `{schema-version, ops[]}`), and `node` is
  the uniform fractal node (`{name, doc, invocation, returns, use-when, notes,
  failure-modes, children}`) at every depth. The projection normalizes today's
  registry data — the op envelope, the arg-spec `explain` (SPEC-003.C64/C65), and
  the per-case return-shape `explain` (SPEC-003.C60b) — into that schema; nothing
  here re-models or hand-writes usage.

  `source` (op-wide handler pointer, resolved later) is `null` here and
  `glossary` (referenced-term closure) is empty here; both are filled by later
  discovery-tier tasks without reshaping the envelope. The load graph pins its
  other reaches: `skein.core.weaver.access` requires the runtime and socket
  namespaces back to this one, so the registry read uses the runtime map's
  `:op-registry` key directly, and everything below `access` in the graph can
  only reach the alpha module dynamically — the two `requiring-resolve` calls
  here (`register-op!`, `resolve-op`) are call-time reaches into the public
  surface, the same idiom the socket transport uses for `op!`."
  (:require [skein.api.cli.alpha :as cli]
            [skein.api.format.alpha :as format-alpha]
            [skein.api.return-shape.alpha :as return-shape]))

(def ^:private schema-version
  "Positive integer versioning the help-schema contract itself.

  Bumps only when the envelope/node shape changes; independent of release/build
  identity and of `protocol_version` (DELTA-Dtf-001.D3)."
  1)

(defn- registered-op-entries
  "Return `runtime`'s registered op entries sorted by canonical name.

  Reads the runtime map's `:op-registry` atom directly: the blessed accessor
  (`skein.core.weaver.access/op-registry`) sits above this namespace in the
  load graph, so requiring it would close a cycle."
  [runtime]
  (mapv val (sort-by key @(:op-registry runtime))))

(defn- resolve-entry
  "Resolve the registry entry for `op-name` (a raw positional string).

  A call-time reach into the alpha surface (everything below `access` in the
  load graph can only reach it dynamically); the loud not-found error carries
  the available names."
  [runtime op-name]
  ((requiring-resolve 'skein.api.weaver.alpha/resolve-op)
   runtime (symbol op-name)))

(defn- operation-facts
  "Project the op-wide envelope metadata for one registry entry.

  These are the facts that are not per-verb — `name` and the registry envelope
  metadata — so they live in the response envelope, never on the recursive node
  (DELTA-Dtf-001.CC1). `raw-envelope` marks an op that declares no arg-spec."
  [entry]
  {:name (:name entry)
   :provenance (str (:provenance entry))
   :stream? (:stream? entry)
   :deadline-class (name (:deadline-class entry))
   :hook-class (name (:hook-class entry))
   :raw-envelope (not (contains? entry :arg-spec))})

(defn- node
  "Assemble one uniform fractal node.

  Every key is always present with the defined empty/null semantics
  (DELTA-Dtf-001.CC2), so one recursive renderer needs no per-level branches.
  Authored annotations (`use-when`/`notes`/`failure-modes`) are empty here; a
  later task fills them from each arg-spec node's annotation sub-map."
  [name doc invocation returns children]
  {:name name
   :doc (or doc "")
   :invocation invocation
   :returns returns
   :use-when []
   :notes []
   :failure-modes []
   :children children})

(defn- routed-return-explain
  "Render the return-shape `explain` for one subcommand's routed case, or nil.

  A subcommand op may declare `:returns {:subcommands {..}}`; the case for
  `subcommand` projects to that child node's `returns`. Ops that declare no
  routed returns yield nil (SPEC-003.C60b)."
  [returns subcommand]
  (when (and (map? returns) (contains? returns :subcommands))
    (when-let [return-case (get (:subcommands returns) subcommand)]
      (return-shape/explain return-case))))

(defn- child-node
  "Project one declared subcommand into a child node of the same fractal shape."
  [returns {:keys [name doc flags positionals]}]
  (node name doc
        {:mode "declared" :flags flags :positionals positionals}
        (routed-return-explain returns name)
        []))

(defn- node-doc
  "The declared doc for an op's root node.

  Prefers the arg-spec's doc (the node is that arg-spec's projection,
  DELTA-Dtf-003.CC1), falling back to the op doc; `node` renders `nil` as `\"\"`."
  [entry explained]
  (or (:doc explained) (:doc entry)))

(defn- op-node
  "Project one op registry entry into its root fractal node.

  A flat op yields a root node carrying its own flags/positionals with empty
  `children`; a subcommand op yields a root node with empty invocation
  flags/positionals and one child per declared subcommand (each the same shape
  with its routed return case); a raw-envelope op (no declared arg-spec) yields a
  `raw-envelope` root node. Op-wide facts stay in the envelope, never here."
  [entry]
  (let [returns (:returns entry)
        node-returns (when (contains? entry :returns) (return-shape/explain returns))
        arg-spec (:arg-spec entry)
        explained (when arg-spec (cli/explain arg-spec))
        doc (node-doc entry explained)]
    (cond
      (nil? arg-spec)
      (node (:name entry) doc
            {:mode "raw-envelope" :flags [] :positionals []}
            node-returns
            [])

      (:subcommands explained)
      (node (:name entry) doc
            {:mode "declared" :flags [] :positionals []}
            nil
            (mapv #(child-node returns %) (:subcommands explained)))

      :else
      (node (:name entry) doc
            {:mode "declared"
             :flags (:flags explained)
             :positionals (:positionals explained)}
            node-returns
            []))))

(defn- summary-node
  "Project the shallow catalog node for one op (DELTA-Dtf-001.CC3).

  `name` and `doc` populated; `invocation` at its declared mode with empty
  flags/positionals; `returns` null, annotations `[]`, `children` `[]`."
  [entry]
  (let [explained (when (:arg-spec entry) (cli/explain (:arg-spec entry)))]
    (node (:name entry) (node-doc entry explained)
          {:mode (if (:arg-spec entry) "declared" "raw-envelope")
           :flags [] :positionals []}
          nil
          [])))

(defn- envelope
  "Build a canonical detail help envelope carrying `node`.

  `source` (op-wide handler pointer) is `null` here and `glossary`
  (referenced-term closure) is empty here; both are filled by later tasks without
  reshaping the envelope (DELTA-Dtf-001.CC1)."
  [entry node]
  {:schema-version schema-version
   :operation (operation-facts entry)
   :source nil
   :glossary {}
   :node node})

(defn- op-envelope
  "Build the canonical detail help envelope for one op registry entry."
  [entry]
  (envelope entry (op-node entry)))

(defn- verb-envelope
  "Build the detail envelope sliced to one subcommand verb's node.

  Op-wide facts (`operation`, `source`, `glossary`) are unchanged; only `node`
  narrows to the named verb's child node, which is the same fractal shape
  (DELTA-Dtf-001.CC2). An unknown verb fails loudly with the available verbs."
  [entry verb]
  (let [root (op-node entry)
        child (some #(when (= verb (:name %)) %) (:children root))]
    (when-not child
      (throw (ex-info "Help verb not found"
                      {:operation (:name entry)
                       :verb verb
                       :available-verbs (mapv :name (:children root))})))
    (envelope entry child)))

(defn- catalog-entry
  "Project one op registry entry into a shallow catalog envelope entry.

  `{operation, source, node}` with the same structure as the detail envelope
  but a summary node (DELTA-Dtf-001.CC3); op-wide facts stay in `operation` and
  `source`, never merged onto the node."
  [entry]
  {:operation (operation-facts entry)
   :source nil
   :node (summary-node entry)})

(defn- op-catalog
  "Build the versioned no-arg catalog `{schema-version, ops[]}` for `runtime`."
  [runtime]
  {:schema-version schema-version
   :ops (mapv catalog-entry (registered-op-entries runtime))})

(defn help-alias-result
  "Return the canonical help envelope when argv/envelope form a help alias.

  The alias applies only to ops whose arg-spec declares `:subcommands`, argv is
  exactly one reserved help token, and the envelope carries no payloads. Returns
  nil when the invocation must flow through normal parsing and handler dispatch.

  The reserved-token set is `skein.api.cli.alpha/reserved-subcommand-names`, the
  single source of truth so validation and dispatch cannot drift."
  [entry argv envelope]
  (let [argv (vec argv)
        payloads (or (:payloads envelope) {})]
    (when (and (contains? (:arg-spec entry) :subcommands)
               (= 1 (count argv))
               (contains? cli/reserved-subcommand-names (first argv))
               (empty? payloads))
      (op-envelope entry))))

(defn op-help-handler
  "Project the op registry as canonical help.

  With no positional op name, return the versioned catalog `{schema-version,
  ops[]}` of shallow per-op envelopes sorted by name. With one op name, return
  that op's detail envelope `{schema-version, operation, source, glossary,
  node}`. With an op name and a verb, slice `node` to that verb's child node.
  Unknown op names fail loudly through `resolve-op` (carrying available names);
  an unknown verb fails loudly carrying the available verbs."
  [ctx]
  (let [runtime (:op/runtime ctx)
        {:keys [op verb]} (:op/args ctx)]
    (cond
      (and op verb) (verb-envelope (resolve-entry runtime op) verb)
      op (op-envelope (resolve-entry runtime op))
      :else (op-catalog runtime))))

(def ^:private help-arg-spec
  "Arg-spec for the built-in `help` op: an optional op name and verb.

  This makes `help` the first parser-consuming op, so `op!` parses its argv and
  supplies the resolved positionals as `:op/args`. A trailing `verb` slices the
  detail envelope's node to one subcommand (DELTA-Dtf-001.CC2)."
  {:op "help"
   :doc "Show the help catalog, one op's detail envelope, or one verb's node."
   :positionals [{:name :op
                  :type :string
                  :required? false
                  :doc (format-alpha/reflow
                        "|Optional op name; when given, return that op's detail
                         |envelope instead of the catalog.")}
                 {:name :verb
                  :type :string
                  :required? false
                  :doc (format-alpha/reflow
                        "|Optional subcommand name; slices the detail envelope's
                         |node to that verb.")}]})

(def ^:private operation-return-shape
  "Declared return shape for the op-wide `operation` map (DELTA-Dtf-001.CC1)."
  {:type :map
   :required {:name :string
              :provenance :string
              :stream? :boolean
              :deadline-class :string
              :hook-class :string
              :raw-envelope :boolean}})

(def ^:private node-return-shape
  "Declared return shape for the uniform fractal `node` (DELTA-Dtf-001.CC2).

  `returns` and `children` items are `:json` here: the per-case return-shape
  explain and the recursive child nodes are arbitrary JSON-safe data, not a
  fixed leaf shape."
  {:type :map
   :required {:name :string
              :doc :string
              :invocation {:type :map
                           :required {:mode :string
                                      :flags {:type :collection :items :json}
                                      :positionals {:type :collection :items :json}}}
              :returns :json
              :use-when {:type :collection :items :string}
              :notes {:type :collection :items :string}
              :failure-modes {:type :collection :items :string}
              :children {:type :collection :items :json}}})

(def ^:private help-return-shape
  "Declared return shape for the `help` op.

  One shape covers both the detail envelope `{schema-version, operation, source,
  glossary, node}` and the versioned no-arg catalog `{schema-version, ops[]}` —
  the same schema family (DELTA-Dtf-001.CC1/CC3). `source` is `:json` so it
  accepts both `null` and a `{file, line}` map."
  {:type :map
   :required {:schema-version :integer}
   :optional {:operation operation-return-shape
              :source :json
              :glossary {:type :map :extra :json}
              :node node-return-shape
              :ops {:type :collection
                    :items {:type :map
                            :required {:operation operation-return-shape
                                       :source :json
                                       :node node-return-shape}}}}})

(defn register-built-in-ops!
  "Install Skein-provided CLI operations into the runtime op registry.

  Resolves `register-op!` at call time: this namespace sits below `access` in
  the load graph, so a static require of the alpha module would close a cycle —
  the same constraint that made `skein.core.weaver.runtime` reach the previous
  alpha registrar dynamically."
  [runtime]
  ((requiring-resolve 'skein.api.weaver.alpha/register-op!)
   runtime 'help
   {:doc (:doc help-arg-spec)
    :hook-class :read
    :arg-spec help-arg-spec
    :returns help-return-shape}
   'skein.core.weaver.help/op-help-handler))
