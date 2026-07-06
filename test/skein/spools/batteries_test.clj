(ns skein.spools.batteries-test
  "Tests for the batteries core strand ops against a disposable weaver runtime:
  op registration/provenance, each op's happy path, attribute merge precedence,
  payload-ref attributes, loud failures, and JSON-shape equivalence with the
  underlying weaver API the old socket dispatch delegates to."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test :refer [match?]]
            [skein.api.weaver.alpha :as api]
            [skein.core.specs :as specs]
            [skein.spools.batteries :as batteries]
            [skein.spools.test-support :refer [with-runtime]]))

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
      (batteries/activate! rt)
      (f rt))))

(defn- op-entry [rt op-name]
  (some #(when (= (name op-name) (:name %)) %) (api/ops rt)))

(deftest activate-registers-ops-with-provenance-and-hook-classes
  (with-batteries
    (fn [rt]
      (testing "all shipped ops are registered under batteries provenance"
        (doseq [op-name ['add 'update 'show 'supersede 'burn 'list 'ready 'subgraph
                         'weave 'query 'pattern]]
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
        (is (= :read (:hook-class (op-entry rt 'pattern))))))))

(deftest add-happy-path-and-json-shape
  (with-batteries
    (fn [rt]
      (let [added (api/op! rt 'add ["Design model" "--attr" "priority=high"])]
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
          (let [direct (api/add rt {:title "Design model" :attributes {:priority "high"} :state "active"})]
            (is (= (set (keys added)) (set (keys direct))))))))))

(deftest add-attr-precedence-and-payload-json-bulk
  (with-batteries
    (fn [rt]
      (testing "--attr overrides --attributes, JSON types preserved"
        (let [added (api/op! rt 'add
                             ["Merge" "--attributes" ":payload/attrs" "--attr" "k=flag"]
                             {:payloads {"attrs" "{\"k\":\"json\",\"x\":1}"}})]
          (is (= {:k "flag" :x 1} (:attributes added)))))
      (testing "--attr value resolves a payload reference"
        (let [added (api/op! rt 'add
                             ["Body" "--attr" "body=:stdin"]
                             {:payloads {"stdin" "hello from payload"}})]
          (is (= "hello from payload" (get-in added [:attributes :body]))))))))

(deftest add-with-edges
  (with-batteries
    (fn [rt]
      (let [target (api/add rt {:title "Target" :attributes {}})
            added (api/op! rt 'add ["Source" "--edge" (str "depends-on:" (:id target))])
            edges (:edges (api/subgraph rt [(:id added)] {:type "depends-on"}))]
        (is (match? (m/embeds [{:from_strand_id (:id added)
                                :to_strand_id (:id target)
                                :edge_type "depends-on"}])
                    edges))))))

(deftest add-loud-failures
  (with-batteries
    (fn [rt]
      (testing "missing required title fails in the parser"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required"
                              (api/op! rt 'add []))))
      (testing "invalid state fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"active or closed"
                              (api/op! rt 'add ["t" "--state" "bogus"]))))
      (testing "duplicate --attr key fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate attribute key"
                              (api/op! rt 'add ["t" "--attr" "k=a" "--attr" "k=b"])))))))

(deftest update-happy-path
  (with-batteries
    (fn [rt]
      (let [created (api/add rt {:title "Before" :attributes {:owner "old"}})
            id (:id created)
            updated (api/op! rt 'update [id "--title" "After" "--attr" "owner=new"])]
        (is (= "After" (:title updated)))
        (is (= {:owner "new"} (:attributes updated)))
        (testing "state can be flipped to closed"
          (is (= "closed" (:state (api/op! rt 'update [id "--state" "closed"])))))
        (testing "invalid state is rejected"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"active or closed"
                                (api/op! rt 'update [id "--state" "replaced"]))))))))

(deftest show-supersede-burn
  (with-batteries
    (fn [rt]
      (let [a (api/add rt {:title "Original" :attributes {}})
            b (api/add rt {:title "Replacement" :attributes {}})]
        (testing "show returns the strand"
          (is (= "Original" (:title (api/op! rt 'show [(:id a)])))))
        (testing "supersede marks the old strand replaced"
          (api/op! rt 'supersede [(:id a) (:id b)])
          (is (= "replaced" (:state (api/show rt (:id a))))))
        (testing "burn deletes the strand"
          (let [result (api/op! rt 'burn [(:id b)])]
            (is (= [(:id b)] (:burned result)))
            (is (nil? (api/show rt (:id b))))))))))

(deftest list-and-ready
  (with-batteries
    (fn [rt]
      (let [active (api/add rt {:title "Active" :attributes {}})
            closed (api/add rt {:title "Closed" :attributes {} :state "closed"})
            blocked (api/add rt {:title "Blocked" :attributes {}
                                 :edges [{:type "depends-on" :to (:id active)}]})]
        (testing "list returns all strands and matches the weaver API"
          (is (= (set (map :id (api/list rt)))
                 (set (map :id (api/op! rt 'list []))))))
        (testing "list --state filters"
          (is (= #{(:id closed)} (set (map :id (api/op! rt 'list ["--state" "closed"]))))))
        (testing "ready hides strands blocked by active dependencies and matches the API"
          (let [ready-ids (set (map :id (api/op! rt 'ready [])))]
            (is (= (set (map :id (api/ready rt))) ready-ids))
            (is (contains? ready-ids (:id active)))
            (is (not (contains? ready-ids (:id blocked))))))))))

(deftest list-and-ready-named-queries
  (with-batteries
    (fn [rt]
      (api/register-query! rt 'owned {:params [:who]
                                      :where [:= [:attr :owner] [:param :who]]})
      (let [mine (api/add rt {:title "Mine" :attributes {:owner "agent"}})
            _theirs (api/add rt {:title "Theirs" :attributes {:owner "other"}})]
        (testing "list --query with --param"
          (is (= #{(:id mine)}
                 (set (map :id (api/op! rt 'list ["--query" "owned" "--param" "who=agent"]))))))
        (testing "ready --query with --param"
          (is (= #{(:id mine)}
                 (set (map :id (api/op! rt 'ready ["--query" "owned" "--param" "who=agent"]))))))
        (testing "unknown query fails loudly"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Query not found"
                                (api/op! rt 'list ["--query" "nope"]))))
        (testing "--param without --query fails loudly"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires --query"
                                (api/op! rt 'list ["--param" "who=agent"]))))))))

(deftest list-ready-and-named-queries-lean-project-large-attributes-only
  (with-batteries
    (fn [rt]
      (api/register-query! rt 'owned {:params [:who]
                                      :where [:= [:attr :owner] [:param :who]]})
      (let [at-floor (str/join (repeat 1022 "a"))
            over-floor (str/join (repeat 1023 "b"))
            strand (api/add rt {:title "Payload"
                                :attributes {:owner "agent"
                                             :at-floor at-floor
                                             :over-floor over-floor}})
            lean-list (first (filter #(= (:id strand) (:id %)) (api/op! rt 'list [])))
            lean-ready (first (filter #(= (:id strand) (:id %)) (api/op! rt 'ready [])))
            lean-query (first (api/op! rt 'list ["--query" "owned" "--param" "who=agent"]))
            full-show (api/op! rt 'show [(:id strand)])
            full-api-list (first (filter #(= (:id strand) (:id %)) (api/list rt)))]
        (testing "list/ready/query preserve values at the fixed 1 KiB floor"
          (doseq [row [lean-list lean-ready lean-query]]
            (is (= "agent" (get-in row [:attributes :owner])))
            (is (= at-floor (get-in row [:attributes :at-floor])))))
        (testing "list/ready/query replace values above the floor with the descriptor"
          (doseq [row [lean-list lean-ready lean-query]]
            (let [descriptor (get-in row [:attributes :over-floor])]
              (is (specs/omitted-attribute-descriptor? descriptor))
              (is (= {:skein/omitted true :bytes 1025} descriptor)))))
        (testing "show and trusted API list remain full-fidelity"
          (is (= over-floor (get-in full-show [:attributes :over-floor])))
          (is (= over-floor (get-in full-api-list [:attributes :over-floor]))))))))

(deftest subgraph-op-shape
  (with-batteries
    (fn [rt]
      (let [root (api/add rt {:title "Root" :attributes {}})
            child (api/add rt {:title "Child" :attributes {}
                               :edges [{:type "parent-of" :to (:id root)}]})
            result (api/op! rt 'subgraph [(:id child) "--relation" "parent-of"])]
        (is (= #{"root_ids" "strands" "edges"} (set (keys result))))
        (is (match? {"strands" (m/embeds [{:id (:id root)} {:id (:id child)}])}
                    result))))))

(defn- with-weave-pattern
  "Run f with a create-only test pattern registered under `task`."
  [f]
  (with-batteries
    (fn [rt]
      (api/register-pattern! rt 'task 'skein.spools.batteries-test/weave-test-pattern ::weave-input)
      (f rt))))

(deftest weave-happy-path-and-json-value
  (with-weave-pattern
    (fn [rt]
      (testing "input as a JSON payload reference creates the pattern batch"
        (let [result (api/op! rt 'weave ["--pattern" "task" "--input" ":stdin"]
                              {:payloads {"stdin" "{\"title\":\"Do it\"}"}})]
          (is (= #{:created :refs} (set (keys result))))
          (is (= ["Do it"] (map :title (:created result))))
          (testing "shape matches a direct weaver-API weave!"
            (let [direct (api/weave! rt 'task {:title "Do it"})]
              (is (= (set (keys result)) (set (keys direct))))))))
      (testing "literal inline JSON input works too"
        (let [result (api/op! rt 'weave ["--pattern" "task" "--input" "{\"title\":\"Inline\"}"])]
          (is (= ["Inline"] (map :title (:created result)))))))))

(deftest weave-loud-input-paths
  (with-weave-pattern
    (fn [rt]
      (testing "malformed JSON fails loudly before mutation"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not valid JSON"
                              (api/op! rt 'weave ["--pattern" "task" "--input" ":stdin"]
                                       {:payloads {"stdin" "{not json"}}))))
      (testing "empty input fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one JSON value"
                              (api/op! rt 'weave ["--pattern" "task" "--input" ":stdin"]
                                       {:payloads {"stdin" "   "}}))))
      (testing "trailing JSON value fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one JSON value"
                              (api/op! rt 'weave ["--pattern" "task" "--input" ":stdin"]
                                       {:payloads {"stdin" "{\"title\":\"x\"} 2"}}))))
      (testing "missing required --pattern fails in the parser"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required flag --pattern"
                              (api/op! rt 'weave ["--input" "{\"title\":\"x\"}"]))))
      (testing "unknown pattern fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Pattern not found"
                              (api/op! rt 'weave ["--pattern" "nope" "--input" "{\"title\":\"x\"}"])))))))

(deftest query-list-and-explain-shapes
  (with-batteries
    (fn [rt]
      (api/register-query! rt 'owned {:params [:who]
                                      :where [:= [:attr :owner] [:param :who]]})
      (testing "query list returns JSON-safe metadata for the registered query"
        (let [entries (api/op! rt 'query ["list"])
              owned (some #(when (= "owned" (get % "name")) %) entries)]
          (is (vector? entries))
          (is (some? owned))
          (is (= ["who"] (get owned "params")))
          (is (= ["who"] (get owned "referenced-params")))))
      (testing "query explain returns JSON-safe caller guidance"
        (let [explained (api/op! rt 'query ["explain" "owned"])]
          (is (= "owned" (get explained "name")))
          (is (= ["=" ["attr" "owner"] ["param" "who"]] (get explained "where")))
          (is (string? (get explained "where-form")))
          (is (string? (get explained "summary")))))
      (testing "query help renders declared subcommands"
        (let [detail (api/op! rt 'help ["query"])]
          (is (= ["explain" "list"] (mapv :name (get-in detail [:arg-spec :subcommands]))))
          (is (= [{:name "name" :type "string" :required true
                   :variadic false :parse nil :doc "Query name."}]
                 (->> (get-in detail [:arg-spec :subcommands])
                      (filter #(= "explain" (:name %)))
                      first
                      :positionals)))))
      (testing "unknown query subcommand fails in the parser with available names"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown subcommand"
                                      (api/op! rt 'query ["bogus"])))]
          (is (= :unknown-subcommand (:reason (ex-data e))))
          (is (= "bogus" (:token (ex-data e))))
          (is (= ["explain" "list"] (:available-subcommands (ex-data e))))))
      (testing "query explain without a name fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required"
                              (api/op! rt 'query ["explain"]))))
      (testing "query explain of an unknown query fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Query not found"
                              (api/op! rt 'query ["explain" "nope"])))))))

(deftest pattern-list-and-explain-shapes
  (with-weave-pattern
    (fn [rt]
      (testing "pattern list returns registered pattern metadata"
        (let [entries (api/op! rt 'pattern ["list"])]
          (is (vector? entries))
          (is (contains? (set (map :name entries)) "task"))))
      (testing "pattern explain returns input-spec guidance"
        (let [explained (api/op! rt 'pattern ["explain" "task"])]
          (is (= "task" (:name explained)))
          (is (= "skein.spools.batteries-test/weave-test-pattern" (:fn explained)))
          (is (string? (:input-spec explained)))))
      (testing "unknown pattern subcommand fails in the parser with available names"
        (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown subcommand"
                                      (api/op! rt 'pattern ["bogus"])))]
          (is (= :unknown-subcommand (:reason (ex-data e))))
          (is (= "bogus" (:token (ex-data e))))
          (is (= ["explain" "list"] (:available-subcommands (ex-data e))))))
      (testing "pattern explain without a name fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required"
                              (api/op! rt 'pattern ["explain"]))))
      (testing "pattern explain of an unknown pattern fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Pattern not found"
                              (api/op! rt 'pattern ["explain" "nope"])))))))
