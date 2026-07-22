(ns skein.macros.rules
  "Macros for defining Chime rule module contribution entries."
  (:require [skein.core.weaver.module-refresh :as module-refresh]))

(defmacro defrule
  "Define a Chime rule and collect its declaration for the current module."
  [name docstring argv & body]
  (when-not (symbol? name)
    (throw (ex-info "defrule name must be a symbol" {:name name})))
  (when-not (string? docstring)
    (throw (ex-info "defrule requires a docstring" {:rule name})))
  (let [ns-sym (ns-name *ns*)
        handler-name (symbol (str name "-rule"))
        fn-sym (symbol (str ns-sym) (str name "-rule"))
        rule-key (keyword (str name))]
    `(do
       (defn ~handler-name ~docstring ~argv ~@body)
       (module-refresh/collect-entry!
        :skein.spools.chime/rules ~rule-key {:key ~rule-key :fn '~fn-sym})
       (var ~handler-name))))
