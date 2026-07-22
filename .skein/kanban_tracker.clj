(ns kanban-tracker
  "Bind this repo's kanban card projection to devflow."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.spool.alpha :as spool]
            [ct.spools.devflow :as devflow]
            [ct.spools.kanban :as kanban]))

(s/def ::run-id (s/and string? (complement str/blank?)))
(s/def ::projection :ct.spools.kanban/tracker-projection)
(s/def ::binding :ct.spools.kanban/tracker-binding)
(s/def ::install-result
  (s/and map?
         #(= #{:tracker} (set (keys %)))
         #(s/valid? ::binding (:tracker %))))

(defn- active-stage
  "Return the active root's non-blank stage, or nil when no root is active."
  [run-id]
  (when-let [root (devflow/current-root run-id)]
    (let [stage (spool/attr-get root :devflow/stage)]
      (when-not (and (string? stage) (not (str/blank? stage)))
        (spool/fail! "Active devflow root must carry a non-blank devflow/stage"
                     {:run-id run-id :root root :stage stage}))
      stage)))

(defn devflow-projection
  "Project a devflow run into kanban's `::projection` tracker shape.

  An absent active root is the accepted no-active-run state: nil status and no
  steps. Kanban validates the same projection again at its strategy boundary."
  [run-id]
  (spool/require-valid! ::run-id run-id "Devflow tracker run id must be a non-blank string")
  (let [stage (active-stage run-id)]
    (spool/require-valid!
     ::projection
     {:status stage
      :ready (if stage (devflow/ready run-id) [])}
     "Devflow tracker projection must match its owning spec")))

(s/fdef devflow-projection
  :args (s/cat :run-id ::run-id)
  :ret ::projection)

(defn install!
  "Bind devflow as the required kanban tracker for this runtime."
  []
  (kanban/set-tracker! {:name "devflow"
                        :project 'kanban-tracker/devflow-projection}))

(s/fdef install!
  :args (s/cat)
  :ret ::install-result)

;; The devflow<->kanban tracker binding is a singleton slot, not a partitioned
;; kind: kanban exposes one tracker per runtime through `set-tracker!`, so there
;; is no owner partition to contribute and nothing for deletion-by-omission to
;; drop. Reconcile is its home — it re-establishes the binding on every refresh.
(defn reconcile
  "Bind devflow as this runtime's required kanban tracker."
  [{:keys [runtime]}]
  (current/with-runtime runtime (install!))
  {:reconciled :kanban-tracker})
