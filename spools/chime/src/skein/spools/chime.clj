(ns skein.spools.chime
  "Human-attention notification bridge for Skein graph events.

  Chime watches strand mutations, evaluates small userland rules, and sends
  attention notices through a workspace-bound local notifier command. It owns
  only weaver-lifetime runtime state and composes the public weaver/event API."
  (:require [clojure.string :as str]
            [skein.api.runtime.alpha :as runtime-alpha]
            [skein.api.weaver.alpha :as api])
  (:import [java.io OutputStreamWriter]
           [java.time Instant]))

(def ^:private event-types
  #{:strand/added :strand/updated :batch/applied :strand/burned :strand/superseded})

(defonce ^:private notifier-binding (atom nil))
(defonce ^:private rule-registry (atom {}))
(defonce ^:private seen-notifications (atom #{}))
(defonce ^:private failure-log (atom []))

(def ^:dynamic *runtime*
  "Runtime captured for asynchronous notifier worker threads."
  nil)

(defn- rt []
  (or *runtime* (runtime-alpha/current-runtime)))

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- now [] (str (Instant/now)))

(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- attr
  "Read attribute `k` from strand, accepting keyword or string keys."
  [strand k]
  (let [attrs (:attributes strand)
        string-key (if (keyword? k) (subs (str k) 1) (str k))
        keyword-key (if (keyword? k) k (keyword k))]
    (if (contains? attrs keyword-key)
      (get attrs keyword-key)
      (get attrs string-key))))

(defn- record-failure! [entry]
  (let [full (assoc entry :at (now))]
    (swap! failure-log #(->> (conj (vec %) full) (take-last 100) vec))
    full))

(defn failures
  "Return recorded notifier, process, and rule failures for this weaver lifetime."
  []
  @failure-log)

(defn reset-seen!
  "Clear per-weaver notification deduplication state."
  []
  (reset! seen-notifications #{})
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
  (reset! notifier-binding (validate-notifier! binding))
  {:notifier @notifier-binding})

(defn notifier
  "Return the current notifier binding, or nil when none is bound."
  []
  @notifier-binding)

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
    (if-let [binding @notifier-binding]
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
    (swap! rule-registry assoc key {:name key :fn fn-symbol})
    {:rule key :fn fn-symbol}))

(defn rules
  "Return registered notification rules ordered by name."
  []
  (mapv second (sort-by key @rule-registry)))

(defn remove-rule!
  "Remove a registered notification rule by name."
  [name]
  (let [key (rule-name name)]
    (when-not (contains? @rule-registry key)
      (fail! "Rule not found" {:rule key :available (sort (keys @rule-registry))}))
    (swap! rule-registry dissoc key)
    {:removed key}))

(defn- affected-strands [_event]
  (api/list (rt)))

(defn- ready-id-set []
  (set (map :id (api/ready (rt)))))

(defn- checkpoint-hitl? [strand]
  (let [value (attr strand :workflow/hitl)]
    (and (= "checkpoint" (attr strand :workflow/role))
         (or (= true value) (= "true" value)))))

(defn hitl-checkpoint-ready
  "Return a notification when a HITL checkpoint strand is currently ready."
  [{:keys [strand]}]
  (when (and strand
             (= "active" (:state strand))
             (checkpoint-hitl? strand)
             (contains? (ready-id-set) (:id strand)))
    {:title (str "HITL checkpoint ready: " (:title strand))
     :body (str "Checkpoint " (:id strand) " is ready for human attention.")}))

(defn agent-failure
  "Return a notification when a shuttle run has failed or exhausted."
  [{:keys [strand]}]
  (when (contains? #{"failed" "exhausted"} (attr strand :shuttle/phase))
    {:title (str "Agent run " (attr strand :shuttle/phase) ": " (:title strand))
     :body (str "Strand " (:id strand) " entered shuttle/phase " (attr strand :shuttle/phase)
                (when-let [error (attr strand :shuttle/error)]
                  (str "\n\n" error)))}))

(defn treadle-error
  "Return a notification when a strand carries a treadle/error attribute."
  [{:keys [strand]}]
  (when-let [error (attr strand :treadle/error)]
    {:title (str "Treadle error: " (:title strand))
     :body (str "Strand " (:id strand) " has treadle/error:\n\n" error)}))

(defn- install-default-rules! []
  (defrule! :hitl-checkpoint-ready 'skein.spools.chime/hitl-checkpoint-ready)
  (defrule! :agent-failure 'skein.spools.chime/agent-failure)
  (defrule! :treadle-error 'skein.spools.chime/treadle-error))

(defn- dispatch-rule! [event strand {:keys [name fn]}]
  (let [seen-key [name (:id strand)]]
    (when-not (contains? @seen-notifications seen-key)
      (let [rule-fn @(resolve-rule-fn fn)
            notification (try
                           (when-let [notification (rule-fn {:event event :strand strand})]
                             (validate-notification! notification))
                           (catch Throwable t
                             (record-failure! {:kind :rule
                                               :rule name
                                               :strand (:id strand)
                                               :message (ex-message t)
                                               :data (ex-data t)})
                             nil))]
        (when notification
          (swap! seen-notifications conj seen-key)
          (notify! notification))))))

(defn scan!
  "Evaluate registered rules against currently affected strands."
  ([event]
   (let [runtime (rt)]
     (binding [*runtime* runtime]
       (let [strands (affected-strands event)]
         (doseq [strand strands
                 rule (vals @rule-registry)]
           (dispatch-rule! event strand rule))
         {:scanned (count strands) :rules (count @rule-registry)}))))
  ([] (scan! {:event/type :manual/scan})))

(defn on-event
  "Weaver event handler: scan graph changes for attention notifications."
  [event]
  (scan! event))

(defn install!
  "Install chime's event handler and default rules into the active weaver."
  []
  (let [runtime (rt)]
    (install-default-rules!)
    (api/register-event-handler! runtime :chime/engine event-types
                                 'skein.spools.chime/on-event
                                 {:spool "chime"})
    {:installed true
     :namespace 'skein.spools.chime
     :handler :chime/engine
     :rules (mapv :name (rules))
     :notifier-bound? (boolean @notifier-binding)}))
