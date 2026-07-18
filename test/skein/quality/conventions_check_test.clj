(ns skein.quality.conventions-check-test
  "Ratchet-edge coverage for the api-form check in `quality.api-form`.

  Sixteen conversion cards will each edit `quality.api-form/pending`, so the edges
  that keep that set honest are the behavior worth pinning: a converted
  module with a private var or a wide line is a finding, a pending module
  is exempt while nonconformant, and a pending entry that is stale or
  already conformant is itself a finding rather than silence."
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

(defn- private-var-analysis
  "Analysis data shaped like clj-kondo's, marking `var-name` private in
  `dir`/alpha.clj."
  [dir var-name]
  {:var-definitions [{:private true
                      :filename (.getPath (io/file dir "alpha.clj"))
                      :row 2
                      :name var-name}]})

(deftest converted-module-with-private-var-is-a-finding
  (with-modules {"tidy" conformant-source}
    (fn [dirs]
      (let [analysis (private-var-analysis (dirs "tidy") 'helper)
            findings (api-form/findings analysis dirs #{})]
        (is (= 1 (count findings)))
        (is (re-find #"private var `helper`.*skein\.api\.tidy\.internal"
                     (first findings)))))))

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

(deftest pending-module-is-exempt-while-nonconformant
  (testing "wide lines and private vars in a pending module stay silent"
    (with-modules {"messy" (str "(ns m.alpha \"Doc.\")\n;; "
                                (str/join (repeat 100 "x")) "\n")}
      (fn [dirs]
        (let [analysis (private-var-analysis (dirs "messy") 'helper)]
          (is (empty? (api-form/findings
                       analysis dirs #{"messy"}))))))))

(deftest conformant-pending-module-demands-ratchet-shrink
  (with-modules {"tidy" conformant-source}
    (fn [dirs]
      (let [findings (api-form/findings {} dirs #{"tidy"})]
        (is (= 1 (count findings)))
        (is (re-find #"pending module `tidy` already satisfies"
                     (first findings)))))))

(deftest stale-pending-entry-is-a-finding
  (with-modules {"tidy" conformant-source}
    (fn [dirs]
      (let [findings (api-form/findings {} dirs #{"tidy" "gone"})]
        (is (= 2 (count findings)))
        (is (some #(re-find #"entry `gone` matches no module directory" %)
                  findings))))))
