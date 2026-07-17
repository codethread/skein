(ns skein.bench-metrics-test
  "Tests for the bench spool's shipped metrics extractors.

  Each extractor is a pure `(fn [ctx] -> partial metrics map)`, so the bulk of
  the coverage builds realistic minimal fixture session files in a temp `home/`
  and calls the extractor directly. Covers the normalized §7 schema per format,
  the tool taxonomy mapping, claude tool-request dedupe across duplicate and
  split assistant rows, and the malformed/missing-artifact contract (warnings
  recorded, absent-not-zero cost for codex). One weaver-backed test proves
  `install!` registers the shipped extractors as defaults without clobbering a
  user-registered one."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.spools.bench :as bench]
            [skein.spools.bench.metrics :as metrics]
            [skein.spools.test-support :as test-support])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Fixtures

(defn- temp-home []
  (.toFile (Files/createTempDirectory (.toPath (io/file "/tmp")) "skein-bench-metrics"
                                      (make-array FileAttribute 0))))

;; a tiny JSON writer so fixtures read like the real transcripts
(defn- json-str [x] (json/write-str x))

(defn- write-jsonl!
  "Write `rows` (maps or raw strings) as JSONL to `file`, creating parents."
  [file rows]
  (let [f (io/file file)]
    (.mkdirs (.getParentFile f))
    (spit f (str/join "\n" (map #(if (string? %) % (json-str %)) rows)))))

;; ---------------------------------------------------------------------------
;; :claude

(defn- claude-result-json []
  (json-str {:type "result" :subtype "success"
             :total_cost_usd 0.4213
             :num_turns 12
             :duration_ms 84210
             :usage {:input_tokens 12034
                     :output_tokens 8211
                     :cache_read_input_tokens 401223
                     :cache_creation_input_tokens 10021}}))

(defn- claude-session-rows []
  (let [;; one response split across two rows sharing a message id: a text row
        ;; then a tool_use row — both must be seen for the tool_use to count.
        msg-text {:type "assistant"
                  :message {:id "msg_1" :role "assistant"
                            :content [{:type "text" :text "reading"}]}}
        msg-read {:type "assistant"
                  :message {:id "msg_1" :role "assistant"
                            :content [{:type "tool_use" :id "toolu_read" :name "Read" :input {}}]}}
        msg-edits {:type "assistant"
                   :message {:id "msg_2" :role "assistant"
                             :content [{:type "tool_use" :id "toolu_edit" :name "Edit" :input {}}
                                       {:type "tool_use" :id "toolu_nb" :name "NotebookEdit" :input {}}
                                       {:type "tool_use" :id "toolu_write" :name "Write" :input {}}
                                       {:type "tool_use" :id "toolu_bash" :name "Bash" :input {}}
                                       {:type "tool_use" :id "toolu_grep" :name "Grep" :input {}}]}}
        ;; a duplicate of the Read row (streamed/resumed) — must not double-count.
        dup-read msg-read
        result-ok {:type "user"
                   :message {:role "user"
                             :content [{:type "tool_result" :tool_use_id "toolu_read" :is_error false}]}}
        result-err {:type "user"
                    :message {:role "user"
                              :content [{:type "tool_result" :tool_use_id "toolu_bash" :is_error true}]}}
        ;; a duplicate error result — deduped by tool_use_id, counted once.
        dup-err result-err]
    [msg-text msg-read msg-edits dup-read result-ok result-err dup-err]))

(deftest claude-extractor-parses-cost-tokens-turns-and-tools
  (let [home (temp-home)]
    (write-jsonl! (io/file home ".claude" "projects" "-enc-cwd" "s.jsonl")
                  (claude-session-rows))
    (let [m (metrics/claude-extractor {:stdout (claude-result-json) :home home})]
      (testing "cost, tokens, and turns come from the result JSON"
        (is (= 0.4213 (:cost-usd m)))
        (is (= {:input 12034 :output 8211 :cache-read 401223 :cache-write 10021} (:tokens m)))
        (is (= 12 (:turns m))))
      (testing "tool taxonomy with split-row and duplicate-row dedupe"
        ;; Read counted once despite the duplicate row; Edit+NotebookEdit both
        ;; map to file-edits; Grep falls through to :other.
        (is (= {:file-reads 1 :file-writes 1 :file-edits 2 :bash 1 :other 1 :total 6}
               (:tools m))))
      (testing "tool errors deduped by tool_use_id"
        (is (= 1 (:tool-errors m))))
      (testing "clean parse records no warnings"
        (is (not (contains? m :extraction-warnings)))))))

(deftest claude-extractor-missing-session-warns-but-keeps-stdout-metrics
  (let [home (temp-home)
        m (metrics/claude-extractor {:stdout (claude-result-json) :home home})]
    (is (= 0.4213 (:cost-usd m)))
    (is (= 12 (:turns m)))
    (testing "no fabricated tool metrics when the session artifact is absent"
      (is (not (contains? m :tools)))
      (is (not (contains? m :tool-errors))))
    (is (some #(str/includes? % "session JSONL not found") (:extraction-warnings m)))))

(deftest claude-extractor-malformed-stdout-warns
  (let [home (temp-home)
        m (metrics/claude-extractor {:stdout "not json {" :home home})]
    (is (not (contains? m :cost-usd)))
    (is (not (contains? m :tokens)))
    (is (not (contains? m :turns)))
    (is (some #(str/includes? % "result JSON on stdout did not parse") (:extraction-warnings m)))))

;; ---------------------------------------------------------------------------
;; :pi

(defn- pi-usage [input output cache-read cost-total]
  {:input input :output output :cacheRead cache-read :cacheWrite 0
   :totalTokens (+ input output cache-read)
   :cost {:input 0 :output 0 :cacheRead 0 :cacheWrite 0 :total cost-total}})

(defn- pi-session-rows []
  [{:type "session" :id "sess"}
   {:type "message"
    :message {:role "assistant"
              :content [{:type "text" :text "ok"}
                        {:type "toolCall" :id "c1" :name "read" :arguments {}}
                        {:type "toolCall" :id "c2" :name "bash" :arguments {}}]
              :usage (pi-usage 8550 257 0 0.05046)}}
   {:type "message"
    :message {:role "assistant"
              :content [{:type "toolCall" :id "c3" :name "edit" :arguments {}}
                        {:type "toolCall" :id "c4" :name "write" :arguments {}}
                        {:type "toolCall" :id "c5" :name "grep" :arguments {}}]
              :usage (pi-usage 12066 227 8192 0.07124)}}
   {:type "message" :message {:role "user" :content "hi"}}
   {:type "message" :message {:role "toolResult" :toolCallId "c2" :toolName "bash"
                              :content [{:type "text" :text "boom"}] :isError true}}])

(deftest pi-extractor-parses-cost-tokens-turns-and-tools
  (let [home (temp-home)]
    (write-jsonl! (io/file home "session.jsonl") (pi-session-rows))
    (let [m (metrics/pi-extractor {:home home})]
      (testing "cost is the sum of per-assistant-message cost.total"
        (is (< (Math/abs (- 0.1217 (:cost-usd m))) 1e-9)))
      (testing "token fields summed across assistant messages"
        (is (= {:input 20616 :output 484 :cache-read 8192 :cache-write 0} (:tokens m))))
      (testing "turns is the assistant row count"
        (is (= 2 (:turns m))))
      (testing "toolCall taxonomy"
        (is (= {:file-reads 1 :file-writes 1 :file-edits 1 :bash 1 :other 1 :total 5}
               (:tools m))))
      (testing "toolResult isError counted"
        (is (= 1 (:tool-errors m))))
      (is (not (contains? m :extraction-warnings))))))

(deftest pi-extractor-missing-session-warns
  (let [home (temp-home)
        m (metrics/pi-extractor {:home home})]
    (is (not (contains? m :cost-usd)))
    (is (not (contains? m :tokens)))
    (is (some #(str/includes? % "not found") (:extraction-warnings m)))))

(deftest pi-extractor-omits-token-fields-and-cost-never-observed
  ;; usage rows that carry no :cost and no :cacheWrite anywhere — the absent
  ;; fields must be omitted, never fabricated as 0 (README §7).
  (let [home (temp-home)]
    (write-jsonl! (io/file home "session.jsonl")
                  [{:type "message"
                    :message {:role "assistant"
                              :content [{:type "toolCall" :id "c1" :name "read"}]
                              :usage {:input 100 :output 20 :cacheRead 5}}}
                   {:type "message"
                    :message {:role "assistant"
                              :content []
                              :usage {:input 50 :output 10 :cacheRead 0}}}])
    (let [m (metrics/pi-extractor {:home home})]
      (testing "cost never reported → :cost-usd omitted, never 0"
        (is (not (contains? m :cost-usd))))
      (testing "cacheWrite never present → omitted; only observed fields summed"
        (is (= {:input 150 :output 30 :cache-read 5} (:tokens m)))
        (is (not (contains? (:tokens m) :cache-write))))
      (testing "turns still counted from assistant rows"
        (is (= 2 (:turns m)))))))

(deftest pi-extractor-sums-cost-only-over-rows-that-report-it
  ;; one row reports a cost, the other does not — cost is the sum of the present
  ;; values, not a run polluted by a fabricated 0 from the silent row.
  (let [home (temp-home)]
    (write-jsonl! (io/file home "session.jsonl")
                  [{:type "message"
                    :message {:role "assistant"
                              :content [{:type "toolCall" :id "c1" :name "bash"}]
                              :usage {:input 100 :output 20 :cacheWrite 3
                                      :cost {:total 0.25}}}}
                   {:type "message"
                    :message {:role "assistant"
                              :content []
                              :usage {:input 50 :output 10}}}])
    (let [m (metrics/pi-extractor {:home home})]
      (is (< (Math/abs (- 0.25 (:cost-usd m))) 1e-9) "cost summed only over the reporting row")
      (testing "cacheWrite observed on one row → kept; cacheRead never present → omitted"
        (is (= {:input 150 :output 30 :cache-write 3} (:tokens m)))
        (is (not (contains? (:tokens m) :cache-read)))))))

;; ---------------------------------------------------------------------------
;; :codex

(defn- codex-token-event [input cached output]
  {:type "event_msg"
   :payload {:type "token_count"
             :info {:total_token_usage {:input_tokens input
                                        :cached_input_tokens cached
                                        :output_tokens output
                                        :total_tokens (+ input output)}}}})

(defn- codex-rollout-rows []
  [{:type "session_meta" :payload {:session_id "x" :cwd "/w"}}
   (codex-token-event 100 10 20)
   {:type "response_item" :payload {:type "function_call" :name "exec_command"
                                    :arguments "{\"cmd\":\"ls\"}"}}
   {:type "response_item" :payload {:type "custom_tool_call" :name "apply_patch"}}
   {:type "response_item" :payload {:type "function_call" :name "web_search"}}
   ;; the later token_count is cumulative and wins
   (codex-token-event 15133 2432 252)])

(deftest codex-extractor-parses-tokens-and-tools-without-cost
  (let [home (temp-home)]
    (write-jsonl! (io/file home ".codex" "sessions" "2026" "07" "03" "rollout-x.jsonl")
                  (codex-rollout-rows))
    (let [m (metrics/codex-extractor {:home home})]
      (testing "tokens from the last cumulative token_count event"
        (is (= {:input 15133 :output 252 :cache-read 2432} (:tokens m))))
      (testing "shell-family → bash, apply_patch → file-edits, else other"
        (is (= {:file-reads 0 :file-writes 0 :file-edits 1 :bash 1 :other 1 :total 3}
               (:tools m))))
      (testing "codex reports no cost — absent, never 0"
        (is (not (contains? m :cost-usd))))
      (is (not (contains? m :extraction-warnings))))))

(deftest codex-extractor-missing-rollout-warns
  (let [home (temp-home)
        m (metrics/codex-extractor {:home home})]
    (is (not (contains? m :cost-usd)))
    (is (not (contains? m :tokens)))
    (is (some #(str/includes? % "not found") (:extraction-warnings m)))))

(deftest codex-extractor-malformed-lines-warn-but-still-count
  (let [home (temp-home)]
    (write-jsonl! (io/file home ".codex" "sessions" "rollout-y.jsonl")
                  ["{ not json"
                   (codex-token-event 5 1 2)
                   {:type "response_item" :payload {:type "function_call" :name "shell"}}])
    (let [m (metrics/codex-extractor {:home home})]
      (is (= {:input 5 :output 2 :cache-read 1} (:tokens m)))
      (is (= 1 (get-in m [:tools :bash])))
      (is (some #(str/includes? % "malformed") (:extraction-warnings m))))))

;; ---------------------------------------------------------------------------
;; Registration through install!

(deftest install-registers-shipped-extractors-without-clobbering-user
  (test-support/with-runtime
    {:prefix "skein-bench-metrics"}
    (fn [rt _]
      (let [sentinel (fn [_ctx] {:cost-usd 9.99})]
        ;; a trusted config that registered its own :claude before install! wins.
        (bench/register-extractor! rt :claude sentinel)
        (bench/install!)
        (is (= #{:claude :codex :generic :pi} (set (bench/extractors rt)))
            "all shipped extractor keys are present")
        (let [reg @(#'bench/extractors-atom rt)]
          (is (identical? sentinel (:claude reg)) "user-registered :claude is not clobbered")
          (is (identical? metrics/pi-extractor (:pi reg)) "unregistered keys get the shipped default")
          (is (identical? metrics/codex-extractor (:codex reg))))))))
