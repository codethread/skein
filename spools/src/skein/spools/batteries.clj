(ns skein.spools.batteries
  "Shipped core strand command surface as parser-backed weaver ops.

  Batteries registers the everyday strand operations — add/update/show/supersede/
  burn/list/ready/subgraph plus the create-only `weave` op and the read-only
  `query`/`pattern` registry-introspection ops — as `register-op!` ops whose
  `:arg-spec` is parsed by `skein.api.cli.alpha`. Each op delegates to the same
  `skein.api.weaver.alpha` calls the JSON socket dispatch uses today and returns
  the same JSON shapes, so the ops are reachable through `strand <name>` at the
  CLI root. The namespace owns no module-level state:
  op handlers read the runtime from their invocation context (`:op/runtime`).

  Attribute/edge flag semantics reproduce old SPEC-002.C6–C11: `--attr key=value`
  is a repeatable, highest-precedence string map whose values may be payload
  references; `--attributes` references a JSON object of typed bulk attributes at
  lowest precedence; `--edge edge-type:to-id` adds outgoing edges. `--state`
  accepts `active|closed` for mutations and `active|closed|replaced` for `list`
  filtering."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [skein.api.current.alpha :as current]
            [skein.api.weaver.alpha :as api]
            [skein.core.query :as query])
  (:import [java.io PushbackReader StringReader]))

(def ^:private generic-states #{"active" "closed"})
(def ^:private readable-states #{"active" "closed" "replaced"})

(defn- validate-generic-state
  "Return state when it is active|closed, else fail loudly (mutations)."
  [state]
  (when-not (generic-states state)
    (throw (ex-info "Strand state must be active or closed"
                    {:state state :allowed (vec (sort generic-states))})))
  state)

(defn- validate-readable-state
  "Return state when it is active|closed|replaced, else fail loudly (list filter)."
  [state]
  (when-not (readable-states state)
    (throw (ex-info "Strand state must be active, closed, or replaced"
                    {:state state :allowed (vec (sort readable-states))})))
  state)

(defn- request-context
  "Build the mutation request context so hooks and events see the operation."
  [operation]
  {:request/source :json-socket
   :request/operation operation})

(defn- json-safe-value
  "Coerce query-introspection payloads (which carry EDN query expressions with
  keywords, symbols, and sets) into JSON-safe data, matching the JSON socket's
  `query-list`/`query-explain` projection so `strand query …` returns identical
  shapes to the old builtin."
  [value]
  (cond
    (nil? value) nil
    (or (string? value) (number? value) (boolean? value)) value
    (keyword? value) (subs (str value) 1)
    (symbol? value) (str value)
    (map? value) (into {} (map (fn [[k v]] [(json-safe-value k) (json-safe-value v)])) value)
    (sequential? value) (mapv json-safe-value value)
    (set? value) (mapv json-safe-value (sort-by pr-str value))
    :else (pr-str value)))

;; The blessed parser's :parse :json uses clojure.data.json/read-str, which
;; silently returns the first value and ignores trailing input, so it cannot
;; enforce old C13a's "exactly one JSON value" contract. weave reads --input as
;; a raw string and parses it strictly here instead: empty, malformed, and
;; trailing-value inputs all fail loudly before any mutation.
(defn- read-single-json
  "Read exactly one JSON value from s, failing loudly on empty, malformed, or
  trailing input (reproduces old SPEC-002.C13a stdin parsing weaver-side)."
  [s]
  (let [eof (Object.)
        ;; data.json/read unreads several characters of lookahead while parsing,
        ;; so the reader needs a pushback buffer wider than the default of 1.
        rdr (PushbackReader. (StringReader. s) 64)
        value (try (json/read rdr :eof-error? false :eof-value eof)
                   (catch Exception e
                     (throw (ex-info (str "weave --input is not valid JSON: " (ex-message e))
                                     {:code "pattern/input-invalid"}))))]
    (when (identical? value eof)
      (throw (ex-info "weave --input requires exactly one JSON value"
                      {:code "pattern/input-invalid"})))
    (when-not (identical? (json/read rdr :eof-error? false :eof-value eof) eof)
      (throw (ex-info "weave --input must contain exactly one JSON value"
                      {:code "pattern/input-invalid"})))
    value))

;; The blessed parser's :map flag silently collapses duplicate keys, but old
;; C6e requires duplicate keys within a single --attr priority to fail loudly.
;; The parser guarantees each --attr is followed by a well-formed key=value
;; token, so the flag keys can be recovered from the raw argv to enforce it.
(defn- attr-flag-keys [argv]
  (keep (fn [[flag token]]
          (when (= "--attr" flag)
            (subs token 0 (str/index-of token "="))))
        (partition 2 1 argv)))

(defn- check-attr-duplicates! [argv]
  (when-let [dup (some (fn [[k n]] (when (> n 1) k))
                       (frequencies (attr-flag-keys argv)))]
    (throw (ex-info (str "Duplicate attribute key in --attr: " dup) {:key dup}))))

(defn- attributes->map
  "Coerce a parsed --attributes value (a JSON object) into an attribute map."
  [attributes]
  (cond
    (nil? attributes) {}
    (map? attributes)
    (do (doseq [k (keys attributes)]
          (when (str/blank? k)
            (throw (ex-info "--attributes contains a blank attribute key" {:key k}))))
        attributes)
    :else (throw (ex-info "--attributes must reference a JSON object" {:value attributes}))))

(defn- parse-edges
  "Parse repeatable --edge edge-type:to-id specs into edge maps."
  [edge-specs]
  (mapv (fn [spec]
          (let [idx (str/index-of spec ":")]
            (when (or (nil? idx) (zero? idx) (= idx (dec (count spec))))
              (throw (ex-info "Malformed --edge; expected edge-type:to-id" {:edge spec})))
            {:type (subs spec 0 idx) :to (subs spec (inc idx))}))
        edge-specs))

(defn- handle-name
  "Coerce a query name string from op args into a registry lookup symbol."
  [query-name]
  (symbol (query/query-lookup-name query-name)))

(defn- validate-query-params
  "Restrict provided string params to a query's declared keyword names, failing
  loudly on unknown params (mirrors the JSON socket dispatch contract)."
  [query-def params]
  (let [declared (set (:params query-def))
        declared-names (set (map name declared))]
    (when-let [unknown (seq (remove declared-names (keys params)))]
      (throw (ex-info "Unknown query parameters"
                      {:params (vec unknown) :declared (vec declared)})))
    (into {} (keep (fn [k]
                     (when (contains? params (name k))
                       [k (get params (name k))]))
                   declared))))

(defn- run-named-query
  "Resolve a named query, validate params, overlay an optional state filter, and
  invoke the runtime list/ready fn exactly as the socket dispatch does."
  [rt query-fn query-name raw-params state]
  (let [query-def (api/resolve-query rt (handle-name query-name))
        params (validate-query-params query-def raw-params)
        query-def (if state
                    [:and (query/query-expr query-def params) [:= :state state]]
                    query-def)]
    (query-fn rt query-def params)))

;; --- op handlers ------------------------------------------------------------

(defn add-op
  "Create a strand with merged attributes, optional state, and outgoing edges."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [title state attr attributes edge]} (:op/args ctx)]
    (check-attr-duplicates! (:op/argv ctx))
    (let [merged (merge (attributes->map attributes) (or attr {}))
          edges (parse-edges edge)]
      (api/add rt
               (cond-> {:title title :attributes merged}
                 (some? state) (assoc :state (validate-generic-state state))
                 (seq edges) (assoc :edges edges))
               (request-context :add)))))

(defn update-op
  "Patch one strand's title, state, attributes, and outgoing edges."
  [ctx]
  (let [rt (:op/runtime ctx)
        args (:op/args ctx)
        {:keys [id title state attr edge]} args]
    (check-attr-duplicates! (:op/argv ctx))
    (let [edges (parse-edges edge)
          patch (cond-> {}
                  (seq edges) (assoc :edges edges)
                  (some? title) (assoc :title title)
                  (some? state) (assoc :state (validate-generic-state state))
                  (contains? args :attr) (assoc :attributes attr))]
      (api/update rt id patch (request-context :update)))))

(defn show-op
  "Return one normalized strand by id."
  [ctx]
  (api/show (:op/runtime ctx) (:id (:op/args ctx))))

(defn supersede-op
  "Replace one strand with another and return the supersession result."
  [ctx]
  (let [{:keys [old-id replacement-id]} (:op/args ctx)]
    (api/supersede (:op/runtime ctx) old-id replacement-id (request-context :supersede))))

(defn burn-op
  "Physically delete one strand by id and return the burn summary."
  [ctx]
  (api/burn-by-ids (:op/runtime ctx) [(:id (:op/args ctx))] (request-context :burn)))

(defn list-op
  "List strands, optionally filtered by lifecycle state and/or a named query."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [state query param]} (:op/args ctx)
        params (or param {})]
    (when state (validate-readable-state state))
    (if query
      (do (when (str/blank? query)
            (throw (ex-info "--query requires a non-empty name" {})))
          (run-named-query rt api/list query params state))
      (do (when (seq params)
            (throw (ex-info "--param requires --query" {})))
          (if state
            (api/list rt [:= :state state] {})
            (api/list rt))))))

(defn ready-op
  "List ready strands, optionally from the result set of a named query."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [query param]} (:op/args ctx)
        params (or param {})]
    (if query
      (do (when (str/blank? query)
            (throw (ex-info "--query requires a non-empty name" {})))
          (run-named-query rt api/ready query params nil))
      (do (when (seq params)
            (throw (ex-info "--param requires --query" {})))
          (api/ready rt)))))

(defn subgraph-op
  "Return a relation-scoped subgraph rooted at one strand."
  [ctx]
  (let [{:keys [root-id relation]} (:op/args ctx)
        {:keys [root-ids strands edges]}
        (api/subgraph (:op/runtime ctx) [root-id]
                      (cond-> {} relation (assoc :type relation)))]
    {"root_ids" root-ids
     "strands" strands
     "edges" edges}))

(defn weave-op
  "Apply a registered create-only weave pattern to one JSON input value."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [pattern input]} (:op/args ctx)]
    (api/weave! rt
                (handle-name pattern)
                (walk/keywordize-keys (read-single-json input))
                (request-context :weave))))

(defn query-op
  "Introspect registered named queries: list all metadata or explain one."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [subcommand] nm :name} (:op/args ctx)]
    (case subcommand
      "list" (do (when (some? nm)
                   (throw (ex-info "query list takes no arguments" {:unexpected nm})))
                 (json-safe-value (api/query-metadata rt)))
      "explain" (do (when (str/blank? nm)
                      (throw (ex-info "query explain requires a query name" {})))
                    (json-safe-value (api/query-explain rt (handle-name nm))))
      (throw (ex-info (str "Unknown query subcommand: " subcommand)
                      {:subcommand subcommand :allowed ["list" "explain"]})))))

(defn pattern-op
  "Introspect registered weave patterns: list all metadata or explain one."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [subcommand] nm :name} (:op/args ctx)]
    (case subcommand
      "list" (do (when (some? nm)
                   (throw (ex-info "pattern list takes no arguments" {:unexpected nm})))
                 (api/patterns rt))
      "explain" (do (when (str/blank? nm)
                      (throw (ex-info "pattern explain requires a pattern name" {})))
                    (api/pattern-explain rt (handle-name nm)))
      (throw (ex-info (str "Unknown pattern subcommand: " subcommand)
                      {:subcommand subcommand :allowed ["list" "explain"]})))))

;; --- arg-specs --------------------------------------------------------------

(def ^:private add-arg-spec
  {:op "add"
   :doc "Create a strand with attributes, lifecycle state, and outgoing edges."
   :flags {:state {:type :string
                   :doc "Lifecycle state: active (default) or closed."}
           :attr {:type :map
                  :doc "String attribute key=value; repeatable, highest precedence. Values may be payload references."}
           :attributes {:type :string
                        :parse :json
                        :doc "Payload reference to a JSON object of typed bulk attributes (lowest precedence)."}
           :edge {:type :string
                  :repeat? true
                  :doc "Outgoing edge edge-type:to-id; repeatable."}}
   :positionals [{:name :title :type :string :required? true :doc "Strand title."}]})

(def ^:private update-arg-spec
  {:op "update"
   :doc "Update one strand's title, state, attributes, and outgoing edges."
   :flags {:title {:type :string
                   :doc "New strand title."}
           :state {:type :string
                   :doc "Lifecycle state: active or closed (cannot set replaced)."}
           :attr {:type :map
                  :doc "String attribute key=value; repeatable, replaces attributes. Values may be payload references."}
           :edge {:type :string
                  :repeat? true
                  :doc "Outgoing edge edge-type:to-id; repeatable."}}
   :positionals [{:name :id :type :string :required? true :doc "Strand id."}]})

(def ^:private show-arg-spec
  {:op "show"
   :doc "Return one strand by id."
   :positionals [{:name :id :type :string :required? true :doc "Strand id."}]})

(def ^:private supersede-arg-spec
  {:op "supersede"
   :doc "Replace one strand with another, marking the old replaced and rewiring dependencies."
   :positionals [{:name :old-id :type :string :required? true :doc "Strand being replaced."}
                 {:name :replacement-id :type :string :required? true :doc "Replacement strand."}]})

(def ^:private burn-arg-spec
  {:op "burn"
   :doc "Physically delete one strand and its incident edges."
   :positionals [{:name :id :type :string :required? true :doc "Strand id."}]})

(def ^:private list-arg-spec
  {:op "list"
   :doc "List strands, optionally filtered by state and/or a named query."
   :flags {:state {:type :string
                   :doc "Filter by lifecycle state: active, closed, or replaced."}
           :query {:type :string
                   :doc "Weaver-registered named query."}
           :param {:type :map
                   :doc "Named-query parameter key=value; repeatable."}}})

(def ^:private ready-arg-spec
  {:op "ready"
   :doc "List ready strands, optionally from a named query result set."
   :flags {:query {:type :string
                   :doc "Weaver-registered named query."}
           :param {:type :map
                   :doc "Named-query parameter key=value; repeatable."}}})

(def ^:private subgraph-arg-spec
  {:op "subgraph"
   :doc "Return a relation-scoped subgraph rooted at a strand."
   :flags {:relation {:type :string
                      :doc "Declared acyclic relation type (defaults to parent-of)."}}
   :positionals [{:name :root-id :type :string :required? true :doc "Root strand id."}]})

(def ^:private weave-arg-spec
  {:op "weave"
   :doc "Apply a registered create-only weave pattern to one JSON input value."
   :flags {:pattern {:type :string
                     :required? true
                     :doc "Registered weave pattern name."}
           :input {:type :string
                   :required? true
                   :doc "Payload reference (e.g. :stdin) to exactly one JSON value for the pattern."}}})

(def ^:private query-arg-spec
  {:op "query"
   :doc "Introspect registered named queries: list all or explain one."
   :positionals [{:name :subcommand :type :string :required? true :doc "Subcommand: list or explain."}
                 {:name :name :type :string :doc "Query name (required for explain)."}]})

(def ^:private pattern-arg-spec
  {:op "pattern"
   :doc "Introspect registered weave patterns: list all or explain one."
   :positionals [{:name :subcommand :type :string :required? true :doc "Subcommand: list or explain."}
                 {:name :name :type :string :doc "Pattern name (required for explain)."}]})

(def ^:private op-registrations
  "Each shipped op: [op-name arg-spec hook-class handler-symbol]."
  [['add add-arg-spec :mutating 'skein.spools.batteries/add-op]
   ['update update-arg-spec :mutating 'skein.spools.batteries/update-op]
   ['show show-arg-spec :read 'skein.spools.batteries/show-op]
   ['supersede supersede-arg-spec :mutating 'skein.spools.batteries/supersede-op]
   ['burn burn-arg-spec :mutating 'skein.spools.batteries/burn-op]
   ['list list-arg-spec :read 'skein.spools.batteries/list-op]
   ['ready ready-arg-spec :read 'skein.spools.batteries/ready-op]
   ['subgraph subgraph-arg-spec :read 'skein.spools.batteries/subgraph-op]
   ['weave weave-arg-spec :mutating 'skein.spools.batteries/weave-op]
   ['query query-arg-spec :read 'skein.spools.batteries/query-op]
   ['pattern pattern-arg-spec :read 'skein.spools.batteries/pattern-op]])

(defn activate!
  "Register the batteries core strand ops into a weaver runtime.

  The no-arg arity registers into the active runtime for `use!`-style
  installation; the explicit-runtime arity is for tests and trusted callers."
  ([] (activate! (current/runtime)))
  ([rt]
   {:installed true
    :namespace 'skein.spools.batteries
    :ops (mapv (fn [[op-name arg-spec hook-class handler]]
                 (api/register-op! rt op-name
                                   {:doc (:doc arg-spec)
                                    :arg-spec arg-spec
                                    :hook-class hook-class}
                                   handler))
               op-registrations)}))
