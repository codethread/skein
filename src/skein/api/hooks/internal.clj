(ns skein.api.hooks.internal
  "Validation and resolution plumbing for `skein.api.hooks.alpha`."
  (:require [clojure.string :as str]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.dispatch :as dispatch]))

(defn validate-hook-key!
  "Return `key` when it is a keyword, symbol, or non-blank string; throw otherwise."
  [key]
  (when-not (or (keyword? key) (symbol? key) (string? key))
    (throw (ex-info "Hook key must be a keyword, symbol, or string" {:key key})))
  (when (and (string? key) (str/blank? key))
    (throw (ex-info "Hook key string must be non-blank" {:key key})))
  key)

(defn validate-hook-types!
  "Return `types` when it is a non-empty set of keywords; throw otherwise."
  [types]
  (when-not (set? types)
    (throw (ex-info "Hook types must be a set" {:types types})))
  (when-not (seq types)
    (throw (ex-info "Hook types must be non-empty" {:types types})))
  (doseq [type types]
    (when-not (keyword? type)
      (throw (ex-info "Hook types must be keywords" {:type type :types types}))))
  types)

(defn validate-hook-opts!
  "Return `opts` (nil becomes {}) when it is a data-first map whose `:order`,
  when present, is an integer; throw otherwise."
  [opts]
  (let [opts (or opts {})]
    (when-not (map? opts)
      (throw (ex-info "Hook opts must be a map" {:opts opts})))
    (when-not (dispatch/data-first-value? opts)
      (throw (ex-info "Hook opts must contain only data-first values" {:opts opts})))
    (when (and (contains? opts :order) (not (integer? (:order opts))))
      (throw (ex-info "Hook :order must be an integer" {:order (:order opts)})))
    opts))

(defn resolve-hook-fn!
  "Resolve `fn-sym` under `runtime`'s spool classloader and return the resolved
  var or value; throw unless the symbol is fully qualified and callable."
  [runtime fn-sym]
  (when-not (and (symbol? fn-sym) (namespace fn-sym))
    (throw (ex-info "Hook function must be a fully qualified symbol" {:fn fn-sym})))
  (let [resolved (access/with-spool-classloader runtime #(requiring-resolve fn-sym))
        value (if (var? resolved) @resolved resolved)]
    (when-not (ifn? value)
      (throw (ex-info "Hook symbol must resolve to a callable value"
                      {:fn fn-sym :resolved-class (str (class value))})))
    resolved))
