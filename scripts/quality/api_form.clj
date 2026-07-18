(ns quality.api-form
  "The api-form slice of quality.conventions-check: SPEC-003.C19a over
  converted `skein.api.*` modules.

  A converted module's `alpha.clj` holds no private vars (plumbing lives in
  the sibling `internal` namespace) and none of the module's source lines
  pass column 96. `pending` names the modules not yet converted; each
  conversion card under epic 9nu0q deletes its entry, and a stale or
  already-conformant entry is itself a finding, so the set can only shrink
  truthfully. Kept clj-kondo-free so the findings logic loads on the test
  classpath; the caller supplies kondo's analysis data."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def pending
  "Modules not yet through their v1 form card (epic 9nu0q)."
  #{"batch" "cli" "current" "events" "graph" "hooks" "notes" "patterns"
    "peers" "relations" "return-shape" "runtime" "scheduler" "spool"
    "vocab" "weaver"})

(def ^:private width-limit 96)

(def ^:private api-root "src/skein/api")

(defn module-dirs
  "Return {module-name module-dir} for every directory under `api-root`,
  with directory underscores read back as namespace dashes."
  []
  (into {}
        (for [^java.io.File dir (.listFiles (io/file api-root))
              :when (.isDirectory dir)]
          [(str/replace (.getName dir) "_" "-") dir])))

(defn- wide-lines
  "Return {:file :line :length} for every line past `width-limit` in the
  .clj files under `dir`."
  [^java.io.File dir]
  (for [^java.io.File file (sort (file-seq dir))
        :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))
        [idx line] (map-indexed vector (str/split-lines (slurp file)))
        :when (> (count line) width-limit)]
    {:file (.getPath file) :line (inc idx) :length (count line)}))

(defn- private-vars
  "Return the kondo var definitions marked private in `dir`/alpha.clj."
  [analysis ^java.io.File dir]
  (let [alpha-path (.getPath (io/file dir "alpha.clj"))]
    (filter #(and (:private %) (= alpha-path (:filename %)))
            (:var-definitions analysis))))

(defn findings
  "Enforce the SPEC-003.C19a form contract over the api modules in `dirs`
  ({module-name module-dir}), skipping modules named in `pending-set` but
  flagging pending entries that are stale or already conformant."
  [analysis dirs pending-set]
  (let [conformant? (fn [dir]
                      (and (empty? (private-vars analysis dir))
                           (empty? (wide-lines dir))))]
    (concat
     (for [m (sort pending-set)
           :when (not (contains? dirs m))]
       (str "conventions-check: api-form pending entry `" m
            "` matches no module directory under " api-root))
     (for [[m dir] (sort dirs)
           :when (and (contains? pending-set m) (conformant? dir))]
       (str "conventions-check: pending module `" m "` already satisfies "
            "SPEC-003.C19a; delete it from quality.api-form/pending"))
     (for [[m dir] (sort dirs)
           :when (not (contains? pending-set m))
           finding (concat
                    (for [{:keys [filename row name]} (private-vars analysis dir)]
                      (str filename ":" row ": private var `" name "` in a converted"
                           " api module; plumbing belongs in skein.api." m ".internal"
                           " (SPEC-003.C19a)"))
                    (for [{:keys [file line length]} (wide-lines dir)]
                      (str file ":" line ": line is " length " columns; converted api"
                           " modules wrap at " width-limit " (SPEC-003.C19a)")))]
       finding))))

(defn check
  "Run `findings` over the live tree and the current `pending` set."
  [analysis]
  (findings analysis (module-dirs) pending))
