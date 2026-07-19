(ns skein.api.batch.internal.fanout
  "Event-fanout plumbing for `skein.api.batch.alpha`.

  Owns the `:batch/applied` event and the per-strand created/updated/burned
  fanout enqueued after a batch commits."
  (:require [skein.core.weaver.dispatch :as dispatch]
            [skein.core.weaver.lifecycle :as lifecycle]))

(defn- strand-patch-for-ref [payload ref]
  (some (fn [strand]
          (when (= ref (:ref strand))
            (dissoc strand :ref)))
        (:strands payload)))

(defn enqueue-batch-applied!
  "Enqueue the single `:batch/applied` event carrying the whole batch result."
  [runtime batch-id result]
  (dispatch/enqueue! runtime (assoc (lifecycle/event-base :batch/applied)
                                    :batch/id batch-id
                                    :batch/refs (:refs result)
                                    :batch/created (:created result)
                                    :batch/updated (:updated result)
                                    :batch/burned (:burned result)
                                    :batch/edges (:edges result))))

(defn enqueue-strand-fanout!
  "Enqueue one event per created, updated, and burned strand in `result`.

  Updated-strand events carry the submitted patch for their ref, resolved from
  `payload`; burned strands fan out as one `:strand/burned` event."
  [runtime batch-id payload result]
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
