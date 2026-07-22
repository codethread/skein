(ns skein.macros.queries
  "Macros for defining named-query module contribution entries."
  (:require [clojure.string :as str]
            [skein.core.weaver.module-refresh :as module-refresh]))

(defn- registered-name
  "Return the unqualified query handle for authoring Var `name`."
  [name]
  (let [s (str name)
        suffix "-query"]
    (symbol (if (and (str/ends-with? s suffix) (> (count s) (count suffix)))
              (subs s 0 (- (count s) (count suffix)))
              s))))

(defmacro defquery
  "Define a named query and collect its current-module declaration.

  `:usage` remains declaration metadata for consumers that publish query
  conventions; it is not part of the runtime query registry entry."
  [name docstring opts query-def]
  (when-not (symbol? name)
    (throw (ex-info "defquery name must be a symbol" {:name name})))
  (when-not (string? docstring)
    (throw (ex-info "defquery requires a docstring" {:query name})))
  (when-not (:usage opts)
    (throw (ex-info "defquery options require a :usage string" {:query name :opts opts})))
  (let [register-sym (registered-name name)]
    `(do
       (def ~name ~docstring ~query-def)
       (module-refresh/collect-entry! :queries ~(str register-sym) ~name)
       (var ~name))))
