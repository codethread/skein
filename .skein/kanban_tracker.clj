(ns kanban-tracker
  "Bind this repo's kanban card projection to devflow."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.spool.alpha :as spool]
            [skein.spools.devflow :as devflow]
            [skein.spools.kanban :as kanban]))

(s/def ::run-id (s/and string? (complement str/blank?)))
(s/def ::projection :skein.spools.kanban/tracker-projection)
(s/def ::binding :skein.spools.kanban/tracker-binding)
(s/def ::install-result
  (s/and map?
         #(= #{:tracker} (set (keys %)))
         #(s/valid? ::binding (:tracker %))))

(defn- active-stage
  "Return the active root's non-blank stage, or nil when no root is active."
  [run-id]
  (let [roots (devflow/feature-roots run-id)]
    (when-not (vector? roots)
      (spool/fail! "Devflow feature-roots must return a vector"
                   {:run-id run-id :roots roots}))
    (case (count roots)
      0 nil
      1 (let [stage (spool/attr-get (first roots) :devflow/stage)]
          (when-not (and (string? stage) (not (str/blank? stage)))
            (spool/fail! "Active devflow root must carry a non-blank devflow/stage"
                         {:run-id run-id :root (first roots) :stage stage}))
          stage)
      (spool/fail! "Devflow tracker expected at most one active root"
                   {:run-id run-id :root-count (count roots) :roots roots}))))

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
  :ret ::install-result)
