(ns skein.macros.demo
  "Demonstrates the macros.patterns defp helper in this workspace."
  (:require [skein.macros.patterns :refer [defp]]))

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
