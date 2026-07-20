(ns skein.spools.batteries-test
  "Tests for the batteries core strand ops against a disposable weaver runtime:
  op registration/provenance, each op's happy path, attribute merge precedence,
  payload-ref attributes, loud failures, and JSON-shape equivalence with the
  underlying weaver API the old socket dispatch delegates to."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test :refer [match?]]
            [skein.api.graph.alpha :as graph]
            [skein.api.notes.alpha :as notes]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.relations.alpha :as relations]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.specs :as specs]
            [skein.spools.batteries :as batteries]
            [skein.spools.test-support :refer [with-runtime]]
            [skein.test.alpha :as t]))

(s/def ::title string?)
(s/def ::weave-input (s/keys :req-un [::title]))

(defn weave-test-pattern
  "Create-only pattern that turns {:title t} into one strand titled t."
  [{:keys [input]}]
  [{:ref 'impl :title (:title input) :attributes {:kind "implementation"}}])

(defn- with-batteries
  "Run f with an activated batteries surface on a disposable runtime."
  [f]
  (with-runtime
    (fn [rt _config-dir]
      (batteries/install! rt)
      (f rt))))

(defn- op-entry [rt op-name]
  (some #(when (= (name op-name) (:name %)) %) (weaver/ops rt)))

(defn- return-case-leaves [operation context return-case]
  (if (and (map? return-case) (contains? return-case :stream))
    (set (map (fn [channel] [operation (assoc context :channel channel)]) [:emits :result]))
    #{[operation context]}))

(defn- op-return-leaves [{:keys [name returns]}]
  (if (and (map? returns) (contains? returns :subcommands))
    (into #{} (mapcat (fn [[subcommand return-case]]
                        (return-case-leaves name {:subcommand subcommand} return-case)))
          (:subcommands returns))
    (return-case-leaves name {} returns)))

(defn- owner-return-coverage [rt checked-leaves]
  (let [entries (filterv #(= 'skein.spools.batteries (:provenance %)) (weaver/ops rt))
        missing (filterv #(not (contains? % :returns)) entries)
        required (into #{} (mapcat op-return-leaves) (filter #(contains? % :returns) entries))]
    {:missing (mapv :name missing)
     :required required
     :unchecked (set/difference required checked-leaves)}))

(defn- wire-value [value]
  (json/read-str (json/write-str value) :key-fn keyword))

(defn- sha [ch]
  (str/join (repeat 40 ch)))

(defn- tag-lines [& tag-shas]
  (str/join "\n"
            (mapcat (fn [[tag tag-object commit]]
                      [(str tag-object "\trefs/tags/" tag)
                       (str commit "\trefs/tags/" tag "^{}")])
                    tag-shas)))

(defn- stub-git-client! [rt client]
  (reset! (runtime/spool-state rt :skein.spools.batteries/git-client #(atom nil)) client))

(defn- stub-git! [rt tags manifest]
  (stub-git-client! rt {:ls-remote (fn [_git-url] tags)
                        :manifest-at (fn [_git-url _sha] manifest)}))

(defn- call-private [name & args]
  (apply (deref (ns-resolve 'skein.spools.batteries name)) args))

(deftest activate-registers-ops-with-provenance-and-hook-classes
  (with-batteries
    (fn [rt]
      (testing "all shipped ops are registered under batteries provenance"
        (doseq [op-name ['add 'update 'show 'supersede 'burn 'list 'ready 'subgraph
                         'weave 'query 'pattern 'note 'notes 'vocab 'spool 'spool-status]]
          (let [entry (op-entry rt op-name)]
            (is (some? entry) (str op-name " should be registered"))
            (is (= 'skein.spools.batteries (:provenance entry)))
            (is (string? (:doc entry)))
            (is (some? (:arg-spec entry))))))
      (testing "hook classes match read/mutating intent"
        (is (= :mutating (:hook-class (op-entry rt 'add))))
        (is (= :mutating (:hook-class (op-entry rt 'update))))
        (is (= :mutating (:hook-class (op-entry rt 'supersede))))
        (is (= :mutating (:hook-class (op-entry rt 'burn))))
        (is (= :mutating (:hook-class (op-entry rt 'weave))))
        (is (= :read (:hook-class (op-entry rt 'show))))
        (is (= :read (:hook-class (op-entry rt 'list))))
        (is (= :read (:hook-class (op-entry rt 'ready))))
        (is (= :read (:hook-class (op-entry rt 'subgraph))))
        (is (= :read (:hook-class (op-entry rt 'query))))
        (is (= :read (:hook-class (op-entry rt 'pattern))))
        (is (= :mutating (:hook-class (op-entry rt 'note))))
        (is (= :read (:hook-class (op-entry rt 'notes))))
        (is (= :read (:hook-class (op-entry rt 'vocab))))
        (is (= :mutating (:hook-class (op-entry rt 'spool))))
        (is (= :read (:hook-class (op-entry rt 'spool-status))))))))

(deftest production-return-coverage-is-derived-from-batteries-provenance
  (with-batteries
    (fn [rt]
      (patterns/register-pattern! rt 'task 'skein.spools.batteries-test/weave-test-pattern ::weave-input)
      (graph/register-query! rt 'all [:exists :id])
      (stub-git! rt
                 (tag-lines ["v1" (sha "a") (sha "b")]
                            ["v2" (sha "c") (sha "d")])
                 nil)
      (let [checked (atom #{})
            check! (fn
                     ([operation value]
                      (t/check-op-return! rt operation (wire-value value))
                      (swap! checked conj [(name operation) {}])
                      value)
                     ([operation subcommand value]
                      (t/check-op-return! rt operation {:subcommand subcommand} (wire-value value))
                      (swap! checked conj [(name operation) {:subcommand subcommand}])
                      value))
            first-row (check! 'add (weaver/op! rt 'add ["First"]))
            replacement (weaver/add! rt {:title "Replacement" :attributes {}})
            burnable (weaver/add! rt {:title "Burnable" :attributes {}})
            note (check! 'note (weaver/op! rt 'note [(:id first-row) "covered" "--by" "inuli"]))]
        (check! 'update (weaver/op! rt 'update [(:id first-row) "--title" "Updated"]))
        (check! 'show (weaver/op! rt 'show [(:id first-row)]))
        (check! 'list (weaver/op! rt 'list []))
        (check! 'ready (weaver/op! rt 'ready []))
        (check! 'subgraph (weaver/op! rt 'subgraph [(:id first-row)]))
        (check! 'weave (weaver/op! rt 'weave ["--pattern" "task" "--input" "{\"title\":\"Woven\"}"]))
        (check! 'query "list" (weaver/op! rt 'query ["list"]))
        (check! 'query "explain" (weaver/op! rt 'query ["explain" "all"]))
        (check! 'pattern "list" (weaver/op! rt 'pattern ["list"]))
        (check! 'pattern "explain" (weaver/op! rt 'pattern ["explain" "task"]))
        (check! 'notes (weaver/op! rt 'notes [(:id first-row)]))
        (check! 'vocab (weaver/op! rt 'vocab []))
        (check! 'spool "about" (weaver/op! rt 'spool ["about"]))
        (check! 'spool "add" (weaver/op! rt 'spool ["add" "https://example.invalid/demo.git"]))
        (check! 'spool "bump" (weaver/op! rt 'spool ["bump" "demo" "--to" "v1"]))
        (check! 'spool-status (weaver/op! rt 'spool-status []))
        (check! 'supersede (weaver/op! rt 'supersede [(:id first-row) (:id replacement)]))
        (check! 'burn (weaver/op! rt 'burn [(:id burnable)]))
        (let [{:keys [missing required unchecked]} (owner-return-coverage rt @checked)]
          (is (empty? missing))
          (is (= required @checked))
          (is (empty? unchecked)))
        (is (= (:id first-row) (:target note)))))))

(deftest spool-add-resolves-peeled-highest-tag-and-materializes-manifest
  (with-runtime
    {:release-marker "v4"}
    (fn [rt _config-dir]
      (batteries/install! rt)
      (let [seen-sha (atom nil)
            peeled-v9 (sha "9")
            peeled-v12 (sha "d")]
        (stub-git-client!
         rt
         {:ls-remote
          (fn [_]
            (tag-lines ["v9" (sha "8") peeled-v9]
                       ["v12" (sha "c") peeled-v12]))
          :manifest-at
          (fn [_ sha]
            (reset! seen-sha sha)
            (pr-str {:spool/format 1
                     :skein/min "v2"
                     :roots {'demo/root {:root "demo"}}
                     :requires {'base/root "v3"}}))})
        (let [result (weaver/op! rt 'spool
                                 ["add" "https://example.invalid/acme/demo.spool.git"
                                  "--lib" "demo/root"])
              entry (:entry result)]
          (is (= peeled-v12 @seen-sha) "manifest lookup receives the peeled commit, not the tag object")
          (is (= {:git/url "https://example.invalid/acme/demo.spool.git"
                  :git/tag "v12"
                  :git/sha peeled-v12
                  :roots {'demo/root "demo"}
                  :requires {'base/root "v3"}
                  :skein/min "v2"}
                 entry))
          (is (= entry (get-in (runtime/declared rt) [:families 'demo/root :declared])))
          (is (= :inserted (:status result)))
          (is (s/valid? ::batteries/spool-add-result result))
          (is (false? (get-in result [:requirements :valid?]))))))))

(deftest manifest-at-distinguishes-confirmed-absence-from-operational-failure
  (let [git-url "https://example.invalid/demo.git"
        commit (sha "b")
        run-git-var (ns-resolve 'skein.spools.batteries 'run-git)]
    (testing "a failed object probe plus an empty successful tree lookup confirms absence"
      (with-redefs-fn
        {run-git-var
         (fn [_dir & args]
           (case (first args)
             "init" {:exit 0 :stdout "" :stderr ""}
             "fetch" {:exit 0 :stdout "" :stderr ""}
             "cat-file" {:exit 128 :stdout "" :stderr "fatal: path does not exist"}
             "ls-tree" {:exit 0 :stdout "" :stderr ""}))}
        #(is (nil? (call-private 'manifest-at git-url commit)))))
    (testing "a failed object probe for a path which exists is operational"
      (with-redefs-fn
        {run-git-var
         (fn [_dir & args]
           (case (first args)
             "init" {:exit 0 :stdout "" :stderr ""}
             "fetch" {:exit 0 :stdout "" :stderr ""}
             "cat-file" {:exit 70 :stdout "" :stderr "object database unavailable\n"}
             "ls-tree" {:exit 0 :stdout "spool.edn\n" :stderr ""}))}
        #(let [error (is (thrown? clojure.lang.ExceptionInfo
                                  (call-private 'manifest-at git-url commit)))]
           (is (= "Git command failed while reading advisory spool.edn" (ex-message error)))
           (is (= {:git-url git-url
                   :git/sha commit
                   :argv ["git" "cat-file" "-e" "FETCH_HEAD:spool.edn"]
                   :exit 70
                   :stderr "object database unavailable"}
                  (ex-data error))))))))

(deftest spool-add-implicit-root-and-release-tag-failures
  (with-runtime
    (fn [rt _config-dir]
      (batteries/install! rt)
      (testing "a missing advisory manifest uses the confirmed URL-derived lib at root dot"
        (stub-git! rt (tag-lines ["v1" (sha "a") (sha "b")]) nil)
        (is (= {'demo.spool "."}
               (get-in (weaver/op! rt 'spool
                                   ["add" "https://example.invalid/acme/demo.spool.git"])
                       [:entry :roots]))))
      (testing "v0 is reserved"
        (stub-git! rt (tag-lines ["v1" (sha "a") (sha "b")]) nil)
        (let [error (is (thrown? clojure.lang.ExceptionInfo
                                 (weaver/op! rt 'spool
                                             ["add" "https://example.invalid/demo.git"
                                              "--tag" "v0"])))]
          (is (re-find #"must match vN" (ex-message error)))
          (is (= "vN where N is a positive integer" (:accepted-format (ex-data error))))
          (is (= ["v1"] (:available (ex-data error))))))
      (testing "lightweight or absent releases name the manual sha-pin path"
        (stub-git! rt (str (sha "c") "\trefs/tags/v1") nil)
        (let [error (is (thrown? clojure.lang.ExceptionInfo
                                 (weaver/op! rt 'spool
                                             ["add" "https://example.invalid/untagged.git"])))]
          (is (re-find #"(?i)pin a reviewed sha manually in spools.edn" (ex-message error)))
          (is (= "vN where N is a positive integer" (:accepted-format (ex-data error))))
          (is (= [] (:available (ex-data error)))))))))

(deftest spool-add-validates-explicit-lib-against-advisory-manifest
  (with-runtime
    (fn [rt _config-dir]
      (batteries/install! rt)
      (stub-git! rt
                 (tag-lines ["v1" (sha "a") (sha "b")])
                 (pr-str {:spool/format 1
                          :roots {'manifest/family {:root "."}}}))
      (testing "matching --lib confirms the manifest root family"
        (is (= 'manifest/family
               (:family (weaver/op! rt 'spool
                                    ["add" "https://example.invalid/demo.git"
                                     "--lib" "manifest/family"])))))
      (testing "conflicting --lib names both requested and manifest symbols"
        (let [error (is (thrown? clojure.lang.ExceptionInfo
                                 (weaver/op! rt 'spool
                                             ["add" "https://example.invalid/demo.git"
                                              "--lib" "requested/family"])))]
          (is (str/includes? (ex-message error) "requested/family"))
          (is (str/includes? (ex-message error) "manifest/family"))
          (is (= 'requested/family (:requested-lib (ex-data error))))
          (is (= ['manifest/family] (:manifest-libs (ex-data error)))))))))

(deftest spool-add-wraps-malformed-trailing-manifest-input-with-coordinate-context
  (with-runtime
    (fn [rt _config-dir]
      (batteries/install! rt)
      (let [git-url "https://example.invalid/trailing.git"
            commit (sha "b")]
        (stub-git! rt
                   (tag-lines ["v1" (sha "a") commit])
                   "{:spool/format 1 :roots {demo/root {:root \".\"}}} {")
        (let [error (is (thrown? clojure.lang.ExceptionInfo
                                 (weaver/op! rt 'spool ["add" git-url])))]
          (is (str/includes? (ex-message error) git-url))
          (is (str/includes? (ex-message error) commit))
          (is (re-find #"(?i)trailing input" (ex-message error)))
          (is (re-find #"exactly one EDN value" (ex-message error)))
          (is (= {:git-url git-url
                  :git/sha commit
                  :contract :single-edn-value
                  :reason :invalid-edn}
                 (ex-data error))))))))

(deftest spool-bump-prefers-floor-suggestion-and-rewrites-tag-sha-together
  (with-runtime
    {:release-marker "v4"}
    (fn [rt _config-dir]
      (batteries/install! rt)
      (let [old-sha (sha "1")
            v3-sha (sha "3")]
        (runtime/upsert-spool-entry!
         rt 'target/family
         {:git/url "https://example.invalid/target.git"
          :git/tag "v1"
          :git/sha old-sha
          :roots {'target/root "."}})
        (runtime/upsert-spool-entry!
         rt 'client/family
         {:git/url "https://example.invalid/client.git"
          :git/tag "v1"
          :git/sha (sha "2")
          :requires {'target/root "v3"}})
        (stub-git! rt
                   (tag-lines ["v2" (sha "a") (sha "2")]
                              ["v3" (sha "b") v3-sha]
                              ["v4" (sha "c") (sha "4")])
                   nil)
        (let [result (weaver/op! rt 'spool ["bump" "target/family"])]
          (is (= {:tag "v1" :sha old-sha} (:old result)))
          (is (= {:tag "v3" :sha v3-sha} (:new result))
              "a failing floor selects its computed suggestion instead of latest v4")
          (is (= (str "https://example.invalid/target/compare/" old-sha "..." v3-sha)
                 (:compare-url result)))
          (is (= {:git/tag "v3" :git/sha v3-sha}
                 (select-keys (get-in (runtime/declared rt)
                                      [:families 'target/family :declared])
                              [:git/tag :git/sha])))
          (is (s/valid? ::batteries/spool-bump-result result))
          (is (true? (get-in result [:requirements :valid?]))))))))

(deftest spool-bump-reports-only-usable-compare-urls
  (doseq [[git-url expected-base]
          [["git@github.com:demo/family.git" "https://github.com/demo/family"]
           ["ssh://git@example.invalid/demo/family.git" nil]]]
    (testing git-url
      (with-runtime
        (fn [rt _config-dir]
          (batteries/install! rt)
          (let [old-sha (sha "1")
                new-sha (sha "2")]
            (runtime/upsert-spool-entry!
             rt 'demo/family
             {:git/url git-url
              :git/tag "v1"
              :git/sha old-sha
              :roots {'demo/root "."}})
            (stub-git! rt (tag-lines ["v2" (sha "a") new-sha]) nil)
            (let [result (weaver/op! rt 'spool ["bump" "demo/family" "--to" "v2"])]
              (is (= (when expected-base
                       (str expected-base "/compare/" old-sha "..." new-sha))
                     (:compare-url result)))
              (is (contains? result :compare-url))
              (is (s/valid? ::batteries/spool-bump-result result)))))))))

(deftest spool-status-joins-overlay-sync-use-pending-and-release-truth-without-git
  (with-runtime
    {:release-marker "v5"}
    (fn [rt config-dir]
      (batteries/install! rt)
      (runtime/upsert-spool-entry!
       rt 'demo/family
       {:git/url "https://example.invalid/demo.git"
        :git/tag "v2"
        :git/sha (sha "d")
        :roots {'demo/root "."}})
      (spit (io/file config-dir "spools.local.edn")
            (pr-str {:spools {'demo/family {:local/root "../demo" :claims "v3"}}}))
      (spit (io/file config-dir "module.clj") "{:loaded true}\n")
      (runtime/use! rt :demo/module {:file "module.clj" :spools ['demo/root]})
      (stub-git-client!
       rt
       {:ls-remote (fn [_] (throw (ex-info "network called" {})))
        :manifest-at (fn [_ _] (throw (ex-info "network called" {})))})
      (let [result (weaver/op! rt 'spool-status [])
            family (get-in result [:families 'demo/family])]
        (is (= {:provenance :local-overlay :claims "v3"}
               (select-keys family [:provenance :claims])))
        (is (= {} (:syncs family)))
        (is (= :not-synced (get-in family [:uses :demo/module :reason])))
        (is (nil? (:pending-generation result)))
        (is (s/valid? ::batteries/spool-status-result result))
        (is (= {:marker "v5" :provenance :claimed} (:release-marker result)))))))

(deftest spool-status-rejects-declared-family-without-roots
  (with-batteries
    (fn [rt]
      (with-redefs [runtime/declared
                    (fn [& _]
                      {:families {'demo/family {:declared {:git/url "https://example.invalid/demo.git"}}}
                       :requirements {:valid? true :pending-validations []}})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Declared spool family has no roots"
                              (weaver/op! rt 'spool-status [])))))))

(deftest spool-status-rejects-malformed-nested-results
  (testing "malformed effective coordinate"
    (with-batteries
      (fn [rt]
        (runtime/upsert-spool-entry!
         rt 'demo/family
         {:git/url "https://example.invalid/demo.git"
          :git/tag "v1"
          :git/sha (sha "d")
          :roots {'demo/root "."}})
        (let [declared runtime/declared]
          (with-redefs [runtime/declared
                        (fn [& args]
                          (assoc-in (apply declared args)
                                    [:families 'demo/family :effective-coordinate]
                                    {:kind :git}))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"spool-status returned an invalid result"
                                  (weaver/op! rt 'spool-status []))))))))
  (testing "malformed per-root sync entry"
    (with-batteries
      (fn [rt]
        (runtime/upsert-spool-entry!
         rt 'demo/family
         {:git/url "https://example.invalid/demo.git"
          :git/tag "v1"
          :git/sha (sha "d")
          :roots {'demo/root "."}})
        (with-redefs [runtime/syncs
                      (fn [_]
                        {:spools {'demo/root {:lib 'demo/root
                                              :status :loaded}}})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"spool-status returned an invalid result"
                                (weaver/op! rt 'spool-status [])))))))
  (testing "malformed runtime use entry"
    (with-batteries
      (fn [rt]
        (runtime/upsert-spool-entry!
         rt 'demo/family
         {:git/url "https://example.invalid/demo.git"
          :git/tag "v1"
          :git/sha (sha "d")
          :roots {'demo/root "."}})
        (let [use-entry {:key :demo/module :status :loaded}]
          (with-redefs [runtime/uses (fn [_] {:demo/module use-entry})]
            (let [error (try
                          (weaver/op! rt 'spool-status [])
                          nil
                          (catch clojure.lang.ExceptionInfo error error))]
              (is (= "Runtime use entry has an invalid shape" (ex-message error)))
              (is (= :demo/module (:use-key (ex-data error))))
              (is (= use-entry (:use-entry (ex-data error)))))))))))

(deftest spool-contracts-and-dispatch-fail-loudly
  (is (s/valid? ::batteries/advisory-manifest
                {:spool/format 1
                 :roots {'demo/root {:root "."}}
                 :requires {'base/root "v2"}
                 :skein/min "v1"}))
  (is (not (s/valid? ::batteries/advisory-manifest
                     {:spool/format 1 :roots {'demo/root {:root "."}} :extra true})))
  (let [about (batteries/spool-op {:op/args {:subcommand "about"}})]
    (is (= ["strand spool add <git-url> [--tag vN] [--lib family]"
            "strand spool bump <family> [--to vN]"
            "strand spool-status"]
           (mapv :form (:commands about))))
    (is (s/valid? ::batteries/spool-about-result about)))
  (let [error (is (thrown? clojure.lang.ExceptionInfo
                           (batteries/spool-op {:op/args {:subcommand :unexpected}})))]
    (is (= ::batteries/spool-op-context (:spec (ex-data error)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid operation context"
                        (batteries/spool-op
                         {:op/args {:subcommand "add" :git-url "https://example.invalid/x"}}))))

(deftest add-happy-path-and-json-shape
  (with-batteries
    (fn [rt]
      (let [added (weaver/op! rt 'add ["Design model" "--attr" "priority=high"])]
        ;; One shape assertion: exact scalar fields, an exact attributes map, and
        ;; m/absent proving the normalized shape omits old lifecycle fields (C9).
        (is (match? {:id string?
                     :title "Design model"
                     :state "active"
                     :attributes (m/equals {:priority "high"})
                     :active m/absent
                     :inactive_at m/absent}
                    added))
        (testing "shape matches a direct weaver-API add"
          (let [direct (weaver/add! rt {:title "Design model" :attributes {:priority "high"} :state "active"})]
            (is (= (set (keys added)) (set (keys direct))))))))))

(deftest add-attr-precedence-and-payload-json-bulk
  (with-batteries
    (fn [rt]
      (testing "--attr overrides --attributes, JSON types preserved"
        (let [added (weaver/op! rt 'add
                                ["Merge" "--attributes" ":payload/attrs" "--attr" "k=flag"]
                                {:payloads {"attrs" "{\"k\":\"json\",\"x\":1}"}})]
          (is (= {:k "flag" :x 1} (:attributes added)))))
      (testing "--attr value resolves a payload reference"
        (let [added (weaver/op! rt 'add
                                ["Body" "--attr" "body=:stdin"]
                                {:payloads {"stdin" "hello from payload"}})]
          (is (= "hello from payload" (get-in added [:attributes :body]))))))))

(deftest add-with-edges
  (with-batteries
    (fn [rt]
      (let [target (weaver/add! rt {:title "Target" :attributes {}})
            added (weaver/op! rt 'add ["Source" "--edge" (str "depends-on:" (:id target))])
            edges (:edges (graph/subgraph rt [(:id added)] {:type "depends-on"}))]
        (is (match? (m/embeds [{:from_strand_id (:id added)
                                :to_strand_id (:id target)
                                :edge_type "depends-on"}])
                    edges))))))

(deftest add-loud-failures
  (with-batteries
    (fn [rt]
      (testing "missing required title fails in the parser"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required"
                              (weaver/op! rt 'add []))))
      (testing "invalid state fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"active or closed"
                              (weaver/op! rt 'add ["t" "--state" "bogus"]))))
      (testing "duplicate --attr key fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate attribute key"
                              (weaver/op! rt 'add ["t" "--attr" "k=a" "--attr" "k=b"])))))))

(deftest update-happy-path
  (with-batteries
    (fn [rt]
      (let [created (weaver/add! rt {:title "Before" :attributes {:owner "old"}})
            id (:id created)
            updated (weaver/op! rt 'update [id "--title" "After" "--attr" "owner=new"])]
        (is (= "After" (:title updated)))
        (is (= {:owner "new"} (:attributes updated)))
        (testing "state can be flipped to closed"
          (is (= "closed" (:state (weaver/op! rt 'update [id "--state" "closed"])))))
        (testing "invalid state is rejected"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"active or closed"
                                (weaver/op! rt 'update [id "--state" "replaced"]))))))))

(deftest update-typed-attributes-merge-patch
  (with-batteries
    (fn [rt]
      (testing "typed --attributes merges below --attr; JSON types preserved"
        (let [created (weaver/add! rt {:title "Merge" :attributes {:owner "old"}})
              updated (weaver/op! rt 'update
                                  [(:id created) "--attributes" ":payload/attrs" "--attr" "owner=flag"]
                                  {:payloads {"attrs" "{\"owner\":\"json\",\"count\":2}"}})]
          (is (= {:owner "flag" :count 2} (:attributes updated)))))
      (testing "JSON null removes the addressed key"
        (let [created (weaver/add! rt {:title "Drop" :attributes {:owner "old" :keep "yes"}})
              updated (weaver/op! rt 'update
                                  [(:id created) "--attributes" ":payload/attrs"]
                                  {:payloads {"attrs" "{\"owner\":null}"}})]
          (is (= {:keep "yes"} (:attributes updated)))))
      (testing "JSON empty string stores an empty string, not absence"
        (let [created (weaver/add! rt {:title "Blank" :attributes {:owner "old"}})
              updated (weaver/op! rt 'update
                                  [(:id created) "--attributes" ":payload/attrs"]
                                  {:payloads {"attrs" "{\"owner\":\"\"}"}})]
          (is (= {:owner ""} (:attributes updated)))))
      (testing "--attr key= stores an empty string and wins over a typed null"
        (let [created (weaver/add! rt {:title "Raw blank" :attributes {:owner "old"}})
              updated (weaver/op! rt 'update
                                  [(:id created) "--attributes" ":payload/attrs" "--attr" "owner="]
                                  {:payloads {"attrs" "{\"owner\":null}"}})]
          (is (= {:owner ""} (:attributes updated)))))
      (testing "no attribute flag leaves attributes untouched"
        (let [created (weaver/add! rt {:title "Keep" :attributes {:owner "old"}})
              updated (weaver/op! rt 'update [(:id created) "--title" "Renamed"])]
          (is (= "Renamed" (:title updated)))
          (is (= {:owner "old"} (:attributes updated)))))
      (testing "blank --attributes key fails loudly"
        (let [created (weaver/add! rt {:title "Bad" :attributes {}})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"blank attribute key"
                                (weaver/op! rt 'update
                                            [(:id created) "--attributes" ":payload/attrs"]
                                            {:payloads {"attrs" "{\"\":\"x\"}"}})))))
      (testing "supplied JSON null for --attributes fails loudly, not as an empty patch"
        (let [created (weaver/add! rt {:title "Null" :attributes {:owner "old"}})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must reference a JSON object"
                                (weaver/op! rt 'update
                                            [(:id created) "--attributes" ":payload/attrs"]
                                            {:payloads {"attrs" "null"}})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must reference a JSON object"
                                (weaver/op! rt 'add
                                            ["Null add" "--attributes" ":payload/attrs"]
                                            {:payloads {"attrs" "null"}}))))))))

(deftest show-supersede-burn
  (with-batteries
    (fn [rt]
      (let [a (weaver/add! rt {:title "Original" :attributes {}})
            b (weaver/add! rt {:title "Replacement" :attributes {}})]
        (testing "show returns the strand"
          (is (= "Original" (:title (weaver/op! rt 'show [(:id a)])))))
        (testing "supersede marks the old strand replaced"
          (weaver/op! rt 'supersede [(:id a) (:id b)])
          (is (= "replaced" (:state (weaver/show rt (:id a))))))
        (testing "burn deletes the strand"
          (let [result (weaver/op! rt 'burn [(:id b)])]
            (is (= [(:id b)] (:burned result)))
            (is (nil? (weaver/show rt (:id b))))))))))

(deftest list-and-ready
  (with-batteries
    (fn [rt]
      (let [active (weaver/add! rt {:title "Active" :attributes {}})
            closed (weaver/add! rt {:title "Closed" :attributes {} :state "closed"})
            blocked (weaver/add! rt {:title "Blocked" :attributes {}
                                     :edges [{:type "depends-on" :to (:id active)}]})]
        (testing "list returns all strands and matches the weaver API"
          (is (= (set (map :id (weaver/list rt)))
                 (set (map :id (weaver/op! rt 'list []))))))
        (testing "list --state filters"
          (is (= #{(:id closed)} (set (map :id (weaver/op! rt 'list ["--state" "closed"]))))))
        (testing "ready hides strands blocked by active dependencies and matches the API"
          (let [ready-ids (set (map :id (weaver/op! rt 'ready [])))]
            (is (= (set (map :id (weaver/ready rt))) ready-ids))
            (is (contains? ready-ids (:id active)))
            (is (not (contains? ready-ids (:id blocked))))))))))

(deftest list-and-ready-named-queries
  (with-batteries
    (fn [rt]
      (graph/register-query! rt 'owned {:params [:who]
                                        :where [:= [:attr :owner] [:param :who]]})
      (let [mine (weaver/add! rt {:title "Mine" :attributes {:owner "agent"}})
            _theirs (weaver/add! rt {:title "Theirs" :attributes {:owner "other"}})]
        (testing "list --query with --param"
          (is (= #{(:id mine)}
                 (set (map :id (weaver/op! rt 'list ["--query" "owned" "--param" "who=agent"]))))))
        (testing "ready --query with --param"
          (is (= #{(:id mine)}
                 (set (map :id (weaver/op! rt 'ready ["--query" "owned" "--param" "who=agent"]))))))
        (testing "unknown query fails loudly"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Query not found"
                                (weaver/op! rt 'list ["--query" "nope"]))))
        (testing "--param without --query fails loudly"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires --query"
                                (weaver/op! rt 'list ["--param" "who=agent"]))))))))

(deftest list-and-ready-result-caps-fail-loudly-with-explicit-override
  (with-batteries
    (fn [rt]
      (let [_rows (doall (for [n (range 3)]
                           (weaver/add! rt {:title (str "Task " n) :attributes {}})))]
        (testing "workspace cap is enforced before returning partial list results"
          (batteries/set-read-limit! rt 2)
          (is (= 2 (batteries/read-limit rt)))
          (let [ex (is (thrown? clojure.lang.ExceptionInfo (weaver/op! rt 'list [])))]
            (is (= "read-limit-exceeded" (-> ex ex-data :code)))
            (is (= 3 (-> ex ex-data :total)))
            (is (= 2 (-> ex ex-data :limit)))
            (is (str/includes? (ex-message ex) "--limit N"))))
        (testing "explicit --limit above the total allows an intentional full read"
          (is (= 3 (count (weaver/op! rt 'list ["--limit" "3"])))))
        (testing "ready uses the same cap"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Read result matched 3 strands"
                                (weaver/op! rt 'ready []))))
        (testing "invalid configured and explicit limits fail loudly"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                                (batteries/set-read-limit! rt 0)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                                (weaver/op! rt 'list ["--limit" "0"]))))))))

(deftest list-ready-and-named-queries-lean-project-large-attributes-only
  (with-batteries
    (fn [rt]
      (graph/register-query! rt 'owned {:params [:who]
                                        :where [:= [:attr :owner] [:param :who]]})
      (let [at-floor (str/join (repeat 1022 "a"))
            over-floor (str/join (repeat 1023 "b"))
            non-ascii-at-floor (str/join (repeat 511 "é"))
            strand (weaver/add! rt {:title "Payload"
                                    :attributes {:owner "agent"
                                                 :at-floor at-floor
                                                 :over-floor over-floor
                                                 :non-ascii-at-floor non-ascii-at-floor}})
            lean-list (first (filter #(= (:id strand) (:id %)) (weaver/op! rt 'list [])))
            lean-ready (first (filter #(= (:id strand) (:id %)) (weaver/op! rt 'ready [])))
            lean-query (first (weaver/op! rt 'list ["--query" "owned" "--param" "who=agent"]))
            lean-ready-filtered (first (weaver/op! rt 'ready ["--query" "owned" "--param" "who=agent"]))
            full-show (weaver/op! rt 'show [(:id strand)])
            full-api-list (first (filter #(= (:id strand) (:id %)) (weaver/list rt)))]
        (testing "list/ready/query preserve values at the fixed 1 KiB floor"
          (doseq [row [lean-list lean-ready lean-query lean-ready-filtered]]
            (is (= "agent" (get-in row [:attributes :owner])))
            (is (= at-floor (get-in row [:attributes :at-floor])))))
        (testing "list and ready use the same stored-byte floor for non-ASCII values"
          (let [projections (mapv #(get-in % [:attributes :non-ascii-at-floor])
                                  [lean-list lean-ready lean-query lean-ready-filtered])]
            (is (= 1 (count (set projections))))
            (is (specs/omitted-attribute-descriptor? (first projections)))))
        (testing "list/ready/query replace values above the floor with the descriptor"
          (doseq [row [lean-list lean-ready lean-query lean-ready-filtered]]
            (let [descriptor (get-in row [:attributes :over-floor])]
              (is (specs/omitted-attribute-descriptor? descriptor))
              (is (= {:skein/omitted true :bytes 1025} descriptor)))))
        (testing "show and trusted API list remain full-fidelity"
          (is (= over-floor (get-in full-show [:attributes :over-floor])))
          (is (= over-floor (get-in full-api-list [:attributes :over-floor]))))))))

(deftest subgraph-op-shape
  (with-batteries
    (fn [rt]
      (let [root (weaver/add! rt {:title "Root" :attributes {}})
            child (weaver/add! rt {:title "Child" :attributes {}
                                   :edges [{:type "parent-of" :to (:id root)}]})
            result (weaver/op! rt 'subgraph [(:id child) "--relation" "parent-of"])]
        (is (= #{"root_ids" "strands" "edges"} (set (keys result))))
        (is (match? {"strands" (m/embeds [{:id (:id root)} {:id (:id child)}])}
                    result))))))

(defn- with-weave-pattern
  "Run f with a create-only test pattern registered under `task`."
  [f]
  (with-batteries
    (fn [rt]
      (patterns/register-pattern! rt 'task 'skein.spools.batteries-test/weave-test-pattern ::weave-input)
      (f rt))))

(deftest weave-happy-path-and-json-value
  (with-weave-pattern
    (fn [rt]
      (testing "input as a JSON payload reference creates the pattern batch"
        (let [result (weaver/op! rt 'weave ["--pattern" "task" "--input" ":stdin"]
                                 {:payloads {"stdin" "{\"title\":\"Do it\"}"}})]
          (is (= #{:created :refs} (set (keys result))))
          (is (= ["Do it"] (map :title (:created result))))
          (testing "shape matches a direct weaver-API weave!"
            (let [direct (patterns/weave! rt 'task {:title "Do it"})]
              (is (= (set (keys result)) (set (keys direct))))))))
      (testing "literal inline JSON input works too"
        (let [result (weaver/op! rt 'weave ["--pattern" "task" "--input" "{\"title\":\"Inline\"}"])]
          (is (= ["Inline"] (map :title (:created result)))))))))

(deftest weave-loud-input-paths
  (with-weave-pattern
    (fn [rt]
      (testing "malformed JSON fails loudly before mutation"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not valid JSON"
                              (weaver/op! rt 'weave ["--pattern" "task" "--input" ":stdin"]
                                          {:payloads {"stdin" "{not json"}}))))
      (testing "empty input fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one JSON value"
                              (weaver/op! rt 'weave ["--pattern" "task" "--input" ":stdin"]
                                          {:payloads {"stdin" "   "}}))))
      (testing "trailing JSON value fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one JSON value"
                              (weaver/op! rt 'weave ["--pattern" "task" "--input" ":stdin"]
                                          {:payloads {"stdin" "{\"title\":\"x\"} 2"}}))))
      (testing "missing required --pattern fails in the parser"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required flag --pattern"
                              (weaver/op! rt 'weave ["--input" "{\"title\":\"x\"}"]))))
      (testing "unknown pattern fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Pattern not found"
                              (weaver/op! rt 'weave ["--pattern" "nope" "--input" "{\"title\":\"x\"}"])))))))

(deftest query-list-and-explain-shapes
  (with-batteries
    (fn [rt]
      (graph/register-query! rt 'owned {:params [:who]
                                        :where [:= [:attr :owner] [:param :who]]})
      (testing "query list returns JSON-safe metadata for the registered query"
        (let [entries (weaver/op! rt 'query ["list"])
              owned (some #(when (= "owned" (get % "name")) %) entries)]
          (is (vector? entries))
          (is (some? owned))
          (is (= ["who"] (get owned "params")))
          (is (= ["who"] (get owned "referenced-params")))))
      (testing "query explain returns JSON-safe caller guidance"
        (let [explained (weaver/op! rt 'query ["explain" "owned"])]
          (is (= "owned" (get explained "name")))
          (is (= ["=" ["attr" "owner"] ["param" "who"]] (get explained "where")))
          (is (string? (get explained "where-form")))
          (is (string? (get explained "summary")))))
      (testing "query help renders declared subcommands"
        (let [detail (weaver/op! rt 'help ["query"])]
          (is (= ["explain" "list"] (mapv :name (get-in detail [:arg-spec :subcommands]))))
          (is (= [{:name "name" :type "string" :required true
                   :variadic false :parse nil :doc "Query name."}]
                 (->> (get-in detail [:arg-spec :subcommands])
                      (filter #(= "explain" (:name %)))
                      first
                      :positionals)))))
      (testing "unknown query subcommand fails in the parser with available names"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown subcommand"
                                      (weaver/op! rt 'query ["bogus"])))]
          (is (= :unknown-subcommand (:reason (ex-data e))))
          (is (= "bogus" (:token (ex-data e))))
          (is (= ["explain" "list"] (:available-subcommands (ex-data e))))))
      (testing "query explain without a name fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required"
                              (weaver/op! rt 'query ["explain"]))))
      (testing "query explain of an unknown query fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Query not found"
                              (weaver/op! rt 'query ["explain" "nope"])))))))

(deftest pattern-list-and-explain-shapes
  (with-weave-pattern
    (fn [rt]
      (testing "pattern list returns registered pattern metadata"
        (let [entries (weaver/op! rt 'pattern ["list"])]
          (is (vector? entries))
          (is (contains? (set (map :name entries)) "task"))))
      (testing "pattern explain returns input-spec guidance"
        (let [explained (weaver/op! rt 'pattern ["explain" "task"])]
          (is (= "task" (:name explained)))
          (is (= "skein.spools.batteries-test/weave-test-pattern" (:fn explained)))
          (is (string? (:input-spec explained)))))
      (testing "unknown pattern subcommand fails in the parser with available names"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown subcommand"
                                      (weaver/op! rt 'pattern ["bogus"])))]
          (is (= :unknown-subcommand (:reason (ex-data e))))
          (is (= "bogus" (:token (ex-data e))))
          (is (= ["explain" "list"] (:available-subcommands (ex-data e))))))
      (testing "pattern explain without a name fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required"
                              (weaver/op! rt 'pattern ["explain"]))))
      (testing "pattern explain of an unknown pattern fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Pattern not found"
                              (weaver/op! rt 'pattern ["explain" "nope"])))))))

(deftest note-op-projects-target-from-edge
  (with-batteries
    (fn [rt]
      (let [target (weaver/add! rt {:title "Design" :attributes {}})
            written (weaver/op! rt 'note [(:id target) "first pass looks solid" "--by" "gpt"])]
        (testing "note returns the primitive's id/target shape"
          (is (match? {:id string? :target (:id target)} written))
          (is (= #{:id :target} (set (keys written)))))
        (testing "target is an edge projection, never a stored attribute"
          (is (nil? (get-in (weaver/show rt (:id written)) [:attributes :note/for])))
          (is (contains? (set (map :from_strand_id
                                   (graph/incoming-edges rt [(:id target)] "notes")))
                         (:id written))))
        (testing "missing required text fails in the parser"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required"
                                (weaver/op! rt 'note [(:id target)]))))
        (testing "blank text fails loudly in the primitive"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank"
                                (weaver/op! rt 'note [(:id target) "   "]))))
        (testing "--attr decoration round-trips as ordinary attrs on the note strand"
          (let [written (weaver/op! rt 'note [(:id target) "decision text"
                                              "--by" "gpt" "--attr" "note/kind=decision"])
                stored (:attributes (weaver/show rt (:id written)))]
            (is (= "decision" (:note/kind stored)))
            (is (= "gpt" (:note/by stored)))
            (is (= "decision text" (:note/text stored)))))
        (testing "duplicate --attr key fails loudly"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate attribute key"
                                (weaver/op! rt 'note [(:id target) "t"
                                                      "--attr" "k=a" "--attr" "k=b"]))))))))

(deftest notes-op-reads-every-writer-in-order
  (with-batteries
    (fn [rt]
      (let [target (weaver/add! rt {:title "Reviewed" :attributes {}})]
        ;; two writers: the CLI verb and a direct primitive caller with its own
        ;; decorating attrs — the read walks the edge regardless of writer.
        (weaver/op! rt 'note [(:id target) "verb note" "--by" "opus" "--round" "1"])
        (notes/note! rt (:id target) "primitive note"
                     {:by "gpt" :round 2 "reviewer/seat" "panel"})
        (let [rows (weaver/op! rt 'notes [(:id target)])]
          (testing "both writers' notes come back, in note/at order"
            ;; rounds are integers from both surfaces (change-review-1a1d1cc7):
            ;; the CLI flag parses to int and the primitive rejects strings.
            (is (match? [{:id string? :note "verb note" :at string? :by "opus" :round 1}
                         {:id string? :note "primitive note" :at string? :by "gpt" :round 2}]
                        rows))
            (is (every? #(= #{:id :note :at :by :round} (set (keys %))) rows)))
          (testing "--round filters to one review round"
            (is (= ["primitive note"] (mapv :note (weaver/op! rt 'notes [(:id target) "--round" "2"]))))))
        (testing "missing required id fails in the parser"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required"
                                (weaver/op! rt 'notes []))))))))

(deftest vocab-lists-seed-declarations-string-keyed
  (with-batteries
    (fn [rt]
      (let [rows (weaver/op! rt 'vocab [])]
        (testing "output is an ordered, string-keyed array of every declaration"
          (is (vector? rows))
          (is (= (count (vocab/declarations rt)) (count rows)))
          (is (= rows (sort-by (juxt #(get % "kind") #(get % "name")) rows)))
          (is (every? #(every? string? (keys %)) rows)))
        (testing "the fresh-world seed is present: reflected edges plus core note/*"
          (let [edges (filter #(= "edge" (get % "kind")) rows)]
            (is (= (set (map :relation relations/catalog))
                   (set (map #(get % "name") edges))))
            (is (every? #(= "skein/core" (get % "owner")) edges)))
          (let [note (some #(when (and (= "attr-namespace" (get % "kind"))
                                       (= "note" (get % "name")))
                              %)
                           rows)]
            (is (some? note))
            (is (= "skein.api.notes.alpha" (get note "owner")))
            (is (= ["note/text" "note/at" "note/by" "note/round" "note/kind"] (get note "keys")))))
        (testing "--kind narrows to one declaration kind"
          (is (= (set (map :relation relations/catalog))
                 (set (map #(get % "name") (weaver/op! rt 'vocab ["--kind" "edge"])))))
          (is (every? #(= "attr-namespace" (get % "kind"))
                      (weaver/op! rt 'vocab ["--kind" "attr-namespace"]))))
        (testing "an invalid --kind fails loudly instead of returning empty"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"attr-namespace or edge"
                                (weaver/op! rt 'vocab ["--kind" "bogus"]))))
        (testing "vocab help renders the generated --kind arg-spec"
          (let [detail (weaver/op! rt 'help ["vocab"])]
            (is (contains? (set (map :name (get-in detail [:arg-spec :flags]))) "kind"))))))))
