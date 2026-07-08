(ns skein.api.views.alpha
  "Explicit-runtime API for registering, inspecting, and invoking weaver views.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns view validation, function resolution, registry
  state, and invocation."
  (:require [skein.core.query :as query]
            [skein.core.weaver.access :as access]))

(defn- canonical-view-name [view-name]
  (query/canonical-query-name view-name))

(defn- validate-view-fn-symbol! [fn-sym]
  (access/validate-fn-symbol! "View" fn-sym))

(defn register-view!
  "Register a weaver-memory view name in `runtime` to a function symbol."
  [runtime view-name fn-sym]
  (let [name (canonical-view-name view-name)
        entry {:name name :fn (validate-view-fn-symbol! fn-sym)}]
    (swap! (access/view-registry runtime) assoc name entry)
    entry))

(defn views
  "Return serializable weaver-memory view registry entries from `runtime`."
  [runtime]
  (mapv val (sort-by key @(access/view-registry runtime))))

(defn- resolve-view [runtime view-name]
  (let [canonical-name (canonical-view-name view-name)]
    (or (get @(access/view-registry runtime) canonical-name)
        (throw (ex-info "View not found" {:view view-name
                                          :canonical-view canonical-name
                                          :available (sort (keys @(access/view-registry runtime)))})))))

(defn view!
  "Invoke a registered weaver-side view with params through `runtime`."
  [runtime view-name params]
  (let [{fn-sym :fn} (resolve-view runtime view-name)]
    (access/with-spool-classloader
      runtime
      #((requiring-resolve fn-sym) {:params params}))))
