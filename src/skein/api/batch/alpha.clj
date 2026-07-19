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
            [skein.api.batch.internal.fanout :as fanout]
            [skein.api.batch.internal.normalize :as normalize]
            [skein.core.db :as db]
            [skein.core.weaver.access :as access]
            [skein.core.weaver.lifecycle :as lifecycle])
  (:import [java.util UUID]))

(declare batch-apply-context)

(defn apply!
  "Apply one transactional batch graph mutation payload to `runtime`.

  The payload is a map of `:refs` (symbol -> existing strand id), `:strands`
  (patches keyed by `:ref`), `:edges` (upsert ops between refs), and `:burn`
  (refs to burn); `skein.core.db/normalize-batch-payload!` is the grammar
  authority and rejects malformed payloads loudly. Normalizes strand
  attributes through the `:attributes/normalize` transform hooks, persists the
  batch atomically, runs the `:batch/apply-before-commit` validation gate
  inside the transaction, then enqueues the batch event followed by the
  per-strand created/updated/burned fanout."
  ([runtime payload]
   (apply! runtime payload (lifecycle/request-context :apply-batch)))
  ([runtime payload req-ctx]
   (let [submitted-payload payload
         normalized-payload (normalize/strand-attributes
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
     (fanout/enqueue-batch-applied! runtime batch-id result)
     (fanout/enqueue-strand-fanout! runtime batch-id normalized-payload result)
     result)))

;; A runtime is an opaque, non-nil handle; callers select it and pass it first.
(s/def ::runtime some?)

(s/fdef apply!
  :args (s/or :default (s/cat :runtime ::runtime :payload map?)
              :with-ctx (s/cat :runtime ::runtime :payload map? :req-ctx map?))
  :ret map?)

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
