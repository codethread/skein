(ns skein.api.patterns.alpha
  "Explicit-runtime API for registering, inspecting, and invoking weave patterns.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns pattern validation, function resolution, input
  spec validation and caller guidance, and the transactional create-only batch a
  weave produces. The SQL batch engine lives in `skein.core.db`; the shared
  lifecycle and dispatch plumbing in `skein.core.weaver.*`."
  (:require [next.jdbc :as jdbc]
            [skein.api.patterns.internal.contract :as contract]
            [skein.api.patterns.internal.registry :as registry]
            [skein.api.patterns.internal.weave :as weave]
            [skein.core.db :as db]
            [skein.core.weaver.access :refer [ds normalize pattern-registry
                                              with-spool-classloader]]
            [skein.core.weaver.dispatch :as dispatch]
            [skein.core.weaver.lifecycle :refer [event-base request-context
                                                 run-validation-hooks!]])
  (:import [java.util UUID]))

(defn register-pattern!
  "Register a trusted weaver pattern handler and input spec in `runtime`."
  ([runtime pattern-name fn-sym input-spec]
   (register-pattern! runtime pattern-name nil fn-sym input-spec))
  ([runtime pattern-name doc fn-sym input-spec]
   (let [entry (registry/entry pattern-name doc fn-sym input-spec)]
     (swap! (pattern-registry runtime) assoc (:name entry) entry)
     entry)))

(defn patterns
  "Return registered weave pattern metadata from `runtime`, ordered by name."
  [runtime]
  (mapv val (sort-by key @(pattern-registry runtime))))

(defn resolve-pattern
  "Return the registered weave pattern for a simple symbol or keyword name.

  Missing patterns fail loudly."
  [runtime pattern-name]
  (let [canonical-name (registry/canonical-name pattern-name)
        registered @(pattern-registry runtime)]
    (or (get registered canonical-name)
        (throw (ex-info "Pattern not found" {:pattern pattern-name
                                             :canonical-pattern canonical-name
                                             :available (sort (keys registered))})))))

(defn explain
  "Describe a registered weave pattern and its input contract in `runtime`.

  Missing patterns or unregistered input specs fail loudly."
  [runtime pattern-name]
  ;; :fn is renamed on destructure: a local named `fn` shadows the fn macro.
  (let [{:keys [name doc input-spec] fn-sym :fn} (resolve-pattern runtime pattern-name)
        input-contract (contract/input-contract input-spec)]
    (cond-> (merge {:name name
                    :fn (str fn-sym)
                    :input-spec (str input-spec)
                    :spec-form (:spec-form input-contract)}
                   (select-keys input-contract [:summary :required :optional]))
      doc (assoc :doc doc))))

(defn weave!
  "Validate pattern input, invoke the pattern, and apply its create-only batch.

  The four-argument arity threads an explicit request-context map for trusted
  callers (the connected-client tier); the three-argument arity derives its own
  weave context."
  ([runtime pattern-name input]
   (weave! runtime pattern-name input (request-context :weave)))
  ([runtime pattern-name input req-ctx]
   (let [{fn-sym :fn input-spec :input-spec} (resolve-pattern runtime pattern-name)
         canonical-name (registry/canonical-name pattern-name)]
     (contract/validate-input! canonical-name input-spec input)
     (let [batch (with-spool-classloader
                   runtime
                   #((requiring-resolve fn-sym) {:input input}))
           normalized-batch (weave/normalize-strand-attributes
                             runtime req-ctx canonical-name input batch)
           normalized-payload (weave/payload normalized-batch)
           result (jdbc/with-transaction [tx (ds runtime)]
                    (let [result (normalize
                                  (db/add-strand-batch-in-transaction! tx normalized-batch))]
                      (run-validation-hooks! runtime
                                             :batch/apply-before-commit
                                             (weave/batch-context req-ctx canonical-name input
                                                                  normalized-payload result))
                      result))]
       ;; a weave is a create-only batch apply; without this event, event-driven
       ;; spools (agent-run, the subagent executor) never see pattern-created
       ;; strands until an unrelated mutation happens to trigger their next scan
       (dispatch/enqueue! runtime (assoc (event-base :batch/applied)
                                         :batch/id (str (UUID/randomUUID))
                                         :pattern/name canonical-name
                                         :batch/refs (:refs result)
                                         :batch/created (:created result)))
       (select-keys result [:created :refs])))))
