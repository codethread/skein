(ns quality.conventions-check
  "Enforce repo-wide Clojure conventions that prose alone cannot hold.

  Two checks, both held at zero findings:
  - every namespace carries a docstring;
  - no local binding is named after a clojure.core macro (a local named
    `fn` shadows the macro and turns later thunks into eager calls; rename
    on destructure instead: `{fn-sym :fn}`)."
  (:require [clj-kondo.core :as kondo]))

(def ^:private source-roots
  ;; Everything lintable: engine, batteries, local-root spools, trusted
  ;; workspace config, and tests. A missing root must fail the gate, not
  ;; silently shrink the scanned set.
  ["src"
   "spools/batteries/src"
   "spools/workflow/src"
   "spools/ephemeral/src"
   "spools/roster/src"
   "spools/loom/src"
   "spools/carder/src"
   "spools/text-search/src"
   "spools/bobbin/src"
   "spools/guild/src"
   "spools/selvage/src"
   "spools/agent-run/src"
   "spools/delegation/src"
   "spools/chime/src"
   "spools/cron/src"
   "spools/bench/src"
   ".skein"
   "test"])

(def ^:private core-macro-names
  (->> (ns-publics 'clojure.core)
       vals
       (filter #(:macro (meta %)))
       (map #(-> % symbol name))
       set))

(defn -main [& _]
  (doseq [root source-roots]
    (when-not (.isDirectory (java.io.File. root))
      (binding [*out* *err*]
        (println "conventions-check: configured source root does not exist:" root))
      (System/exit 1)))
  (let [{:keys [analysis]} (kondo/run! {:lint source-roots
                                        :config {:analysis {:locals true}}})
        undocumented (->> (:namespace-definitions analysis)
                          (remove :doc)
                          (map (juxt :filename :name)))
        macro-shadows (->> (:locals analysis)
                           (filter #(core-macro-names (str (:name %))))
                           (map (juxt :filename :row :name)))
        findings (concat
                  (for [[file ns-name] undocumented]
                    (str file ": namespace " ns-name " has no docstring"))
                  (for [[file row local] macro-shadows]
                    (str file ":" row ": local `" local
                         "` shadows the clojure.core macro; rename on destructure"
                         " (e.g. `{" local "-sym :" local "}`)")))]
    (if (seq findings)
      (do (binding [*out* *err*]
            (doseq [f findings] (println f))
            (println (str "conventions-check: " (count findings) " finding(s)")))
          (System/exit 1))
      (println "conventions-check: OK"))))
