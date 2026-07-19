(ns skein.api.events.internal
  "Plumbing for `skein.api.events.alpha`: handler seam validation, function
  resolution, and event-system state access (SPEC-005.C5b)."
  (:require [clojure.string :as str]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.dispatch :as dispatch]))

;; --- event-system state access ------------------------------------------------

(defn handler-registry
  "Return `runtime`'s event handler registry atom (a map keyed by handler key)."
  [runtime]
  (:handler-registry (access/event-system runtime)))

(defn recent-failures-state
  "Return `runtime`'s bounded recent handler failure state atom (a vector)."
  [runtime]
  (:recent-failures (access/event-system runtime)))

;; --- handler seam validation --------------------------------------------------

(defn validate-handler-key!
  "Return `key` when it is a keyword, symbol, or non-blank string; throw otherwise."
  [key]
  (when-not (or (keyword? key) (symbol? key) (string? key))
    (throw (ex-info "Event handler key must be a keyword, symbol, or string" {:key key})))
  (when (and (string? key) (str/blank? key))
    (throw (ex-info "Event handler key string must be non-blank" {:key key})))
  key)

(defn validate-handler-types!
  "Return `types` when it is a non-empty set of keywords; throw otherwise."
  [types]
  (when-not (set? types)
    (throw (ex-info "Event handler types must be a set" {:types types})))
  (when-not (seq types)
    (throw (ex-info "Event handler types must be non-empty" {:types types})))
  (doseq [type types]
    (when-not (keyword? type)
      (throw (ex-info "Event handler types must be keywords" {:type type :types types}))))
  types)

(defn validate-handler-metadata!
  "Return `metadata` (nil becomes `{}`) when it is a data-first map; throw otherwise."
  [metadata]
  (let [metadata (or metadata {})]
    (when-not (map? metadata)
      (throw (ex-info "Event handler metadata must be a map" {:metadata metadata})))
    (when-not (dispatch/data-first-value? metadata)
      (throw (ex-info "Event handler metadata must contain only data-first values"
                      {:metadata metadata})))
    metadata))

;; --- handler function resolution ----------------------------------------------

(defn resolve-handler-fn!
  "Resolve `fn-sym` under `runtime`'s spool classloader to a callable value.

  Throws when the symbol is not fully qualified, cannot be resolved, or names
  a non-callable value."
  [runtime fn-sym]
  (when-not (and (symbol? fn-sym) (namespace fn-sym))
    (throw (ex-info "Event handler function must be a fully qualified symbol" {:fn fn-sym})))
  (let [resolved (try
                   (access/with-spool-classloader runtime #(requiring-resolve fn-sym))
                   (catch Throwable t
                     (throw (ex-info "Event handler function could not be resolved"
                                     {:fn fn-sym} t))))
        value (if (var? resolved) @resolved resolved)]
    (when-not (ifn? value)
      (throw (ex-info "Event handler symbol must resolve to a callable value"
                      {:fn fn-sym :resolved-class (str (class value))})))
    value))
