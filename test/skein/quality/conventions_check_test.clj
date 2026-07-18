(ns skein.quality.conventions-check-test
  "Ratchet-edge coverage for the api-form check in `quality.api-form`.

  Sixteen conversion cards will each edit `quality.api-form/pending`, so
  the edges that keep that set honest are the behavior worth pinning: a
  converted module with a private var, an undocumented public var, or a
  wide line is a finding; a pending module is exempt even when conformant
  (deleting the entry is the card's deliberate act, never forced by an
  unrelated cleanup); a stale entry is a finding; and the internal
  dependency rules hold for every module, pending or not."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [quality.api-form :as api-form])
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

(deftest converted-module-with-private-var-is-a-finding
  (with-modules {"tidy" conformant-source}
    (fn [dirs]
      (let [analysis {:var-definitions [(var-def (dirs "tidy")
                                                 {:private true :name 'helper})]}
            findings (api-form/findings analysis dirs #{})]
        (is (= 1 (count findings)))
        (is (re-find #"private var `helper`.*skein\.api\.tidy\.internal"
                     (first findings)))))))

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
                                                   {:private true :name 'helper})]}]
          (is (empty? (api-form/findings analysis dirs #{"messy"})))))))
  (testing "a conformant pending module forces nothing; shrinking pending is
            the conversion card's own deliberate act"
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
  (testing "the module's own alpha and its tests are allowed"
    (let [own {:from 'skein.api.tidy.alpha :to 'skein.api.tidy.internal
               :filename "src/skein/api/tidy/alpha.clj" :row 3}
          test-use {:from 'skein.api.tidy.alpha-test :to 'skein.api.tidy.internal
                    :filename "test/skein/api/tidy/alpha_test.clj" :row 3}]
      (is (empty? (api-form/findings {:namespace-usages [own test-use]} {} #{}))))))
