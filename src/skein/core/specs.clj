(ns skein.core.specs
  "Shared clojure.spec contracts for Skein boundary data.

  These specs describe public data shapes consumed by the database, query,
  daemon, and CLI-facing layers. They capture reusable boundary contracts such
  as non-blank ids, relation names, lifecycle states, and JSON-object-encodable
  attributes."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str])
  (:import [java.time Instant]))

(defn- non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn- json-compatible? [value]
  (cond
    (nil? value) true
    (string? value) true
    (number? value) true
    (true? value) true
    (false? value) true
    (map? value) (and (every? #(or (keyword? %) (string? %)) (keys value))
                      (every? json-compatible? (vals value)))
    (vector? value) (every? json-compatible? value)
    (sequential? value) (every? json-compatible? value)
    :else false))

(defn- json-object-encodable-attributes? [x]
  (or (nil? x)
      (and (map? x) (json-compatible? x))))

(def ^:private generated-id-pattern #"[a-z0-9]+")

(defn- generated-id? [x]
  (and (string? x) (boolean (re-matches generated-id-pattern x))))

(def ^:private relation-name-pattern #"[a-z0-9][a-z0-9._/-]*")

(defn- relation-name? [x]
  (and (string? x) (boolean (re-matches relation-name-pattern x))))

(defn- instant? [x]
  (instance? Instant x))

(defn- fully-qualified-symbol? [x]
  (and (symbol? x)
       (not (str/blank? (namespace x)))
       (not (str/blank? (name x)))))

(s/def ::id non-blank-string?)
(s/def ::generated-id generated-id?)
(s/def ::from ::id)
(s/def ::to ::id)
(s/def ::edge-type relation-name?)
(s/def ::type ::edge-type)
(s/def ::title non-blank-string?)
(s/def ::attr-key keyword?)
(s/def ::cli-attr-value string?)
(s/def ::cli-attributes (s/map-of ::attr-key ::cli-attr-value))
(s/def ::attributes json-object-encodable-attributes?)
(s/def ::state #{"active" "closed" "replaced"})
(s/def ::generic-state #{"active" "closed"})
(s/def ::format #{"human" "edn" "json"})
(s/def ::db non-blank-string?)
(s/def ::opts (s/keys :req-un [::db ::format]))

(s/def ::add-command (s/cat :title ::title :opts (s/* string?)))
(s/def ::update-command (s/cat :id ::id :opts (s/* string?)))
(s/def ::one-id-command (s/cat :id ::id))
(s/def ::empty-command (s/cat))

(s/def ::strand-input
  (s/and (s/keys :req-un [::title] :opt-un [::attributes ::state])
         #(or (not (contains? % :state)) (s/valid? ::generic-state (:state %)))))
(s/def ::edge-input (s/keys :req-un [::from ::to ::type] :opt-un [::attributes]))

;; Batch-creation boundary shapes (skein.core.db/add-strand-batch!): the single
;; contract for one graph-authoring batch, so the DB validators route shape
;; checks through `require-valid!` instead of hand-rolling per-field predicates
;; (and re-deriving the JSON-encodability rule) beside the spec seam. A batch
;; edge target is either a symbolic batch ref (resolved batch-locally) or a
;; durable id string. Component keys live under dedicated qualified namespaces so
;; `:req-un`/`:opt-un` bind the unqualified batch keys without colliding with the
;; strand-level `::to`.
(s/def :skein.batch-edge/to (s/or :ref symbol? :id string?))
(s/def ::batch-edge
  (s/keys :req-un [::type :skein.batch-edge/to]
          :opt-un [::attributes]))
(s/def :skein.batch-strand/ref symbol?)
(s/def :skein.batch-strand/edges (s/nilable (s/coll-of ::batch-edge :kind vector?)))
(s/def ::batch-strand
  (s/keys :req-un [::title]
          :opt-un [:skein.batch-strand/ref :skein.batch-strand/edges ::attributes]))
(s/def ::batch-input (s/coll-of ::batch-strand :kind vector? :min-count 1))

;; Weaver-owned scheduler wake boundary shape (RFC-009): the single durable-write
;; contract shared by db persistence and the API tiers above it, so prose specs,
;; DB validation, and callers cannot drift apart. Component keys live under a
;; dedicated qualified namespace so `:req-un`/`:opt-un` bind them to the
;; unqualified wake keys without polluting this namespace with a bare `::key`.
(s/def :skein.scheduler-wake/key non-blank-string?)
(s/def :skein.scheduler-wake/wake-at instant?)
(s/def :skein.scheduler-wake/handler fully-qualified-symbol?)
(s/def :skein.scheduler-wake/payload json-object-encodable-attributes?)
(s/def ::scheduler-wake
  (s/keys :req-un [:skein.scheduler-wake/key
                   :skein.scheduler-wake/wake-at
                   :skein.scheduler-wake/handler]
          :opt-un [:skein.scheduler-wake/payload]))
