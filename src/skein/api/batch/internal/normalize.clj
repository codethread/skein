(ns skein.api.batch.internal.normalize
  "Attribute-normalization plumbing for `skein.api.batch.alpha`.

  Runs each batch strand's attribute map through the runtime's
  `:attributes/normalize` transform hooks before persistence."
  (:require [skein.core.weaver.lifecycle :as lifecycle]))

(defn strand-attributes
  "Normalize every strand's attributes in `payload` through the transform hooks.

  Strands without attributes pass through untouched; each hook invocation
  carries the request context, the batch ref, and the submitted strand patch."
  [runtime req-ctx payload]
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
