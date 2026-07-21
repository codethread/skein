(ns skein.api.batch.alpha
  "Explicit-runtime API for applying batch graph mutations.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns transactional batch persistence, attribute
  normalization through transform hooks, the pre-commit validation gate, and the
  batch plus per-strand event fanout. The SQL batch engine lives in
  `skein.core.db`; the shared lifecycle and dispatch plumbing in
  `skein.core.weaver.*`."
  (:require [clojure.spec.alpha :as s]
            [next.jdbc :as jdbc]
            [skein.core.db :as db]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.dispatch :as dispatch]
            [skein.core.weaver.lifecycle :as lifecycle])
  (:import [java.util UUID]))

(declare normalize-strand-attributes batch-apply-context
         enqueue-batch-applied! enqueue-strand-fanout! strand-patch-for-ref)

(defn apply!
  "Apply one transactional batch graph mutation payload to `runtime`.

  The payload is a map of `:refs` (symbol -> existing strand id), `:strands`
  (patches keyed by `:ref`), `:edges` (ordered edge ops between refs), and
  `:burn` (refs to burn); `skein.core.db/normalize-batch-payload!` is the
  grammar authority and rejects malformed payloads loudly.

  An `:edges` entry is one of two closed ops. `{:op :upsert ...}` carries
  `:from`, `:to`, `:type`, and optional `:attributes`; it inserts a missing
  edge or replaces attributes on the matching `(from, to, type)` edge, and
  either endpoint may be a ref created earlier in the same payload.
  `{:op :remove ...}` carries exactly `:from`, `:to`, and `:type` — no
  `:attributes`, no other keys — and deletes that exact `(from, to, type)`
  edge; both endpoints must be top-level pre-bound `:refs`, never a ref
  created earlier in the payload. A remove whose exact edge is absent — a
  wrong direction or wrong relation type included — fails loudly and rolls the
  whole batch back. Edge ops execute in submitted `:edges` order inside the
  one transaction, so an ordered `:remove` then `:upsert` of one identity is a
  deterministic program.

  Each returned edge outcome is one transition with exactly `:op`, `:from`,
  `:to`, `:type`, `:before`, and `:after`. `:from` and `:to` are the submitted
  local refs and `:type` the submitted relation text; `:before` and `:after`
  are each `nil` or a normalized edge row with `:from_strand_id`,
  `:to_strand_id`, `:edge_type`, and a decoded-map `:attributes` — durable ids
  and the full attribute map, never storage JSON. An upsert carries its
  pre-image (or `nil` when the edge is new) in `:before` and the written row
  in `:after`; a remove carries the removed row in `:before` and `nil` in
  `:after`. There is no `:edge` alias. The result `:edges`, the
  `:batch/apply-before-commit` hook's `:batch/edge-ops`, and the
  `:batch/applied` event's `:batch/edges` are equal ordered transition
  vectors.

  Normalizes strand attributes through the `:attributes/normalize` transform
  hooks, persists the batch atomically, runs the `:batch/apply-before-commit`
  validation gate inside the transaction, then enqueues the batch event
  followed by the per-strand created/updated/burned fanout."
  ([runtime payload]
   (apply! runtime payload (lifecycle/request-context :apply-batch)))
  ([runtime payload req-ctx]
   (let [submitted-payload payload
         normalized-payload (normalize-strand-attributes
                             runtime req-ctx (db/normalize-batch-payload! payload))
         result (jdbc/with-transaction [tx (access/ds runtime)]
                  (let [result (access/normalize
                                (db/apply-batch-in-transaction! tx normalized-payload))]
                    (lifecycle/run-validation-hooks!
                     runtime
                     :batch/apply-before-commit
                     (batch-apply-context req-ctx submitted-payload result))
                    result))
         batch-id (str (UUID/randomUUID))]
     (enqueue-batch-applied! runtime batch-id result)
     (enqueue-strand-fanout! runtime batch-id normalized-payload result)
     result)))

;; A runtime is an opaque, non-nil handle; callers select it and pass it first.
(s/def ::runtime some?)

(s/fdef apply!
  :args (s/or :default (s/cat :runtime ::runtime :payload map?)
              :with-ctx (s/cat :runtime ::runtime :payload map? :req-ctx map?))
  :ret map?)

;; --- attribute normalization -------------------------------------------------

(defn- normalize-strand-attributes [runtime req-ctx payload]
  (update payload :strands
          (fn [strands]
            (mapv (fn [{:keys [ref attributes] :as strand}]
                    (if (nil? attributes)
                      strand
                      (assoc strand :attributes
                             (lifecycle/run-transform-hooks
                              runtime
                              :attributes/normalize
                              (merge req-ctx
                                     {:hook/value attributes
                                      :mutation/operation :batch/apply
                                      :batch/ref ref
                                      :strand/patch strand})))))
                  strands))))

;; --- validation-gate context -------------------------------------------------

(defn- batch-apply-context [req-ctx payload result]
  (merge req-ctx
         {:mutation/operation :batch/apply
          :batch/source :apply
          :batch/payload payload
          :batch/refs (:refs result)
          :batch/created (:created result)
          :batch/updated (:updated result)
          :batch/burned (:burned result)
          :batch/edge-ops (:edges result)}))

;; --- event fanout ------------------------------------------------------------

(defn- enqueue-batch-applied! [runtime batch-id result]
  (dispatch/enqueue! runtime (assoc (lifecycle/event-base :batch/applied)
                                    :batch/id batch-id
                                    :batch/refs (:refs result)
                                    :batch/created (:created result)
                                    :batch/updated (:updated result)
                                    :batch/burned (:burned result)
                                    :batch/edges (:edges result))))

(defn- enqueue-strand-fanout! [runtime batch-id payload result]
  (doseq [created (:created result)]
    (dispatch/enqueue! runtime (assoc (lifecycle/event-base :strand/added)
                                      :batch/id batch-id
                                      :strand/id (:id created)
                                      :strand created)))
  (doseq [{:keys [ref id before after]} (:updated result)]
    (dispatch/enqueue! runtime (assoc (lifecycle/event-base :strand/updated)
                                      :batch/id batch-id
                                      :strand/id id
                                      :strand/patch (strand-patch-for-ref payload ref)
                                      :strand/before before
                                      :strand/after after)))
  (when (seq (:burned result))
    (dispatch/enqueue! runtime (assoc (lifecycle/event-base :strand/burned)
                                      :batch/id batch-id
                                      :strand/requested-ids (mapv :id (:burned result))
                                      :strand/burned-ids (mapv :id (:burned result))
                                      :strand/before (mapv :before (:burned result))))))

;; --- leaf mechanics ----------------------------------------------------------

(defn- strand-patch-for-ref [payload ref]
  (some (fn [strand]
          (when (= ref (:ref strand))
            (dissoc strand :ref)))
        (:strands payload)))
