(ns skein.spools.brief-test
  "Tests for the brief/guide substrate: clause-block and guide registries, loud
  validation, the shared `brief->prompt` renderer, the `brief-attrs` projection
  and `overlapping-owns` collision detector, the `guide/key` step-advertising
  convention, and the installed `brief` op. The `pipeline-brief` fixture is the
  wired end-to-end consumer proof: it reconstructs `.skein/config.clj`'s
  `pipeline-task-prompt` shape on top of `brief->prompt`, including the real
  run-context section."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.api.weaver.alpha :as api]
            [skein.spools.brief :as brief]
            [skein.spools.test-support :refer [assert-state-shape with-runtime]]))

;; --- versioned spool-state drift alarm --------------------------------------

(deftest state-shape-is-pinned
  (assert-state-shape #'brief/new-state #{:blocks :guides}))

;; --- clause blocks ----------------------------------------------------------

(deftest defblock!-registers-and-fetches
  (with-runtime
    (fn [rt _]
      (is (= :src/rules (brief/defblock! rt :src/rules {:title "Source rules"
                                                        :lines ["Cite every claim with a URL."]})))
      (is (= "Source rules" (:title (brief/block rt :src/rules))))
      (is (= {:src/rules "Source rules"} (brief/blocks rt))))))

(deftest defblock!-and-block-fail-loudly
  (with-runtime
    (fn [rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"key must be a keyword"
                            (brief/defblock! rt "nope" {:lines ["x"]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"received unknown keys"
                            (brief/defblock! rt :b {:lines ["x"] :bogus 1})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed"
                            (brief/defblock! rt :b {:lines []})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown clause block"
                            (brief/block rt :missing))))))

;; --- brief validation + rendering -------------------------------------------

(deftest validate-brief-rejects-unknown-and-mistyped-subkeys
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"brief received unknown keys"
                        (brief/validate-brief {:mision ["typo"]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"brief :deliverable received unknown keys"
                        (brief/validate-brief {:deliverable {:paths "x"}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"brief :scope received unknown keys"
                        (brief/validate-brief {:scope {:owned ["x"]}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"brief :context received unknown keys"
                        (brief/validate-brief {:context {:ref "x" :notee "typo"}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed"
                        (brief/validate-brief {:budgets {:web-searches "eighteen"}})))
  (testing "a well-formed brief validates and is returned unchanged"
    (let [b {:mission ["Do the thing."] :budgets {:web-searches 4}}]
      (is (= b (brief/validate-brief b))))))

(deftest brief->prompt-renders-fixed-order-sections-and-blocks
  (with-runtime
    (fn [rt _]
      (brief/defblock! rt :blocked-domains {:title "Blocked domains"
                                            :lines ["ocado.com returns 403 to headless fetches"]})
      (let [prompt (brief/brief->prompt
                    rt
                    {:context "The decision: which grocery integration to build."
                     :mission ["Which retailers block headless fetches?"]
                     :deliverable {:path "research/01-a.md" :format :markdown
                                   :end-with "a verdict section"
                                   :validate ["test -s research/01-a.md"]}
                     :scope {:owns ["research/01-a.md"]
                             :forbid-reads ["research/*.md"]
                             :commit? false}
                     :budgets {:web-searches 18 :web-fetches 12}
                     :rules ["Never fabricate; flag uncertainty."]
                     :blocks [:blocked-domains]})]
        (testing "every section is present and ordered"
          (is (str/includes? prompt "## Context"))
          (is (< (str/index-of prompt "## Context")
                 (str/index-of prompt "## Mission")
                 (str/index-of prompt "## Deliverable")
                 (str/index-of prompt "## Scope")
                 (str/index-of prompt "## Budgets")
                 (str/index-of prompt "## Rules")
                 (str/index-of prompt "## Blocked domains"))))
        (testing "content is rendered"
          (is (str/includes? prompt "web-searches: 18"))
          (is (str/includes? prompt "You OWN only these files"))
          (is (str/includes? prompt "Do NOT commit"))
          (is (str/includes? prompt "ocado.com returns 403")))))))

(deftest brief->prompt-renders-mission-and-body-together
  (with-runtime
    (fn [rt _]
      (let [prompt (brief/brief->prompt rt {:mission ["Ship the feature."]
                                            :body "Elaboration the objective list omits."})]
        (is (str/includes? prompt "- Ship the feature."))
        (is (str/includes? prompt "Elaboration the objective list omits."))))))

(deftest brief->prompt-omits-empty-sections-and-fails-on-unknown-block
  (with-runtime
    (fn [rt _]
      (let [prompt (brief/brief->prompt rt {:mission ["Just do the thing."]})]
        (is (str/includes? prompt "## Mission"))
        (is (not (str/includes? prompt "## Scope")))
        (is (not (str/includes? prompt "## Budgets"))))
      (testing "an unknown :blocks key fails loudly with the available keys"
        (brief/defblock! rt :known {:lines ["x"]})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown clause block"
                              (brief/brief->prompt rt {:blocks [:nope]})))))))

;; --- projection attrs + owned-path collisions -------------------------------

(deftest brief-attrs-projects-owns-and-budgets
  (testing "declared scope/owns and budgets project to their attrs"
    (is (= {"brief/owns" ["a.md" "b.md"]
            "brief/budgets" {:web-searches 8}}
           (brief/brief-attrs {:mission ["m"]
                               :scope {:owns ["a.md" "b.md"]}
                               :budgets {:web-searches 8}}))))
  (testing "a brief declaring neither projects an empty attr map"
    (is (= {} (brief/brief-attrs {:mission ["m"]}))))
  (testing "projection validates first — a malformed brief never half-projects"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"received unknown keys"
                          (brief/brief-attrs {:scope {:owned ["x"]}})))))

(deftest overlapping-owns-detects-cross-task-collisions
  (testing "a path claimed by two siblings is reported; disjoint paths are not"
    (let [tasks [{:id "t1" :attributes {"brief/owns" ["a.md" "shared.md"]}}
                 {:id "t2" :attributes {"brief/owns" ["b.md" "shared.md"]}}
                 {:id "t3" :attributes {"brief/owns" ["c.md"]}}]]
      (is (= [{:path "shared.md" :tasks ["t1" "t2"]}]
             (brief/overlapping-owns tasks)))))
  (testing "keyword-keyed attributes (native read) are tolerated"
    (let [tasks [{:id "t1" :attributes {:brief/owns ["shared.md"]}}
                 {:id "t2" :attributes {:brief/owns ["shared.md"]}}]]
      (is (= [{:path "shared.md" :tasks ["t1" "t2"]}]
             (brief/overlapping-owns tasks)))))
  (testing "no collisions yields an empty vector"
    (is (= [] (brief/overlapping-owns [{:id "t1" :attributes {"brief/owns" ["a.md"]}}
                                       {:id "t2" :attributes {"brief/owns" ["b.md"]}}])))))

;; --- guides + step-advertising ----------------------------------------------

(deftest defguide!-registers-and-fails-loudly
  (with-runtime
    (fn [rt _]
      (is (= :proposal (brief/defguide! rt :proposal {:purpose "Frame the feature."
                                                      :constraints ["Keep it short."]})))
      (is (= "Frame the feature." (:purpose (brief/guide rt :proposal))))
      (is (= {:proposal "Frame the feature."} (brief/guides rt)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing :purpose"
                            (brief/defguide! rt :bad {:constraints ["x"]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"received unknown keys"
                            (brief/defguide! rt :bad {:purpose "p" :steps ["x"]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown guide"
                            (brief/guide rt :missing))))))

(deftest strand-guide-resolves-advertised-guide
  (with-runtime
    (fn [rt _]
      (brief/defguide! rt :proposal {:purpose "Frame the feature."})
      (let [advertised (api/add rt {:title "Write proposal"
                                    :attributes {:guide/key "proposal"}})
            plain (api/add rt {:title "Do something"})]
        (is (= :proposal (:key (brief/strand-guide rt advertised))))
        (is (= "Frame the feature." (:purpose (:guide (brief/strand-guide rt advertised)))))
        (is (nil? (brief/strand-guide rt plain)))
        (testing "an advertised-but-unregistered guide fails loudly"
          (let [dangling (api/add rt {:title "x" :attributes {:guide/key "ghost"}})]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown guide"
                                  (brief/strand-guide rt dangling)))))
        (testing "a malformed guide/key attr fails with the raw value"
          (let [bad (api/add rt {:title "x" :attributes {:guide/key false}})]
            (try
              (brief/strand-guide rt bad)
              (is false "expected malformed guide/key to throw")
              (catch clojure.lang.ExceptionInfo e
                (is (re-find #"must be a string" (ex-message e)))
                (is (false? (:value (ex-data e))))
                (is (= :guide/key (:attribute (ex-data e))))))))))))

;; --- wired consumer proof: pipeline-task-prompt on top of brief -------------

(def worker-contract-lines
  ["Read your assigned strand AND its notes first."
   "Set status=implemented only when your validation gate is green."
   "Never close your assigned strand; never commit unless told."])

(defn pipeline-brief
  "Reconstruct `.skein/config.clj`'s `pipeline-task-prompt` shape as a brief:
  the delegated run context, task title/body, and standing worker contract."
  [rt run-id {:keys [title body]}]
  (brief/defblock! rt :worker-contract {:title "Worker contract" :lines worker-contract-lines})
  (brief/brief->prompt rt (cond-> {:context (str "Delegated pipeline run: " run-id)
                                   :mission [title]
                                   :blocks [:worker-contract]}
                            body (assoc :body body))))

(deftest brief-reconstructs-a-real-pipeline-prompt
  (with-runtime
    (fn [rt _]
      (let [prompt (pipeline-brief rt "pipe-1" {:title "Add priority filter"
                                                :body "Filter ready strands by attribute priority=high."})]
        (is (str/includes? prompt "## Context"))
        (is (str/includes? prompt "Delegated pipeline run: pipe-1"))
        (is (str/includes? prompt "## Worker contract"))
        (is (str/includes? prompt "Never close your assigned strand"))
        (is (str/includes? prompt "Add priority filter"))
        (is (str/includes? prompt "priority=high"))
        (is (not (str/includes? prompt "## Deliverable")))))))

;; --- installed op -----------------------------------------------------------

(deftest install!-registers-a-fetch-only-op
  (with-runtime
    (fn [rt _]
      (brief/install!)
      (brief/defguide! rt :proposal {:purpose "Frame the feature."})
      (brief/defblock! rt :src/rules {:lines ["Cite every claim."]})
      (is (= {:proposal "Frame the feature."} (api/op! rt 'brief ["guides"])))
      (is (= "Frame the feature." (:purpose (api/op! rt 'brief ["guide" "proposal"]))))
      (is (= ["Cite every claim."] (:lines (api/op! rt 'brief ["block" "src/rules"]))))
      (is (= "brief about" (:operation (api/op! rt 'brief ["about"]))))
      (is (= "brief prime" (:operation (api/op! rt 'brief ["prime"])))))))
