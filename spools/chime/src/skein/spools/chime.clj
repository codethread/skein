(ns skein.spools.chime
  "Human-attention notification bridge for Skein graph events.

  Chime watches strand mutations, evaluates small userland rules, and sends
  attention notices through a workspace-bound local notifier command. It owns
  only weaver-lifetime runtime state and composes the public weaver/event API."
  (:require [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.weaver.alpha :as api]
            [skein.api.runtime.alpha :as runtime]
            [skein.spools.util :refer [fail!]])
  (:import [java.io OutputStreamWriter]
           [java.time Instant]))

(def ^:private event-types
  #{:strand/added :strand/updated :batch/applied :strand/burned :strand/superseded})

(declare rt)

(defn- state []
  (runtime/spool-state (rt) ::state
                       #(hash-map :notifier-binding (atom nil)
                                  :rule-registry (atom {})
                                  :seen-notifications (atom #{})
                                  :failure-log (atom [])
                                  :scanned-batch-ids (atom []))))

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
  (let [full (assoc entry :at (now))]
    (swap! (failure-log) #(->> (conj (vec %) full) (take-last 100) vec))
    full))

(defn failures
  "Return recorded notifier, process, and rule failures for this weaver lifetime."
  []
  @(failure-log))

(defn reset-seen!
  "Clear per-weaver notification deduplication and batch-scan state."
  []
  (reset! (seen-notifications) #{})
  (reset! (scanned-batch-ids) [])
  {:seen 0})

(defn- validate-notifier! [binding]
  (when-not (map? binding)
    (fail! "Notifier binding must be a map" {:binding binding}))
  (when-let [unknown (seq (remove #{:argv} (keys binding)))]
    (fail! "Notifier binding contains unknown keys" {:unknown (vec unknown)}))
  (when-not (and (vector? (:argv binding)) (seq (:argv binding)) (every? non-blank-string? (:argv binding)))
    (fail! "Notifier :argv must be a non-empty vector of non-blank strings" {:argv (:argv binding)}))
  binding)

(defn set-notifier!
  "Bind the local notifier command for this weaver lifetime.

  The binding is `{:argv [..]}`. Chime appends the notification title as the
  final argument and writes the body to stdin. Rebinding replaces the prior
  value; pass a valid binding after every weaver startup or config reload."
  [binding]
  (reset! (notifier-binding) (validate-notifier! binding))
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

(defn- process-thread! [binding notification result]
  (let [argv (conj (:argv binding) (:title notification))]
    (doto (Thread.
           (fn []
             (try
               (let [process (.start (ProcessBuilder. ^java.util.List argv))]
                 (with-open [writer (OutputStreamWriter. (.getOutputStream process) "UTF-8")]
                   (.write writer (or (:body notification) "")))
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
    (if-let [binding @(notifier-binding)]
      (let [result (atom {:status :started
                          :argv (conj (:argv binding) (:title notification))
                          :title (:title notification)})]
        (process-thread! binding notification result)
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

(defn defrule!
  "Register or replace a notification rule.

  `fn-symbol` names a function receiving `{:event .. :strand ..}` and returning
  nil or `{:title .. :body ..}`."
  [name fn-symbol]
  (let [key (rule-name name)]
    (resolve-rule-fn fn-symbol)
    (swap! (rule-registry) assoc key {:name key :fn fn-symbol})
    {:rule key :fn fn-symbol}))

(defn rules
  "Return registered notification rules ordered by name."
  []
  (mapv second (sort-by key @(rule-registry))))

(defn remove-rule!
  "Remove a registered notification rule by name."
  [name]
  (let [key (rule-name name)]
    (when-not (contains? @(rule-registry) key)
      (fail! "Rule not found" {:rule key :available (sort (keys @(rule-registry)))}))
    (swap! (rule-registry) dissoc key)
    {:removed key}))

(defn- affected-strands [_event]
  (api/list (rt)))

(defn- ready-id-set []
  (set (map :id (api/ready (rt)))))

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

;; :fn is renamed on destructure: a local named `fn` shadows the fn macro.
(defn- dispatch-rule! [context strand {:keys [name] fn-sym :fn}]
  (let [seen-key [name (:id strand)]
        rule-fn @(resolve-rule-fn fn-sym)
        notification (try
                       (when-let [notification (rule-fn (assoc context :strand strand))]
                         (validate-notification! notification))
                       (catch Throwable t
                         (record-failure! {:kind :rule
                                           :rule name
                                           :strand (:id strand)
                                           :message (ex-message t)
                                           :data (ex-data t)})
                         nil))]
    (if notification
      ;; claim the key atomically before notifying: a plain check-then-act
      ;; contains? lets a concurrent event-worker scan and an explicit scan!
      ;; both pass the check and double-notify. Only the thread that finds the
      ;; key absent in the pre-swap value owns the notification.
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
   (if (already-scanned-batch? event)
     {:scanned 0 :rules (count @(rule-registry)) :skipped :batch/already-scanned}
     (let [runtime (rt)]
       (binding [*runtime* runtime]
         (let [strands (affected-strands event)
               context {:event event :ready-ids (ready-id-set)}]
           (doseq [strand strands
                   rule (vals @(rule-registry))]
             (dispatch-rule! context strand rule))
           {:scanned (count strands) :rules (count @(rule-registry))})))))
  ([] (scan! {:event/type :manual/scan})))

(defn on-event
  "Weaver event handler: scan graph changes for attention notifications."
  [event]
  (scan! event))

(defn install!
  "Install chime's event handler into the active weaver.

  Chime ships no rules and no notifier: trusted config supplies rules with
  `defrule!` and a notifier with `set-notifier!`."
  []
  (let [runtime (rt)]
    (api/register-event-handler! runtime :chime/engine event-types
                                 'skein.spools.chime/on-event
                                 {:spool "chime"})
    {:installed true
     :namespace 'skein.spools.chime
     :handler :chime/engine
     :rules (mapv :name (rules))
     :notifier-bound? (boolean @(notifier-binding))}))
