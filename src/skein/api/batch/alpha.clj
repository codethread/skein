(ns skein.api.batch.alpha
  "Explicit-runtime API for applying batch graph mutations.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns transactional batch persistence, attribute
  normalization through transform hooks, the pre-commit validation gate, and the
  batch plus per-strand event fanout. The SQL batch engine lives in
  `skein.core.db`; the shared lifecycle and dispatch plumbing in
  `skein.core.weaver.*`."
  (:require [next.jdbc :as jdbc]
            [skein.core.db :as db]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.dispatch :as dispatch]
            [skein.core.weaver.lifecycle :as lifecycle])
  (:import [java.util UUID]))

(defn- strand-patch-for-ref [payload ref]
  (some (fn [strand]
          (when (= ref (:ref strand))
            (dissoc strand :ref)))
        (:strands payload)))

(defn- enqueue-batch-fanout! [runtime batch-id payload result]
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

(defn- normalize-batch-strand-attributes [runtime req-ctx payload]
  (update payload :strands
          (fn [strands]
            (mapv (fn [{:keys [ref attributes] :as strand}]
                    (if (nil? attributes)
                      strand
                      (assoc strand :attributes
                             (lifecycle/run-transform-hooks runtime
                                                            :attributes/normalize
                                                            (merge req-ctx
                                                                   {:hook/value attributes
                                                                    :mutation/operation :batch/apply
                                                                    :batch/ref ref
                                                                    :strand/patch strand})))))
                  strands))))

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

(defn apply!
  "Apply one transactional batch graph mutation payload to `runtime`.

  Persists the batch atomically, runs the `:batch/apply-before-commit`
  validation gate inside the transaction, then enqueues the batch event followed
  by the per-strand created/updated/burned fanout."
  ([runtime payload]
   (apply! runtime payload (lifecycle/request-context :apply-batch)))
  ([runtime payload req-ctx]
   (let [submitted-payload payload
         normalized-payload (normalize-batch-strand-attributes runtime req-ctx (db/normalize-batch-payload! payload))
         result (jdbc/with-transaction [tx (access/ds runtime)]
                  (let [result (access/normalize (db/apply-batch-in-transaction! tx normalized-payload))]
                    (lifecycle/run-validation-hooks! runtime
                                                     :batch/apply-before-commit
                                                     (batch-apply-context req-ctx submitted-payload result))
                    result))
         batch-id (str (UUID/randomUUID))]
     (dispatch/enqueue! runtime (assoc (lifecycle/event-base :batch/applied)
                                       :batch/id batch-id
                                       :batch/refs (:refs result)
                                       :batch/created (:created result)
                                       :batch/updated (:updated result)
                                       :batch/burned (:burned result)
                                       :batch/edges (:edges result)))
     (enqueue-batch-fanout! runtime batch-id normalized-payload result)
     result)))
