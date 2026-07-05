(ns skein.chime-test
  "Tests for the chime local notification spool against a real weaver runtime.

  Chime ships no rules, so these tests register their own small rules over a
  neutral attribute vocabulary; the parent-completed rule mirrors the worked
  example in spools/chime/README.md."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.api.weaver.alpha :as api]
            [skein.core.db-test :as db-test]
            [skein.core.weaver.config :as daemon-config]
            [skein.core.weaver.runtime :as runtime]
            [skein.repl :as repl]
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

(defn- with-chime [f]
  (let [db-file (db-test/temp-db-file)
        config-dir (temp-config-dir)]
    (try
      (let [rt (runtime/start! db-file {:world (test-world (.getCanonicalPath config-dir))
                                        :publish? false})]
        (try
          (runtime/with-runtime-binding
            rt
            (fn []
              (chime/install!)
              (f rt config-dir)))
          (finally
            (runtime/stop! rt))))
      (finally
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

;; --- test rules over a neutral attribute vocabulary ------------------------

(deftest chime-rules-are-owned-by-runtime
  (let [db-a (db-test/temp-db-file)
        db-b (db-test/temp-db-file)
        config-a (temp-config-dir)
        config-b (temp-config-dir)]
    (try
      (let [rt-a (runtime/start! db-a {:world (test-world (.getCanonicalPath config-a))
                                       :publish? false})
            rt-b (runtime/start! db-b {:world (test-world (.getCanonicalPath config-b))
                                       :publish? false})]
        (try
          (runtime/with-runtime-binding rt-a
            #(chime/defrule! :phase-failed 'skein.chime-test/phase-failed-rule))
          (runtime/with-runtime-binding rt-b
            #(chime/defrule! :needs-human 'skein.chime-test/needs-human-ready-rule))
          (is (= [:phase-failed]
                 (runtime/with-runtime-binding rt-a #(mapv :name (chime/rules)))))
          (is (= [:needs-human]
                 (runtime/with-runtime-binding rt-b #(mapv :name (chime/rules)))))
          (finally
            (runtime/stop! rt-a)
            (runtime/stop! rt-b))))
      (finally
        (db-test/delete-sqlite-family! db-a)
        (db-test/delete-sqlite-family! db-b)))))

(defn- test-attr [strand k]
  (let [attrs (:attributes strand)]
    (or (get attrs (keyword k)) (get attrs k))))

(defn phase-failed-rule
  "Notify when a strand's phase attribute is failed."
  [{:keys [strand]}]
  (when (= "failed" (test-attr strand "phase"))
    {:title (str "Run failed: " (:title strand))
     :body (str "Strand " (:id strand)
                (when-let [error (test-attr strand "error")]
                  (str "\n\n" error)))}))

(defn needs-human-ready-rule
  "Notify when an active strand flagged needs-human is ready."
  [{:keys [strand ready-ids]}]
  (when (and (= "active" (:state strand))
             (= "true" (test-attr strand "needs-human"))
             (contains? ready-ids (:id strand)))
    {:title (str "Needs human: " (:title strand))
     :body (str "Strand " (:id strand) " is ready for attention.")}))

(defn parent-completed-rule
  "README worked example: notify when a strand with parent-of children closes."
  [{:keys [strand]}]
  (when (and (= "closed" (:state strand))
             (seq (repl/query [:edge/in "parent-of" [:= :id (:id strand)]])))
    {:title (str "Plan complete: " (:title strand))
     :body (str "Strand " (:id strand) " and the work it parents are finished.")}))

(defn- throwing-rule [_]
  (throw (ex-info "rule boom" {:why :test})))

(defn- invalid-notification-rule [_]
  {:body "missing title"})

;; --- tests ------------------------------------------------------------------

(deftest install-registers-no-rules
  (with-chime
    (fn [_ _]
      (is (= [] (chime/rules))))))

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
          ;; Wait for the record terminator, not the title: the notifier script
          ;; writes TITLE first and "---" last, and snapshotting mid-write races.
          (eventually #(file-contains? out-file "---"))
          (is (file-contains? out-file "BODY=Body text")))))))

(deftest rule-registration-validation
  (with-chime
    (fn [_ _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fully qualified"
                            (chime/defrule! :bad 'not-qualified)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be resolved"
                            (chime/defrule! :bad 'missing.ns/fn)))
      (is (= :phase-failed (:rule (chime/defrule! "phase-failed" 'skein.chime-test/phase-failed-rule))))
      (is (= :phase-failed (:removed (chime/remove-rule! :phase-failed))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Rule not found"
                            (chime/remove-rule! :phase-failed))))))

(deftest missing-notifier-is-recorded-loudly
  (with-chime
    (fn [rt _]
      (chime/defrule! :phase-failed 'skein.chime-test/phase-failed-rule)
      (let [run (api/add rt {:title "failed run"
                             :attributes {"phase" "failed"
                                          "error" "boom"}})]
        (chime/scan! {:strand/id (:id run)})
        (is (= :notifier-missing (:kind (last (chime/failures)))))
        (is (= "Run failed: failed run" (:title (last (chime/failures)))))))))

(deftest registered-rules-fire-end-to-end
  (with-chime
    (fn [rt config-dir]
      (chime/defrule! :phase-failed 'skein.chime-test/phase-failed-rule)
      (chime/defrule! :parent-completed 'skein.chime-test/parent-completed-rule)
      (let [out-file (bind-file-notifier! config-dir)
            failed (api/add rt {:title "run a"
                                :attributes {"phase" "failed"
                                             "error" "stacktrace"}})
            child (api/add rt {:title "child work"})
            parent (api/add rt {:title "plan p"
                                :edges [{:type "parent-of" :to (:id child)}]})]
        (chime/scan! {:strand/id (:id failed)})
        (eventually #(file-contains? out-file "Run failed: run a"))
        ;; titles and bodies land in separate appends, so bodies need their own wait
        (is (eventually #(file-contains? out-file "stacktrace")))
        (testing "parent-completed matches only once the parent closes"
          (Thread/sleep 150)
          (is (not (file-contains? out-file "Plan complete: plan p")))
          (api/update rt (:id parent) {:state "closed"})
          (chime/scan! {:strand/id (:id parent)})
          (is (eventually #(file-contains? out-file "Plan complete: plan p"))))))))

(deftest ready-rule-fires-born-ready-and-when-unblocked
  (with-chime
    (fn [rt config-dir]
      (chime/defrule! :needs-human 'skein.chime-test/needs-human-ready-rule)
      (let [out-file (bind-file-notifier! config-dir)
            born (api/add rt {:title "Approve proposal"
                              :attributes {"needs-human" "true"}})]
        (chime/scan! {:strand/id (:id born)})
        (eventually #(file-contains? out-file "Needs human: Approve proposal"))
        (let [blocker (api/add rt {:title "blocker"})
              blocked (:id (api/add rt {:title "Approve blocked"
                                        :attributes {"needs-human" "true"}
                                        :edges [{:type "depends-on" :to (:id blocker)}]}))]
          (chime/scan! {:strand/id blocked})
          (Thread/sleep 150)
          (is (not (file-contains? out-file "Needs human: Approve blocked")))
          (api/update rt (:id blocker) {:state "closed"})
          (chime/scan! {:strand/id (:id blocker)})
          (eventually #(file-contains? out-file "Needs human: Approve blocked")))))))

(deftest dedup-and-reset-seen
  (with-chime
    (fn [rt config-dir]
      (chime/defrule! :phase-failed 'skein.chime-test/phase-failed-rule)
      (let [out-file (bind-file-notifier! config-dir)
            run (api/add rt {:title "flaky run"
                             :attributes {"phase" "failed"
                                          "error" "boom"}})]
        (chime/scan! {:strand/id (:id run)})
        ;; Wait for the record terminator, not the title: the notifier script
        ;; writes TITLE first and "---" last, and snapshotting mid-write races.
        (eventually #(file-contains? out-file "---"))
        (let [once (slurp out-file)]
          (chime/scan! {:strand/id (:id run)})
          (Thread/sleep 150)
          (is (= once (slurp out-file)))
          (is (= {:seen 0} (chime/reset-seen!)))
          (chime/scan! {:strand/id (:id run)})
          (eventually #(> (count (re-seq #"flaky run" (slurp out-file))) 1)))))))

(deftest dedup-rearms-when-rule-stops-matching
  (with-chime
    (fn [rt config-dir]
      (chime/defrule! :phase-failed 'skein.chime-test/phase-failed-rule)
      (let [out-file (bind-file-notifier! config-dir)
            run (api/add rt {:title "retried run"
                             :attributes {"phase" "failed"
                                          "error" "first crash"}})]
        (chime/scan! {:strand/id (:id run)})
        (eventually #(file-contains? out-file "retried run"))
        ;; recovery clears the seen mark, so a later recurrence notifies again
        (api/update rt (:id run) {:attributes {"phase" "done"}})
        (chime/scan! {:strand/id (:id run)})
        (api/update rt (:id run) {:attributes {"phase" "failed"
                                               "error" "second crash"}})
        (chime/scan! {:strand/id (:id run)})
        (eventually #(> (count (re-seq #"retried run" (slurp out-file))) 1))))))

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
