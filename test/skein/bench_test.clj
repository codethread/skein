(ns skein.bench-test
  "Tests for the bench spool against real weaver runtimes.

  The whole container lifecycle is exercised with no docker/podman present by
  injecting a fake engine (a shell script written to a temp dir) via
  `set-engine!`: it records its argv/stdin and fabricates harness-shaped
  artifacts. Repo/sha pinning uses a throwaway local git repo the test builds,
  so clone/overlay/remove/resolved-rev all run for real. Async work is observed
  by polling bounded deadlines, never fixed sleeps."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.api.graph.alpha :as graph]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.bench :as bench]
            [skein.spools.bench.exec :as exec]
            [skein.spools.agent-run :as shuttle]
            [skein.spools.test-support :as test-support]
            [skein.test.alpha :as t])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent CyclicBarrier Semaphore]))

;; ---------------------------------------------------------------------------
;; Fixtures

(defn- temp-dir [prefix]
  (.toFile (Files/createTempDirectory (.toPath (io/file "/tmp")) prefix
                                      (make-array FileAttribute 0))))

(def ^:private fake-engine-template
  "Fake docker/podman: records calls, answers image/kill, emulates the three
  run invocations by marker token. AGENT touches the workspace (a diff) and
  fabricates a home session artifact; the *-FAIL markers exit non-zero; SLEEP
  hangs so the wall-clock timeout fires."
  "#!/bin/sh
sub=\"$1\"
{ echo \"=== $sub ===\"; echo \"args=$*\"; } >> \"@@REC@@/calls.log\"
case \"$sub\" in
  image) echo \"sha256:fakedigest\"; exit 0 ;;
  kill) echo \"$2\" >> \"@@REC@@/kills.log\"; exit 0 ;;
  run)
    ws=\"\"; home=\"\"; marker=\"\"
    for a in \"$@\"; do
      case \"$a\" in
        *:/bench/workspace) ws=\"${a%:/bench/workspace}\" ;;
        *:/bench/home) home=\"${a%:/bench/home}\" ;;
        AGENT) marker=AGENT ;;
        AGENT-BOTH-STREAMS) marker=AGENT-BOTH-STREAMS ;;
        AGENT-NOZERO-DIFF) marker=AGENT-NOZERO-DIFF ;;
        AGENT-NOZERO-EMPTY) marker=AGENT-NOZERO-EMPTY ;;
        BLOCK) marker=BLOCK ;;
        SETUP-OK) marker=SETUP-OK ;;
        SETUP-FAIL) marker=SETUP-FAIL ;;
        SETUP-BOTH-STREAMS) marker=SETUP-BOTH-STREAMS ;;
        VALIDATE-OK) marker=VALIDATE-OK ;;
        VALIDATE-FAIL) marker=VALIDATE-FAIL ;;
        SLEEP) marker=SLEEP ;;
      esac
    done
    case \"$marker\" in
      AGENT)
        cat > \"@@REC@@/agent-stdin\"
        echo changed > \"$ws/AGENT_TOUCHED.txt\"
        mkdir -p \"$home/.claude/projects\"
        echo '{\"session\":\"x\"}' > \"$home/.claude/projects/s.jsonl\"
        echo '{\"result\":\"done\"}'
        exit 0 ;;
      AGENT-BOTH-STREAMS)
        cat > /dev/null
        echo changed > \"$ws/AGENT_TOUCHED.txt\"
        echo OUT_LINE
        echo ERR_LINE >&2
        exit 0 ;;
      AGENT-NOZERO-DIFF)
        cat > /dev/null
        echo changed > \"$ws/AGENT_TOUCHED.txt\"
        exit 3 ;;
      AGENT-NOZERO-EMPTY)
        cat > /dev/null
        exit 4 ;;
      BLOCK)
        cat > /dev/null
        echo changed > \"$ws/AGENT_TOUCHED.txt\"
        while [ ! -f \"@@REC@@/release\" ]; do sleep 0.1; done
        echo '{\"result\":\"done\"}'
        exit 0 ;;
      SETUP-BOTH-STREAMS) echo SETUP_OUT; echo SETUP_ERR >&2; exit 0 ;;
      SETUP-FAIL) echo boom >&2; exit 7 ;;
      VALIDATE-FAIL) exit 5 ;;
      SLEEP) sleep 30; exit 0 ;;
      *) exit 0 ;;
    esac ;;
  *) exit 0 ;;
esac
")

(defn- fake-engine!
  "Write the fake engine script into a fresh record dir and return
  `{:engine [script] :record record-dir}`."
  []
  (let [rec (temp-dir "skein-bench-rec")
        script (io/file rec "fake-engine")]
    (spit script (str/replace fake-engine-template "@@REC@@" (.getCanonicalPath rec)))
    (.setExecutable script true)
    {:engine [(.getCanonicalPath script)] :record rec}))

(defn- git! [dir & args]
  (let [res (apply sh (concat (cons "git" args) [:dir (str dir)]))]
    (when-not (zero? (:exit res))
      (throw (ex-info "test git command failed" (assoc res :args (vec args)))))
    res))

(defn- fixture-repo!
  "Create a throwaway git repo with README.md and app.txt; return
  `{:path repo-path :sha head-sha}`."
  []
  (let [repo (temp-dir "skein-bench-repo")]
    (git! repo "init" "-q" "-b" "main")
    (spit (io/file repo "README.md") "readme\n")
    (spit (io/file repo "app.txt") "hello\n")
    (git! repo "add" "-A")
    (git! repo "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-q" "-m" "init")
    {:path (.getCanonicalPath repo)
     :sha (str/trim (:out (git! repo "rev-parse" "HEAD")))}))

(defn- with-bench
  ([f] (with-bench {} f))
  ([opts f]
   (test-support/with-runtime
     (merge {:prefix "skein-bench"} opts)
     (fn [rt config-dir] (bench/install!) (f rt config-dir)))))

(defn- with-bench-shuttle [f]
  (test-support/with-runtime
    {:publish? true :prefix "skein-bench-judge"}
    (fn [rt config-dir]
      (shuttle/install!)
      (bench/install!)
      (f rt config-dir))))

(defn- fake-agent! [rt]
  (bench/defagent! rt :fake
    {:image "fake-image:1"
     :argv ["AGENT"]
     :prompt-via :stdin
     :model-flag "--model"
     :metrics :generic}))

(defn- await-phase [rt id phases]
  (test-support/poll-until
   #(let [s (weaver/show rt id)] (when (contains? phases (get-in s [:attributes :bench/phase])) s))
   {:on-timeout #(throw (ex-info "timed out waiting for bench phase"
                                 {:id id :want phases :strand (weaver/show rt id)}))}))

(defn- entry-dir [rt run-id slug]
  (io/file (get-in (weaver/show rt run-id) [:attributes :bench/data-dir]) slug))

(defn- wire-value [value]
  (json/read-str (json/write-str value) :key-fn keyword))

(deftest production-return-coverage-is-derived-from-bench-provenance
  (with-bench
    (fn [rt _]
      (let [entries (filterv #(= 'skein.spools.bench (:provenance %)) (weaver/ops rt))
            missing (mapv :name (filter #(not (contains? % :returns)) entries))
            required (into #{} (mapcat (fn [{:keys [name returns]}]
                                         (for [subcommand (keys (:subcommands returns))]
                                           [name {:subcommand subcommand}]))) entries)
            representatives
            {"run" {:operation "bench run" :run "bench-1" :entries {"cell" "entry-1"} :judge nil}
             "runs" [{:run "bench-1" :suite "demo" :sha "abc" :state "active"
                      :entries 1 :phases {"done" 1}}]
             "status" {:operation "bench status" :run "bench-1" :suite "demo" :repo "repo"
                       :sha "abc" :entries [] :judge nil :blocking-failures []}
             "report" {:operation "bench report" :run "bench-1" :suite "demo" :repo "repo"
                       :sha "abc" :data-dir "/tmp/bench" :entries [] :judge nil}
             "retry" {:operation "bench retry" :retried "entry-1" :attempt 2}
             "abort" {:operation "bench abort" :aborted "bench-1"
                      :failed ["cell"] :judge nil}
             "suites" [{:name "demo" :repo "repo"}]
             "agents" [{:name "fake" :image "image"}]
             "gc" {:operation "bench gc" :removed ["bench-1"]}
             "about" (assoc (bench/about) :operation "bench about")}
            checked (into #{}
                          (map (fn [[subcommand value]]
                                 (t/check-op-return! rt 'bench {:subcommand subcommand}
                                                     (wire-value value))
                                 ["bench" {:subcommand subcommand}]))
                          representatives)]
        (is (= [] missing))
        (is (= #{} (set/difference required checked)))))))

;; ---------------------------------------------------------------------------
;; Registry validation

(deftest defagent-validates
  (with-bench
    (fn [rt _]
      (testing "required keys and closed key set"
        (is (thrown-with-msg? Exception #"invalid" (bench/defagent! rt :x {:image "i"})))
        (is (thrown-with-msg? Exception #"unknown keys" (bench/defagent! rt :x {:image "i" :argv ["a"] :nope 1})))
        (is (thrown-with-msg? Exception #"invalid" (bench/defagent! rt :x {:image "i" :argv ["a"] :prompt-via :pipe}))))
      (testing "a valid agent is stored"
        (bench/defagent! rt :ok {:image "i" :argv ["run"]})
        (is (= [:ok] (mapv :name (bench/agents rt))))))))

(deftest defsuite-validates
  (with-bench
    (fn [rt _]
      (let [base {:repo "r" :sha (str/join (repeat 40 "a"))
                  :prompt "p" :entries [{:agent :fake}] :judge :none}]
        (testing "unknown keys and bad shapes fail"
          (is (thrown-with-msg? Exception #"unknown keys" (bench/defsuite! rt :s (assoc base :bogus 1))))
          (is (thrown-with-msg? Exception #"invalid" (bench/defsuite! rt :s (assoc base :sha "short")))))
        (testing "exactly one of :sha/:rev"
          (is (thrown-with-msg? Exception #"one of :sha" (bench/defsuite! rt :s (assoc base :rev "main"))))
          (is (thrown-with-msg? Exception #"one of :sha" (bench/defsuite! rt :s (dissoc base :sha)))))
        (testing "exactly one of :prompts/:prompt"
          (is (thrown-with-msg? Exception #"one of :prompts"
                                (bench/defsuite! rt :s (assoc base :prompts {:a "x"})))))
        (testing "multi-prompt entries must name a known prompt"
          (is (thrown-with-msg? Exception #"unknown prompt"
                                (bench/defsuite! rt :s (-> base (dissoc :prompt)
                                                           (assoc :prompts {:a "x"})
                                                           (assoc :entries [{:agent :fake :prompt :missing}])))))
          (is (thrown-with-msg? Exception #"must name a :prompt"
                                (bench/defsuite! rt :s (-> base (dissoc :prompt)
                                                           (assoc :prompts {:a "x"})
                                                           (assoc :entries [{:agent :fake}]))))))
        (testing "slug collisions fail loudly"
          (is (thrown-with-msg? Exception #"slugs collide"
                                (bench/defsuite! rt :s (assoc base :entries [{:agent :fake} {:agent :fake}])))))
        (testing "a valid suite is stored"
          (bench/defsuite! rt :s base)
          (is (= [:s] (mapv :name (bench/suites rt)))))))))

(deftest cross-expands
  (is (= [{:agent :claude :prompt :baseline}
          {:agent :claude :prompt :strict}
          {:agent :codex :prompt :baseline}
          {:agent :codex :prompt :strict}]
         (bench/cross {:agent [:claude :codex]} {:prompt [:baseline :strict]}))))

(deftest set-engine-validates
  (with-bench
    (fn [rt _]
      (is (thrown? Exception (bench/set-engine! rt "docker")))
      (is (thrown? Exception (bench/set-engine! rt [])))
      (is (= ["podman"] (bench/set-engine! rt ["podman"])))
      (is (= ["podman"] (bench/engine rt))))))

(deftest state-shape-matches-declared-version
  (test-support/assert-state-shape
   #_{:clj-kondo/ignore [:unresolved-var]}
   #'bench/new-state
   #{:agents :suites :extractors :engine :executor :semaphores :in-flight :close-fn}))

;; ---------------------------------------------------------------------------
;; Argv compilation / redaction (unit)

(deftest compile-argv-redacts-auth-env
  ;; HOME is reliably present in the host env, so it exercises the auth-env
  ;; passthrough and its manifest redaction deterministically.
  (let [{:keys [argv redacted]}
        (exec/compile-argv {:engine ["docker"] :run-id "r1" :slug "cell"
                            :image "img" :cmd ["agent"] :env {"FOO" "bar"}
                            :auth {:env ["HOME"]
                                   :mounts [{:host "~/creds" :container "/bench/home/creds"}]}
                            :entry-dir "/tmp/bench/entry" :prompt-arg "P"})]
    (is (= "docker" (first argv)))
    (is (some #{"skein-bench-r1-cell"} argv))
    (is (some #{"FOO=bar"} argv))
    (is (some #{(str "HOME=" (System/getenv "HOME"))} argv) "real auth value in launch argv")
    (is (some #{"HOME=<redacted>"} redacted) "auth value masked in manifest argv")
    (is (not-any? #(= % (str "HOME=" (System/getenv "HOME"))) redacted))
    (is (some #{"/bench/workspace"} (map #(second (str/split % #":")) (filter #(str/includes? % "/bench/workspace") argv))))
    (is (= "P" (last argv)) "prompt-arg appended last")))

;; ---------------------------------------------------------------------------
;; Full lifecycle + judge

(deftest full-lifecycle-runs-entry-metrics-and-judge
  (with-bench-shuttle
    (fn [rt _]
      (let [{:keys [engine record]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (fake-agent! rt)
       ;; a judge harness that ignores its prompt and yields a fixed verdict
        (shuttle/register-harness! :judge-echo {:argv ["sh" "-c" "echo verdict"]
                                          :parse :raw :prompt-via :arg :preamble? false})
        (bench/defsuite! rt :demo
          {:repo path :sha sha
           :prompt "do the thing"
           :setup ["SETUP-OK"]
           :validation ["VALIDATE-OK"]
           :files {"CLAUDE.md" {:content "house rules"}}
           :remove ["README.md"]
           :entries [{:agent :fake :model "opus"}]
           :parallel 1
           :timeout-secs 60
           :judge {:harness :judge-echo :contract "pick a winner"}})
        (let [{:keys [run entries judge]} (bench/run! rt :demo {})
              slug "fake-opus"
              entry-id (get entries slug)
              done (await-phase rt entry-id #{"done"})
              dir (entry-dir rt run slug)]
          (testing "entry closed with generic metrics stamped"
            (is (= "closed" (:state done)))
            (is (zero? (get-in done [:attributes :bench/exit-code])))
            (is (= 1 (get-in done [:attributes :bench/diff-files])))
            (is (number? (get-in done [:attributes :bench/duration-ms])))
            (is (zero? (get-in done [:attributes :bench/validation-exit])))
            (is (str/starts-with? (get-in done [:attributes :bench/image-digest]) "sha256:")))
          (testing "artifacts and manifest written"
            (is (.exists (io/file dir "metrics.json")))
            (let [manifest (json/read-str (slurp (io/file dir "manifest.json")) :key-fn keyword)]
              (is (= sha (:resolved-sha manifest)))
              (is (= "do the thing" (:prompt manifest)))
              (is (str/starts-with? (:image-digest manifest) "sha256:"))
              (is (= ["CLAUDE.md"] (get-in manifest [:overlay :files])))
              (is (= ["README.md"] (get-in manifest [:overlay :removed])))))
          (testing "overlay applied and removal honored on disk"
            (is (= "house rules" (slurp (io/file dir "workspace" "CLAUDE.md"))))
            (is (not (.exists (io/file dir "workspace" "README.md")))))
          (testing "prompt delivered on stdin and three invocations ran"
            (is (= "do the thing" (str/trim (slurp (io/file record "agent-stdin")))))
            (let [calls (slurp (io/file record "calls.log"))]
              (is (str/includes? calls "SETUP-OK"))
              (is (str/includes? calls "AGENT"))
              (is (str/includes? calls "VALIDATE-OK"))))
          (testing "judge depends on every entry and runs once they close"
            (is (= #{entry-id} (set (map :to_strand_id (graph/outgoing-edges rt [judge] "depends-on")))))
            (is (= "true" (get-in (weaver/show rt judge) [:attributes :bench/judge])))
            (let [judged (test-support/await-phase rt judge #{"done"})]
              (is (= "verdict" (get-in judged [:attributes :agent-run/result]))))))))))

;; ---------------------------------------------------------------------------
;; :rev resolution

(deftest resolves-rev-to-sha
  (with-bench
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (fake-agent! rt)
        (bench/defsuite! rt :byrev
          {:repo path :rev "main"
           :prompt "p"
           :entries [{:agent :fake}]
           :judge :none})
        (let [{:keys [run]} (bench/run! rt :byrev {})]
          (is (= sha (get-in (weaver/show rt run) [:attributes :bench/sha]))
              "the resolved sha is stamped so the run reproduces"))))))

;; ---------------------------------------------------------------------------
;; Mirror concurrency / git failure detail

(deftest concurrent-mirror-prep-serializes-clone
  (let [{:keys [path]} (fixture-repo!)
        data-dir (temp-dir "skein-bench-data")
        barrier (CyclicBarrier. 2)
        prep! (fn [] (.await barrier) (exec/ensure-mirror! data-dir path))
        f1 (future (prep!))
        f2 (future (prep!))
        m1 @f1
        m2 @f2]
    (testing "two entries prepping a fresh repo URL concurrently both succeed"
      (is (= (.getCanonicalPath ^java.io.File m1) (.getCanonicalPath ^java.io.File m2)))
      (is (.exists (io/file m1 "HEAD"))))))

(deftest git-failure-carries-command-and-stderr-detail
  (with-bench
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path]} (fixture-repo!)
            bogus-sha (str/join (repeat 40 "f"))]
        (bench/set-engine! rt engine)
        (fake-agent! rt)
        (bench/defsuite! rt :badsha
          {:repo path :sha bogus-sha :prompt "p"
           :entries [{:agent :fake}] :judge :none})
        (let [{:keys [entries]} (bench/run! rt :badsha {})
              failed (await-phase rt (get entries "fake") #{"failed"})
              err (get-in failed [:attributes :bench/error])
              detail (get-in failed [:attributes :bench/error-detail])]
          (testing "bench/error names the git command and first stderr line"
            (is (str/includes? err "checkout"))
            (is (str/includes? err bogus-sha)))
          (testing "bench/error-detail carries the fuller stderr"
            (is (some? detail))
            (is (str/includes? detail "fatal"))
            (is (str/includes? detail bogus-sha))))))))

;; ---------------------------------------------------------------------------
;; Failure / retry / timeout / abort / reconcile

(deftest retry-reruns-failed-entry
  (with-bench
    (fn [rt _]
      (let [{:keys [engine record]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (fake-agent! rt)
        (bench/defsuite! rt :flaky
          {:repo path :sha sha :prompt "p"
           :setup ["SETUP-FAIL"]
           :entries [{:agent :fake}]
           :judge :none})
        (let [{:keys [entries]} (bench/run! rt :flaky {})
              entry-id (get entries "fake")
              failed (await-phase rt entry-id #{"failed"})]
          (is (= "active" (:state failed)) "failed entries stay active and loud")
          (is (str/includes? (get-in failed [:attributes :bench/error]) "setup failed"))
          (testing "retry only applies to failed entries after a phase change"
            (bench/retry! rt entry-id)
            (let [again (await-phase rt entry-id #{"failed"})]
              (is (= 2 (get-in again [:attributes :bench/attempt])) "attempt incremented"))
           ;; two SETUP-FAIL invocations recorded proves the cell re-executed
            (is (<= 2 (count (re-seq #"SETUP-FAIL" (slurp (io/file record "calls.log")))))))
          (testing "retrying a non-failed entry fails loudly"
            (weaver/update rt entry-id {:attributes {"bench/phase" "done"}})
            (is (thrown-with-msg? Exception #"only applies to failed" (bench/retry! rt entry-id)))))))))

(deftest timeout-fails-entry-loudly
  (with-bench
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (bench/defagent! rt :slow {:image "i" :argv ["SLEEP"] :prompt-via :stdin})
        (bench/defsuite! rt :slowsuite
          {:repo path :sha sha :prompt "p"
           :entries [{:agent :slow}]
           :timeout-secs 1
           :judge :none})
        (let [{:keys [entries]} (bench/run! rt :slowsuite {})
              failed (await-phase rt (get entries "slow") #{"failed"})]
          (is (= "active" (:state failed)))
          (is (str/includes? (get-in failed [:attributes :bench/error]) "timed out")))))))

(deftest abort-fails-outstanding-and-supersedes-judge
  (with-bench-shuttle
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (bench/defagent! rt :slow {:image "i" :argv ["SLEEP"] :prompt-via :stdin})
        (bench/defsuite! rt :longsuite
          {:repo path :sha sha :prompt "p"
           :entries [{:agent :slow}]
           :parallel 1 :timeout-secs 120
           :judge {:harness :sh}})
        (let [{:keys [run entries judge]} (bench/run! rt :longsuite {})
              entry-id (get entries "slow")]
          (await-phase rt entry-id #{"running"})
          (bench/abort! rt run)
          (let [aborted (test-support/poll-until
                         #(let [s (weaver/show rt entry-id)]
                            (when (= "failed" (get-in s [:attributes :bench/phase])) s)))]
            (is (= "aborted" (get-in aborted [:attributes :bench/error]))))
          (let [j (weaver/show rt judge)]
            (is (= "closed" (:state j)))
            (is (= "superseded" (get-in j [:attributes :agent-run/phase])))))))))

(deftest reconcile-fails-orphaned-entries
  (with-bench
    (fn [rt _]
      (bench/set-engine! rt (:engine (fake-engine!)))
      (let [root (weaver/add rt {:title "run" :attributes {"bench/run" "true"}})
            entry (weaver/add rt {:title "entry"
                                  :attributes {"bench/entry" "true" "bench/slug" "s"
                                               "bench/phase" "running"}})]
        (weaver/update rt (:id root) {:edges [{:type "parent-of" :to (:id entry)}]})
        (is (= [(:id entry)] (bench/reconcile! rt)))
        (let [reconciled (weaver/show rt (:id entry))]
          (is (= "failed" (get-in reconciled [:attributes :bench/phase])))
          (is (str/includes? (get-in reconciled [:attributes :bench/error]) "orphaned")))))))

(deftest setup-log-merges-both-streams
  (with-bench
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (bench/defagent! rt :streams
          {:image "i" :argv ["AGENT-BOTH-STREAMS"] :prompt-via :stdin :metrics :generic})
        (bench/defsuite! rt :demo
          {:repo path :sha sha :prompt "p"
           :setup ["SETUP-BOTH-STREAMS"]
           :entries [{:agent :streams}] :parallel 1 :timeout-secs 60 :judge :none})
        (let [{:keys [run entries]} (bench/run! rt :demo {})
              done (await-phase rt (get entries "streams") #{"done"})
              dir (entry-dir rt run "streams")]
          (is (= "done" (get-in done [:attributes :bench/phase])))
          (testing "setup.log merges stdout and stderr — neither clobbers the other"
            (let [setup-log (slurp (io/file dir "setup.log"))]
              (is (str/includes? setup-log "SETUP_OUT"))
              (is (str/includes? setup-log "SETUP_ERR"))))
          (testing "the agent's distinct stdout/stderr files stay separate"
            (is (str/includes? (slurp (io/file dir "stdout")) "OUT_LINE"))
            (is (str/includes? (slurp (io/file dir "stderr")) "ERR_LINE"))))))))

(deftest run-rejects-axis-without-matching-flag
  (with-bench
    (fn [rt _]
      (bench/set-engine! rt ["docker"])
      (bench/defagent! rt :noflags {:image "i" :argv ["AGENT"] :prompt-via :stdin})
      (let [base {:repo "r" :sha (str/join (repeat 40 "a")) :prompt "p" :judge :none}]
        (testing ":model without :model-flag fails loudly before any strand"
          (bench/defsuite! rt :m (assoc base :entries [{:agent :noflags :model "opus"}]))
          (is (thrown-with-msg? Exception #":model-flag" (bench/run! rt :m {}))))
        (testing ":thinking without :thinking-flag fails loudly"
          (bench/defsuite! rt :t (assoc base :entries [{:agent :noflags :thinking "high"}]))
          (is (thrown-with-msg? Exception #":thinking-flag" (bench/run! rt :t {}))))))))

(deftest nonzero-agent-exit-with-artifacts-finalizes-done
  (with-bench
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (bench/defagent! rt :diffly
          {:image "i" :argv ["AGENT-NOZERO-DIFF"] :prompt-via :stdin :metrics :generic})
        (bench/defsuite! rt :s
          {:repo path :sha sha :prompt "p"
           :entries [{:agent :diffly}] :parallel 1 :timeout-secs 60 :judge :none})
        (let [{:keys [entries]} (bench/run! rt :s {})
              done (await-phase rt (get entries "diffly") #{"done"})]
          (is (= "closed" (:state done)) "a non-zero exit with a diff still finalizes done")
          (is (= 3 (get-in done [:attributes :bench/exit-code])))
          (is (= 1 (get-in done [:attributes :bench/diff-files]))))))))

(deftest nonzero-agent-exit-with-no-artifacts-fails
  (with-bench
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (bench/defagent! rt :empty
          {:image "i" :argv ["AGENT-NOZERO-EMPTY"] :prompt-via :stdin :metrics :generic})
        (bench/defsuite! rt :s
          {:repo path :sha sha :prompt "p"
           :entries [{:agent :empty}] :parallel 1 :timeout-secs 60 :judge :none})
        (let [{:keys [entries]} (bench/run! rt :s {})
              failed (await-phase rt (get entries "empty") #{"failed"})]
          (is (= "active" (:state failed)))
          (is (str/includes? (get-in failed [:attributes :bench/error]) "with no artifacts")))))))

(deftest aborted-entry-not-resurrected-by-worker
  (with-bench
    (fn [rt _]
      (let [{:keys [engine record]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (bench/defagent! rt :block {:image "i" :argv ["BLOCK"] :prompt-via :stdin :metrics :generic})
        (bench/defsuite! rt :blocksuite
          {:repo path :sha sha :prompt "p"
           :entries [{:agent :block}] :parallel 1 :timeout-secs 120 :judge :none})
        (let [{:keys [run entries]} (bench/run! rt :blocksuite {})
              entry-id (get entries "block")]
          (await-phase rt entry-id #{"running"})
          ;; mark aborted mid-run, exactly as abort! would, before the worker finalizes
          (weaver/update rt entry-id {:attributes {"bench/phase" "failed" "bench/error" "aborted"}})
          ;; release the blocked agent so its worker proceeds to finalize
          (spit (io/file record "release") "go")
          ;; the worker reaches finalization (writes metrics.json) but must not
          ;; resurrect the aborted entry to done/closed
          (test-support/poll-until
           #(when (.exists (io/file (entry-dir rt run "block") "metrics.json")) true))
          (let [final (weaver/show rt entry-id)]
            (is (= "failed" (get-in final [:attributes :bench/phase])))
            (is (= "aborted" (get-in final [:attributes :bench/error])))
            (is (= "active" (:state final)) "aborted entry stays active, never closed")))))))

(deftest retry-reuses-run-semaphore-and-queues-under-parallel-cap
  (with-bench
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (fake-agent! rt)
        (bench/defsuite! rt :flaky
          {:repo path :sha sha :prompt "p" :setup ["SETUP-FAIL"]
           :entries [{:agent :fake}] :parallel 1 :timeout-secs 60 :judge :none})
        (let [{:keys [run entries]} (bench/run! rt :flaky {})
              entry-id (get entries "fake")
              _ (await-phase rt entry-id #{"failed"})
              ^Semaphore sem (get @(#'bench/semaphores-atom rt) run)]
          (is (some? sem) "run! created a shared per-run semaphore")
          ;; hold the run's only permit, simulating an in-flight sibling entry
          (.acquire sem)
          (bench/retry! rt entry-id)
          (testing "the retry waits on the SHARED semaphore rather than exceeding :parallel"
            (test-support/poll-until #(when (pos? (.getQueueLength sem)) true))
            (is (= "pending" (get-in (weaver/show rt entry-id) [:attributes :bench/phase]))
                "queued behind the held permit, not advanced to preparing"))
          ;; releasing the permit lets the queued retry proceed (and fail again)
          (.release sem)
          (let [again (await-phase rt entry-id #{"failed"})]
            (is (= 2 (get-in again [:attributes :bench/attempt])))))))))

(deftest bad-extractor-output-is-dropped-not-laundered
  (with-bench
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (bench/defextractor! rt :bad
          (fn [_ctx] {:cost-usd "not-a-number" :tokens {:input "x"} :bogus 1 :turns 3}))
        (bench/defagent! rt :badagent
          {:image "i" :argv ["AGENT"] :prompt-via :stdin :metrics :bad})
        (bench/defsuite! rt :bads
          {:repo path :sha sha :prompt "p"
           :entries [{:agent :badagent}] :parallel 1 :timeout-secs 60 :judge :none})
        (let [{:keys [run entries]} (bench/run! rt :bads {})
              entry-id (get entries "badagent")
              done (await-phase rt entry-id #{"done"})]
          (is (= "closed" (:state done)) "a bad extractor never fails the entry")
          (testing "nonconforming keys/values are dropped, not stamped onto bench/*"
            (is (nil? (get-in done [:attributes :bench/cost-usd])))
            (is (nil? (get-in done [:attributes :bench/tokens-in])))
            (is (= 3 (get-in done [:attributes :bench/turns])) "a valid key is kept"))
          (testing "each drop is recorded as an extraction warning in metrics.json"
            (let [warnings (:extraction-warnings
                            (json/read-str (slurp (io/file (entry-dir rt run "badagent") "metrics.json"))
                                           :key-fn keyword))]
              (is (some #(str/includes? % "cost-usd") warnings))
              (is (some #(str/includes? % "tokens") warnings))
              (is (some #(str/includes? % "bogus") warnings)))))))))

(deftest report-flags-unreadable-metrics-json
  (with-bench
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (fake-agent! rt)
        (bench/defsuite! rt :demo
          {:repo path :sha sha :prompt "p"
           :entries [{:agent :fake}] :parallel 1 :timeout-secs 60 :judge :none})
        (let [{:keys [run entries]} (bench/run! rt :demo {})
              entry-id (get entries "fake")]
          (await-phase rt entry-id #{"done"})
          ;; corrupt the entry's metrics.json to look like a crash mid-spit
          (spit (io/file (entry-dir rt run "fake") "metrics.json") "{ not json")
          (let [entry (first (:entries (bench/report rt run)))]
            (is (some #(str/includes? % "metrics.json unreadable") (:extraction-warnings entry)))
            (is (nil? (:metrics entry)) "an unreadable file surfaces no metrics data")))))))

;; ---------------------------------------------------------------------------
;; CLI op surface (§10)

(defn- bench-op! [rt & argv]
  (weaver/op! rt 'bench (vec argv)))

(deftest bench-op-declares-subcommands-and-routes-loudly
  (with-bench
    (fn [rt _]
      (is (some #(= "bench" (:name %)) (weaver/ops rt)) "install! registered the op")
      (is (contains? (graph/queries rt) "bench-runs") "install! registered the query")
      (testing "the help alias projects the declared verb surface"
        (let [detail (bench-op! rt "help")
              verbs (mapv :name (get-in detail [:arg-spec :subcommands]))]
          (is (= ["abort" "about" "agents" "gc" "report" "retry" "run" "runs" "status" "suites"]
                 verbs))))
      (testing "bare op and unknown verb fail during parser routing"
        (let [missing (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing subcommand"
                                            (bench-op! rt)))
              unknown (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown subcommand"
                                            (bench-op! rt "bogus")))]
          (is (= :missing-subcommand (:reason (ex-data missing))))
          (is (= :unknown-subcommand (:reason (ex-data unknown)))))))))

(deftest bench-op-suites-agents-and-about
  (with-bench
    (fn [rt _]
      (fake-agent! rt)
      (bench/defsuite! rt :demo
        {:repo "r" :sha (str/join (repeat 40 "a")) :prompt "p"
         :entries [{:agent :fake}] :judge :none})
      (testing "agents and suites list registered defs as JSON-safe maps"
        (let [ags (bench-op! rt "agents")]
          (is (= [:fake] (mapv :name ags)))
          (is (= "fake-image:1" (:image (first ags)))))
        (is (= [:demo] (mapv :name (bench-op! rt "suites")))))
      (testing "about is a concept manual carrying no argument shapes"
        (let [manual (bench-op! rt "about")]
          (is (= "bench about" (:operation manual)))
          (is (every? #(contains? manual %)
                      [:determinism-model :run-lifecycle :judge-protocol :artifact-layout :attributes]))
          (is (contains? (:attributes manual) :entry))
          (is (not (contains? manual :subcommands)) "arg shapes are help's job, not about's"))))))

(deftest bench-op-run-status-report-runs-and-query
  (with-bench
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (fake-agent! rt)
        (bench/defsuite! rt :demo
          {:repo path :sha sha :prompt "do the thing"
           :validation ["VALIDATE-OK"]
           :entries [{:agent :fake :model "opus"}]
           :parallel 1 :timeout-secs 60 :judge :none})
        (let [started (bench-op! rt "run" "demo")
              run (:run started)
              slug "fake-opus"
              entry-id (get (:entries started) slug)]
          (is (string? run))
          (is (nil? (:judge started)) "judgeless suite spawns no judge")
          (await-phase rt entry-id #{"done"})
          (testing "status carries headline metrics and no blocking failures"
            (let [st (bench-op! rt "status" run)
                  entry (first (:entries st))]
              (is (= run (:run st)))
              (is (= "demo" (:suite st)))
              (is (nil? (:judge st)))
              (is (= [] (:blocking-failures st)))
              (is (= slug (:slug entry)))
              (is (= "opus" (:model entry)))
              (is (= "done" (:phase entry)))
              (is (zero? (get-in entry [:metrics :exit-code])))
              (is (= 1 (get-in entry [:metrics :diff :files])))))
          (testing "report joins per-entry metrics, artifact paths, and empty judge notes"
            (let [rep (bench-op! rt "report" run)
                  entry (first (:entries rep))]
              (is (nil? (:judge rep)))
              (is (zero? (get-in entry [:metrics :exit])))
              (is (str/ends-with? (get-in entry [:artifacts :metrics]) "metrics.json"))
              (is (.exists (io/file (get-in entry [:artifacts :dir]))))
              (is (= [] (:judge-notes entry)))))
          (testing "runs verb and the bench-runs query both surface the active root"
            (let [listed (bench-op! rt "runs")]
              (is (= [run] (mapv :run listed)))
              (is (= 1 (:entries (first listed))))
              (is (= {"done" 1} (:phases (first listed)))))
            (is (= [run] (mapv :id (weaver/list rt (graph/resolve-query rt 'bench-runs) {})))))
          (testing "gc removes only the artifact dir; the run strand survives"
            (let [dir (entry-dir rt run slug)]
              (is (.exists dir))
              (is (= {:removed [run] :operation "bench gc"} (bench-op! rt "gc" "--run" run)))
              (is (not (.exists dir)))
              (is (= "true" (get-in (weaver/show rt run) [:attributes :bench/run]))))))))))

(deftest bench-op-run-honors-entries-subset
  (with-bench
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (fake-agent! rt)
        (bench/defsuite! rt :multi
          {:repo path :sha sha :prompt "p"
           :entries [{:agent :fake :model "opus"} {:agent :fake :model "sonnet"}]
           :parallel 1 :timeout-secs 60 :judge :none})
        (let [{:keys [entries]} (bench-op! rt "run" "multi" "--entries" "fake-opus")]
          (is (= ["fake-opus"] (keys entries)) "only the named cell is poured"))))))

(deftest bench-op-retry-reruns-failed-entry
  (with-bench
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (fake-agent! rt)
        (bench/defsuite! rt :flaky
          {:repo path :sha sha :prompt "p" :setup ["SETUP-FAIL"]
           :entries [{:agent :fake}] :judge :none})
        (let [{:keys [entries]} (bench-op! rt "run" "flaky")
              entry-id (get entries "fake")]
          (await-phase rt entry-id #{"failed"})
          (let [retried (bench-op! rt "retry" entry-id)]
            (is (= entry-id (:retried retried)))
            (is (= 2 (:attempt retried)))))))))

(deftest bench-op-abort-fails-outstanding
  (with-bench
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (bench/defagent! rt :slow {:image "i" :argv ["SLEEP"] :prompt-via :stdin})
        (bench/defsuite! rt :longsuite
          {:repo path :sha sha :prompt "p" :entries [{:agent :slow}]
           :parallel 1 :timeout-secs 120 :judge :none})
        (let [{:keys [run entries]} (bench-op! rt "run" "longsuite")
              entry-id (get entries "slow")]
          (await-phase rt entry-id #{"running"})
          (let [res (bench-op! rt "abort" run)]
            (is (= run (:aborted res)))
            (is (= ["slow"] (:failed res)))
            (is (nil? (:judge res)))))))))

(deftest bench-op-report-joins-judge-verdict-and-notes
  (with-bench-shuttle
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (fake-agent! rt)
       ;; a judge harness that ignores its prompt and yields a fixed verdict
        (shuttle/register-harness! :judge-echo {:argv ["sh" "-c" "echo winner: fake-opus"]
                                          :parse :raw :prompt-via :arg :preamble? false})
        (bench/defsuite! rt :demo
          {:repo path :sha sha :prompt "do it"
           :entries [{:agent :fake :model "opus"}]
           :parallel 1 :timeout-secs 60
           :judge {:harness :judge-echo :contract "pick a winner"}})
        (let [{:keys [run entries judge]} (bench/run! rt :demo {})
              entry-id (get entries "fake-opus")]
          (await-phase rt entry-id #{"done"})
         ;; stand in for the judge's per-entry note before it finishes
          (shuttle/note! entry-id "score 9/10" {:by judge})
          (test-support/await-phase rt judge #{"done"})
          (let [rep (bench-op! rt "report" run)
                entry (first (:entries rep))]
            (is (= judge (get-in rep [:judge :id])))
            (is (= "winner: fake-opus" (get-in rep [:judge :verdict])))
            (is (= ["score 9/10"] (mapv :note (:judge-notes entry))))))))))

;; ---------------------------------------------------------------------------
;; Judge as a decoupled fulfilment seam

(deftest judge-mode-requires-exactly-one-of-harness-external
  (with-bench
    (fn [rt _]
      (let [base {:repo "r" :sha (str/join (repeat 40 "a")) :prompt "p"
                  :entries [{:agent :fake}]}]
        (is (thrown-with-msg? Exception #"exactly one of :harness"
                              (bench/defsuite! rt :both (assoc base :judge {:harness :x :external true}))))
        (is (thrown-with-msg? Exception #"exactly one of :harness"
                              (bench/defsuite! rt :neither (assoc base :judge {:contract "c"}))))
        (is (thrown-with-msg? Exception #"unknown keys"
                              (bench/defsuite! rt :bogus (assoc base :judge {:external true :nope 1}))))))))

(deftest external-judge-pours-seam-without-spawning-a-run
  (with-bench
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (fake-agent! rt)
        (bench/defsuite! rt :ext
          {:repo path :sha sha :prompt "do the thing"
           :entries [{:agent :fake :model "opus"}]
           :parallel 1 :timeout-secs 60
           :judge {:external true :contract "pick a winner"}})
        (let [{:keys [run entries judge]} (bench/run! rt :ext {})
              slug "fake-opus"
              entry-id (get entries slug)
              js (weaver/show rt judge)]
          (testing "judge strand is a fulfilment seam, not an agent run"
            (is (some? judge))
            (is (= "true" (get-in js [:attributes :bench/judge])))
            (is (= run (get-in js [:attributes :bench/run])))
            (is (nil? (get-in js [:attributes :agent-run/run])) "external mode spawns no agent run")
            (is (str/includes? (get-in js [:attributes :bench/judge-prompt]) entry-id))
            (is (str/includes? (get-in js [:attributes :body]) "bench/verdict"))
            (is (= #{entry-id} (set (map :to_strand_id (graph/outgoing-edges rt [judge] "depends-on"))))))
          (testing "judge-spec is the single pour source (no drift)"
            (let [spec (bench/judge-spec rt :ext
                                         {:run-id run :sha sha
                                          :entries [{:id entry-id :slug slug
                                                     :data-dir (.getCanonicalPath (entry-dir rt run slug))
                                                     :agent :fake :model "opus"}]})]
              (is (= (:prompt spec) (get-in js [:attributes :bench/judge-prompt])))
              (is (= [entry-id] (:entry-ids spec)))
              (is (= (get (:attrs spec) "body") (get-in js [:attributes :body])))
              (is (= (get (:attrs spec) "bench/run") (get-in js [:attributes :bench/run])))))
          (testing "manual fulfilment: stamp bench/verdict + close completes the run"
            (await-phase rt entry-id #{"done"})
            (weaver/update rt judge {:state "closed"
                                     :attributes {"bench/verdict" "winner: fake-opus (manual)"}})
            (let [rep (bench/report rt run)]
              (is (= "winner: fake-opus (manual)" (get-in rep [:judge :verdict])))
              (is (= "attr" (get-in rep [:judge :verdict-source]))))
            (is (= "closed" (:state (weaver/show rt judge))) "closing the judge strand completes the run")))))))

(deftest judge-prompt-resolves-path-backed-prompt-text
  (with-bench
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)
            prompt-file (io/file (temp-dir "skein-bench-prompt") "prompt.txt")]
        (spit prompt-file "do the file-backed thing\nacross multiple lines")
        (bench/set-engine! rt engine)
        (fake-agent! rt)
        (bench/defsuite! rt :pathprompt
          {:repo path :sha sha :prompts {:only {:path (.getCanonicalPath prompt-file)}}
           :entries [{:agent :fake :prompt :only}]
           :parallel 1 :timeout-secs 60
           :judge {:external true}})
        (let [{:keys [judge]} (bench/run! rt :pathprompt {})
              jp (get-in (weaver/show rt judge) [:attributes :bench/judge-prompt])]
          (is (str/includes? jp "do the file-backed thing across multiple lines")
              "the :path prompt is slurped and rendered, not the {:path ...} map"))))))

(deftest judge-prompt-lists-only-prompts-referenced-by-an-entries-subset
  (with-bench
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (fake-agent! rt)
        (bench/defsuite! rt :multiprompt
          {:repo path :sha sha
           :prompts {:alpha "prompt alpha text" :beta "prompt beta text"}
           :entries [{:agent :fake :prompt :alpha} {:agent :fake :prompt :beta}]
           :parallel 1 :timeout-secs 60
           :judge {:external true}})
        (let [{:keys [judge]} (bench/run! rt :multiprompt {:entries ["fake-alpha"]})
              jp (get-in (weaver/show rt judge) [:attributes :bench/judge-prompt])]
          (is (str/includes? jp "alpha: prompt alpha text"))
          (is (not (str/includes? jp "beta"))
              "an unreferenced suite prompt is not listed as under test"))))))

(deftest shuttle-judge-verdict-readable-via-attr-path
  (with-bench-shuttle
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (fake-agent! rt)
        (shuttle/register-harness! :judge-echo {:argv ["sh" "-c" "echo run-result"]
                                          :parse :raw :prompt-via :arg :preamble? false})
        (bench/defsuite! rt :demo
          {:repo path :sha sha :prompt "do it"
           :entries [{:agent :fake :model "opus"}]
           :parallel 1 :timeout-secs 60
           :judge {:harness :judge-echo}})
        (let [{:keys [run entries judge]} (bench/run! rt :demo {})
              entry-id (get entries "fake-opus")]
          (await-phase rt entry-id #{"done"})
          (testing "prompt and stamped attr share one builder (no drift)"
            (let [js (weaver/show rt judge)]
              (is (= (get-in js [:attributes :agent-run/prompt])
                     (get-in js [:attributes :bench/judge-prompt])))))
          (test-support/await-phase rt judge #{"done"})
          (testing "bench/verdict attr wins over the run result"
            ;; the judge worker stamps its verdict on its own run strand
            (weaver/update rt judge {:attributes {"bench/verdict" "winner via attr"}})
            (let [rep (bench/report rt run)]
              (is (= "winner via attr" (get-in rep [:judge :verdict])))
              (is (= "attr" (get-in rep [:judge :verdict-source]))))))))))

(deftest abort-marks-external-judge-cleanly
  (with-bench
    (fn [rt _]
      (let [{:keys [engine]} (fake-engine!)
            {:keys [path sha]} (fixture-repo!)]
        (bench/set-engine! rt engine)
        (bench/defagent! rt :slow {:image "i" :argv ["SLEEP"] :prompt-via :stdin})
        (bench/defsuite! rt :extlong
          {:repo path :sha sha :prompt "p"
           :entries [{:agent :slow}]
           :parallel 1 :timeout-secs 120
           :judge {:external true}})
        (let [{:keys [run entries judge]} (bench/run! rt :extlong {})
              entry-id (get entries "slow")]
          (await-phase rt entry-id #{"running"})
          (bench/abort! rt run)
          (let [j (weaver/show rt judge)]
            (is (= "closed" (:state j)))
            (is (= "aborted" (get-in j [:attributes :bench/error])))
            (is (nil? (get-in j [:attributes :agent-run/phase]))
                "an external judge is not an agent run — no superseded phase")))))))
