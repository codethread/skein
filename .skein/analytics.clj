(ns analytics
  "Repo-local analytics over agent-run usage stamps.

  Registers `feature-costs`: a read-only rollup of every agent run recorded
  beneath a work root (kanban card, plan, or ad hoc root) via parent-of.
  Output is deliberately pure data — per-run rows plus totals, wall-clock
  bounds, and per-harness aggregates — so consumers (rich docs, CSV exports,
  chat summaries) apply their own presentation. Loaded from init.clj as the
  :analytics module; sibling modules hold the rest of the repo policy (see
  config.clj's namespace docstring for the file-per-concern map)."
  (:require [clojure.data.json :as json]
            [skein.macros.ops :refer [defop]]
            [skein.api.current.alpha :as current]
            [skein.api.graph.alpha :as graph]
            [skein.api.weaver.alpha :as weaver])
  (:import (java.time Duration Instant)))

(def ^:private feature-costs-return
  {:type :map :required {:operation :string} :extra :json})

(defn- attr
  "Read strand attribute k, tolerating keyword- or string-keyed maps."
  [strand k]
  (let [attrs (:attributes strand)]
    (or (get attrs k) (get attrs (subs (str k) 1)))))

(defn- parse-attr
  "Parse strand attribute k with parse-fn, failing loudly on malformed values.

  Returns nil when the attribute is absent: absence is part of the shipped
  contract (harnesses that report no usage, runs still in flight), but a
  present value that does not parse is corrupt data and throws. Attribute
  values are JSON TEXT, so a value arrives as whatever JSON type the writer
  stamped — parse-fns must accept both the typed and the string form."
  [strand k parse-fn]
  (when-some [raw (attr strand k)]
    (try
      (parse-fn raw)
      (catch Exception e
        (throw (ex-info "malformed agent-run attribute"
                        {:strand (:id strand) :attribute k :value raw}
                        e))))))

(defn- as-double
  "Coerce a JSON number or numeric string to a double."
  [v]
  (if (number? v) (double v) (Double/parseDouble v)))

(defn- as-long
  "Coerce a JSON integer or integer string to a long."
  [v]
  (if (integer? v) (long v) (Long/parseLong v)))

(defn- as-token-map
  "Coerce a token breakdown to a map: already-typed JSON or its text form.

  Text parses with keyword keys to match how the attribute store returns
  already-typed JSON objects, so both forms project one shape."
  [v]
  (if (map? v) v (json/read-str v :key-fn keyword)))

(defn- run-row
  "Project one agent-run strand into a flat, JSON-friendly usage row.

  Numeric and timestamp attributes are parsed into numbers/ISO strings;
  `:tokens` is the harness token-breakdown map when one was recorded. Usage
  keys are nil when the harness recorded none. No rounding: presentation
  precision belongs to the consumer."
  [strand]
  (let [started (parse-attr strand :agent-run/started-at #(Instant/parse %))
        finished (parse-attr strand :agent-run/finished-at #(Instant/parse %))]
    {:id (:id strand)
     :title (:title strand)
     :state (:state strand)
     :harness (attr strand :agent-run/harness)
     :attempt (parse-attr strand :agent-run/attempt as-long)
     :exit-code (parse-attr strand :agent-run/exit-code as-long)
     :cost-usd (parse-attr strand :agent-run/cost-usd as-double)
     :tokens-total (parse-attr strand :agent-run/tokens-total as-long)
     :tokens (parse-attr strand :agent-run/tokens as-token-map)
     :usage-source (attr strand :agent-run/usage-source)
     :session-id (attr strand :agent-run/session-id)
     :started-at (some-> ^Instant started str)
     :finished-at (some-> ^Instant finished str)
     :duration-secs (when (and started finished)
                      (/ (.toMillis (Duration/between started finished)) 1000.0))}))

(defn- usage-rollup
  "Sum usage over rows: run count plus cost and token totals.

  Rows without usage still count toward :runs; :runs-with-usage says how many
  actually carried cost data, so consumers can flag undercounted totals."
  [rows]
  {:runs (count rows)
   :runs-with-usage (count (filter :cost-usd rows))
   :cost-usd (reduce + 0.0 (keep :cost-usd rows))
   :tokens-total (reduce + 0 (keep :tokens-total rows))})

(defn- by-harness
  "Roll usage up per harness, most expensive first."
  [rows]
  (->> (group-by :harness rows)
       (map (fn [[harness harness-rows]]
              (assoc (usage-rollup harness-rows) :harness harness)))
       (sort-by :cost-usd #(compare %2 %1))
       vec))

(defn- wall-clock
  "Earliest run start, latest run finish, and the elapsed seconds between.

  Nil when no row carries timestamps. Comparison happens on parsed instants,
  not strings: Instant round-trips print fractional seconds at varying
  precision, which breaks lexical ordering."
  [rows]
  (let [starts (map #(Instant/parse ^String %) (keep :started-at rows))
        finishes (map #(Instant/parse ^String %) (keep :finished-at rows))]
    (when (and (seq starts) (seq finishes))
      (let [^Instant earliest (reduce (fn [^Instant a ^Instant b]
                                        (if (.isBefore b a) b a))
                                      starts)
            ^Instant latest (reduce (fn [^Instant a ^Instant b]
                                      (if (.isAfter b a) b a))
                                    finishes)]
        {:started-at (str earliest)
         :finished-at (str latest)
         :duration-secs (/ (.toMillis (Duration/between earliest latest)) 1000.0)}))))

(defop feature-costs
  "Return the agent-run cost/usage rollup for a work root's subtree.

  Walks the root's full parent-of subtree (all lifecycle states, so finished
  features still report) and projects every agent-run record into a usage
  row, ordered by start time. Alongside the rows: totals with wall-clock
  bounds, per-harness rollups, and the ids of runs that recorded no usage.
  Pure data by design — no formatting, no rounding — so one payload serves a
  rich doc, a CSV, or an in-chat summary. Fails loudly on an unknown root."
  {:returns feature-costs-return :arg-spec {:op "feature-costs"
                                            :hook-class :read
                                            :deadline-class :standard
                                            :doc "Show the agent-run cost and usage rollup beneath a work root."
                                            :positionals [{:name :root-id
                                                           :type :string
                                                           :required? true
                                                           :doc "Work-root strand id (kanban card, plan, or ad hoc root)."}]}}
  [ctx]
  (let [{:keys [root-id]} (:op/args ctx)
        rt (current/runtime)
        root (weaver/show rt root-id)]
    (when-not root
      (throw (ex-info "feature-costs root strand not found" {:root-id root-id})))
    (let [{:keys [strands]} (graph/subgraph rt [root-id] {:type "parent-of"})
          rows (->> strands
                    (filter #(attr % :agent-run/run))
                    (map run-row)
                    (sort-by (juxt :started-at :id))
                    vec)]
      {:operation "feature-costs"
       :root {:id (:id root) :title (:title root) :state (:state root)}
       :runs rows
       :totals (assoc (usage-rollup rows) :wall-clock (wall-clock rows))
       :by-harness (by-harness rows)
       :missing-usage (->> rows (remove :cost-usd) (mapv :id))})))
