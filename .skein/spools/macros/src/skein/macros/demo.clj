(ns skein.macros.demo
  "Demonstrates the macros.patterns defp helper in this workspace."
  (:require [skein.macros.patterns :refer [defp forget-patterns! install-patterns!]]))

;; Reload correctness: clear this namespace's remembered patterns before the
;; defp form below re-registers, so this file's current source is exactly what
;; installs rather than also re-registering patterns since renamed or removed
;; (TEN-003).
;;
;; Limitation: .skein/init.clj loads this namespace via `:ns`, and a bare
;; `runtime/reload!` reruns install! but skips already-loaded namespaces,
;; so this top-level forget does NOT re-run. To pick up renamed or removed
;; patterns here, reload the namespace explicitly: (require 'skein.macros.demo
;; :reload).
(forget-patterns! 'skein.macros.demo)

(defp macros-demo
  "Create a tiny two-step dependency chain to prove defp-backed patterns load."
  {:input {:title string?}
   :optional {:owner string?}}
  [{:keys [input]}]
  (let [owner (or (:owner input) "ct")]
    [{:ref 'start
      :title (:title input)
      :attributes {:kind "macros-demo" :phase "start" :owner owner}}
     {:ref 'finish
      :title (str "Finish: " (:title input))
      :attributes {:kind "macros-demo" :phase "finish" :owner owner}
      :edges [{:type "depends-on" :to 'start}]}]))

(defn install!
  "Install demo patterns defined with skein.macros.patterns/defp."
  []
  (install-patterns! 'skein.macros.demo))
