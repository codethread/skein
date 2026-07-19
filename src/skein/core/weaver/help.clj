(ns skein.core.weaver.help
  "Built-in `help` op wiring and the dispatch-level help-alias projection.

  This surface is core, not alpha: it owns the reserved help-alias token check
  (`help-alias-result`), the op-summary/op-detail help projections, the built-in
  `help` op handler (`op-help-handler`), and its registrar
  (`register-built-in-ops!`). It lives in core because both `skein.api.weaver.alpha`
  (the alias check inside `op!`) and `skein.core.weaver.socket` (invoke-path
  dispatch) consume `help-alias-result`, and core must not require an alpha
  namespace.

  It renders help detail through the `skein.api.cli.alpha`/
  `skein.api.return-shape.alpha` explain surfaces. The load graph pins its
  other reaches: `skein.core.weaver.access` requires the runtime and socket
  namespaces back to this one, so the registry read uses the runtime map's
  `:op-registry` key directly, and everything below `access` in the graph can
  only reach the alpha module dynamically — the two `requiring-resolve` calls
  here (`register-op!`, `resolve-op`) are call-time reaches into the public
  surface, the same idiom the socket transport uses for `op!`."
  (:require [skein.api.cli.alpha :as cli]
            [skein.api.return-shape.alpha :as return-shape]))

(defn- registered-op-entries
  "Return `runtime`'s registered op entries sorted by canonical name.

  Reads the runtime map's `:op-registry` atom directly: the blessed accessor
  (`skein.core.weaver.access/op-registry`) sits above this namespace in the
  load graph, so requiring it would close a cycle."
  [runtime]
  (mapv val (sort-by key @(:op-registry runtime))))

(defn- op-summary
  "Project one op registry entry to its help-listing summary."
  [entry]
  (cond-> {:name (:name entry)
           :provenance (str (:provenance entry))
           :stream? (:stream? entry)
           :deadline-class (name (:deadline-class entry))
           :hook-class (name (:hook-class entry))}
    (:doc entry) (assoc :doc (:doc entry))))

(defn- op-detail
  "Project one op registry entry to its full help detail.

  Arg-spec ops carry the parser `explain` rendering; raw-envelope ops carry a
  `:raw-envelope true` marker instead. Declared return shapes carry the
  JSON-safe return-shape `explain` rendering."
  [entry]
  (cond-> (merge (op-summary entry)
                 (if-let [arg-spec (:arg-spec entry)]
                   {:arg-spec (cli/explain arg-spec)}
                   {:raw-envelope true}))
    (contains? entry :returns) (assoc :returns (return-shape/explain (:returns entry)))))

(defn help-alias-result
  "Return an op detail projection when argv/envelope form a help alias.

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
      (op-detail entry))))

(defn op-help-handler
  "Project the op registry as help.

  With no positional op name, return every registered op's summary (name, doc,
  provenance, stream?, deadline-class, hook-class) sorted by name. With one op
  name, return that op's full detail including the parser `explain` of its
  arg-spec (or a raw-envelope marker) and a JSON-safe explanation of any
  declared return shape. Unknown names fail loudly through `resolve-op`, which
  carries the available names."
  [ctx]
  (let [runtime (:op/runtime ctx)
        op-name (:op (:op/args ctx))]
    (if op-name
      ;; The parsed positional is a raw string; resolve-op keys on simple
      ;; symbols/keywords, and its loud not-found error carries available names.
      ;; A call-time reach into the public surface: everything below `access`
      ;; in the load graph can only reach the alpha module dynamically.
      (op-detail ((requiring-resolve 'skein.api.weaver.alpha/resolve-op)
                  runtime (symbol op-name)))
      {:ops (mapv op-summary (registered-op-entries runtime))})))

(def ^:private help-arg-spec
  "Arg-spec for the built-in `help` op: an optional positional op name.

  This makes `help` the first parser-consuming op, so `op!` parses its argv and
  supplies the resolved positional as `:op/args`."
  {:op "help"
   :doc "List registered weaver ops, or show one op's full detail."
   :positionals [{:name :op
                  :type :string
                  :required? false
                  :doc "Optional op name; when given, return that op's full detail instead of the listing."}]})

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
    :returns {:type :map
              :optional {:ops {:type :collection
                               :items {:type :map
                                       :required {:name :string
                                                  :doc :string
                                                  :provenance :string
                                                  :stream? :boolean
                                                  :deadline-class :string
                                                  :hook-class :string}}}
                         :name :string
                         :doc :string
                         :provenance :string
                         :stream? :boolean
                         :deadline-class :string
                         :hook-class :string
                         :arg-spec {:type :map :extra :json}
                         :raw-envelope :boolean
                         :returns :json}}}
   'skein.core.weaver.help/op-help-handler))
