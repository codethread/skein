(ns skein.spools.backlog
  "Repo-local BACKLOG.md convention over Skein strands.

  The backlog spool keeps a Git-visible Markdown checkbox list as the human
  queue while Skein strands remain the executable/audit graph. The Markdown row
  stores the strand id; the strand carries `backlog/*` attributes and can be the
  parent of feature plans and task DAGs."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.events.alpha :as events]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.weaver.alpha :as api]))

(def ^:private backlog-file-name "BACKLOG.md")
(def ^:private item-attr "backlog/item")
(def ^:private status-attr "backlog/status")
(def ^:private file-attr "backlog/file")

(def ^:private row-pattern
  #"^-\s+\[([ xX])\]\s+(?:`([^`]+)`\s+)?(.+?)\s*$")

(defn- non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (and (string? v) (not (str/blank? v))))

(defn- require-non-blank!
  "Return v when it is a non-blank string, otherwise throw with arg context."
  [arg v]
  (when-not (non-blank-string? v)
    (throw (ex-info (str (name arg) " must be a non-blank string")
                    {:argument arg :value v})))
  v)

(defn- config-dir
  "Return the selected workspace config directory from the active runtime."
  []
  (or (get-in (current/runtime) [:metadata :config-dir])
      (throw (ex-info "backlog requires an active weaver runtime" {}))))

(defn- repo-root-dir
  "Return the directory that owns BACKLOG.md for the selected workspace."
  []
  (let [dir (io/file (config-dir))]
    (if (= ".skein" (.getName dir))
      (-> dir .getParentFile .getCanonicalFile)
      (.getCanonicalFile dir))))

(defn- backlog-file
  "Return the configured BACKLOG.md file."
  []
  (io/file (repo-root-dir) backlog-file-name))

(defn- ensure-backlog-file!
  "Create BACKLOG.md with a heading if it is missing, then return it."
  []
  (let [file (backlog-file)]
    (when-not (.exists file)
      (spit file "# Backlog\n\n"))
    file))

(defn- read-lines
  "Return BACKLOG.md lines, creating the file when needed."
  []
  (str/split-lines (slurp (ensure-backlog-file!))))

(defn- write-lines!
  "Overwrite BACKLOG.md with lines and a trailing newline."
  [lines]
  (spit (ensure-backlog-file!) (str (str/join "\n" lines) "\n")))

(defn- parse-row
  "Parse one BACKLOG.md checkbox row, returning nil for non-item lines."
  [line-number line]
  (when-let [[_ mark id title] (re-matches row-pattern line)]
    {:line line-number
     :checked? (contains? #{"x" "X"} mark)
     :id id
     :title (str/trim title)
     :raw line}))

(defn- rows
  "Return parsed BACKLOG.md item rows in file order."
  []
  (->> (read-lines)
       (map-indexed (fn [idx line] (parse-row (inc idx) line)))
       (remove nil?)
       vec))

(defn- row-by-id
  "Return the BACKLOG.md row for id or nil."
  [id]
  (first (filter #(= id (:id %)) (rows))))

(defn- require-row-id!
  "Return row id, failing if a backlog row is missing its strand id."
  [row]
  (or (:id row)
      (throw (ex-info "Backlog row is missing a strand id"
                      {:row row}))))

(defn- parse-op-argv
  "Parse op argv into positional args and single-value flags."
  [op argv flag-spec]
  (loop [remaining argv
         positional []
         flags {}]
    (if-let [arg (first remaining)]
      (if (str/starts-with? arg "--")
        (let [kind (or (get flag-spec arg)
                       (throw (ex-info (str op " unknown flag")
                                       {:flag arg :allowed (sort (keys flag-spec))})))
              value (or (second remaining)
                        (throw (ex-info (str op " flag requires a value")
                                        {:flag arg})))]
          (when-not (= :single kind)
            (throw (ex-info (str op " unsupported flag kind")
                            {:flag arg :kind kind})))
          (recur (drop 2 remaining) positional (assoc flags arg value)))
        (recur (rest remaining) (conj positional arg) flags))
      {:positional positional :flags flags})))

(defn- item-attributes
  "Return the attributes for a newly added backlog item strand."
  [flags]
  (cond-> {item-attr "true"
           status-attr "pending"
           file-attr backlog-file-name
           :kind "feature"}
    (get flags "--body") (assoc :body (get flags "--body"))
    (get flags "--source") (assoc "backlog/source" (get flags "--source"))))

(defn- append-row!
  "Append an unchecked BACKLOG.md row for strand id and title."
  [id title]
  (let [file (ensure-backlog-file!)
        prefix (let [content (slurp file)]
                 (if (or (empty? content) (str/ends-with? content "\n")) "" "\n"))]
    (spit file (str prefix "- [ ] `" id "` " title "\n") :append true)))

(defn add!
  "Create a backlog item strand and append its Markdown checkbox row."
  [title flags]
  (let [title (require-non-blank! :title title)
        strand (api/add (current/runtime)
                        {:title title
                         :attributes (item-attributes flags)})]
    (append-row! (:id strand) title)
    {:operation "backlog add"
     :item (select-keys strand [:id :title :state :attributes])
     :row (row-by-id (:id strand))
     :file backlog-file-name}))

;; backlog-batch weave pattern
(s/def ::non-blank-string non-blank-string?)
(s/def ::key ::non-blank-string)
(s/def ::title ::non-blank-string)
(s/def ::body ::non-blank-string)
(s/def ::deps (s/coll-of ::non-blank-string :kind vector?))
(def ^:private batch-item-keys #{:key :title :body :deps})
(def ^:private batch-input-keys #{:items})

(defn- known-keys?
  "Return true when map m contains only allowed keys."
  [allowed m]
  (empty? (remove allowed (keys m))))

(s/def ::batch-item
  (s/and map?
         #(known-keys? batch-item-keys %)
         (s/keys :req-un [::key ::title]
                 :opt-un [::body ::deps])))
(s/def ::items (s/coll-of ::batch-item :kind vector? :min-count 1))
(s/def ::backlog-batch-input
  (s/and map?
         #(known-keys? batch-input-keys %)
         (s/keys :req-un [::items])))

(defn- duplicate-item
  "Return the first duplicate value in xs, or nil."
  [xs]
  (some (fn [[v n]] (when (> n 1) v)) (frequencies xs)))

(defn- item-ref
  "Return the batch-local symbol for item key."
  [key]
  (symbol key))

(defn backlog-batch
  "Create backlog item strands with bodies and depends-on edges.

  Input shape: {:items [{:key \"slug\" :title \"Title\" :body \"optional\"
  :deps [\"sibling-key-or-existing-strand-id\"]}]}. `deps` values matching sibling
  keys become batch-local edges; all other values are treated as durable strand
  ids and fail loudly if absent. BACKLOG.md rows are appended by the installed
  batch event handler after the create-only weave has committed strand ids."
  [{:keys [input]}]
  (let [{:keys [items]} input
        keys (mapv :key items)]
    (when-let [duplicate-key (duplicate-item keys)]
      (throw (ex-info "backlog-batch item keys must be unique" {:key duplicate-key})))
    (let [sibling-keys (set keys)]
      (mapv (fn [{:keys [key title body deps]}]
              (cond-> {:ref (item-ref key)
                       :title title
                       :attributes (item-attributes (cond-> {}
                                                   body (assoc "--body" body)))}
                (seq deps)
                (assoc :edges (mapv (fn [dep]
                                       {:type "depends-on"
                                        :to (if (contains? sibling-keys dep)
                                              (item-ref dep)
                                              dep)})
                                     deps))))
            items))))

(defn append-backlog-batch-rows!
  "Append BACKLOG.md rows for a committed backlog-batch weave event."
  [{pattern-name :pattern/name created :batch/created}]
  (when (= "backlog-batch" pattern-name)
    (doseq [{:keys [id title]} created]
      (append-row! id title))))

(defn- attr-value
  "Return a strand attribute by string or keyword key."
  [strand k]
  (let [attrs (:attributes strand)]
    (or (get attrs k)
        (get attrs (keyword k)))))

(defn- item-strand
  "Return id's backlog item strand, failing loudly if it is absent or not an item."
  [id]
  (let [strand (or (api/show (current/runtime) id)
                   (throw (ex-info "Backlog strand not found" {:id id})))]
    (when-not (= "true" (attr-value strand item-attr))
      (throw (ex-info "Strand is not a backlog item" {:id id :attributes (:attributes strand)})))
    strand))

(defn- merge-attrs
  "Merge attrs into strand attributes for api/update."
  [strand attrs]
  (merge (:attributes strand) attrs))

(defn claim!
  "Mark a backlog item as claimed without checking off its Markdown row."
  [id flags]
  (let [id (require-non-blank! :id id)
        strand (item-strand id)]
    (when-not (= "active" (:state strand))
      (throw (ex-info "Backlog item must be active to claim" {:id id :state (:state strand)})))
    (when-not (= "pending" (attr-value strand status-attr))
      (throw (ex-info "Backlog item must be pending to claim"
                      {:id id :status (attr-value strand status-attr)})))
    (when-not (row-by-id id)
      (throw (ex-info "Backlog item has no BACKLOG.md row" {:id id})))
    (let [attrs (cond-> {status-attr "claimed"}
                  (get flags "--owner") (assoc :owner (get flags "--owner"))
                  (get flags "--branch") (assoc :branch (get flags "--branch"))
                  (get flags "--worktree") (assoc :worktree (get flags "--worktree")))
          updated (api/update (current/runtime) id {:attributes (merge-attrs strand attrs)})]
      {:operation "backlog claim"
       :item (select-keys updated [:id :title :state :attributes])
       :row (row-by-id id)})))

(defn- replace-row-check!
  "Set row id's checkbox to checked? while preserving the row title."
  [id checked?]
  (let [lines (read-lines)
        target (row-by-id id)]
    (when-not target
      (throw (ex-info "Backlog row not found" {:id id})))
    (write-lines!
     (map-indexed (fn [idx line]
                    (if (= (inc idx) (:line target))
                      (str "- [" (if checked? "x" " ") "] `" id "` " (:title target))
                      line))
                  lines))))

(defn finish!
  "Close a backlog item strand and check off its Markdown row."
  [id flags]
  (let [id (require-non-blank! :id id)
        strand (item-strand id)
        outcome (or (get flags "--outcome") "done")]
    (when-not (= "active" (:state strand))
      (throw (ex-info "Backlog item must be active to finish" {:id id :state (:state strand)})))
    (when-not (row-by-id id)
      (throw (ex-info "Backlog item has no BACKLOG.md row" {:id id})))
    (replace-row-check! id true)
    (let [updated (api/update (current/runtime)
                              id
                              {:state "closed"
                               :attributes (merge-attrs strand {status-attr outcome})})]
      {:operation "backlog finish"
       :item (select-keys updated [:id :title :state :attributes])
       :row (row-by-id id)})))

(defn next-item
  "Return the next active pending backlog item in BACKLOG.md order, or nil."
  []
  (let [rt (current/runtime)]
    (some (fn [row]
            (let [id (require-row-id! row)
                  strand (api/show rt id)]
              (when (and strand
                         (not (:checked? row))
                         (= "active" (:state strand))
                         (= "true" (attr-value strand item-attr))
                         (= "pending" (attr-value strand status-attr)))
                {:row row
                 :item (select-keys strand [:id :title :state :attributes])})))
          (rows))))

(defn- backlog-strands-by-id
  "Return all strands marked as backlog items keyed by id."
  []
  (into {}
        (map (juxt :id identity))
        (api/list (current/runtime) [:= [:attr item-attr] "true"] {})))

(defn sync!
  "Validate BACKLOG.md rows against backlog item strands, failing on drift."
  []
  (let [rows (rows)
        row-ids (set (keep :id rows))
        duplicate-ids (->> rows
                           (keep :id)
                           frequencies
                           (keep (fn [[id n]] (when (> n 1) id)))
                           set)
        strands (backlog-strands-by-id)
        problems (vec
                  (concat
                   (map (fn [id]
                          {:problem "duplicate-row-id" :id id})
                        (sort duplicate-ids))
                   (keep (fn [{:keys [id checked? line title] :as row}]
                           (cond
                             (nil? id) {:problem "row-missing-id" :line line :title title}
                             (nil? (get strands id)) {:problem "row-strand-missing" :id id :line line}
                             (and checked? (= "active" (:state (get strands id))))
                             {:problem "checked-row-active-strand" :id id :line line}
                             (and (not checked?) (= "closed" (:state (get strands id))))
                             {:problem "unchecked-row-closed-strand" :id id :line line}
                             :else nil))
                         rows)
                   (keep (fn [[id strand]]
                           (when-not (contains? row-ids id)
                             {:problem "strand-missing-row" :id id :title (:title strand)}))
                         strands)))]
    (when (seq problems)
      (throw (ex-info "BACKLOG.md is out of sync with backlog strands"
                      {:problems problems :file backlog-file-name})))
    {:operation "backlog sync"
     :ok true
     :items (count rows)
     :file backlog-file-name}))

(defn about
  "Return the backlog convention and installed helper surface."
  []
  {:operation "backlog about"
   :summary "BACKLOG.md is the human feature queue; Skein strands are the executable/audit graph."
   :file backlog-file-name
   :row-format "- [ ] `<strand-id>` Feature idea, optionally referencing RFC/feat docs"
   :attributes {item-attr "true"
                status-attr "pending|claimed|done|abandoned"
                file-attr backlog-file-name
                :kind "feature"}
   :commands [{:usage "strand backlog add <title> [--body <text>] [--source <path-or-url>]"}
              {:usage "strand weave --pattern backlog-batch < batch.json"}
              {:usage "strand backlog next"}
              {:usage "strand backlog claim <id> [--owner <name>] [--branch <branch>] [--worktree <path>]"}
              {:usage "strand backlog finish <id> [--outcome done|abandoned]"}
              {:usage "strand backlog sync"}]
   :patterns [{:name "backlog-batch"
               :input {:items [{:key "slug"
                                :title "Feature title"
                                :body "optional body"
                                :deps ["sibling-key-or-existing-strand-id"]}]}}]})

(defn backlog-op
  "Dispatch `strand backlog ...` subcommands."
  [ctx]
  (let [[subcommand & argv] (:op/argv ctx)]
    (case subcommand
      "about" (about)
      "add" (let [{:keys [positional flags]}
                  (parse-op-argv "backlog add" argv {"--body" :single "--source" :single})]
              (add! (str/join " " positional) flags))
      "next" {:operation "backlog next" :next (next-item)}
      "claim" (let [{:keys [positional flags]}
                    (parse-op-argv "backlog claim" argv {"--owner" :single
                                                          "--branch" :single
                                                          "--worktree" :single})
                    [id] positional]
                (when-not (= 1 (count positional))
                  (throw (ex-info "backlog claim expects one id" {:argv argv})))
                (claim! id flags))
      "finish" (let [{:keys [positional flags]}
                     (parse-op-argv "backlog finish" argv {"--outcome" :single})
                     [id] positional]
                 (when-not (= 1 (count positional))
                   (throw (ex-info "backlog finish expects one id" {:argv argv})))
                 (finish! id flags))
      "sync" (do
                (when (seq argv)
                  (throw (ex-info "backlog sync expects no arguments" {:argv argv})))
                (sync!))
      (throw (ex-info "backlog expects a subcommand"
                      {:usage "strand backlog <about|add|next|claim|finish|sync> ..."
                       :subcommand subcommand})))))

(defn install!
  "Install the BACKLOG.md helper op and backlog queries into the active weaver."
  []
  (let [rt (current/runtime)]
    {:installed true
     :namespace 'skein.spools.backlog
     :ops [(api/register-op! rt 'backlog
                             "Manage the BACKLOG.md feature queue backed by Skein strands"
                             'skein.spools.backlog/backlog-op)]
     :pattern (patterns/register-pattern! rt 'backlog-batch
                                          "Create backlog item strands with bodies, depends-on edges, and BACKLOG.md rows."
                                          'skein.spools.backlog/backlog-batch
                                          ::backlog-batch-input)
     :event-handler (events/register! rt :backlog-batch-rows #{:batch/applied}
                                      'skein.spools.backlog/append-backlog-batch-rows!
                                      {:doc "Append BACKLOG.md rows after backlog-batch weaves commit."})
     :queries [(api/register-query! rt 'backlog-items [:= [:attr item-attr] "true"])
               (api/register-query! rt 'backlog-unstarted
                                    [:and
                                     [:= :state "active"]
                                     [:= [:attr item-attr] "true"]
                                     [:= [:attr status-attr] "pending"]])] }))
