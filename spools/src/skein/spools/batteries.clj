(ns skein.spools.batteries
  "Shipped core strand command surface as parser-backed weaver ops.

  Batteries registers the everyday strand operations — add/update/show/supersede/
  burn/list/ready/subgraph plus the create-only `weave` op and the read-only
  `query`/`pattern` registry-introspection ops — as `register-op!` ops whose
  `:arg-spec` is parsed by `skein.api.cli.alpha`. Each op delegates to the same
  `skein.api.*.alpha` calls the JSON socket dispatch uses today and returns
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
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [skein.api.current.alpha :as current]
            [skein.api.graph.alpha :as graph]
            [skein.api.notes.alpha :as notes-api]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.runtime.alpha :as runtime-api]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as api]
            [skein.core.query :as query]
            [skein.core.specs :as specs])
  (:import [java.io PushbackReader StringReader]))

(def ^:private generic-states #{"active" "closed"})
(def ^:private lean-attribute-byte-floor 1024)
(def ^:private default-read-limit 500)
(def ^:private readable-states #{"active" "closed" "replaced"})
(def ^:private vocab-kinds #{"attr-namespace" "edge"})

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

(defn- validate-read-limit
  "Return limit when it is a positive integer, else fail loudly."
  [limit]
  (when-not (s/valid? ::specs/read-limit limit)
    (throw (ex-info "Read result limit must be a positive integer"
                    {:limit limit :explain (s/explain-str ::specs/read-limit limit)})))
  limit)

(defn- validate-vocab-kind
  "Return the --kind value as its declaration-kind keyword, else fail loudly."
  [kind]
  (when-not (vocab-kinds kind)
    (throw (ex-info "vocab --kind must be attr-namespace or edge"
                    {:kind kind :allowed (vec (sort vocab-kinds))})))
  (keyword kind))

(defn- read-limit-state [rt]
  (runtime-api/spool-state rt ::read-limit #(atom default-read-limit)))

(defn read-limit
  "Return the runtime's batteries read-result cap for CLI list/ready ops."
  [rt]
  @(read-limit-state rt))

(defn set-read-limit!
  "Set the runtime's batteries read-result cap for CLI list/ready ops.

  Intended for trusted workspace config. Invalid values fail loudly instead of
  falling back to the default cap."
  [rt limit]
  (let [limit (validate-read-limit limit)]
    (reset! (read-limit-state rt) limit)
    limit))

(defn- effective-read-limit [rt explicit-limit]
  (validate-read-limit (or explicit-limit (read-limit rt))))

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
  [rt query-fn query-name raw-params state limit]
  (let [query-def (graph/resolve-query rt (handle-name query-name))
        params (validate-query-params query-def raw-params)
        query-def (if state
                    [:and (query/query-expr query-def params) [:= :state state]]
                    query-def)]
    (query-fn rt lean-attribute-byte-floor query-def params limit)))

(defn- run-named-ready-query-lean [rt query-name raw-params limit]
  (let [query-def (graph/resolve-query rt (handle-name query-name))
        params (validate-query-params query-def raw-params)]
    (api/ready-lean rt lean-attribute-byte-floor query-def params limit)))

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
  (graph/burn-by-ids! (:op/runtime ctx) [(:id (:op/args ctx))] (request-context :burn)))

(defn list-op
  "List lean-projected strands, optionally filtered by lifecycle state and/or a named query."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [state query param limit]} (:op/args ctx)
        params (or param {})
        limit (effective-read-limit rt limit)]
    (when state (validate-readable-state state))
    (if query
      (do (when (str/blank? query)
            (throw (ex-info "--query requires a non-empty name" {})))
          (run-named-query rt api/list-lean query params state limit))
      (do (when (seq params)
            (throw (ex-info "--param requires --query" {})))
          (api/list-lean rt lean-attribute-byte-floor (if state [:= :state state] [:exists :id]) {} limit)))))

(defn ready-op
  "List lean-projected ready strands, optionally from the result set of a named query."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [query param limit]} (:op/args ctx)
        params (or param {})
        limit (effective-read-limit rt limit)]
    (if query
      (do (when (str/blank? query)
            (throw (ex-info "--query requires a non-empty name" {})))
          (run-named-ready-query-lean rt query params limit))
      (do (when (seq params)
            (throw (ex-info "--param requires --query" {})))
          (api/ready-lean rt lean-attribute-byte-floor [:exists :id] {} limit)))))

(defn subgraph-op
  "Return a relation-scoped subgraph rooted at one strand."
  [ctx]
  (let [{:keys [root-id relation]} (:op/args ctx)
        {:keys [root-ids strands edges]}
        (graph/subgraph (:op/runtime ctx) [root-id]
                        (cond-> {} relation (assoc :type relation)))]
    {"root_ids" root-ids
     "strands" strands
     "edges" edges}))

(defn weave-op
  "Apply a registered create-only weave pattern to one JSON input value."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [pattern input]} (:op/args ctx)]
    (patterns/weave! rt
                     (handle-name pattern)
                     (walk/keywordize-keys (read-single-json input))
                     (request-context :weave))))

(defn query-op
  "Introspect registered named queries: list all metadata or explain one."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [subcommand] nm :name} (:op/args ctx)]
    (case subcommand
      "list" (json-safe-value (graph/query-metadata rt))
      "explain" (do (when (str/blank? nm)
                      (throw (ex-info "query explain requires a query name" {})))
                    (json-safe-value (graph/query-explain rt (handle-name nm)))))))

(defn pattern-op
  "Introspect registered weave patterns: list all metadata or explain one."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [subcommand] nm :name} (:op/args ctx)]
    (case subcommand
      "list" (patterns/patterns rt)
      "explain" (do (when (str/blank? nm)
                      (throw (ex-info "pattern explain requires a pattern name" {})))
                    (patterns/explain rt (handle-name nm))))))

(defn note-op
  "Append an immutable note to a target strand's memory via the note primitive.

  Returns the primitive's `{:id :target}` shape, where `target` is a projection
  of the `notes` edge rather than a stored attribute."
  [ctx]
  (let [{:keys [id text by round]} (:op/args ctx)]
    (notes-api/note! (:op/runtime ctx) id text {:by by :round round})))

(defn notes-op
  "Return a target strand's notes from every primitive writer in note/at order,
  optionally filtered to one review round."
  [ctx]
  (let [{:keys [id round]} (:op/args ctx)]
    (notes-api/notes (:op/runtime ctx) id {:round round})))

(defn vocab-op
  "List the runtime's vocabulary declarations as an ordered array of C1 maps,
  string-keyed at the wire boundary, optionally narrowed to one --kind."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [kind]} (:op/args ctx)]
    (json-safe-value
     (vocab/declarations rt (when kind {:kind (validate-vocab-kind kind)})))))

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
   :doc "List lean-projected strands, optionally filtered by state and/or a named query."
   :flags {:state {:type :string
                   :doc "Filter by lifecycle state: active, closed, or replaced."}
           :query {:type :string
                   :doc "Weaver-registered named query."}
           :param {:type :map
                   :doc "Named-query parameter key=value; repeatable."}
           :limit {:type :int
                   :doc "Explicit maximum result count; set above the total for an intentional full read."}}})

(def ^:private ready-arg-spec
  {:op "ready"
   :doc "List lean-projected ready strands, optionally from a named query result set."
   :flags {:query {:type :string
                   :doc "Weaver-registered named query."}
           :param {:type :map
                   :doc "Named-query parameter key=value; repeatable."}
           :limit {:type :int
                   :doc "Explicit maximum result count; set above the total for an intentional full read."}}})

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
   :subcommands {"list" {:doc "List registered named query metadata."}
                 "explain" {:doc "Explain one registered named query."
                            :positionals [{:name :name
                                           :type :string
                                           :required? true
                                           :doc "Query name."}]}}})

(def ^:private pattern-arg-spec
  {:op "pattern"
   :doc "Introspect registered weave patterns: list all or explain one."
   :subcommands {"list" {:doc "List registered weave pattern metadata."}
                 "explain" {:doc "Explain one registered weave pattern."
                            :positionals [{:name :name
                                           :type :string
                                           :required? true
                                           :doc "Pattern name."}]}}})

(def ^:private note-arg-spec
  {:op "note"
   :doc "Append an immutable note to a target strand's memory."
   :flags {:by {:type :string
                :doc "Author attribution recorded on the note."}
           :round {:type :int
                   :doc "Review round the note belongs to."}}
   :positionals [{:name :id :type :string :required? true :doc "Target strand id."}
                 {:name :text :type :string :required? true :doc "Note text."}]})

(def ^:private notes-arg-spec
  {:op "notes"
   :doc "Return a target strand's notes in note/at order from every writer."
   :flags {:round {:type :int
                   :doc "Filter to notes from one review round."}}
   :positionals [{:name :id :type :string :required? true :doc "Target strand id."}]})

(def ^:private vocab-arg-spec
  {:op "vocab"
   :doc "List the declared attribute-namespace and edge vocabulary."
   :flags {:kind {:type :string
                  :doc "Narrow to one declaration kind: attr-namespace or edge."}}})

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
   ['pattern pattern-arg-spec :read 'skein.spools.batteries/pattern-op]
   ['note note-arg-spec :mutating 'skein.spools.batteries/note-op]
   ['notes notes-arg-spec :read 'skein.spools.batteries/notes-op]
   ['vocab vocab-arg-spec :read 'skein.spools.batteries/vocab-op]])

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
