(ns kanban-tracker
  "Bind this repo's kanban card projection to devflow."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.spool.alpha :as spool]
            [skein.spools.devflow :as devflow]
            [skein.spools.kanban :as kanban]))

(s/def ::run-id (s/and string? (complement str/blank?)))
(s/def ::status (s/nilable string?))
(s/def ::step map?)
(s/def ::next-steps (s/coll-of ::step :kind vector?))
(s/def ::projection (s/keys :req-un [::status ::next-steps]))

(defn devflow-projection
  "Project a devflow run into kanban's `::projection` tracker shape.

  An absent active root is the accepted no-active-run state: nil status and no
  steps. Kanban validates the same projection again at its strategy boundary."
  [run-id]
  (spool/require-valid! ::run-id run-id "Devflow tracker run id must be a non-blank string")
  (let [stage (some-> (devflow/feature-roots run-id)
                      first
                      (spool/attr-get :devflow/stage))]
    (spool/require-valid!
     ::projection
     {:status stage
      :next-steps (if stage (devflow/next-steps run-id) [])}
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
  :ret map?)
