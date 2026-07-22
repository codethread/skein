(ns skein.macros.ops
  "Macros for defining Skein CLI ops as module contribution entries."
  (:require [skein.core.weaver.module-refresh :as module-refresh]))

(defn declaration-entry
  "Return the stored op declaration assembled from a `defop` form.

  `defop` expansions call this function from the namespace that owns the
  declared operation, so it is public even though callers should normally use
  the macro."
  [op-name opts fn-sym]
  (let [stream? (boolean (:stream? opts))]
    (cond-> {:name (name op-name)
             :fn fn-sym
             :stream? stream?
             :provenance (symbol (namespace fn-sym))}
      (:doc opts) (assoc :doc (:doc opts))
      (some? (:arg-spec opts)) (assoc :arg-spec (:arg-spec opts))
      (contains? opts :deadline-class) (assoc :deadline-class (:deadline-class opts))
      (contains? opts :hook-class) (assoc :hook-class (:hook-class opts))
      (contains? opts :returns) (assoc :returns (:returns opts)))))

(defmacro defop
  "Define a Skein CLI op and collect its declaration for the current module.

  The macro defines the ordinary `<name>-op` handler Var, then records its
  validated registry entry while module source collection is active. Outside
  collection the record call is passive, so code-only loads only define Vars."
  [name docstring opts argv & body]
  (when-not (symbol? name)
    (throw (ex-info "defop name must be a symbol" {:name name})))
  (when-not (string? docstring)
    (throw (ex-info "defop requires a docstring" {:op name})))
  (when-not (:arg-spec opts)
    (throw (ex-info "defop options require an :arg-spec" {:op name :opts opts})))
  (let [ns-sym (ns-name *ns*)
        handler-name (symbol (str name "-op"))
        fn-sym (symbol (str ns-sym) (str name "-op"))
        metadata (assoc (dissoc opts :convention) :doc docstring)]
    `(do
       (defn ~handler-name ~docstring ~argv ~@body)
       (module-refresh/collect-entry!
        :ops ~(str name)
        (declaration-entry '~name ~metadata '~fn-sym))
       (var ~handler-name))))
