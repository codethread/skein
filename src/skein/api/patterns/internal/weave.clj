(ns skein.api.patterns.internal.weave
  "Weave batch normalization, payload projection, and hook-context plumbing for
  `skein.api.patterns.alpha`."
  (:require [skein.core.weaver.lifecycle :refer [run-transform-hooks]]))

(defn- require-batch-vector! [batch]
  (when-not (vector? batch)
    (throw (ex-info "Pattern must return a batch strand vector" {:value batch})))
  batch)

(defn normalize-strand-attributes
  "Run the `:attributes/normalize` transform hooks over every strand in `batch`.

  Requires `batch` to be a vector; strands without attributes pass through."
  [runtime req-ctx pattern-name input batch]
  (mapv (fn [{:keys [ref attributes] :as strand}]
          (if (nil? attributes)
            strand
            (assoc strand :attributes
                   (run-transform-hooks runtime
                                        :attributes/normalize
                                        (merge req-ctx
                                               {:hook/value attributes
                                                :mutation/operation :batch/apply
                                                :batch/ref ref
                                                :strand/patch strand
                                                :pattern/name pattern-name
                                                :pattern/input input})))))
        (require-batch-vector! batch)))

(defn payload
  "Project a normalized batch strand vector into a create-only batch payload."
  [strands]
  {:refs {}
   :strands (mapv #(dissoc % :edges) strands)
   :edges (into []
                (mapcat (fn [{:keys [ref edges]}]
                          (map (fn [edge]
                                 (merge {:op :upsert
                                         :from (some-> ref str)
                                         :to (cond-> (:to edge)
                                               (symbol? (:to edge)) str)}
                                        (select-keys edge [:type :attributes])))
                               edges)))
                strands)
   :burn []})

(defn batch-context
  "Build the `:batch/apply-before-commit` hook context for a weave batch apply."
  [req-ctx pattern-name input payload result]
  (merge req-ctx
         {:mutation/operation :batch/apply
          :batch/source :weave
          :batch/payload payload
          :batch/refs (:refs result)
          :batch/created (:created result)
          :batch/updated []
          :batch/burned []
          :batch/edge-ops (:edges result)
          :pattern/name pattern-name
          :pattern/input input}))
