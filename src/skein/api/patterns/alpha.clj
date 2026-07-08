(ns skein.api.patterns.alpha
  "Explicit-runtime API for registering, inspecting, and invoking weave patterns.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns pattern validation, function resolution, input
  spec validation and caller guidance, and the transactional create-only batch a
  weave produces. The SQL batch engine lives in `skein.core.db`; the shared
  lifecycle and dispatch plumbing in `skein.core.weaver.*`."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [skein.core.db :as db]
            [skein.core.query :as query]
            [skein.core.weaver.access :refer [ds normalize pattern-registry
                                              with-spool-classloader validate-fn-symbol!]]
            [skein.core.weaver.dispatch :as dispatch]
            [skein.core.weaver.lifecycle :refer [event-base request-context
                                                 run-validation-hooks! run-transform-hooks]])
  (:import [java.util UUID]))

(defn- canonical-pattern-name [pattern-name]
  (query/canonical-query-name pattern-name))

(defn- validate-pattern-fn-symbol! [fn-sym]
  (validate-fn-symbol! "Pattern" fn-sym))

(defn- validate-pattern-spec! [spec-name]
  (when-not (or (keyword? spec-name) (symbol? spec-name))
    (throw (ex-info "Pattern input spec must be a keyword or symbol" {:spec spec-name})))
  spec-name)

(defn- validate-pattern-doc! [doc]
  (when-not (and (string? doc) (not (str/blank? doc)))
    (throw (ex-info "Pattern doc must be a non-blank string" {:doc doc})))
  doc)

(defn- pattern-entry [pattern-name doc fn-sym input-spec]
  (cond-> {:name (canonical-pattern-name pattern-name)
           :fn (validate-pattern-fn-symbol! fn-sym)
           :input-spec (validate-pattern-spec! input-spec)}
    doc (assoc :doc (validate-pattern-doc! doc))))

(defn register-pattern!
  "Register a trusted weaver pattern handler and input spec in `runtime`."
  ([runtime pattern-name fn-sym input-spec]
   (let [entry (pattern-entry pattern-name nil fn-sym input-spec)]
     (swap! (pattern-registry runtime) assoc (:name entry) entry)
     entry))
  ([runtime pattern-name doc fn-sym input-spec]
   (let [entry (pattern-entry pattern-name doc fn-sym input-spec)]
     (swap! (pattern-registry runtime) assoc (:name entry) entry)
     entry)))

(defn patterns
  "Return registered weave pattern metadata from `runtime`, ordered by name."
  [runtime]
  (mapv val (sort-by key @(pattern-registry runtime))))

(defn pattern
  "Return the registered weave pattern for a simple symbol or keyword name.

  Missing patterns fail loudly."
  [runtime pattern-name]
  (let [canonical-name (canonical-pattern-name pattern-name)]
    (or (get @(pattern-registry runtime) canonical-name)
        (throw (ex-info "Pattern not found" {:pattern pattern-name
                                             :canonical-pattern canonical-name
                                             :available (sort (keys @(pattern-registry runtime)))})))))

(defn- spec-form [spec-name]
  (let [form (s/form spec-name)]
    (when (= ::s/unknown form)
      (throw (ex-info "Pattern input spec is not registered" {:input-spec spec-name})))
    form))

(defn- spec-summary [spec-name]
  {:spec (str spec-name)
   :spec-form (pr-str (spec-form spec-name))})

(defn- key-spec-summary [key-spec]
  (merge {:key (name key-spec)}
         (try
           (spec-summary key-spec)
           (catch clojure.lang.ExceptionInfo _
             {:spec (str key-spec)
              :spec-form "<unregistered>"}))))

(defn- keys-spec-summary [form]
  (when (and (seq? form) (= 'clojure.spec.alpha/keys (first form)))
    (let [opts (apply hash-map (rest form))]
      {:required (mapv key-spec-summary (concat (:req opts) (:req-un opts)))
       :optional (mapv key-spec-summary (concat (:opt opts) (:opt-un opts)))})))

(defn- pattern-input-contract [input-spec]
  (let [form (spec-form input-spec)
        keys-summary (keys-spec-summary form)]
    (cond-> (spec-summary input-spec)
      true (assoc :summary "Input must satisfy this clojure.spec contract. For key specs, see required/optional entries for each key's own predicate.")
      keys-summary (merge keys-summary))))

(defn explain
  "Describe a registered weave pattern and its input contract in `runtime`.

  Missing patterns or unregistered input specs fail loudly."
  [runtime pattern-name]
  ;; :fn is renamed on destructure: a local named `fn` shadows the fn macro.
  (let [{:keys [name doc input-spec] fn-sym :fn} (pattern runtime pattern-name)
        contract (pattern-input-contract input-spec)]
    (cond-> (merge {:name name
                    :fn (str fn-sym)
                    :input-spec (str input-spec)
                    :spec-form (:spec-form contract)}
                   (select-keys contract [:summary :required :optional]))
      doc (assoc :doc doc))))

(defn- missing-key [problem]
  (let [pred (pr-str (:pred problem))]
    (when (str/includes? pred "contains?")
      (or (last (:path problem))
          (some->> (re-find #"contains\? % (:?[A-Za-z0-9._/-]+)" pred) second keyword)))))

(defn- problem-message [contract problem]
  (if-let [key-spec (missing-key problem)]
    (let [key-contract (some #(when (= (name key-spec) (:key %)) %)
                             (:required contract))]
      (str "missing required key `" (name key-spec) "`"
           (when key-contract
             (str " (expected " (:spec key-contract) " " (:spec-form key-contract) ")"))))
    (str "value at " (pr-str (:in problem)) " failed predicate " (pr-str (:pred problem)))))

(defn- pattern-validation-message [pattern-name contract explain-data]
  (let [problems (::s/problems explain-data)]
    (str "Pattern input failed spec validation for `" (canonical-pattern-name pattern-name) "`"
         (when (seq problems)
           (str ": " (str/join "; " (map #(problem-message contract %) problems)))))))

(defn- require-pattern-batch-vector! [batch]
  (when-not (vector? batch)
    (throw (ex-info "Pattern must return a batch strand vector" {:value batch})))
  batch)

(defn- normalize-weave-strand-attributes [runtime req-ctx pattern-name input batch]
  (mapv (fn [{:keys [ref attributes] :as strand}]
          (if (nil? attributes)
            strand
            (assoc strand :attributes
                   (run-transform-hooks runtime
                                        :attributes/normalize
                                        (merge req-ctx
                                               {:hook/value attributes
                                                :mutation/operation :batch/apply
                                                :batch/ref ref
                                                :strand/patch strand
                                                :pattern/name pattern-name
                                                :pattern/input input})))))
        (require-pattern-batch-vector! batch)))

(defn- weave-payload [strands]
  {:refs {}
   :strands (mapv #(dissoc % :edges) strands)
   :edges (into []
                (mapcat (fn [{:keys [ref edges]}]
                          (map (fn [edge]
                                 (merge {:op :upsert
                                         :from (some-> ref str)
                                         :to (cond-> (:to edge)
                                               (symbol? (:to edge)) str)}
                                        (select-keys edge [:type :attributes])))
                               edges)))
                strands)
   :burn []})

(defn- weave-batch-context [req-ctx pattern-name input payload result]
  (merge req-ctx
         {:mutation/operation :batch/apply
          :batch/source :weave
          :batch/payload payload
          :batch/refs (:refs result)
          :batch/created (:created result)
          :batch/updated []
          :batch/burned []
          :batch/edge-ops (:edges result)
          :pattern/name pattern-name
          :pattern/input input}))

(defn weave!
  "Validate pattern input, invoke the pattern, and apply its create-only batch."
  ([runtime pattern-name input]
   (weave! runtime pattern-name input (request-context :weave)))
  ([runtime pattern-name input req-ctx]
   (let [{fn-sym :fn input-spec :input-spec} (pattern runtime pattern-name)
         canonical-name (canonical-pattern-name pattern-name)]
     (spec-form input-spec)
     (when-not (s/valid? input-spec input)
       (let [explain-data (s/explain-data input-spec input)
             contract (pattern-input-contract input-spec)]
         (throw (ex-info (pattern-validation-message pattern-name contract explain-data)
                         {:code "pattern/input-invalid"
                          :pattern canonical-name
                          :input-spec (str input-spec)
                          :contract contract
                          :problems (mapv #(problem-message contract %) (::s/problems explain-data))
                          :explain explain-data}))))
     (let [batch (with-spool-classloader
                   runtime
                   #((requiring-resolve fn-sym) {:input input}))
           normalized-batch (normalize-weave-strand-attributes runtime req-ctx canonical-name input batch)
           normalized-payload (weave-payload normalized-batch)
           result (jdbc/with-transaction [tx (ds runtime)]
                    (let [result (normalize (db/add-strand-batch-in-transaction! tx normalized-batch))]
                      (run-validation-hooks! runtime
                                             :batch/apply-before-commit
                                             (weave-batch-context req-ctx canonical-name input normalized-payload result))
                      result))]
      ;; a weave is a create-only batch apply; without this event, event-driven
      ;; spools (shuttle, treadle) never see pattern-created strands until an
      ;; unrelated mutation happens to trigger their next scan
       (dispatch/enqueue! runtime (assoc (event-base :batch/applied)
                                         :batch/id (str (UUID/randomUUID))
                                         :pattern/name canonical-name
                                         :batch/refs (:refs result)
                                         :batch/created (:created result)))
       (select-keys result [:created :refs])))))
