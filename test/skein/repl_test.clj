(ns skein.repl-test
  "Tests for skein.repl interactive convenience wrappers."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [nrepl.cmdline]
            [nrepl.core :as nrepl]
            [skein.core.client]
            [skein.core.weaver.config :as weaver-config]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.core.db-test :as db-test]
            [skein.repl :as repl]
            [skein.source-file :as source-file]))
(defn test-world [config-dir]
  (weaver-config/world config-dir
                       (str config-dir "/state")
                       (str config-dir "/data")))

(defn reset-open-state! []
  (reset! (var-get (ns-resolve 'skein.repl 'active-config-dir))
          (var-get (ns-resolve 'skein.repl 'no-connection))))

(s/def ::title string?)
(s/def ::simple-pattern-input (s/keys :req-un [::title]))

(defn simple-pattern [ctx]
  [{:ref 'created
    :title (get-in ctx [:input :title])}])

(defn with-runtime
  ([f]
   (with-runtime {} f))
  ([opts f]
   (let [db-file (db-test/temp-db-file)
         config-dir (str "/tmp/td-" (java.util.UUID/randomUUID))]
     (.mkdirs (java.io.File. config-dir))
     (let [world (test-world config-dir)
           rt (weaver-runtime/start! db-file (merge {:world world} opts))]
       (try
         (f rt db-file)
         (finally
           (reset-open-state!)
           (weaver-runtime/stop! rt)
           (db-test/delete-sqlite-family! db-file)))))))

(deftest helpers-fail-before-connect
  (reset-open-state!)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"No Skein weaver world is connected"
                        (repl/strands)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"No Skein weaver world is connected"
                        (repl/load-queries! "/path/does/not/matter.edn"))))

(deftest connect-without-arg-fails-loudly-without-selected-world
  (let [calls (atom [])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"connect! requires an explicit config-dir"
                          (repl/connect!)))
    (is (= [] @calls)
        "zero-arg connect! throws before reaching the status-world seam")
    (let [connected (#'repl/connect!* "/tmp/skein-connect-check" nil
                                      (fn [config-dir opts]
                                        (swap! calls conj {:config-dir config-dir :opts opts})
                                        {:ok true}))]
      (is (= connected (-> @calls first :config-dir)))
      (is (= [{:config-dir connected :opts {}}] @calls)
          "the seam is meaningful for config-dir connects, so the zero-arg assertion is not vacuous"))
    (reset-open-state!)))

(deftest connect-fails-without-selecting-a-daemon
  (let [config-dir (str "/tmp/td-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. config-dir))
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"metadata is missing or stale"
                            (repl/connect! config-dir)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No Skein weaver world is connected"
                            (repl/strands)))
      (finally
        (reset-open-state!)))))

(deftest failed-connect-clears-previous-selection
  (with-runtime
    {:publish? false}
    (fn [rt db-file]
      (repl/connect! (:config-dir (:metadata rt)))
      (spit db-file "not a config dir")
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"connect! expects a daemon config directory"
                              (repl/connect! db-file)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"No Skein weaver world is connected"
                              (repl/strands)))
        (finally
          (db-test/delete-sqlite-family! db-file))))))

(deftest dev-user-namespace-loads
  (require 'user :reload)
  (is (some? (ns-resolve 'user 'demo!))))

(deftest helpers-use-in-process-runtime-without-connect
  (with-runtime
    (fn [_rt _db-file]
      (reset-open-state!)
      (is (= {:database "initialized"} (repl/init!)))
      (let [design (repl/strand! "Sketch model" {:priority "high"} {:state "closed"})
            docs (repl/strand! "Write docs" {:owner "agent"})]
        (repl/update! (:id docs) {:edges [{:type "depends-on" :to (:id design)}]})
        (is (= {:owner "agent"} (:attributes (repl/strand (:id docs)))))
        (is (= #{(:id design) (:id docs)} (set (map :id (repl/strands)))))
        (is (= [(:id docs)] (mapv :id (repl/ready))))
        (is (= {"agent" [:= [:attr :owner] "agent"]}
               (repl/defquery! 'agent [:= [:attr :owner] "agent"])))
        (is (= [(:id docs)] (mapv :id (repl/query :agent))))
        (is (= {:name "simple"
                :fn 'skein.repl-test/simple-pattern
                :input-spec :skein.repl-test/simple-pattern-input}
               (repl/defpattern! 'simple 'skein.repl-test/simple-pattern :skein.repl-test/simple-pattern-input)))
        (is (= ["simple"] (mapv :name (repl/patterns))))
        (is (= "simple" (:name (repl/pattern 'simple))))
        (is (= "simple" (:name (repl/pattern-explain 'simple))))
        (is (= ["Pattern strand"]
               (mapv :title (:created (repl/weave! 'simple {:title "Pattern strand"})))))))))

(deftest helpers-use-daemon-backed-strand-flow
  (with-runtime
    (fn [rt _db-file]
      (is (= (:config-dir (:metadata rt)) (repl/connect! (:config-dir (:metadata rt)))))
      (is (= {:database "initialized"} (repl/init!)))
      (is (nil? (ns-resolve 'skein.repl 'task!)))
      (is (nil? (ns-resolve 'skein.repl 'task)))
      (is (nil? (ns-resolve 'skein.repl 'tasks)))
      (let [design (repl/strand! "Sketch model" {:priority "high"} {:state "closed"})
            docs (repl/strand! "Write docs" {:owner "agent"})
            scratch (repl/strand! "Scratch" {:kind "scratch"})
            old (repl/strand! "Old impl")
            replacement (repl/strand! "New impl")]
        (is (= {:priority "high"} (:attributes design)))
        (is (= "closed" (:state design)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Two-argument strand! treats the second argument as attributes"
                              (repl/strand! "Ambiguous" {:state "closed"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown core strand fields"
                              (repl/strand! "Invalid" {} {:priority "high"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown strand update fields"
                              (repl/update! (:id docs) {:priority "high"})))
        (repl/update! (:id docs) {:edges [{:type "depends-on" :to (:id design)}]})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"active or closed"
                              (repl/update! (:id docs) {:state "replaced"})))
        (is (= {:owner "agent"} (:attributes (repl/strand (:id docs)))))
        (is (= #{(:id design) (:id docs) (:id scratch) (:id old) (:id replacement)} (set (map :id (repl/strands)))))
        (is (= #{(:id docs) (:id scratch) (:id old) (:id replacement)} (set (map :id (repl/ready)))))
        (let [result (repl/supersede! (:id old) (:id replacement))]
          (is (= "replaced" (get-in result [:old :after :state])))
          (is (= [(:id replacement) (:id old) "supersedes"]
                 ((juxt :from_strand_id :to_strand_id :edge_type) (:supersedes-edge result)))))
        (is (= {:burned [(:id scratch)] :count 1} (repl/burn! (:id scratch))))
        (is (nil? (repl/strand (:id scratch))))))))

(deftest explicit-connected-stdin-main-evaluates-fixed-helper-forms
  (with-runtime
    (fn [rt _]
      (let [out (java.io.StringWriter.)]
        (binding [*in* (java.io.StringReader. "(init!)\n(strands)\n(ready)\n")
                  *out* out
                  *err* (java.io.StringWriter.)
                  *ns* (the-ns 'user)]
          (repl/-main "--stdin" (:config-dir (:metadata rt))))
        (let [lines (str/split-lines (str out))]
          (is (= 3 (count lines)))
          (is (= {:database "initialized"} (read-string (first lines))))
          (is (= [] (read-string (second lines))))
          (is (= [] (read-string (nth lines 2)))))))))

(deftest attach-stdin-evaluates-inside-weaver-jvm
  (with-runtime
    (fn [rt _]
      (let [{:keys [endpoint]} (:metadata rt)
            out (java.io.StringWriter.)]
        (binding [*in* (java.io.StringReader. "(+ 1 2)\n(str \"a\" \"b\")\n@skein.core.weaver.runtime/current-runtime\n")
                  *out* out
                  *err* (java.io.StringWriter.)]
          ((ns-resolve 'skein.repl 'attach-stdin!) (:host endpoint) (str (:port endpoint))))
        (let [lines (str/split-lines (str out))]
          (is (= 3 (count lines)))
          (is (= "3" (first lines)))
          (is (= "\"ab\"" (second lines)))
          (is (str/includes? (nth lines 2) ":metadata"))
          (is (str/includes? (nth lines 2) ":query-store")))))))

(deftest attach-stdin-preserves-out-and-value-order-per-form
  (with-runtime
    (fn [rt _]
      (let [{:keys [endpoint]} (:metadata rt)
            out (java.io.StringWriter.)]
        (binding [*in* (java.io.StringReader. "(do (print \"a\") 1)\n(do (print \"b\") 2)\n")
                  *out* out
                  *err* (java.io.StringWriter.)]
          ((ns-resolve 'skein.repl 'attach-stdin!) (:host endpoint) (str (:port endpoint))))
        (is (= "a1\nb2\n" (str out)))))))

(deftest attach-repl-delegates-to-helper-ready-nrepl-client-repl
  (with-runtime
    (fn [rt _]
      (let [{:keys [endpoint]} (:metadata rt)
            calls (atom [])
            out (java.io.StringWriter.)
            run-repl-fn (fn [host port options]
                          (swap! calls conj {:host host
                                             :port port
                                             :options options})
                          (let [conn (nrepl/connect :host host :port port)
                                session (nrepl/client-session (nrepl/client conn 60000))]
                            (try
                              (swap! nrepl.cmdline/running-repl assoc :client session)
                              ((:prompt options) 'user)
                              (let [responses (doall (nrepl/message session {:op "eval" :code "(ready)"}))]
                                (is (= "[]" (last (keep :value responses)))))
                              (finally
                                (swap! nrepl.cmdline/running-repl assoc :client nil)
                                (.close conn)))))]
        (binding [*in* (java.io.StringReader. "(+ 10 5)\n")
                  *out* out
                  *err* (java.io.StringWriter.)]
          ((ns-resolve 'skein.repl 'attach-repl!)
           (:host endpoint)
           (str (:port endpoint))
           {:run-repl-fn run-repl-fn}))
        (is (= [{:host (:host endpoint)
                 :port (:port endpoint)
                 :options {:prompt (:prompt (:options (first @calls)))}}]
               @calls))
        (is (fn? (get-in (first @calls) [:options :prompt])))
        (is (not (str/includes? (str out) "15")))))))

(deftest runtime-api-works-from-explicit-connected-stdin-main
  (with-runtime
    (fn [rt _]
      (let [out (java.io.StringWriter.)]
        (binding [*in* (java.io.StringReader.
                        (source-file/render-forms
                         ['(require '[skein.api.current.alpha :as current]
                                    '[skein.api.runtime.alpha :as runtime])
                          '(def rt (current/runtime))
                          '(runtime/approved rt)
                          '(runtime/status rt)
                          '(runtime/plan rt)]))
                  *out* out
                  *err* (java.io.StringWriter.)
                  *ns* (the-ns 'user)]
          (repl/-main "--stdin" (:config-dir (:metadata rt))))
        (let [lines (str/split-lines (str out))
              status (read-string (nth lines 3))
              plan (read-string (nth lines 4))]
          (is (= 5 (count lines)))
          (is (= {:spools {} :families {}} (read-string (nth lines 2))))
          (is (= {:modules {}
                  :root/outcomes {}
                  :pending-generation nil}
                 (select-keys status [:modules :root/outcomes :pending-generation])))
          (is (= {:status :unchanged :mode :full}
                 (select-keys (:last-refresh status) [:status :mode])))
          (is (= {:status :unchanged :mode :full :dry-run? true}
                 (select-keys plan [:status :mode :dry-run?])))
          (is (str/includes? (:caveat plan) "No registry publication")))))))

(deftest query-helpers-use-daemon-backed-task-flow
  (with-runtime
    (fn [rt _db-file]
      (repl/connect! (:config-dir (:metadata rt)))
      (repl/init!)
      (let [design (:id (repl/strand! "Design" {:owner "agent"} {:state "closed"}))
            docs (:id (repl/strand! "Docs" {:owner "agent"}))
            misc (:id (repl/strand! "Misc" {:owner "human"}))]
        (repl/update! docs {:edges [{:type "depends-on" :to design}]})
        (is (= {"agent-ready" {:params [:owner]
                               :where [:= [:attr :owner] [:param :owner]]}}
               (repl/defquery! 'agent-ready {:params [:owner]
                                             :where [:= [:attr :owner] [:param :owner]]})))
        (is (= {"agent-ready" {:params [:owner]
                               :where [:= [:attr :owner] [:param :owner]]}}
               (repl/queries)))
        (is (= {:name "agent-ready"
                :params [:owner]
                :referenced-params [:owner]
                :where [:= [:attr :owner] [:param :owner]]
                :definition {:params [:owner]
                             :where [:= [:attr :owner] [:param :owner]]}
                :where-form "[:= [:attr :owner] [:param :owner]]"
                :definition-form "{:params [:owner], :where [:= [:attr :owner] [:param :owner]]}"
                :summary (str "Invoke this query with `strand list --query <name>` or `strand ready --query <name>` "
                              "and pass runtime values with repeated `--param key=value` arguments.")}
               (repl/query-explain :agent-ready)))
        (try
          (repl/query-explain :missing)
          (is false "missing query should fail loudly")
          (catch clojure.lang.ExceptionInfo e
            (is (str/includes? (ex-message e) "Query not found"))
            (is (= ["agent-ready"] (:available (ex-data e))))))
        (is (= {"agent-ready" {:params [:owner]
                               :where [:= [:attr :owner] [:param :owner]]}}
               (repl/queries)))
        (is (= #{design docs}
               (set (map :id (repl/strands 'agent-ready {:owner "agent"})))))
        (is (= [docs]
               (mapv :id (repl/ready [:= [:attr :owner] "agent"]))))
        (is (= [docs]
               (mapv :id (repl/ready :agent-ready {:owner "agent"}))))
        (is (= [misc]
               (mapv :id (repl/query :agent-ready {:owner "human"}))))))))

(deftest query-registry-helpers-use-daemon-memory
  (with-runtime
    (fn [rt db-file]
      (repl/connect! (:config-dir (:metadata rt)))
      (repl/init!)
      (let [agent (:id (repl/strand! "Agent task" {:owner "agent"}))
            human (:id (repl/strand! "Human task" {:owner "human"}))]
        (is (= {"mine" [:= [:attr :owner] "agent"]}
               (repl/defquery! :mine [:= [:attr :owner] "agent"])))
        (is (= {"mine" [:= [:attr :owner] "agent"]}
               (repl/queries)))
        (is (= "mine" (:name (repl/query-explain "mine"))))
        (is (= [agent] (mapv :id (repl/strands 'mine))))
        (weaver-runtime/stop! rt)
        (let [fresh-rt (weaver-runtime/start! db-file {:world (test-world (:config-dir (:metadata rt)))})]
          (try
            (is (= {} (repl/queries)))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Query not found"
                                  (repl/strands :mine)))
            (let [query-file (java.io.File/createTempFile "todo-queries" ".edn")]
              (try
                (spit query-file (pr-str {'mine [:= [:attr :owner] "human"]}))
                (is (= {"mine" [:= [:attr :owner] "human"]}
                       (repl/load-queries! (.getAbsolutePath query-file))))
                (is (= [human] (mapv :id (repl/query :mine))))
                (spit query-file "{mine [:= [:attr :owner] \"agent\"]} {:extra true}")
                (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"exactly one form"
                                      (repl/load-queries! (.getAbsolutePath query-file))))
                (finally
                  (.delete query-file))))
            (finally
              (weaver-runtime/stop! fresh-rt))))))))

(deftest burn-tombstone-reads-use-in-process-datasource
  (with-runtime
    (fn [_rt _db-file]
      (reset-open-state!)
      (repl/init!)
      (let [design (:id (repl/strand! "Sketch model" {:priority "high"}))
            docs (:id (repl/strand! "Write docs" {:owner "agent"}))]
        (repl/update! docs {:edges [{:type "depends-on" :to design}]})
        (repl/burn! docs)
        (let [[tombstone :as history] (repl/burn-history docs)]
          (is (= 1 (count history)))
          (is (= docs (:strand_id tombstone)))
          (is (= "Write docs" (:title tombstone)))
          (is (= {:value "agent" :archived false} (get-in tombstone [:attributes :owner])))
          (is (= [{:from docs :to design :type "depends-on" :attributes {}}]
                 (:edges tombstone)))
          (is (some? (:recorded_at tombstone))))
        (is (= [] (repl/burn-history design)))
        (is (= [docs] (mapv :strand_id (repl/recent-burns 10))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Read result limit must be a positive integer"
                              (repl/recent-burns 0)))))))

(deftest burn-tombstone-reads-require-in-process-runtime
  (reset-open-state!)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"mill weaver repl"
                        (repl/burn-history "anything")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"mill weaver repl"
                        (repl/recent-burns 5))))

(deftest helpers-fail-loudly-when-daemon-becomes-unavailable
  (let [db-file (db-test/temp-db-file)
        config-dir (str "/tmp/td-" (java.util.UUID/randomUUID))]
    (.mkdirs (java.io.File. config-dir))
    (let [world (test-world config-dir)
          rt (weaver-runtime/start! db-file {:world world})]
      (try
        (repl/connect! (:config-dir (:metadata rt)))
        (weaver-runtime/stop! rt)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"metadata is missing or stale"
                              (repl/strands)))
        (finally
          (reset-open-state!)
          (weaver-runtime/stop! rt)
          (db-test/delete-sqlite-family! db-file))))))
