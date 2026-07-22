(ns skein.macros.patterns
  "Macros for defining Skein weave patterns with concise input schemas."
  (:require [clojure.spec.alpha :as s]
            [skein.core.weaver.module-refresh :as module-refresh]))

(defn- qualify-in [ns-sym k]
  (keyword (str ns-sym) (name k)))

(defn- input-spec-key [ns-sym pattern-name]
  (keyword (str ns-sym) (str (name pattern-name) "-input")))

(defn- schema-spec-forms [ns-sym spec-key schema]
  (cond
    (map? schema)
    (let [field-forms (mapcat (fn [[k v]] (schema-spec-forms ns-sym (qualify-in ns-sym k) v)) schema)
          key-specs (mapv #(qualify-in ns-sym %) (keys schema))]
      (concat field-forms `((s/def ~spec-key (s/keys :req-un ~key-specs)))))
    (and (vector? schema) (= 1 (count schema)))
    `((s/def ~spec-key (s/coll-of ~(first schema) :kind vector?)))
    :else `((s/def ~spec-key ~schema))))

(defn- input-option-forms [ns-sym pattern-name opts]
  (cond
    (:spec opts) (let [spec (:spec opts)]
                   (if (or (keyword? spec) (symbol? spec))
                     {:spec-key spec :forms []}
                     (let [spec-key (input-spec-key ns-sym pattern-name)]
                       {:spec-key spec-key :forms `((s/def ~spec-key ~spec))})))
    (or (:input opts) (:optional opts))
    (let [spec-key (input-spec-key ns-sym pattern-name)
          req (:input opts)
          opt (:optional opts)]
      {:spec-key spec-key
       :forms (concat (mapcat (fn [[k v]] (schema-spec-forms ns-sym (qualify-in ns-sym k) v)) req)
                      (mapcat (fn [[k v]] (schema-spec-forms ns-sym (qualify-in ns-sym k) v)) opt)
                      `((s/def ~spec-key (s/keys :req-un ~(mapv #(qualify-in ns-sym %) (keys req))
                                                   :opt-un ~(mapv #(qualify-in ns-sym %) (keys opt))))))})
    :else (throw (ex-info "defpattern requires either :spec or :input/:optional options"
                          {:pattern pattern-name :opts opts}))))

(defmacro defpattern
  "Define a weave pattern and collect its declaration for the current module."
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
       (module-refresh/collect-entry!
        :patterns '~name
        {:name ~(str name) :fn '~fn-sym :input-spec ~spec-key :doc ~docstring})
       (var ~name))))

(defmacro defp
  "Short alias for defpattern."
  [& args]
  `(defpattern ~@args))
