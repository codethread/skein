(ns skein.spools.chime
  "Human-attention notification bridge for Skein graph events.

  Chime watches strand mutations, evaluates small userland rules, and sends
  attention notices through a workspace-bound local notifier command. It owns
  only weaver-lifetime runtime state and composes the public weaver/event API."
  (:require [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.events.alpha :as events]
            [skein.api.hooks.alpha :as hooks]
            [skein.api.weaver.alpha :as weaver]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.spool.alpha :refer [fail!]])
  (:import [java.io OutputStreamWriter]
           [java.time Instant]))

(def ^:private event-types
  #{:strand/added :strand/updated :batch/applied :strand/burned :strand/superseded})

(def ^:private mutation-hook-types
  #{:strand/add-before-commit
    :strand/update-before-commit
    :strand/supersede-before-commit
    :strand/burn-before-commit
    :batch/apply-before-commit})

(declare baseline-rule! rt)

(def ^:private state-version
  "Shape version for chime's runtime spool-state map. Bump whenever `new-state`'s
  key set changes: spool-state survives `reload!`, so a post-upgrade reload would
  otherwise reuse a preserved map missing the new key (docs/spools/writing-shared-spools.md
  'Versioned spool state', SPEC-004.C95). The `state-shape-matches-declared-version`
  test fails loudly if `new-state` and this version drift apart."
  1)

(defn- new-state []
  {:notifier-binding (atom nil)
   :rule-registry (atom {})
   :seen-notifications (atom #{})
   :failure-log (atom [])
   :scanned-batch-ids (atom [])})

(defn- state []
  (runtime/spool-state (rt) ::state {:version state-version} new-state))

(defn- notifier-binding [] (:notifier-binding (state)))
(defn- rule-registry [] (:rule-registry (state)))
(defn- seen-notifications [] (:seen-notifications (state)))
(defn- failure-log [] (:failure-log (state)))
(defn- scanned-batch-ids [] (:scanned-batch-ids (state)))

(def ^:dynamic *runtime*
  "Runtime captured for asynchronous notifier worker threads."
  nil)

(defn- rt []
  (or *runtime* (current/runtime)))

(defn- now [] (str (Instant/now)))

(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- record-failure! [entry]
  (let [full (assoc entry :failed/at (now))]
    (swap! (failure-log) #(->> (conj (vec %) full) (take-last 100) vec))
    full))

(defn recent-failures
  "Return the last 100 notifier, process, and rule failures for this weaver lifetime.

  Entries diverge from the blessed event-failure entry
  (`skein.api.events.alpha/recent-failures`) on two keys, because chime's
  failures carry no event context to describe them with:

  - `:kind` — `:notifier-missing`, `:process`, or `:rule`. The blessed entry has
    no counterpart; it discriminates on `:event/type`, which chime's failures do
    not have. Two of chime's three kinds are not throws at all, so the kind is
    the only thing that says what went wrong.
  - `:message` — present only when something threw, not `:exception/message`:
    a missing notifier and a non-zero notifier exit are failures without an
    exception to take a message from."
  []
  @(failure-log))

(defn reset-seen!
  "Clear per-weaver notification deduplication and batch-scan state."
  []
  (reset! (seen-notifications) #{})
  (reset! (scanned-batch-ids) [])
  {:seen 0})

(defn- validate-notifier! [notifier]
  (when-not (map? notifier)
    (fail! "Notifier binding must be a map" {:binding notifier}))
  (when-let [unknown (seq (remove #{:argv} (keys notifier)))]
    (fail! "Notifier binding contains unknown keys" {:unknown (vec unknown)}))
  (when-not (and (vector? (:argv notifier)) (seq (:argv notifier)) (every? non-blank-string? (:argv notifier)))
    (fail! "Notifier :argv must be a non-empty vector of non-blank strings" {:argv (:argv notifier)}))
  notifier)

(defn set-notifier!
  "Bind the local notifier command for this weaver lifetime.

  The binding is `{:argv [..]}`. Chime appends the notification title as the
  final argument and writes the body to stdin. Rebinding replaces the prior
  value; pass a valid binding after every weaver startup or config reload."
  [notifier]
  (reset! (notifier-binding) (validate-notifier! notifier))
  {:notifier @(notifier-binding)})

(defn notifier
  "Return the current notifier binding, or nil when none is bound."
  []
  @(notifier-binding))

(defn- validate-notification! [notification]
  (when-not (map? notification)
    (fail! "Notification must be a map" {:notification notification}))
  (when-let [unknown (seq (remove #{:title :body} (keys notification)))]
    (fail! "Notification contains unknown keys" {:unknown (vec unknown)}))
  (when-not (non-blank-string? (:title notification))
    (fail! "Notification :title must be a non-blank string" {:notification notification}))
  (when (and (contains? notification :body) (not (string? (:body notification))))
    (fail! "Notification :body must be a string when present" {:body (:body notification)}))
  notification)

(defn- process-thread! [notifier notification result]
  (let [argv (conj (:argv notifier) (:title notification))]
    (doto (Thread.
           (fn []
             (try
               (let [process (.start (ProcessBuilder. ^java.util.List argv))]
                 (with-open [writer (OutputStreamWriter. (.getOutputStream process) "UTF-8")]
                   (.write writer (str (or (:body notification) ""))))
                 (let [exit (.waitFor process)]
                   (swap! result assoc :exit-code exit)
                   (when-not (zero? exit)
                     (record-failure! {:kind :process
                                       :argv argv
                                       :exit-code exit
                                       :title (:title notification)}))))
               (catch Throwable t
                 (swap! result assoc :error (ex-message t))
                 (record-failure! {:kind :process
                                   :argv argv
                                   :title (:title notification)
                                   :message (ex-message t)
                                   :data (ex-data t)}))))
           (str "chime-notify-" (System/nanoTime)))
      (.setDaemon true)
      (.start))))

(defn notify!
  "Send one notification through the current binding.

  Returns an inspectable map immediately. Missing notifier is recorded as a loud
  failure instead of silently dropping the notification."
  [notification]
  (let [notification (validate-notification! notification)]
    (if-let [notifier @(notifier-binding)]
      (let [result (atom {:status :started
                          :argv (conj (:argv notifier) (:title notification))
                          :title (:title notification)})]
        (process-thread! notifier notification result)
        @result)
      (let [failure (record-failure! {:kind :notifier-missing
                                      :title (:title notification)
                                      :body (:body notification)})]
        {:status :failed :failure failure}))))

(defn- rule-name [name]
  (cond
    (keyword? name) name
    (symbol? name) (keyword (str name))
    (and (string? name) (not (str/blank? name))) (keyword name)
    :else (fail! "Rule name must be a keyword, symbol, or non-blank string" {:name name})))

(defn- resolve-rule-fn [fn-symbol]
  (when-not (and (symbol? fn-symbol) (namespace fn-symbol))
    (fail! "Rule fn must be a fully qualified symbol" {:fn fn-symbol}))
  (try
    (or (requiring-resolve fn-symbol)
        (fail! "Rule fn cannot be resolved" {:fn fn-symbol}))
    (catch java.io.FileNotFoundException e
      (fail! "Rule fn cannot be resolved" {:fn fn-symbol :message (ex-message e)}))))

(defn register!
  "Register or replace a notification rule.

  `fn-symbol` names a function receiving `{:event .. :strand ..}` and returning
  nil or `{:title .. :body ..}`. Currently matching strands become the rule's
  initial seen baseline, so durable conditions do not notify after registration
  even when they have never notified before. Mutations serialized after
  registration notify normally."
  [name fn-symbol]
  (let [rule-key (rule-name name)
        rule {:key rule-key :fn fn-symbol}
        registry (rule-registry)]
    (resolve-rule-fn fn-symbol)
    ;; Registration, event scans, and the pre-commit mutation barrier share this
    ;; monitor. Seed before publishing; a mutation cannot commit and enqueue its
    ;; event until the baseline and rule become visible together.
    (locking registry
      (baseline-rule! rule)
      (swap! registry assoc rule-key rule))
    {:key rule-key :fn fn-symbol}))

(defn rules
  "Return registered notification rules ordered by key."
  []
  (mapv second (sort-by key @(rule-registry))))

(defn unregister!
  "Unregister a notification rule by key."
  [name]
  (let [rule-key (rule-name name)
        registry (rule-registry)]
    (locking registry
      (when-not (contains? @registry rule-key)
        (fail! "Rule not found" {:rule rule-key :available (sort (keys @registry))}))
      (swap! registry dissoc rule-key)
      (swap! (seen-notifications)
             #(into #{} (remove (fn [[seen-rule-key _]] (= rule-key seen-rule-key))) %)))
    {:unregistered rule-key}))

(defn- affected-strands [_event]
  (weaver/list (rt)))

(defn- ready-id-set []
  (set (map :id (weaver/ready (rt)))))

;; batch/applied and its per-strand fanout share a :batch/id, and weave emits
;; batch/applied with no fanout, so scan-once-per-batch keeps both correctness
;; and cost bounded
(def ^:private scanned-batch-memory 64)

(defn- already-scanned-batch? [event]
  (when-let [batch-id (:batch/id event)]
    (let [[old _] (swap-vals! (scanned-batch-ids)
                              (fn [ids]
                                (if (some #(= batch-id %) ids)
                                  ids
                                  (vec (take-last scanned-batch-memory (conj ids batch-id))))))]
      (boolean (some #(= batch-id %) old)))))

;; :fn and :key are renamed on destructure: locals named `fn`/`key` shadow the
;; fn macro and clojure.core/key.
(defn- evaluate-rule [context strand {rule-key :key fn-sym :fn}]
  (let [rule-fn @(resolve-rule-fn fn-sym)]
    (try
      (when-let [notification (rule-fn (assoc context :strand strand))]
        (validate-notification! notification))
      (catch Throwable t
        (record-failure! {:kind :rule
                          :rule rule-key
                          :strand (:id strand)
                          :message (ex-message t)
                          :data (ex-data t)})
        nil))))

(defn- baseline-rule! [{rule-key :key :as rule}]
  (let [runtime (rt)]
    (binding [*runtime* runtime]
      (let [context {:event {:event/type :chime/rule-registered}
                     :ready-ids (ready-id-set)}
            matching-keys (into #{}
                                (keep (fn [strand]
                                        (when (evaluate-rule context strand rule)
                                          [rule-key (:id strand)])))
                                (affected-strands (:event context)))]
        (swap! (seen-notifications)
               (fn [seen]
                 (into matching-keys
                       (remove (fn [[seen-rule-key _]] (= rule-key seen-rule-key)))
                       seen)))))))

(defn- dispatch-rule! [context strand {rule-key :key :as rule}]
  (let [seen-key [rule-key (:id strand)]
        notification (evaluate-rule context strand rule)]
    (if notification
      ;; scan! serializes dispatch under the registry monitor. Keep the atomic
      ;; claim as a second guard so this invariant survives a future narrowing
      ;; of that lock without restoring check-then-act duplicate delivery.
      (let [[old _] (swap-vals! (seen-notifications) conj seen-key)]
        (when-not (contains? old seen-key)
          ;; keep the mark only when the notifier process actually started;
          ;; otherwise release the claim so a missing or failing notifier does
          ;; not permanently swallow the alert
          (when-not (= :started (:status (notify! notification)))
            (swap! (seen-notifications) disj seen-key))))
      ;; the condition no longer holds: re-arm so a later recurrence
      ;; (the rule stops matching, then matches again later) notifies again
      (swap! (seen-notifications) disj seen-key))))

(defn scan!
  "Evaluate registered rules against currently affected strands.

  Rules receive `{:event .. :strand .. :ready-ids #{..}}`; `:ready-ids` is
  computed once per scan. Batch events and their per-strand fanout share a
  `:batch/id`, and only the first event of a batch triggers a scan."
  ([event]
   (let [registry (rule-registry)]
     (locking registry
       (if (already-scanned-batch? event)
         {:scanned 0 :rules (count @registry) :skipped :chime/already-scanned}
         (let [runtime (rt)]
           (binding [*runtime* runtime]
             (let [strands (affected-strands event)
                   context {:event event :ready-ids (ready-id-set)}]
               (doseq [strand strands
                       rule (vals @registry)]
                 (dispatch-rule! context strand rule))
               {:scanned (count strands) :rules (count @registry)})))))))
  ([] (scan! {:event/type :chime/scan})))

(defn on-event
  "Weaver event handler: scan graph changes for attention notifications."
  [event]
  (scan! event))

(defn mutation-registration-barrier!
  "Serialize a pending graph mutation after any in-progress rule registration.

  Installed as a synchronous pre-commit hook. Its return value is ignored."
  [_context]
  (locking (rule-registry) nil)
  nil)

(defn install!
  "Install chime's mutation barrier and event handler into the active weaver.

  Chime ships no rules and no notifier: trusted config supplies rules with
  `register!` and a notifier with `set-notifier!`."
  []
  (let [runtime (rt)]
    (hooks/register! runtime :chime/registration-barrier mutation-hook-types
                     'skein.spools.chime/mutation-registration-barrier!
                     {:order Long/MAX_VALUE :spool "chime"})
    (events/register! runtime :chime/engine event-types
                      'skein.spools.chime/on-event
                      {:spool "chime"})
    {:installed true
     :namespace 'skein.spools.chime
     :handler :chime/engine
     :rules (mapv :key (rules))
     :notifier-bound? (boolean @(notifier-binding))}))
