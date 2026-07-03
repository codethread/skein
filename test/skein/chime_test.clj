(ns skein.chime-test
  "Tests for the chime local notification spool against a real weaver runtime."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.api.batch.alpha :as batch]
            [skein.api.weaver.alpha :as api]
            [skein.core.db-test :as db-test]
            [skein.core.weaver.config :as daemon-config]
            [skein.core.weaver.runtime :as runtime]
            [skein.spools.chime :as chime]))

(defn- temp-config-dir []
  (doto (.toFile (java.nio.file.Files/createTempDirectory
                  (.toPath (io/file "/tmp"))
                  "skein-chime-config"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
    (.mkdirs)))

(defn- test-world [config-dir]
  (daemon-config/world config-dir
                       (str config-dir "/state")
                       (str config-dir "/data")))

(defn- reset-chime! []
  (reset! @#'chime/notifier-binding nil)
  (reset! @#'chime/rule-registry {})
  (reset! @#'chime/seen-notifications #{})
  (reset! @#'chime/failure-log []))

(defn- with-chime [f]
  (let [db-file (db-test/temp-db-file)
        config-dir (temp-config-dir)]
    (try
      (reset-chime!)
      (let [rt (runtime/start! db-file {:world (test-world (.getCanonicalPath config-dir))})]
        (try
          (chime/install!)
          (f rt config-dir)
          (finally
            (runtime/stop! rt))))
      (finally
        (reset-chime!)
        (db-test/delete-sqlite-family! db-file)))))

(defn- write-notifier! [dir out-file]
  (let [script (io/file dir "notify.sh")]
    (spit script (str "#!/bin/sh\n"
                      "printf 'TITLE=%s\\n' \"$1\" >> " (pr-str (str out-file)) "\n"
                      "printf 'BODY=' >> " (pr-str (str out-file)) "\n"
                      "cat >> " (pr-str (str out-file)) "\n"
                      "printf '\\n---\\n' >> " (pr-str (str out-file)) "\n"))
    (.setExecutable script true)
    (.getCanonicalPath script)))

(defn- eventually [pred]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (if-let [value (pred)]
        value
        (if (> (System/currentTimeMillis) deadline)
          (throw (ex-info "Timed out waiting for condition" {}))
          (do (Thread/sleep 50) (recur)))))))

(defn- file-contains? [file text]
  (and (.exists file) (str/includes? (slurp file) text)))

(defn- bind-file-notifier! [dir]
  (let [out-file (io/file dir "notifications.txt")
        script (write-notifier! dir out-file)]
    (chime/set-notifier! {:argv [script]})
    out-file))

(deftest notifier-binding-and-manual-notify
  (with-chime
    (fn [_ config-dir]
      (testing "binding validation fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (chime/set-notifier! {:argv ["x"] :extra true})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty"
                              (chime/set-notifier! {:argv []})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank"
                              (chime/notify! {:body "no title"}))))
      (testing "notify! appends title and writes body to stdin"
        (let [out-file (bind-file-notifier! config-dir)]
          (is (= {:argv [(.getCanonicalPath (io/file config-dir "notify.sh")) "Hello"]
                  :status :started
                  :title "Hello"}
                 (chime/notify! {:title "Hello" :body "Body text"})))
          (eventually #(file-contains? out-file "TITLE=Hello"))
          (is (file-contains? out-file "BODY=Body text")))))))

(deftest rule-registration-validation
  (with-chime
    (fn [_ _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fully qualified"
                            (chime/defrule! :bad 'not-qualified)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be resolved"
                            (chime/defrule! :bad 'missing.ns/fn)))
      (is (= :agent-failure (:rule (chime/defrule! "agent-failure" 'skein.spools.chime/agent-failure))))
      (is (= :agent-failure (:removed (chime/remove-rule! :agent-failure))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Rule not found"
                            (chime/remove-rule! :agent-failure))))))

(deftest missing-notifier-is-recorded-loudly
  (with-chime
    (fn [rt _]
      (let [run (api/add rt {:title "failed agent"
                             :attributes {"shuttle/phase" "failed"
                                          "shuttle/error" "boom"}})]
        (chime/scan! {:strand/id (:id run)})
        (is (= :notifier-missing (:kind (last (chime/failures)))))
        (is (= "Agent run failed: failed agent" (:title (last (chime/failures)))))))))

(deftest default-agent-and-treadle-rules-fire-end-to-end
  (with-chime
    (fn [rt config-dir]
      (let [out-file (bind-file-notifier! config-dir)
            failed (api/add rt {:title "agent a"
                                :attributes {"shuttle/phase" "failed"
                                             "shuttle/error" "stacktrace"}})
            errored (api/add rt {:title "gate b"
                                 :attributes {"treadle/error" "no harness"}})]
        (chime/scan! {:strand/id (:id failed)})
        (chime/scan! {:strand/id (:id errored)})
        (eventually #(file-contains? out-file "Agent run failed: agent a"))
        (eventually #(file-contains? out-file "Treadle error: gate b"))
        ;; titles and bodies land in separate appends, so bodies need their own wait
        (is (eventually #(file-contains? out-file "stacktrace")))
        (is (eventually #(file-contains? out-file "no harness")))))))

(deftest hitl-checkpoint-ready-born-ready-and-unblocked
  (with-chime
    (fn [rt config-dir]
      (let [out-file (bind-file-notifier! config-dir)
            born (api/add rt {:title "Approve proposal"
                              :attributes {"workflow/role" "checkpoint"
                                           "workflow/hitl" "true"}})]
        (chime/scan! {:strand/id (:id born)})
        (eventually #(file-contains? out-file "HITL checkpoint ready: Approve proposal"))
        (let [blocker (api/add rt {:title "blocker"})
              blocked (:id (api/add rt {:title "Approve blocked"
                                        :attributes {"workflow/role" "checkpoint"
                                                     "workflow/hitl" "true"}
                                        :edges [{:type "depends-on" :to (:id blocker)}]}))]
          (chime/scan! {:strand/id blocked})
          (Thread/sleep 150)
          (is (not (file-contains? out-file "HITL checkpoint ready: Approve blocked")))
          (api/update rt (:id blocker) {:state "closed"})
          (chime/scan! {:strand/id (:id blocker)})
          (eventually #(file-contains? out-file "HITL checkpoint ready: Approve blocked")))))))

(deftest dedup-and-reset-seen
  (with-chime
    (fn [rt config-dir]
      (let [out-file (bind-file-notifier! config-dir)
            run (api/add rt {:title "flaky agent"
                             :attributes {"shuttle/phase" "failed"
                                          "shuttle/error" "boom"}})]
        (chime/scan! {:strand/id (:id run)})
        (eventually #(file-contains? out-file "flaky agent"))
        (let [once (slurp out-file)]
          (chime/scan! {:strand/id (:id run)})
          (Thread/sleep 150)
          (is (= once (slurp out-file)))
          (is (= {:seen 0} (chime/reset-seen!)))
          (chime/scan! {:strand/id (:id run)})
          (eventually #(> (count (re-seq #"flaky agent" (slurp out-file))) 1)))))))

(deftest rule-failures-are-recorded
  (with-chime
    (fn [rt _]
      (let [strand (api/add rt {:title "x"})]
        (chime/defrule! :throwing 'skein.chime-test/throwing-rule)
        (chime/scan! {:strand/id (:id strand)})
        (is (= :rule (:kind (last (chime/failures)))))
        (is (= :throwing (:rule (last (chime/failures)))))
        (chime/defrule! :invalid 'skein.chime-test/invalid-notification-rule)
        (chime/scan! {:strand/id (:id strand)})
        (is (= :rule (:kind (last (chime/failures)))))
        (is (= :invalid (:rule (last (chime/failures)))))))))

(defn- throwing-rule [_]
  (throw (ex-info "rule boom" {:why :test})))

(defn- invalid-notification-rule [_]
  {:body "missing title"})

(deftest spool-loads-through-approved-spool-workspace-flow
  (let [db-file (db-test/temp-db-file)
        config-dir (temp-config-dir)
        repo-root (.getCanonicalPath (io/file "spools/chime"))]
    (try
      (reset-chime!)
      (spit (io/file config-dir "spools.edn")
            (pr-str {:spools {'skein.spools/chime {:local/root repo-root}}}))
      (let [rt (runtime/start! db-file {:world (test-world (.getCanonicalPath config-dir))})]
        (try
          (let [synced ((requiring-resolve 'skein.api.runtime.alpha/sync!))
                used ((requiring-resolve 'skein.api.runtime.alpha/use!)
                      :chime {:ns 'skein.spools.chime
                              :spools ['skein.spools/chime]
                              :call 'skein.spools.chime/install!
                              :required? true})]
            (is (contains? #{:loaded :already-available}
                           (get-in synced [:spools 'skein.spools/chime :status])))
            (is (= :loaded (:status used)))
            (is (some #(= :chime/engine (:key %)) (api/event-handlers rt))))
          (finally
            (runtime/stop! rt))))
      (finally
        (reset-chime!)
        (db-test/delete-sqlite-family! db-file)))))
