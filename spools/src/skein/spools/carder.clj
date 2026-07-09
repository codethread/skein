(ns skein.spools.carder
  "Read-only graph hygiene reports for long-lived Skein strand graphs.

  Carder composes the public weaver/graph helper surfaces for strand reads and
  uses the active runtime datasource only to inspect edge incidence, because the
  shipped public graph helpers expose relation-scoped traversal rather than a
  workspace-wide edge listing. It never mutates strands, edges, runtime config,
  or registered operations."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [skein.api.current.alpha :as current]
            [skein.api.graph.alpha :as graph]
            [skein.api.weaver.alpha :as api]
            [skein.spools.util :refer [fail! attr-get]])
  (:import [java.time Duration Instant LocalDateTime ZoneOffset]))

(def default-days
  "Default age threshold, in days, used by `stale`."
  14)

(def ^:private excluded-workflow-roles #{"molecule" "procedure" "digest"})
(def ^:private failed-run-phases #{"failed" "exhausted"})

(defn- require-map! [opts context]
  (when-not (map? opts)
    (fail! "Carder options must be a map" {:context context :opts opts}))
  opts)

(defn- reject-unknown-keys! [opts allowed context]
  (when-let [unknown (seq (remove allowed (keys opts)))]
    (fail! "Unknown carder option keys" {:context context :unknown (vec unknown) :allowed (vec allowed)}))
  opts)

(defn- normalize-opts [opts context]
  (-> opts
      (require-map! context)
      (reject-unknown-keys! #{:days :include-plumbing?} context)))

(defn- days-opt [opts]
  (let [days (get opts :days default-days)]
    (when-not (pos-int? days)
      (fail! "Carder :days must be a positive integer" {:days days}))
    days))

(defn- attr [strand k]
  (attr-get strand k))

(defn- workflow-attr? [[k _]]
  (= "workflow" (namespace k)))

(defn- workflow-plumbing? [strand]
  (contains? excluded-workflow-roles (attr strand :workflow/role)))

(defn- agent-run? [strand]
  (= "true" (attr strand :agent-run/run)))

(defn- excluded? [opts strand]
  (and (not (:include-plumbing? opts))
       (or (workflow-plumbing? strand)
           (agent-run? strand))))

(defn- summary [strand]
  (select-keys strand [:id :title :state :attributes :updated_at]))

(defn- runtime []
  (current/runtime))

(defn- all-strands [rt]
  (api/list rt))

(defn- active-strands [opts]
  (->> (all-strands (runtime))
       (filter #(= "active" (:state %)))
       (remove #(excluded? opts %))
       vec))

(defn- parse-db-time ^LocalDateTime [^String s]
  (try
    (LocalDateTime/parse (str/replace s " " "T"))
    (catch Exception e
      (fail! "Could not parse strand updated_at timestamp" {:updated_at s :cause (ex-message e)}))))

(defn- days-between [updated now]
  (.toDays (Duration/between (.toInstant (parse-db-time updated) ZoneOffset/UTC) now)))

(defn stale
  "Return active strands older than the configured age threshold.

  Options: `:days` positive integer threshold (default `default-days`) and
  `:include-plumbing? true` to include workflow plumbing and agent-run run records.
  Each row is a compact strand summary plus `:days-stale`."
  ([]
   (stale {}))
  ([opts]
   (let [opts (normalize-opts opts :stale)
         days (days-opt opts)
         now (Instant/now)]
     (->> (active-strands opts)
          (map (fn [strand]
                 (assoc (summary strand) :days-stale (days-between (:updated_at strand) now))))
          (filter #(>= (:days-stale %) days))
          (sort-by (juxt :days-stale :id))
          reverse
          vec))))

(defn- datasource [rt]
  (or (:datasource rt)
      (fail! "Carder edge inspection requires an active in-process weaver runtime" {})))

(defn- edge-incidence [rt]
  (let [rows (jdbc/execute! (datasource rt)
                            ["SELECT from_strand_id, to_strand_id, edge_type FROM strand_edges"]
                            {:builder-fn rs/as-unqualified-lower-maps})]
    (reduce (fn [acc {:keys [from_strand_id to_strand_id]}]
              (-> acc
                  (update from_strand_id (fnil inc 0))
                  (update to_strand_id (fnil inc 0))))
            {}
            rows)))

(defn- workflow-attr-carrier? [strand]
  (boolean (some workflow-attr? (:attributes strand))))

(defn orphans
  "Return active strands with no incident edges and no `workflow/*` attributes.

  An orphan has zero incoming and zero outgoing edges across every relation in
  `strand_edges`, including declared acyclic and annotation relations. Workflow
  attribute carriers are excluded from this section even when they have no edges."
  ([]
   (orphans {}))
  ([opts]
   (let [rt (runtime)
         opts (normalize-opts opts :orphans)
         incidence (edge-incidence rt)]
     (->> (active-strands opts)
          (remove workflow-attr-carrier?)
          (remove #(pos? (get incidence (:id %) 0)))
          (map summary)
          (sort-by :id)
          vec))))

(defn- failed-blocker? [strand]
  (contains? failed-run-phases (attr strand :agent-run/phase)))

(defn- blocker-detail [strand]
  (cond-> (summary strand)
    (attr strand :agent-run/phase) (assoc :agent-run/phase (attr strand :agent-run/phase))
    (attr strand :agent-run/error) (assoc :agent-run/error (attr strand :agent-run/error))))

(defn- depends-on-edges [rt]
  (jdbc/execute! (datasource rt)
                 ["SELECT from_strand_id, to_strand_id FROM strand_edges WHERE edge_type = 'depends-on'"]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn blocked-by-failure
  "Return active strands blocked by active failed or exhausted depends-on targets.

  A blocker is any active `depends-on` target whose `agent-run/phase` attribute is
  the string `failed` or `exhausted`. Rows include the blocked strand summary and
  a `:blockers` vector with compact blocker details."
  ([]
   (blocked-by-failure {}))
  ([opts]
   (let [rt (runtime)
         opts (normalize-opts opts :blocked-by-failure)
         candidates (active-strands opts)
         candidate-ids (set (map :id candidates))
         edges (filter #(contains? candidate-ids (:from_strand_id %)) (depends-on-edges rt))
         blocker-ids (distinct (map :to_strand_id edges))
         blockers-by-id (->> (graph/strands-by-ids rt blocker-ids)
                             (filter #(= "active" (:state %)))
                             (filter failed-blocker?)
                             (map (juxt :id identity))
                             (into {}))
         blocker-ids-by-strand (reduce (fn [acc {:keys [from_strand_id to_strand_id]}]
                                         (if (contains? blockers-by-id to_strand_id)
                                           (update acc from_strand_id (fnil conj []) to_strand_id)
                                           acc))
                                       {}
                                       edges)]
     (->> candidates
          (keep (fn [strand]
                  (when-let [ids (seq (get blocker-ids-by-strand (:id strand)))]
                    (assoc (summary strand)
                           :blockers (mapv #(blocker-detail (get blockers-by-id %)) ids)))))
          (sort-by :id)
          vec))))

(defn report
  "Return a JSON-compatible aggregate graph hygiene report.

  Options are passed to all sections. The result includes each section's rows and
  count under `:stale`, `:orphans`, and `:blocked-by-failure`."
  ([]
   (report {}))
  ([opts]
   (let [opts (normalize-opts opts :report)
         stale-rows (stale opts)
         orphan-rows (orphans opts)
         blocked-rows (blocked-by-failure opts)]
     {:opts {:days (days-opt opts)
             :include-plumbing? (boolean (:include-plumbing? opts))}
      :stale {:count (count stale-rows) :rows stale-rows}
      :orphans {:count (count orphan-rows) :rows orphan-rows}
      :blocked-by-failure {:count (count blocked-rows) :rows blocked-rows}})))

(defn install!
  "Return carder installation metadata for trusted registration by name."
  []
  {:installed true
   :namespace 'skein.spools.carder
   :carder {:stale 'skein.spools.carder/stale
            :orphans 'skein.spools.carder/orphans
            :blocked-by-failure 'skein.spools.carder/blocked-by-failure
            :report 'skein.spools.carder/report
            :default-days default-days
            :read-only true}})
