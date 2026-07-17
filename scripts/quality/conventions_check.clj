(ns quality.conventions-check
  "Enforce repo-wide Clojure conventions that prose alone cannot hold.

  Three checks, all held at zero findings:
  - every namespace carries a docstring;
  - no local binding is named after a clojure.core macro (a local named
    `fn` shadows the macro and turns later thunks into eager calls; rename
    on destructure instead: `{fn-sym :fn}`);
  - every literal `(require ...)` embedded in code-as-data — the quoted
    forms tests route through `skein.test.alpha/repl!` and init fixtures —
    names a namespace that resolves to a source file, so a namespace rename
    cannot silently strand a tested form until weaver-side eval."
  (:require [clj-kondo.core :as kondo]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]))

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

(defn- read-all-forms
  "Read every top-level form in `file`, tolerating the full source reader
  surface (auto-resolved keywords, syntax quote, tagged literals)."
  [^java.io.File file]
  (let [rdr (reader-types/indexing-push-back-reader (slurp file) 1 (.getPath file))
        opts {:eof ::eof :read-cond :allow :features #{:clj}}]
    (binding [*ns* (the-ns 'user)
              reader/*read-eval* false
              ;; Aliased ::kw/name forms only need to read, not resolve
              ;; truthfully; map every alias to a throwaway namespace.
              reader/*alias-map* (fn [alias] (symbol (str "conventions-check." alias)))
              reader/*default-data-reader-fn* (fn [_tag value] value)]
      (loop [forms []]
        (let [form (reader/read opts rdr)]
          (if (= ::eof form)
            forms
            (recur (conj forms form))))))))

(defn- quoted-libspec-ns
  "Return the namespace symbol named by a literal quoted require argument:
  `'ns.sym` or `'[ns.sym ...]`. Dynamically assembled arguments return nil."
  [arg]
  (when (and (seq? arg) (= 'quote (first arg)) (= 2 (count arg)))
    (let [libspec (second arg)]
      (cond
        (symbol? libspec) libspec
        (and (vector? libspec) (symbol? (first libspec))) (first libspec)))))

(defn- quoted-require-calls
  "Return every `(require ...)` call sitting inside quoted data in `form`.

  Requires executing directly in live code are exercised (and sometimes
  deliberately fail) when the suite runs; requires inside quoted data reach
  no loader until weaver-side eval, so only those can silently strand a
  tested form after a namespace rename."
  [form]
  (letfn [(walk [node quoted?]
            (if (coll? node)
              (let [inner? (or quoted? (and (seq? node) (= 'quote (first node))))
                    hit (when (and quoted? (seq? node) (= 'require (first node)))
                          [node])]
                (into (vec hit) (mapcat #(walk % inner?)) (seq node)))
              []))]
    (walk form false)))

(defn- embedded-requires
  "Return {:ns sym :line n} for every literal libspec passed to a `require`
  call inside quoted data anywhere in `form`."
  [form]
  (for [call (quoted-require-calls form)
        arg (rest call)
        :let [ns-sym (quoted-libspec-ns arg)]
        :when ns-sym]
    {:ns ns-sym :line (:line (meta call))}))

(defn- resolvable-namespace?
  "True when `ns-sym` maps to a source file under a repo root or on this
  JVM's classpath (clojure.* and other library namespaces)."
  [ns-sym]
  (let [path (-> (name ns-sym) (str/replace "-" "_") (str/replace "." "/"))
        candidates [(str path ".clj") (str path ".cljc")]]
    (boolean (or (some (fn [root]
                         (some #(.isFile (io/file root %)) candidates))
                       source-roots)
                 (some io/resource candidates)))))

(defn- embedded-require-findings
  "Scan every source file under `source-roots` for embedded literal requires
  of namespaces that resolve nowhere. An unreadable file is itself a finding."
  []
  (for [root source-roots
        ^java.io.File file (sort (file-seq (io/file root)))
        :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))
        finding (try
                  (for [{:keys [ns line]} (embedded-requires (read-all-forms file))
                        :when (not (resolvable-namespace? ns))]
                    (str (.getPath file) ":" line ": embedded require of `" ns
                         "` resolves to no source file under the repo roots or classpath"))
                  (catch Exception e
                    [(str (.getPath file) ": embedded-require scan could not read file: "
                          (ex-message e))]))]
    finding))

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
                         " (e.g. `{" local "-sym :" local "}`)"))
                  (embedded-require-findings))]
    (if (seq findings)
      (do (binding [*out* *err*]
            (doseq [f findings] (println f))
            (println (str "conventions-check: " (count findings) " finding(s)")))
          (System/exit 1))
      (println "conventions-check: OK"))))
