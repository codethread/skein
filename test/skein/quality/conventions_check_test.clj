(ns skein.quality.conventions-check-test
  "Ratchet-edge coverage for the `quality.api-form`, `quality.spool-tiers`,
  and `quality.spool-var` checks.

  The conversion cards each edit `quality.api-form/pending`, so the edges
  that keep that set honest are the behavior worth pinning: a converted
  module with an undocumented public var or a wide line is a finding
  (privates in alpha are story-support, not findings — SPEC-003.C19a); a
  pending module is exempt even when conformant (deleting the entry is
  the card's deliberate act, never forced by an unrelated cleanup); a
  stale entry is a finding; and the internal dependency rules hold for
  every module, pending or not.

  The spool-tiers rules pin the unsafe-namespace convention: shipped
  spool sources use `skein.core.*` only from unsafe-named namespaces
  (SPEC-005.C5), an unsafe name that touches no internals is stale,
  cross-spool unsafe requires from safe namespaces are findings, and
  the `UNSAFE:` docstring lead agrees with the name in both
  directions.

  The spool-var rules pin PROP-Dsp-001.G6a: a public `spool` var in a
  module-loadable namespace must carry a well-formed `::spool` value (a
  map keyed by a non-empty subset of `:contribute`/`:reconcile`, each a
  quoted entry-point symbol). Malformed and incidental public values are
  findings; private `spool` vars are ignored, and the guard is
  structural — it reads the authored literal, never resolving symbols."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [quality.api-form :as api-form]
   [quality.spool-tiers :as spool-tiers]
   [quality.spool-var :as spool-var])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

(def ^:private conformant-source
  "(ns m.alpha \"Doc.\")\n(defn ok \"Doc.\" [] :ok)\n")

(defn- module-dir!
  "Create module directory `name` under `root` holding an alpha.clj with
  `source`; return the directory as a File."
  [root name source]
  (let [dir (io/file root name)]
    (.mkdirs dir)
    (spit (io/file dir "alpha.clj") source)
    dir))

(defn- with-modules
  "Run `f` with {module-name dir} built from `sources` ({name source}) in a
  fresh temp root."
  [sources f]
  (let [root (.toFile (Files/createTempDirectory
                       "conventions-check-test" (make-array FileAttribute 0)))]
    (f (into {} (for [[name source] sources]
                  [name (module-dir! root name source)])))))

(defn- var-def
  "One kondo-shaped var-definition row for `dir`/alpha.clj."
  [dir attrs]
  (merge {:filename (.getPath (io/file dir "alpha.clj")) :row 2} attrs))

(deftest private-vars-in-a-converted-alpha-are-not-findings
  (with-modules {"tidy" conformant-source}
    (fn [dirs]
      (let [analysis {:var-definitions [(var-def (dirs "tidy")
                                                 {:private true :name 'helper})]}]
        (is (empty? (api-form/findings analysis dirs #{})))))))

(deftest converted-module-with-undocumented-public-var-is-a-finding
  (with-modules {"tidy" conformant-source}
    (fn [dirs]
      (let [analysis {:var-definitions
                      [(var-def (dirs "tidy") {:name 'bare})
                       ;; declare sites carry no doc; the definition does.
                       (var-def (dirs "tidy") {:name 'forward
                                               :defined-by 'clojure.core/declare})
                       (var-def (dirs "tidy") {:name 'ok :doc "Doc."})]}
            findings (api-form/findings analysis dirs #{})]
        (is (= 1 (count findings)))
        (is (re-find #"public var `bare`.*no docstring" (first findings)))))))

(deftest converted-module-with-wide-line-is-a-finding
  (with-modules {"tidy" (str "(ns m.alpha \"Doc.\")\n;; "
                             (str/join (repeat 100 "x")) "\n")}
    (fn [dirs]
      (let [findings (api-form/findings {} dirs #{})]
        (is (= 1 (count findings)))
        (is (re-find #"alpha\.clj:2: line is 103 columns" (first findings)))))))

(deftest conformant-converted-module-yields-no-findings
  (with-modules {"tidy" conformant-source}
    (fn [dirs]
      (is (empty? (api-form/findings {} dirs #{}))))))

(deftest pending-module-is-exempt-even-when-conformant
  (testing "nonconformance in a pending module stays silent"
    (with-modules {"messy" (str "(ns m.alpha \"Doc.\")\n;; "
                                (str/join (repeat 100 "x")) "\n")}
      (fn [dirs]
        (let [analysis {:var-definitions [(var-def (dirs "messy")
                                                   {:name 'bare})]}]
          (is (empty? (api-form/findings analysis dirs #{"messy"})))))))
  (testing "a conformant pending module forces nothing; deletion is deliberate"
    (with-modules {"tidy" conformant-source}
      (fn [dirs]
        (is (empty? (api-form/findings {} dirs #{"tidy"})))))))

(deftest stale-pending-entry-is-a-finding
  (with-modules {"tidy" conformant-source}
    (fn [dirs]
      (let [findings (api-form/findings {} dirs #{"tidy" "gone"})]
        (is (= 1 (count findings)))
        (is (re-find #"entry `gone` matches no module directory"
                     (first findings)))))))

(deftest internal-namespaces-never-require-alpha
  (let [usage {:from 'skein.api.tidy.internal :to 'skein.api.other.alpha
               :filename "src/skein/api/tidy/internal.clj" :row 3}
        findings (api-form/findings {:namespace-usages [usage]} {} #{})]
    (is (= 1 (count findings)))
    (is (re-find #"plumbing stays tier-free" (first findings)))))

(deftest only-own-alpha-reaches-internal
  (testing "a foreign src namespace requiring internal is a finding"
    (let [usage {:from 'skein.core.weaver :to 'skein.api.tidy.internal
                 :filename "src/skein/core/weaver.clj" :row 3}
          findings (api-form/findings {:namespace-usages [usage]} {} #{})]
      (is (= 1 (count findings)))
      (is (re-find #"only the module's own alpha" (first findings)))))
  (testing "absolute kondo filenames still hit the source-tier rule"
    (let [usage {:from 'skein.core.weaver :to 'skein.api.tidy.internal
                 :filename "/abs/repo/src/skein/core/weaver.clj" :row 3}]
      (is (= 1 (count (api-form/findings {:namespace-usages [usage]} {} #{}))))))
  (testing "the module's own alpha, internal siblings, and tests are allowed"
    (let [own {:from 'skein.api.tidy.alpha :to 'skein.api.tidy.internal
               :filename "src/skein/api/tidy/alpha.clj" :row 3}
          nested {:from 'skein.api.tidy.alpha :to 'skein.api.tidy.internal.validate
                  :filename "src/skein/api/tidy/alpha.clj" :row 4}
          sibling {:from 'skein.api.tidy.internal.validate
                   :to 'skein.api.tidy.internal.shared
                   :filename "src/skein/api/tidy/internal/validate.clj" :row 3}
          test-use {:from 'skein.api.tidy.alpha-test :to 'skein.api.tidy.internal
                    :filename "test/skein/api/tidy/alpha_test.clj" :row 3}]
      (is (empty? (api-form/findings
                   {:namespace-usages [own nested sibling test-use]} {} #{})))))
  (testing "a foreign module's internal is still fenced, nested or not"
    (let [usage {:from 'skein.api.other.internal.helpers
                 :to 'skein.api.tidy.internal.validate
                 :filename "src/skein/api/other/internal/helpers.clj" :row 3}]
      (is (= 1 (count (api-form/findings {:namespace-usages [usage]} {} #{})))))))

(defn- spool-file
  "Source path for namespace `from-ns` inside spool `spool`."
  [spool from-ns]
  (str "spools/" spool "/src/"
       (-> (str from-ns) (str/replace "-" "_") (str/replace "." "/")) ".clj"))

(defn- spool-usage
  "One kondo-shaped usage row from `from-ns` in spool `spool` to `to-ns`."
  [spool from-ns to-ns row]
  {:from from-ns :to to-ns :filename (spool-file spool from-ns) :row row})

(defn- spool-ns-def
  "One kondo-shaped namespace-definition row for `from-ns` in `spool`."
  [spool from-ns doc]
  (cond-> {:name from-ns :filename (spool-file spool from-ns) :row 1}
    doc (assoc :doc doc)))

(deftest core-usage-from-safe-namespace-is-a-finding
  (testing "a require and a qualified var usage are each findings"
    (let [findings (spool-tiers/findings
                    {:namespace-usages [(spool-usage "tidy" 'skein.spools.tidy
                                                     'skein.core.db 5)]
                     :var-usages [(spool-usage "tidy" 'skein.spools.tidy
                                               'skein.core.weaver.module-refresh
                                               40)]}
                    #{"tidy"})]
      (is (= 2 (count findings)))
      (is (every? #(re-find #"spools build on `skein.api" %) findings))))
  (testing "absolute kondo filenames still hit the rule"
    (let [usage (assoc (spool-usage "tidy" 'skein.spools.tidy 'skein.core.db 5)
                       :filename "/abs/repo/spools/tidy/src/skein/spools/tidy.clj")]
      (is (= 1 (count (spool-tiers/findings {:namespace-usages [usage]}
                                            #{"tidy"})))))))

(deftest core-usage-from-unsafe-named-namespace-is-permitted
  (let [analysis {:namespace-definitions
                  [(spool-ns-def "unsafe-tidy" 'skein.spools.unsafe-tidy
                                 "UNSAFE: reads skein.core.db directly.")]
                  :namespace-usages
                  [(spool-usage "unsafe-tidy" 'skein.spools.unsafe-tidy
                                'skein.core.db 5)]}]
    (is (empty? (spool-tiers/findings analysis #{"unsafe-tidy"})))))

(deftest unsafe-marker-matches-segments-not-substrings
  (testing "a substring hit grants nothing"
    (let [analysis {:namespace-usages
                    [(spool-usage "tidy" 'skein.spools.notunsafe
                                  'skein.core.db 5)]}]
      (is (= 1 (count (spool-tiers/findings analysis #{"tidy"}))))))
  (testing "a bare `unsafe` segment is a marker"
    (is (spool-tiers/unsafe-ns? 'skein.spools.tidy.unsafe))
    (is (not (spool-tiers/unsafe-ns? 'skein.spools.unsafely)))))

(deftest core-usage-outside-shipped-spool-sources-is-out-of-scope
  (let [engine {:from 'skein.core.weaver :to 'skein.core.db
                :filename "src/skein/core/weaver.clj" :row 3}
        test-use {:from 'skein.spools.tidy-test :to 'skein.core.db
                  :filename "spools/tidy/test/skein/spools/tidy_test.clj" :row 3}
        local-spool {:from 'skein.macros.rules :to 'skein.core.weaver.module-refresh
                     :filename ".skein/spools/macros/src/skein/macros/rules.clj"
                     :row 3}]
    (is (empty? (spool-tiers/findings
                 {:namespace-usages [engine test-use local-spool]}
                 #{"tidy"})))))

(deftest own-spool-unsafe-require-is-permitted-cross-spool-is-not
  (let [defs [(spool-ns-def "tidy" 'skein.spools.tidy.unsafe-db
                            "UNSAFE: reads skein.core.db directly.")
              (spool-ns-def "tidy" 'skein.spools.tidy "Doc.")
              (spool-ns-def "other" 'skein.spools.other "Doc.")]
        core-use (spool-usage "tidy" 'skein.spools.tidy.unsafe-db
                              'skein.core.db 5)
        own-use (spool-usage "tidy" 'skein.spools.tidy
                             'skein.spools.tidy.unsafe-db 3)
        foreign-use (spool-usage "other" 'skein.spools.other
                                 'skein.spools.tidy.unsafe-db 3)]
    (testing "a safe ns wrapping its own spool's unsafe boundary is clean"
      (is (empty? (spool-tiers/findings
                   {:namespace-definitions defs
                    :namespace-usages [core-use own-use]}
                   #{"tidy" "other"}))))
    (testing "a safe ns requiring another spool's unsafe ns is a finding"
      (let [findings (spool-tiers/findings
                      {:namespace-definitions defs
                       :namespace-usages [core-use own-use foreign-use]}
                      #{"tidy" "other"})]
        (is (= 1 (count findings)))
        (is (re-find #"from another spool" (first findings)))))))

(deftest stale-unsafe-name-is-a-finding
  (let [analysis {:namespace-definitions
                  [(spool-ns-def "tidy" 'skein.spools.tidy.unsafe-db
                                 "UNSAFE: reads skein.core.db directly.")]
                  :namespace-usages
                  [(spool-usage "tidy" 'skein.spools.tidy.unsafe-db
                                'skein.api.graph.alpha 5)]}]
    (is (= 1 (count (spool-tiers/findings analysis #{"tidy"}))))
    (is (re-find #"drop the unsafe marker"
                 (first (spool-tiers/findings analysis #{"tidy"}))))))

(deftest unsafe-docstring-lead-agrees-with-the-name
  (testing "unsafe-named ns without the UNSAFE: lead is a finding"
    (let [analysis {:namespace-definitions
                    [(spool-ns-def "tidy" 'skein.spools.tidy.unsafe-db "Doc.")]
                    :namespace-usages
                    [(spool-usage "tidy" 'skein.spools.tidy.unsafe-db
                                  'skein.core.db 5)]}
          findings (spool-tiers/findings analysis #{"tidy"})]
      (is (= 1 (count findings)))
      (is (re-find #"must lead with `UNSAFE:`" (first findings)))))
  (testing "safe ns claiming UNSAFE: in its docstring is a finding"
    (let [analysis {:namespace-definitions
                    [(spool-ns-def "tidy" 'skein.spools.tidy
                                   "UNSAFE: but the name says otherwise.")]}
          findings (spool-tiers/findings analysis #{"tidy"})]
      (is (= 1 (count findings)))
      (is (re-find #"the marker is the name" (first findings))))))

(defn- def-spool-sites
  "Read `content` as a temp source file and return its `(def spool …)`
  sites, each tagged with a stub filename for `spool-var/findings`."
  [content]
  (let [file (io/file (.toFile (Files/createTempDirectory
                                "spool-var-test" (make-array FileAttribute 0)))
                      "spool.clj")]
    (spit file content)
    (map #(assoc % :filename "spools/tidy/src/skein/spools/tidy.clj")
         (spool-var/def-spool-sites file))))

(defn- findings-for
  "Findings for a single-form `content` string."
  [content]
  (spool-var/findings (def-spool-sites content)))

(deftest well-formed-public-spool-var-is-accepted
  (testing "both entry points, qualified quoted symbols"
    (is (empty? (findings-for
                 "(def spool {:contribute 'skein.spools.tidy/contribute
                              :reconcile 'skein.spools.tidy/reconcile})"))))
  (testing "a single entry point is enough"
    (is (empty? (findings-for "(def spool {:reconcile 'skein.spools.tidy/reconcile})")))
    (is (empty? (findings-for "(def spool {:contribute 'skein.spools.tidy/contribute})"))))
  (testing "unqualified entry-point symbols are permitted (G2)"
    (is (empty? (findings-for "(def spool {:contribute 'contribute})"))))
  (testing "a docstring form reports the value, not the docstring"
    (is (empty? (findings-for
                 "(def spool \"Base declaration.\" {:reconcile 'skein.spools.tidy/reconcile})")))))

(deftest private-spool-var-is-ignored-even-when-malformed
  (is (empty? (findings-for "(def ^:private spool {:contribute 42})")))
  (is (empty? (findings-for "(def ^:private spool \"not a declaration\")"))))

(deftest incidental-non-map-public-spool-var-is-a-finding
  (let [findings (findings-for "(def spool \"a shadowing string\")")]
    (is (= 1 (count findings)))
    (is (re-find #"authored value is not a map" (first findings)))
    (is (re-find #"PROP-Dsp-001.G6a" (first findings)))))

(deftest public-spool-var-with-no-value-is-a-finding
  (let [findings (findings-for "(def spool)")]
    (is (= 1 (count findings)))
    (is (re-find #"has no value" (first findings)))))

(deftest malformed-public-spool-values-are-findings
  (testing "a map with neither entry point"
    (let [findings (findings-for "(def spool {})")]
      (is (= 1 (count findings)))
      (is (re-find #"declares neither :contribute nor :reconcile" (first findings)))))
  (testing "a leftover :ns key from an incomplete rename"
    (let [findings (findings-for
                    "(def spool {:ns 'skein.spools.tidy
                                 :contribute 'skein.spools.tidy/contribute})")]
      (is (= 1 (count findings)))
      (is (re-find #"has unsupported key :ns" (first findings)))))
  (testing "a non-symbol entry-point value"
    (let [findings (findings-for "(def spool {:contribute \"contribute\"})")]
      (is (= 1 (count findings)))
      (is (re-find #"entry point :contribute must be a quoted symbol" (first findings)))))
  (testing "a fn literal is rejected on sight (ADR-002.O1)"
    (let [findings (findings-for "(def spool {:contribute (fn [ctx] ctx)})")]
      (is (= 1 (count findings)))
      (is (re-find #"must be a quoted symbol" (first findings)))))
  (testing "a bare unquoted symbol is not the authored data shape"
    (let [findings (findings-for "(def spool {:contribute contribute})")]
      (is (= 1 (count findings)))
      (is (re-find #"must be a quoted symbol" (first findings))))))

(deftest only-a-var-named-spool-is-a-declaration-site
  (testing "adjacent names are not the reserved var"
    (is (empty? (def-spool-sites "(def spooler {:contribute 42})")))
    (is (empty? (def-spool-sites "(def spool-state {:contribute 42})"))))
  (testing "other public var forms named spool are malformed declaration sites"
    (doseq [content ["(defn spool [ctx] ctx)"
                     "(defmacro spool [form] form)"
                     "(defonce spool {:contribute 'a/b})"]]
      (let [findings (findings-for content)]
        (is (= 1 (count findings)))
        (is (re-find #"must be authored with `def`" (first findings))))))
  (testing "private defn shorthand is unaffected"
    (is (empty? (def-spool-sites "(defn- spool [ctx] ctx)"))))
  (testing "the extraction records privacy and the authored value"
    (let [[site] (def-spool-sites "(def ^:private spool {:reconcile 'a/b})")]
      (is (:private? site))
      (is (:has-value? site))
      (is (map? (:value site))))))

(deftest executable-wrapper-spool-vars-are-declaration-sites
  (testing "nested executable wrappers do not hide a malformed declaration"
    (doseq [content ["(do (def spool {:contribute 42}))"
                     "(when true (do (def spool {:contribute 42})))"
                     "(if true :ok (def spool {:contribute 42}))"
                     "(cond false :ok :else (def spool {:contribute 42}))"]]
      (let [findings (findings-for content)]
        (is (= 1 (count findings)))
        (is (re-find #"entry point :contribute must be a quoted symbol"
                     (first findings))))))
  (testing "quoted data, ordinary calls, function bodies, and unrelated var values stay inert"
    (doseq [content ["'(do (def spool {:contribute 42}))"
                     "(identity (do (def spool {:contribute 42})))"
                     "(def held (do (def spool {:contribute 42})))"
                     "(defn factory [] (do (def spool {:contribute 42})))"]]
      (is (empty? (def-spool-sites content))))))

(deftest spool-value-problem-conforms-and-rejects
  (testing "a conformant map yields no problem"
    (is (nil? (spool-var/spool-value-problem
               {:contribute (list 'quote 'skein.spools.tidy/contribute)}))))
  (testing "an unknown key is the problem before the missing entry point"
    (is (re-find #"has unsupported key :bogus"
                 (spool-var/spool-value-problem {:bogus 1})))))

(deftest unreadable-file-site-is-surfaced-as-a-finding
  (let [findings (spool-var/findings
                  [{:filename "spools/tidy/src/skein/spools/tidy.clj"
                    :read-error "EOF while reading"}])]
    (is (= 1 (count findings)))
    (is (re-find #"could not read file: EOF while reading" (first findings)))))
