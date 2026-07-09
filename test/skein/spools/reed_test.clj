(ns skein.spools.reed-test
  "Tests for the reed workflow-gate to shell-command executor."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [skein.spools.reed :as reed]
            [skein.spools.workflow :as workflow]
            [skein.spools.test-support :as test-support :refer [with-runtime]]
            [skein.api.weaver.alpha :as api]
            [skein.api.events.alpha :as events])
  (:import [java.io File]))

(defn- with-reed [f]
  (with-runtime
    (fn [rt _]
      (reed/install!)
      (f rt))))

(defn- await-eventually
  "Poll for a real `:shell` subprocess outcome (RFC-Dtt-001.REC7): callers
  settle dispatch with `events/await-quiescent!` first, then use this only for
  the off-lane process-completion signal that quiescence cannot observe."
  ([pred] (await-eventually pred (test-support/await-budget-ms)))
  ([pred timeout-ms]
   (test-support/poll-until pred
                            {:timeout-ms timeout-ms
                             :on-timeout #(throw (ex-info "Timed out" {}))})))

(defn- attr [strand k]
  (get-in strand [:attributes k]))

(defn- single-gate
  "A run whose first ready step is a `:shell` gate, followed by a dependent step."
  [run-id gate-attrs]
  (workflow/workflow
   "Reed single"
   (workflow/gate :check "Run shell check" :shell :attributes (assoc gate-attrs "test/run-id" run-id))
   (workflow/step :after "After" :self :depends-on [:check])))

(defn- gated-gate
  "A `:self` step feeding a dependent `:shell` gate, then a trailing step."
  [run-id gate-attrs]
  (workflow/workflow
   "Reed gated"
   (workflow/step :first "First" :self)
   (workflow/gate :check "Run shell check" :shell :depends-on [:first] :attributes (assoc gate-attrs "test/run-id" run-id))
   (workflow/step :after "After" :self :depends-on [:check])))

(defn- ready-shell-gate [run-id]
  (first (filter #(= "shell" (:gate %)) (workflow/next-steps run-id))))

(defn- shell-gate-strand [rt run-id]
  (first (api/list rt [:and [:= [:attr "workflow/gate"] "shell"]
                       [:= [:attr "test/run-id"] run-id]]
                   {})))

(defn- temp-file [suffix]
  (doto (File/createTempFile "reed-test" suffix)
    (.deleteOnExit)))

(deftest pass-closes-gate-records-outcome-and-unblocks-next-step
  (with-reed
    (fn [rt]
      (workflow/start! "pass" (single-gate "pass" {"shell/argv" ["true"]}) {})
      (events/await-quiescent! rt)
      (let [gate-id (:id (shell-gate-strand rt "pass"))
            closed (await-eventually #(let [g (api/show rt gate-id)]
                                        (when (= "closed" (:state g)) g)))]
        (is (= "shell" (attr closed :workflow/outcome-by)))
        (is (zero? (attr closed :shell/exit-code)))
        (is (string? (attr closed :shell/output)))
        (is (nil? (attr closed :shell/error)))
        (is (= "After" (:title (first (workflow/next-steps "pass")))))))))

(deftest non-zero-exit-stamps-error-stays-ready-and-is-discoverable
  (with-reed
    (fn [rt]
      (workflow/start! "fail" (single-gate "fail" {"shell/argv" ["false"]}) {})
      (events/await-quiescent! rt)
      (let [gate-id (:id (ready-shell-gate "fail"))
            errored (await-eventually #(let [g (api/show rt gate-id)]
                                         (when (attr g :shell/error) g)))]
        (is (= "active" (:state errored)))
        (is (= 1 (attr errored :shell/exit-code)))
        (is (string? (attr errored :shell/output)))
        (is (str/includes? (attr errored :shell/error) "exited 1"))
        ;; the gate stays ready and stamped, not masquerading as a closed step
        (is (= [gate-id] (mapv :id (filter #(= "shell" (:gate %)) (workflow/next-steps "fail")))))
        (is (nil? (attr (api/show rt gate-id) :workflow/outcome-by)))
        ;; discoverable through both the stall predicate and the coordinator query
        (is (= gate-id (:gate (reed/gate-stalled? (ready-shell-gate "fail")))))
        (is (some #(= gate-id (:id %)) (api/list-query rt 'stalled-shell-gates {})))))))

(deftest errored-gate-is-not-rerun-until-error-cleared
  (with-reed
    (fn [rt]
      (let [counter (temp-file ".count")
            run-count (fn [] (count (remove str/blank? (str/split-lines (slurp counter)))))
            argv (fn [exit] ["sh" "-c" (str "echo run >> '" (.getPath counter) "'; exit " exit)])]
        (workflow/start! "rec" (single-gate "rec" {"shell/argv" (argv 3)}) {})
        (events/await-quiescent! rt)
        (let [gate-id (:id (ready-shell-gate "rec"))
              errored (await-eventually #(let [g (api/show rt gate-id)]
                                           (when (attr g :shell/error) g)))]
          (is (= 3 (attr errored :shell/exit-code)))
          (is (= 1 (run-count)))
          ;; unrelated graph mutations fire scans, but the errored gate is skipped:
          ;; the expensive check runs once, not per mutation. This is deterministic
          ;; without waiting: claim-and-dispatch! stamps shell/running on the scan
          ;; thread strictly before the only worker-pool submission path, so a
          ;; re-dispatch regression is visible as a claim marker the moment scan!
          ;; returns — no marker means nothing was submitted.
          (api/add rt {:title "noise-1"})
          (api/add rt {:title "noise-2"})
          (reed/scan!)
          (is (nil? (attr (api/show rt gate-id) :shell/running)))
          (is (some? (attr (api/show rt gate-id) :shell/error)))
          (is (= 1 (run-count)))
          ;; clearing shell/error (and fixing the command) re-runs the check once
          ;; and closes the gate on the next scan.
          (api/update rt gate-id {:attributes {"shell/error" nil
                                               "shell/running" nil
                                               "shell/argv" (argv 0)}})
          (events/await-quiescent! rt)
          (let [closed (await-eventually #(let [g (api/show rt gate-id)]
                                            (when (= "closed" (:state g)) g)))]
            (is (zero? (attr closed :shell/exit-code)))
            (is (nil? (attr closed :shell/error)))
            (is (= 2 (run-count)))))))))

(deftest invalid-input-fails-loudly-and-spawns-no-process
  (with-reed
    (fn [rt]
      (doseq [[i [bad expected]] (map-indexed vector [[{} "shell/argv"]
                                                      [{"shell/argv" ""} "shell/argv"]
                                                      [{"shell/argv" []} "shell/argv"]
                                                      [{"shell/argv" ["echo" 5]} "shell/argv"]
                                                      [{"shell/argv" ["true"] "shell/cwd" 7} "shell/cwd"]
                                                      [{"shell/argv" ["true"] "shell/cwd" ""} "shell/cwd"]])]
        (let [run-id (str "invalid-" i)]
          (workflow/start! run-id (single-gate run-id bad) {})
          (events/await-quiescent! rt)
          (let [gate-id (:id (ready-shell-gate run-id))
                errored (await-eventually #(let [g (api/show rt gate-id)]
                                             (when (attr g :shell/error) g)))]
            (is (= "active" (:state errored)) (str "case " i))
            (is (str/includes? (attr errored :shell/error) expected) (str "case " i))
            ;; no process ran: no exit code and no captured output
            (is (nil? (attr errored :shell/exit-code)) (str "case " i))
            (is (nil? (attr errored :shell/output)) (str "case " i))))))))

(deftest timeout-kills-process-and-bad-timeout-fails-loudly
  (with-reed
    (fn [rt]
      ;; a command exceeding the wall-clock bound is force-killed and stamped
      (workflow/start! "timeout" (single-gate "timeout" {"shell/argv" ["sh" "-c" "sleep 30"]
                                                         "shell/timeout-secs" 1}) {})
      (events/await-quiescent! rt)
      (let [gate-id (:id (ready-shell-gate "timeout"))
            errored (await-eventually #(let [g (api/show rt gate-id)]
                                         (when (attr g :shell/error) g)))]
        (is (= "active" (:state errored)))
        (is (str/includes? (attr errored :shell/error) "timed out")))
      ;; Time is the behavior under test: a backgrounded descendant inherits the
      ;; output pipe, so the timeout path must still reach a terminal stamp.
      (workflow/start! "timeout-descendant" (single-gate "timeout-descendant" {"shell/argv" ["sh" "-c" "sleep 30 & sleep 30"]
                                                                               "shell/timeout-secs" 1}) {})
      (events/await-quiescent! rt)
      (let [gate-id (:id (ready-shell-gate "timeout-descendant"))
            errored (await-eventually #(let [g (api/show rt gate-id)]
                                         (when (attr g :shell/error) g)))]
        (is (= "active" (:state errored)))
        (is (str/includes? (attr errored :shell/error) "timed out")))
      ;; a non-positive timeout fails loudly with no process
      (workflow/start! "timeout-bad" (single-gate "timeout-bad" {"shell/argv" ["true"]
                                                                 "shell/timeout-secs" 0}) {})
      (events/await-quiescent! rt)
      (let [gate-id (:id (ready-shell-gate "timeout-bad"))
            errored (await-eventually #(let [g (api/show rt gate-id)]
                                         (when (attr g :shell/error) g)))]
        (is (str/includes? (attr errored :shell/error) "shell/timeout-secs"))
        (is (nil? (attr errored :shell/exit-code)))))))

(deftest non-shell-gate-is-ignored-and-output-is-bounded
  (with-reed
    (fn [rt]
      ;; a non-:shell gate is never touched, even carrying shell/* attributes
      (workflow/start! "iso" (workflow/workflow
                              "Iso"
                              (workflow/gate :sub "Delegate" :subagent
                                             :attributes {"shell/argv" ["true"]})) {})
      (let [sub-gate-id (:id (first (workflow/next-steps "iso")))]
        (reed/scan!)
        (is (= "active" (:state (api/show rt sub-gate-id))))
        (is (nil? (attr (api/show rt sub-gate-id) :shell/running)))
        (is (nil? (attr (api/show rt sub-gate-id) :shell/exit-code))))
      ;; large output is retained only as a bounded tail
      (workflow/start! "big" (single-gate "big" {"shell/argv" ["sh" "-c" "yes 0123456789 | head -c 200000"]}) {})
      (events/await-quiescent! rt)
      (let [gate-id (:id (shell-gate-strand rt "big"))
            closed (await-eventually #(let [g (api/show rt gate-id)]
                                        (when (= "closed" (:state g)) g)))
            output (attr closed :shell/output)]
        (is (zero? (attr closed :shell/exit-code)))
        (is (pos? (count output)))
        (is (<= (count output) @#'reed/output-tail-bytes))
        (is (< (count output) 200000))))))

(deftest dependent-shell-gate-runs-only-after-its-dependency-closes
  (with-reed
    (fn [rt]
      (workflow/start! "comp" (gated-gate "comp" {"shell/argv" ["true"]}) {})
      (let [first-step (first (workflow/next-steps "comp"))]
        (is (= "First" (:title first-step)))
        ;; the :shell gate is not ready yet, so reed must not touch it
        (reed/scan!)
        (let [gate (shell-gate-strand rt "comp")]
          (is (= "active" (:state gate)))
          (is (nil? (attr gate :shell/running)))
          (is (nil? (attr gate :shell/exit-code))))
        ;; close the dependency; the gate becomes ready and reed runs the check
        (workflow/complete! "comp" {:step (:id first-step)})
        (events/await-quiescent! rt))
      (let [gate-id (:id (shell-gate-strand rt "comp"))]
        (await-eventually #(= "closed" (:state (api/show rt gate-id))))
        (is (zero? (attr (api/show rt gate-id) :shell/exit-code)))
        (is (= "After" (:title (first (workflow/next-steps "comp")))))))))

(deftest state-shape-matches-declared-version
  ;; Drift alarm for reed's versioned spool-state: a key added to new-state
  ;; without a state-version bump would survive reload! as a stale map.
  (test-support/assert-state-shape
   #'reed/new-state
   #{:scan-monitor :worker-executor :close-fn}))
