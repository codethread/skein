(ns skein.spools.bench.metrics
  "Deterministic, code-owned metrics extractors for the bench spool (README §7).

  Each shipped extractor is a pure `(fn [ctx] -> partial metrics map)` over the
  session artifacts a container left on the host: the parsed agent `:stdout` and
  the per-entry `:home` directory mounted as the container HOME. Parsing is
  Clojure JSON over files only — no jq, no model involvement, no shelling out.

  All three normalize onto the §7 schema (`:cost-usd`, `:tokens`, `:turns`,
  `:tools`, `:tool-errors`); the engine merges its own `:exit`/`:duration-ms`/
  `:diff`/`:validation` over the result. A missing or malformed artifact never
  fabricates a value and never throws the entry into failure: the extractor
  records what parsed and lists what did not under `:extraction-warnings`. Cost
  that a provider does not report (codex) is omitted entirely — absent, never 0."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Shared parsing helpers

(defn- parse-json
  "Parse `s` as JSON with keyword keys, or return ::error on any failure."
  [s]
  (try (json/read-str s :key-fn keyword) (catch Exception _ ::error)))

(defn- read-jsonl
  "Parse `file` as JSONL, returning `{:rows [obj ...] :malformed n}`.

  Blank lines are skipped; lines that fail to parse are dropped and counted."
  [^File file]
  (reduce (fn [acc line]
            (if (str/blank? line)
              acc
              (let [v (parse-json line)]
                (if (= ::error v)
                  (update acc :malformed inc)
                  (update acc :rows conj v)))))
          {:rows [] :malformed 0}
          (str/split-lines (slurp file))))

(defn- jsonl-files
  "Return every `.jsonl` file under `dir` (recursively), or nil when `dir` is not
  an existing directory."
  [^File dir]
  (when (and dir (.isDirectory dir))
    (filter (fn [^File f] (and (.isFile f) (str/ends-with? (.getName f) ".jsonl")))
            (file-seq dir))))

(defn- read-jsonl-tree
  "Concatenate `read-jsonl` across every `.jsonl` file under `dir`."
  [^File dir]
  (reduce (fn [acc f]
            (let [{:keys [rows malformed]} (read-jsonl f)]
              (-> acc (update :rows into rows) (update :malformed + malformed))))
          {:rows [] :malformed 0}
          (jsonl-files dir)))

(defn- dedupe-by
  "Return `coll` with the first element for each distinct `(get % k)` kept.

  Elements missing `k` are all retained (no dedupe key to collide on)."
  [k coll]
  (second
   (reduce (fn [[seen acc] x]
             (let [id (get x k)]
               (if (and id (contains? seen id))
                 [seen acc]
                 [(conj seen id) (conj acc x)])))
           [#{} []]
           coll)))

(defn- tally
  "Bucket `names` into the normalized tool taxonomy via `category-fn`.

  Always returns every bucket plus `:total`, so a parsed-but-quiet session
  reports honest zeros rather than absent keys."
  [names category-fn]
  (let [freq (frequencies (map category-fn names))]
    {:file-reads (get freq :file-reads 0)
     :file-writes (get freq :file-writes 0)
     :file-edits (get freq :file-edits 0)
     :bash (get freq :bash 0)
     :other (get freq :other 0)
     :total (count names)}))

(defn- prune-nils
  "Drop nil-valued entries from map `m`."
  [m]
  (into {} (remove (comp nil? val)) m))

(defn- sum-present
  "Sum `(f row)` over `rows` where it is non-nil, or nil when no row carries it.

  Keeps the absent-never-0 invariant: a field never observed yields nil (and is
  pruned) rather than a fabricated zero."
  [f rows]
  (let [present (keep f rows)]
    (when (seq present) (reduce + present))))

;; ---------------------------------------------------------------------------
;; :claude — result JSON on stdout + session JSONL under home/.claude/projects

(defn- claude-category [tool-name]
  (case tool-name
    "Read" :file-reads
    "Write" :file-writes
    ("Edit" "NotebookEdit") :file-edits
    "Bash" :bash
    :other))

(defn claude-extractor
  "Extract `:claude` metrics.

  Cost, tokens, and turns come from the `--output-format json` result object on
  stdout (`total_cost_usd`, `usage`, `num_turns`); the tool taxonomy and
  tool-error count come from the session JSONL under `home/.claude/projects/**`.
  `tool_use` requests are deduped by their globally-unique block id — duplicate
  or streamed assistant rows repeat that id, so this counts each request once
  and subsumes message-id dedupe while still counting blocks split across rows
  of one message. Missing/malformed artifacts add `:extraction-warnings`."
  [{:keys [stdout home]}]
  (let [result (parse-json (str stdout))
        result-ok? (map? result)
        usage (when result-ok? (:usage result))
        tokens (when usage
                 (prune-nils {:input (:input_tokens usage)
                              :output (:output_tokens usage)
                              :cache-read (:cache_read_input_tokens usage)
                              :cache-write (:cache_creation_input_tokens usage)}))
        proj (io/file home ".claude" "projects")
        files (jsonl-files proj)
        {:keys [rows malformed]} (read-jsonl-tree proj)
        assistant-rows (filter #(= "assistant" (:type %)) rows)
        tool-uses (->> assistant-rows
                       (mapcat #(get-in % [:message :content]))
                       (filter #(= "tool_use" (:type %)))
                       (dedupe-by :id))
        tool-results (->> rows
                          (filter #(= "user" (:type %)))
                          (mapcat (fn [row] (let [c (get-in row [:message :content])]
                                              (when (sequential? c) c))))
                          (filter #(= "tool_result" (:type %)))
                          (dedupe-by :tool_use_id))
        warnings (cond-> []
                   (not result-ok?) (conj "claude result JSON on stdout did not parse")
                   (empty? files) (conj "claude session JSONL not found under home/.claude/projects")
                   (pos? malformed) (conj (str "claude session JSONL had " malformed " malformed lines")))]
    (cond-> {}
      (and result-ok? (:total_cost_usd result)) (assoc :cost-usd (:total_cost_usd result))
      (seq tokens) (assoc :tokens tokens)
      (and result-ok? (:num_turns result)) (assoc :turns (:num_turns result))
      (seq files) (assoc :tools (tally (map :name tool-uses) claude-category)
                         :tool-errors (count (filter #(true? (:is_error %)) tool-results)))
      (seq warnings) (assoc :extraction-warnings warnings))))

;; ---------------------------------------------------------------------------
;; :pi — the pinned session JSONL at home/session.jsonl

(defn- pi-category [tool-name]
  (case tool-name
    "read" :file-reads
    "write" :file-writes
    "edit" :file-edits
    "bash" :bash
    :other))

(defn pi-extractor
  "Extract `:pi` metrics from the pinned session JSONL (`home/session.jsonl`).

  Emits only what the usage rows actually carry: a `:tokens` sub-key
  (`input`/`output`/`cacheRead`/`cacheWrite`) and `:cost-usd` appear only when at
  least one assistant `usage` row reports them — a field never present is omitted,
  never fabricated as 0 (README §7). Counts assistant rows as `:turns`, maps
  `content` `toolCall` names to the taxonomy, and counts `toolResult` rows flagged
  `isError`. A missing session adds a warning and yields no fabricated counts."
  [{:keys [home]}]
  (let [^File f (io/file home "session.jsonl")]
    (if-not (.isFile f)
      {:extraction-warnings ["pi session JSONL not found at home/session.jsonl"]}
      (let [{:keys [rows malformed]} (read-jsonl f)
            assistants (filter #(and (= "message" (:type %))
                                     (= "assistant" (get-in % [:message :role])))
                               rows)
            usages (keep #(get-in % [:message :usage]) assistants)
            cost (sum-present #(get-in % [:cost :total]) usages)
            tokens (prune-nils {:input (sum-present :input usages)
                                :output (sum-present :output usages)
                                :cache-read (sum-present :cacheRead usages)
                                :cache-write (sum-present :cacheWrite usages)})
            tool-names (->> assistants
                            (mapcat #(get-in % [:message :content]))
                            (filter #(= "toolCall" (:type %)))
                            (map :name))
            tool-errors (->> rows
                             (filter #(and (= "message" (:type %))
                                           (= "toolResult" (get-in % [:message :role]))
                                           (true? (get-in % [:message :isError]))))
                             count)
            warnings (cond-> []
                       (pos? malformed) (conj (str "pi session JSONL had " malformed " malformed lines"))
                       (empty? assistants) (conj "pi session JSONL had no assistant messages"))]
        (cond-> {:turns (count assistants)
                 :tools (tally tool-names pi-category)
                 :tool-errors tool-errors}
          (seq tokens) (assoc :tokens tokens)
          cost (assoc :cost-usd cost)
          (seq warnings) (assoc :extraction-warnings warnings))))))

;; ---------------------------------------------------------------------------
;; :codex — rollout JSONL under home/.codex/sessions

(defn- codex-category [tool-name]
  (cond
    ;; the shell tool is surfaced as shell/local_shell in the documented schema
    ;; and as exec_command on current Codex builds (README §7 records the family).
    (#{"shell" "local_shell" "exec_command"} tool-name) :bash
    (= "apply_patch" tool-name) :file-edits
    :else :other))

(defn codex-extractor
  "Extract `:codex` metrics from the rollout JSONL under `home/.codex/sessions/**`.

  Token totals are the last `token_count` event's cumulative
  `info.total_token_usage`; shell-family calls map to `:bash`, `apply_patch` to
  `:file-edits`. Codex reports no cost, so `:cost-usd` is omitted entirely —
  absent, never 0. A missing rollout adds a warning."
  [{:keys [home]}]
  (let [dir (io/file home ".codex" "sessions")
        files (jsonl-files dir)]
    (if (empty? files)
      {:extraction-warnings ["codex rollout JSONL not found under home/.codex/sessions"]}
      (let [{:keys [rows malformed]} (read-jsonl-tree dir)
            token-events (filter #(and (= "event_msg" (:type %))
                                       (= "token_count" (get-in % [:payload :type])))
                                 rows)
            totals (get-in (last token-events) [:payload :info :total_token_usage])
            tokens (when totals
                     (prune-nils {:input (:input_tokens totals)
                                  :output (:output_tokens totals)
                                  :cache-read (:cached_input_tokens totals)}))
            call-names (->> rows
                            (filter #(= "response_item" (:type %)))
                            (keep (fn [r] (let [p (:payload r)]
                                            (when (#{"function_call" "custom_tool_call"} (:type p))
                                              (:name p))))))
            warnings (cond-> []
                       (pos? malformed) (conj (str "codex rollout JSONL had " malformed " malformed lines"))
                       (empty? token-events) (conj "codex rollout JSONL had no token_count events"))]
        (cond-> {:tools (tally call-names codex-category)}
          (seq tokens) (assoc :tokens tokens)
          (seq warnings) (assoc :extraction-warnings warnings))))))

;; ---------------------------------------------------------------------------
;; Registry

(def shipped
  "The shipped extractor functions keyed by their harness-def `:extractor` key. Bench
  registers these as install-time defaults, leaving any user-registered
  extractor of the same key untouched."
  {:claude claude-extractor
   :pi pi-extractor
   :codex codex-extractor})
