(ns skein.api.scheduler.internal
  "Plumbing for `skein.api.scheduler.alpha`: persisted-row decoding and
  handler resolution. Implementation tier (SPEC-005.C5b), not public
  surface."
  (:require [skein.core.db :as db]
            [skein.core.weaver.access :as access]))

(defn decoded-row
  "Decode a scheduler wake/history row's JSON payload and handler symbol."
  [row]
  (some-> row
          (update :payload db/<-json)
          (update :handler symbol)))

(defn resolve-handler-fn!
  "Resolve `handler` under `runtime`'s spool classloader to a callable value.

  Throws when the symbol is not fully qualified, cannot be resolved, or
  names a non-callable value."
  [runtime handler]
  (when-not (and (symbol? handler) (namespace handler))
    (throw (ex-info "Scheduler handler must be a fully qualified symbol"
                    {:handler handler})))
  (let [resolved (try
                   (access/with-spool-classloader runtime #(requiring-resolve handler))
                   (catch Throwable t
                     (throw (ex-info "Scheduler handler could not be resolved"
                                     {:handler handler} t))))
        value (if (var? resolved) @resolved resolved)]
    (when-not (ifn? value)
      (throw (ex-info "Scheduler handler symbol must resolve to a callable value"
                      {:handler handler :resolved-class (str (class value))})))
    value))
