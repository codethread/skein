(ns skein.api.hooks.alpha
  "Explicit-runtime API for registering and inspecting weaver lifecycle hooks.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns hook validation, function resolution, and
  registry state; synchronous invocation by later lifecycle gates lives in
  `skein.core.weaver.lifecycle`."
  (:require [skein.api.hooks.internal :as internal]
            [skein.core.weaver.access :as access]))

(defn register-hook!
  "Register or replace a lifecycle hook in `runtime` for selected hook types.

  `key` is the stable registry identity (keyword, symbol, or non-blank string):
  registering an existing key replaces that entry. `types` is a non-empty set of
  hook type keywords, and `fn-sym` a fully qualified symbol resolved under the
  runtime's spool classloader. `opts` may carry an integer `:order` (default 0)
  plus data-first metadata. Returns the registered entry without its resolved
  function value."
  ([runtime key types fn-sym]
   (register-hook! runtime key types fn-sym {}))
  ([runtime key types fn-sym opts]
   (let [opts (internal/validate-hook-opts! opts)
         entry {:key (internal/validate-hook-key! key)
                :types (internal/validate-hook-types! types)
                :fn fn-sym
                :fn-value (internal/resolve-hook-fn! runtime fn-sym)
                :order (get opts :order 0)
                :metadata (dissoc opts :order)}]
     (swap! (access/hook-registry runtime) assoc (:key entry) entry)
     (dissoc entry :fn-value))))

(defn unregister-hook!
  "Unregister a lifecycle hook by stable key from `runtime` and return that key.

  Unregistering an absent key is a no-op returning the validated key."
  [runtime key]
  (let [key (internal/validate-hook-key! key)]
    (swap! (access/hook-registry runtime) dissoc key)
    key))

(defn hooks
  "Return data-first lifecycle hook registry entries in execution order.

  Entries sort by `:order`, then printed key for a deterministic tie-break, and
  never carry the resolved `:fn-value`."
  [runtime]
  (mapv #(dissoc % :fn-value)
        (sort-by (juxt :order (comp pr-str :key)) (vals @(access/hook-registry runtime)))))
