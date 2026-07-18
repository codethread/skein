(ns skein.api.hooks.alpha
  "Explicit-runtime API for registering and inspecting weaver lifecycle hooks.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns hook validation, function resolution, and
  registry state; synchronous invocation by later lifecycle gates lives in
  `skein.core.weaver.lifecycle`."
  (:require [clojure.string :as str]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.dispatch :as dispatch]))

(defn- validate-hook-key! [key]
  (when-not (or (keyword? key) (symbol? key) (string? key))
    (throw (ex-info "Hook key must be a keyword, symbol, or string" {:key key})))
  (when (and (string? key) (str/blank? key))
    (throw (ex-info "Hook key string must be non-blank" {:key key})))
  key)

(defn- validate-hook-types! [types]
  (when-not (set? types)
    (throw (ex-info "Hook types must be a set" {:types types})))
  (when-not (seq types)
    (throw (ex-info "Hook types must be non-empty" {:types types})))
  (doseq [type types]
    (when-not (keyword? type)
      (throw (ex-info "Hook types must be keywords" {:type type :types types}))))
  types)

(defn- resolve-hook-fn! [runtime fn-sym]
  (when-not (and (symbol? fn-sym) (namespace fn-sym))
    (throw (ex-info "Hook function must be a fully qualified symbol" {:fn fn-sym})))
  (let [resolved (access/with-spool-classloader runtime #(requiring-resolve fn-sym))
        value (if (var? resolved) @resolved resolved)]
    (when-not (ifn? value)
      (throw (ex-info "Hook symbol must resolve to a callable value" {:fn fn-sym :resolved-class (str (class value))})))
    resolved))

(defn- validate-hook-opts! [opts]
  (let [opts (or opts {})]
    (when-not (map? opts)
      (throw (ex-info "Hook opts must be a map" {:opts opts})))
    (when-not (dispatch/data-first-value? opts)
      (throw (ex-info "Hook opts must contain only data-first values" {:opts opts})))
    (when (and (contains? opts :order) (not (integer? (:order opts))))
      (throw (ex-info "Hook :order must be an integer" {:order (:order opts)})))
    opts))

(defn register-hook!
  "Register or replace a lifecycle hook in `runtime` for selected hook types."
  ([runtime key types fn-sym]
   (register-hook! runtime key types fn-sym {}))
  ([runtime key types fn-sym opts]
   (let [opts (validate-hook-opts! opts)
         entry {:key (validate-hook-key! key)
                :types (validate-hook-types! types)
                :fn fn-sym
                :fn-value (resolve-hook-fn! runtime fn-sym)
                :order (get opts :order 0)
                :metadata (dissoc opts :order)}]
     (swap! (access/hook-registry runtime) assoc (:key entry) entry)
     (dissoc entry :fn-value))))

(defn unregister-hook!
  "Unregister a lifecycle hook by stable key from `runtime` and return that key."
  [runtime key]
  (let [key (validate-hook-key! key)]
    (swap! (access/hook-registry runtime) dissoc key)
    key))

(defn ^:deprecated register!
  "Renamed to register-hook! (card d6xgt); this alias is removed before the v1 stamp."
  [& args]
  (apply register-hook! args))

(defn ^:deprecated unregister!
  "Renamed to unregister-hook! (card d6xgt); this alias is removed before the v1 stamp."
  [& args]
  (apply unregister-hook! args))

(defn hooks
  "Return data-first lifecycle hook registry entries in execution order."
  [runtime]
  (mapv #(dissoc % :fn-value)
        (sort-by (juxt :order (comp pr-str :key)) (vals @(access/hook-registry runtime)))))
