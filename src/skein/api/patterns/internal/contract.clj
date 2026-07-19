(ns skein.api.patterns.internal.contract
  "Input-spec introspection and caller-guidance plumbing for `skein.api.patterns.alpha`."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

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

(defn input-contract
  "Return serializable caller guidance for a registered pattern input spec.

  Unregistered specs fail loudly."
  [input-spec]
  (let [form (spec-form input-spec)
        keys-summary (keys-spec-summary form)]
    (cond-> (spec-summary input-spec)
      true (assoc :summary (str "Input must satisfy this clojure.spec contract. For key "
                                "specs, see required/optional entries for each key's own "
                                "predicate."))
      keys-summary (merge keys-summary))))

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

(defn- validation-message [canonical-name contract explain-data]
  (let [problems (::s/problems explain-data)]
    (str "Pattern input failed spec validation for `" canonical-name "`"
         (when (seq problems)
           (str ": " (str/join "; " (map #(problem-message contract %) problems)))))))

(defn validate-input!
  "Validate weave `input` against a pattern's registered `input-spec`.

  Throws when the spec is unregistered or the input fails it; the ex-data carries
  the input contract and per-problem caller guidance."
  [canonical-name input-spec input]
  (spec-form input-spec)
  (when-not (s/valid? input-spec input)
    (let [explain-data (s/explain-data input-spec input)
          contract (input-contract input-spec)]
      (throw (ex-info (validation-message canonical-name contract explain-data)
                      {:code "pattern/input-invalid"
                       :pattern canonical-name
                       :input-spec (str input-spec)
                       :contract contract
                       :problems (mapv #(problem-message contract %)
                                       (::s/problems explain-data))
                       :explain explain-data})))))
