(ns skein.api.cli.alpha-test
  "Tests for the blessed declarative op argv parser (SPEC-003-D003.C1/C2)."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.api.cli.alpha :as cli]))

(defn- reason
  "Run `f`, returning the :reason of the thrown parse error (or ::no-throw)."
  [f]
  (try (f) ::no-throw
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(defn- thrown-data
  "Run `f`, returning thrown ex-data (or ::no-throw)."
  [f]
  (try (f) ::no-throw
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(def add-spec
  {:op :add
   :doc "Add a strand"
   :flags {:state {:type :string :doc "Lifecycle state"}
           :count {:type :int :doc "How many"}
           :force {:type :boolean :doc "Skip checks"}
           :attr {:type :map :doc "Attribute key=value"}
           :edge {:type :string :repeat? true :doc "Edge spec"}
           :owner {:type :string :required? true :doc "Owner"}}
   :positionals [{:name :title :type :string :required? true :doc "Title"}
                 {:name :note :type :string :doc "Optional note"}]})

;; ---------------------------------------------------------------------------
;; Happy paths for every arg kind
;; ---------------------------------------------------------------------------

(deftest string-flag
  (is (= "closed" (:state (cli/parse add-spec ["--owner" "me" "--state" "closed" "t"])))))

(deftest int-flag-coerces
  (is (= 3 (:count (cli/parse add-spec ["--owner" "me" "--count" "3" "t"])))))

(deftest boolean-flag-presence
  (let [r (cli/parse add-spec ["--owner" "me" "--force" "t"])]
    (is (true? (:force r)))
    (is (not (contains? (cli/parse add-spec ["--owner" "me" "t"]) :force)))))

(deftest map-flag-accumulates
  (is (= {"priority" "high" "owner" "agent"}
         (:attr (cli/parse add-spec ["--owner" "me" "--attr" "priority=high" "--attr" "owner=agent" "t"])))))

(deftest map-flag-empty-value-allowed
  (is (= {"k" ""} (:attr (cli/parse add-spec ["--owner" "me" "--attr" "k=" "t"])))))

(deftest repeat-flag-collects-vector
  (is (= ["depends-on:a" "depends-on:b"]
         (:edge (cli/parse add-spec ["--owner" "me" "--edge" "depends-on:a" "--edge" "depends-on:b" "t"])))))

(deftest required-positional-and-optional
  (let [r (cli/parse add-spec ["--owner" "me" "title" "a note"])]
    (is (= "title" (:title r)))
    (is (= "a note" (:note r))))
  (is (= "title" (:title (cli/parse add-spec ["--owner" "me" "title"])))))

(deftest variadic-trailing-positional
  (let [spec {:op :weave
              :positionals [{:name :pattern :type :string :required? true}
                            {:name :ids :type :string :variadic? true}]}]
    (is (= {:pattern "p" :ids ["a" "b" "c"]} (cli/parse spec ["p" "a" "b" "c"])))
    (is (= {:pattern "p" :ids []} (cli/parse spec ["p"])))))

;; ---------------------------------------------------------------------------
;; Loud failures (MI2)
;; ---------------------------------------------------------------------------

(deftest unknown-flag-fails
  (is (= :unknown-flag (reason #(cli/parse add-spec ["--owner" "me" "--bogus" "x" "t"])))))

(deftest missing-required-flag-fails
  (is (= :missing-required (reason #(cli/parse add-spec ["--state" "closed" "t"])))))

(deftest missing-required-positional-fails
  (is (= :missing-required (reason #(cli/parse add-spec ["--owner" "me"])))))

(deftest type-violation-fails
  (is (= :type-violation (reason #(cli/parse add-spec ["--owner" "me" "--count" "notint" "t"])))))

(deftest duplicate-non-repeat-flag-fails
  (is (= :duplicate-flag (reason #(cli/parse add-spec ["--owner" "me" "--state" "a" "--state" "b" "t"]))))
  (testing "boolean duplicate is loud too"
    (is (= :duplicate-flag (reason #(cli/parse add-spec ["--owner" "me" "--force" "--force" "t"]))))))

(deftest malformed-kv-fails
  (is (= :malformed-kv (reason #(cli/parse add-spec ["--owner" "me" "--attr" "novalue" "t"]))))
  (testing "empty key is malformed"
    (is (= :malformed-kv (reason #(cli/parse add-spec ["--owner" "me" "--attr" "=v" "t"])))))
  (testing "missing token after map flag is malformed"
    (is (= :malformed-kv (reason #(cli/parse add-spec ["--owner" "me" "--attr"]))))))

(deftest trailing-tokens-fail
  (is (= :trailing-tokens (reason #(cli/parse add-spec ["--owner" "me" "a" "b" "c"])))))

;; ---------------------------------------------------------------------------
;; Payload references (MI3)
;; ---------------------------------------------------------------------------

(def payload-spec
  {:op :weave
   :flags {:attr {:type :map}}
   :positionals [{:name :body :type :string :required? true}
                 {:name :extra :type :string :variadic? true}]})

(deftest stdin-reference-resolves
  (is (= "hello" (:body (cli/parse payload-spec [":stdin"] {"stdin" "hello"})))))

(deftest named-payload-reference-resolves
  (is (= "manifest-body"
         (:body (cli/parse payload-spec [":payload/manifest"] {"manifest" "manifest-body"})))))

(deftest reference-resolves-across-map-and-positionals
  (let [r (cli/parse payload-spec [":stdin" "--attr" "doc=:payload/note" ":payload/tail"]
                     {"stdin" "B" "note" "N" "tail" "T"})]
    (is (= "B" (:body r)))
    (is (= {"doc" "N"} (:attr r)))
    (is (= ["T"] (:extra r)))))

(deftest whole-value-only-matching
  (testing "a value containing :stdin as a substring is untouched (real ref consumes the payload)"
    (let [r (cli/parse payload-spec ["prefix:stdin" ":stdin"] {"stdin" "X"})]
      (is (= "prefix:stdin" (:body r)))
      (is (= ["X"] (:extra r)))))
  (testing "a leading-substring :payload ref is untouched"
    (let [r (cli/parse payload-spec ["x:payload/note" ":payload/note"] {"note" "N"})]
      (is (= "x:payload/note" (:body r)))
      (is (= ["N"] (:extra r))))))

(deftest dangling-reference-fails-loudly
  (is (= :missing-payload (reason #(cli/parse payload-spec [":stdin"] {}))))
  (is (= :missing-payload (reason #(cli/parse payload-spec [":payload/absent"] {"stdin" "x"})))))

(deftest unused-payload-fails-loudly
  (let [r (reason #(cli/parse payload-spec ["literal"] {"stdin" "unused"}))]
    (is (= :unused-payloads r)))
  (testing "message names the unused slots"
    (try (cli/parse payload-spec [":stdin"] {"stdin" "used" "orphan" "x"})
         (is false "expected throw")
         (catch clojure.lang.ExceptionInfo e
           (is (= :unused-payloads (:reason (ex-data e))))
           (is (= ["orphan"] (:unused (ex-data e))))))))

(deftest same-payload-referenced-twice-is-not-unused
  (let [r (cli/parse payload-spec [":stdin" ":stdin"] {"stdin" "S"})]
    (is (= "S" (:body r)))
    (is (= ["S"] (:extra r)))))

;; ---------------------------------------------------------------------------
;; Payload parse declarations (MI4)
;; ---------------------------------------------------------------------------

(def parse-spec
  {:op :weave
   :flags {:data {:type :string :parse :json}}
   :positionals [{:name :items :type :string :parse :jsonl :required? true}]})

(deftest json-parse-success
  (is (= {"a" 1} (:data (cli/parse parse-spec ["[]" "--data" ":stdin"] {"stdin" "{\"a\": 1}"})))))

(deftest jsonl-parse-success
  (is (= [{"k" 1} {"k" 2}]
         (:items (cli/parse parse-spec [":stdin"] {"stdin" "{\"k\":1}\n{\"k\":2}\n"})))))

(deftest jsonl-skips-blank-lines
  (is (= [{"k" 1}] (:items (cli/parse parse-spec [":stdin"] {"stdin" "\n{\"k\":1}\n\n"})))))

(deftest json-parse-failure-is-loud
  (is (= :malformed-json (reason #(cli/parse parse-spec ["[]" "--data" ":stdin"] {"stdin" "{not json"})))))

(deftest jsonl-parse-failure-has-line-context
  (try (cli/parse parse-spec [":stdin"] {"stdin" "{\"k\":1}\nnot-json"})
       (is false "expected throw")
       (catch clojure.lang.ExceptionInfo e
         (is (= :malformed-jsonl (:reason (ex-data e))))
         (is (= 2 (:line (ex-data e)))))))

(deftest parse-applies-to-literal-value
  (is (= {"x" true} (:data (cli/parse parse-spec ["[]" "--data" "{\"x\":true}"])))))

;; ---------------------------------------------------------------------------
;; explain (MI5)
;; ---------------------------------------------------------------------------

(deftest explain-shape
  (let [e (cli/explain add-spec)]
    (is (= "add" (:op e)))
    (is (= "Add a strand" (:doc e)))
    (let [attr (first (filter #(= "attr" (:name %)) (:flags e)))]
      (is (= "map" (:type attr)))
      (is (false? (:required attr))))
    (let [owner (first (filter #(= "owner" (:name %)) (:flags e)))]
      (is (= "--owner" (:flag owner)))
      (is (true? (:required owner))))
    (let [edge (first (filter #(= "edge" (:name %)) (:flags e)))]
      (is (true? (:repeat edge))))
    (is (= [{:name "title" :type "string" :required true :variadic false :parse nil :doc "Title"}
            {:name "note" :type "string" :required false :variadic false :parse nil :doc "Optional note"}]
           (:positionals e)))))

(deftest explain-renders-parse-declarations
  (let [e (cli/explain parse-spec)
        data (first (filter #(= "data" (:name %)) (:flags e)))]
    (is (= "json" (:parse data)))
    (is (= "jsonl" (:parse (first (:positionals e)))))))

;; ---------------------------------------------------------------------------
;; Subcommands
;; ---------------------------------------------------------------------------

(def subcommand-spec
  {:op :thing
   :doc "Manage things"
   :subcommands {"add" {:doc "Add a thing"
                        :flags {:force {:type :boolean :doc "Skip checks"}
                                :data {:type :string :parse :json :doc "JSON data"}}
                        :positionals [{:name :title :type :string :required? true :doc "Title"}]}
                 "list" {:doc "List things"
                         :flags {:limit {:type :int :doc "Maximum count"}}
                         :positionals []}
                 "tag" {:doc "Tag things"
                        :flags {:attr {:type :map :doc "Metadata"}}
                        :positionals [{:name :id :type :string :required? true}
                                      {:name :labels :type :string :variadic? true}]}}})

(deftest subcommand-routes-and-merges-matched-name
  (is (= {:subcommand "add" :force true :title "hello"}
         (cli/parse subcommand-spec ["add" "--force" "hello"]))))

(deftest subcommand-supports-each-arg-kind
  (testing "int flags"
    (is (= {:subcommand "list" :limit 10}
           (cli/parse subcommand-spec ["list" "--limit" "10"]))))
  (testing "map flags and variadic positionals"
    (is (= {:subcommand "tag" :attr {"k" "v"} :id "abc" :labels ["x" "y"]}
           (cli/parse subcommand-spec ["tag" "--attr" "k=v" "abc" "x" "y"]))))
  (testing "nested required args remain local to the routed subcommand"
    (is (= :missing-required (reason #(cli/parse subcommand-spec ["add"]))))))

(deftest subcommand-missing-and-unknown-errors-carry-available-names
  (try (cli/parse subcommand-spec [])
       (is false "expected throw")
       (catch clojure.lang.ExceptionInfo e
         (is (= :missing-subcommand (:reason (ex-data e))))
         (is (= :thing (:op (ex-data e))))
         (is (= ["add" "list" "tag"] (:available-subcommands (ex-data e))))))
  (try (cli/parse subcommand-spec ["bogus"])
       (is false "expected throw")
       (catch clojure.lang.ExceptionInfo e
         (is (= :unknown-subcommand (:reason (ex-data e))))
         (is (= "bogus" (:token (ex-data e))))
         (is (= ["add" "list" "tag"] (:available-subcommands (ex-data e)))))))

(deftest subcommand-structure-failures-are-loud
  (testing "top-level flags cannot be mixed with subcommands"
    (is (= :invalid-subcommands
           (reason #(cli/validate-subcommands! (assoc subcommand-spec :flags {:verbose {:type :boolean}}))))))
  (testing "top-level positionals cannot be mixed with subcommands"
    (is (= :invalid-subcommands
           (reason #(cli/validate-subcommands! (assoc subcommand-spec :positionals [{:name :id}]))))))
  (testing "subcommand names must be non-blank strings"
    (let [data (thrown-data #(cli/validate-subcommands! {:op :x :subcommands {"" {:doc "Nope"}}}))]
      (is (= :invalid-subcommand-name (:reason data)))
      (is (= :x (:op data)))
      (is (= "" (:subcommand data)))
      (is (= :subcommands (:field data)))))
  (testing "nested subcommand specs must be maps"
    (let [data (thrown-data #(cli/validate-subcommands! {:op :x :subcommands {"a" 42}}))]
      (is (= :invalid-subcommand-spec (:reason data)))
      (is (= :x (:op data)))
      (is (= "a" (:subcommand data)))
      (is (= :subcommands (:field data)))
      (is (= 42 (:value data)))))
  (testing "nested flags must be a map"
    (let [data (thrown-data #(cli/validate-subcommands! {:op :x :subcommands {"a" {:flags []}}}))]
      (is (= :invalid-subcommand-flags (:reason data)))
      (is (= :x (:op data)))
      (is (= "a" (:subcommand data)))
      (is (= :flags (:field data)))
      (is (= [] (:value data)))))
  (testing "nested flag entries must be keyword to map"
    (let [bad-name (thrown-data #(cli/validate-subcommands! {:op :x :subcommands {"a" {:flags {"force" {:type :boolean}}}}}))
          bad-spec (thrown-data #(cli/validate-subcommands! {:op :x :subcommands {"a" {:flags {:force true}}}}))]
      (is (= :invalid-subcommand-flag (:reason bad-name)))
      (is (= :flags (:field bad-name)))
      (is (= "force" (:arg bad-name)))
      (is (= :invalid-subcommand-flag (:reason bad-spec)))
      (is (= :force (:arg bad-spec)))
      (is (true? (:value bad-spec)))))
  (testing "nested positionals must be sequential"
    (let [data (thrown-data #(cli/validate-subcommands! {:op :x :subcommands {"a" {:positionals {:name :id}}}}))]
      (is (= :invalid-subcommand-positionals (:reason data)))
      (is (= :x (:op data)))
      (is (= "a" (:subcommand data)))
      (is (= :positionals (:field data)))
      (is (= {:name :id} (:value data)))))
  (testing "nested positional entries must be maps with keyword :name"
    (let [bad-entry (thrown-data #(cli/validate-subcommands! {:op :x :subcommands {"a" {:positionals ["id"]}}}))
          bad-name (thrown-data #(cli/validate-subcommands! {:op :x :subcommands {"a" {:positionals [{:name "id"}]}}}))]
      (is (= :invalid-subcommand-positional (:reason bad-entry)))
      (is (= :positionals (:field bad-entry)))
      (is (= 0 (:index bad-entry)))
      (is (= "id" (:value bad-entry)))
      (is (= :invalid-subcommand-positional (:reason bad-name)))
      (is (= {:name "id"} (:value bad-name)))))
  (testing "nested flag and positional types must be known"
    (let [bad-flag (thrown-data #(cli/validate-subcommands! {:op :x :subcommands {"a" {:flags {:n {:type :strnig}}}}}))
          bad-pos (thrown-data #(cli/validate-subcommands! {:op :x :subcommands {"a" {:positionals [{:name :id :type :uuid}]}}}))]
      (is (= :invalid-arg-type (:reason bad-flag)))
      (is (= :flags (:field bad-flag)))
      (is (= :n (:arg bad-flag)))
      (is (= :strnig (:type bad-flag)))
      (is (= [:boolean :boolean-token :int :map :string] (:supported-types bad-flag)))
      (is (= :invalid-arg-type (:reason bad-pos)))
      (is (= :positionals (:field bad-pos)))
      (is (= :id (:arg bad-pos)))
      (is (= :uuid (:type bad-pos)))))
  (testing "nested subcommands are rejected"
    (is (= :invalid-subcommands
           (reason #(cli/validate-subcommands!
                     {:op :x :subcommands {"a" {:subcommands {"b" {}}}}}))))))

(deftest subcommand-reserved-name-failures-are-loud
  (testing "nested flag named subcommand is reserved"
    (is (= :reserved-subcommand
           (reason #(cli/validate-subcommands!
                     {:op :x :subcommands {"a" {:flags {:subcommand {:type :string}}}}})))))
  (testing "nested positional named subcommand is reserved"
    (is (= :reserved-subcommand
           (reason #(cli/validate-subcommands!
                     {:op :x :subcommands {"a" {:positionals [{:name :subcommand}]}}}))))))

(deftest subcommand-payload-ref-and-json-parse-use-routed-spec
  (is (= {:subcommand "add" :title "literal" :data {"ok" true}}
         (cli/parse subcommand-spec ["add" "literal" "--data" ":payload/body"]
                    {"body" "{\"ok\":true}"})))
  (testing "unused payloads are evaluated against routed subcommand consumption"
    (try (cli/parse subcommand-spec ["list"] {"body" "unused"})
         (is false "expected throw")
         (catch clojure.lang.ExceptionInfo e
           (is (= :unused-payloads (:reason (ex-data e))))
           (is (= ["body"] (:unused (ex-data e))))))))

(deftest explain-renders-subcommands
  (let [e (cli/explain subcommand-spec)]
    (is (= "thing" (:op e)))
    (is (= "Manage things" (:doc e)))
    (is (= [] (:flags e)))
    (is (= [] (:positionals e)))
    (is (= ["add" "list" "tag"] (mapv :name (:subcommands e))))
    (let [add (first (:subcommands e))]
      (is (= "Add a thing" (:doc add)))
      (is (= [{:name "data" :flag "--data" :type "string" :required false :repeat false :parse "json" :doc "JSON data"}
              {:name "force" :flag "--force" :type "boolean" :required false :repeat false :parse nil :doc "Skip checks"}]
             (:flags add)))
      (is (= [{:name "title" :type "string" :required true :variadic false :parse nil :doc "Title"}]
             (:positionals add))))))

(deftest flat-spec-parse-and-explain-regression
  (is (= {:owner "me" :title "title"}
         (cli/parse add-spec ["--owner" "me" "title"])))
  (is (= {:op "add"
          :doc "Add a strand"
          :flags (mapv (fn [[k v]]
                         {:name (name k)
                          :flag (str "--" (name k))
                          :type (name (:type v :string))
                          :required (boolean (:required? v))
                          :repeat (boolean (:repeat? v))
                          :parse (some-> (:parse v) name)
                          :doc (:doc v)})
                       (sort-by key (:flags add-spec)))
          :positionals [{:name "title" :type "string" :required true :variadic false :parse nil :doc "Title"}
                        {:name "note" :type "string" :required false :variadic false :parse nil :doc "Optional note"}]}
         (cli/explain add-spec))))
