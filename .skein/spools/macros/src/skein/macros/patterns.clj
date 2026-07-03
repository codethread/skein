(ns skein.macros.patterns
  "Macros for defining Skein weave patterns with concise input schemas."
  (:require [clojure.spec.alpha :as s]
            [skein.api.patterns.alpha :as patterns]))

(defonce ^:private pattern-registry (atom {}))

(defn- qualify-in [ns-sym k]
  (keyword (str ns-sym) (name k)))

(defn- input-spec-key [ns-sym pattern-name]
  (keyword (str ns-sym) (str (name pattern-name) "-input")))

(defn- schema-spec-forms
  "Return s/def forms needed for schema under spec-key.

  Supported shorthand:
  - predicate or existing spec symbol/keyword: string?, pos-int?, ::shared
  - one-element vector: [string?]
  - map: {:field string? :nested {:key string?}}

  Map keys become unqualified JSON/Clojure input keys. Their specs are named in
  the pattern namespace with the same name, e.g. :my.ns/title validates :title."
  [ns-sym spec-key schema]
  (cond
    (map? schema)
    (let [field-forms (mapcat (fn [[k v]]
                                (schema-spec-forms ns-sym (qualify-in ns-sym k) v))
                              schema)
          key-specs (mapv #(qualify-in ns-sym %) (keys schema))]
      (concat field-forms
              `((s/def ~spec-key (s/keys :req-un ~key-specs)))))

    (and (vector? schema) (= 1 (count schema)))
    (let [item-spec (first schema)]
      `((s/def ~spec-key (s/coll-of ~item-spec :kind vector?))))

    :else
    `((s/def ~spec-key ~schema))))

(defn- input-option-forms [ns-sym pattern-name opts]
  (cond
    (:spec opts)
    (let [spec (:spec opts)]
      (if (or (keyword? spec) (symbol? spec))
        {:spec-key spec
         :forms []}
        (let [spec-key (input-spec-key ns-sym pattern-name)]
          {:spec-key spec-key
           :forms `((s/def ~spec-key ~spec))})))

    (or (:input opts) (:optional opts))
    (let [spec-key (input-spec-key ns-sym pattern-name)
          req (:input opts)
          opt (:optional opts)
          req-forms (mapcat (fn [[k v]]
                              (schema-spec-forms ns-sym (qualify-in ns-sym k) v))
                            req)
          opt-forms (mapcat (fn [[k v]]
                              (schema-spec-forms ns-sym (qualify-in ns-sym k) v))
                            opt)
          req-keys (mapv #(qualify-in ns-sym %) (keys req))
          opt-keys (mapv #(qualify-in ns-sym %) (keys opt))]
      {:spec-key spec-key
       :forms (concat req-forms
                      opt-forms
                      `((s/def ~spec-key (s/keys :req-un ~req-keys
                                                 :opt-un ~opt-keys))))})

    :else
    (throw (ex-info "defpattern requires either :spec or :input/:optional options"
                    {:pattern pattern-name :opts opts}))))

(defn remember-pattern!
  "Remember a pattern defined in namespace `ns-sym` for later install-patterns!."
  [ns-sym entry]
  (swap! pattern-registry update ns-sym
         (fnil assoc {}) (:name entry) entry)
  entry)

(defn install-patterns!
  "Install all patterns remembered for the current namespace, or for `ns-sym`."
  ([]
   (install-patterns! (ns-name *ns*)))
  ([ns-sym]
   (let [entries (vals (get @pattern-registry ns-sym))]
     (doseq [{:keys [name doc fn input-spec]} entries]
       (patterns/register-pattern! name doc fn input-spec))
     {:installed (count entries)
      :patterns (mapv :name entries)})))

(defmacro defpattern
  "Define a Skein weave pattern and remember it for install-patterns!.

  The options map accepts either shorthand :input/:optional maps or :spec for
  an existing/generated spec. The body is a normal single-arity function body
  that receives Skein's pattern context map, typically destructured as
  [{:keys [input]}]."
  [name docstring opts argv & body]
  (when-not (symbol? name)
    (throw (ex-info "defpattern name must be a symbol" {:name name})))
  (when-not (string? docstring)
    (throw (ex-info "defpattern requires a docstring" {:pattern name})))
  (let [ns-sym (ns-name *ns*)
        {:keys [spec-key forms]} (input-option-forms ns-sym name opts)
        fn-sym (symbol (str ns-sym) (str name))]
    `(do
       ~@forms
       (defn ~name ~docstring ~argv ~@body)
       (remember-pattern! '~ns-sym
                          {:name '~name
                           :fn '~fn-sym
                           :input-spec ~spec-key
                           :doc ~docstring})
       (var ~name))))

(defmacro defp
  "Short alias for defpattern."
  [& args]
  `(defpattern ~@args))
