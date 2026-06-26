(ns todo.repl
  (:require [clojure.main :as main]
            [todo.client :as client]
            [todo.daemon.config :as daemon-config]
            [todo.query :as query]))

(def ^:private no-connection ::no-connection)
(def ^:private default-world ::default-world)
(defonce ^:private active-config-dir (atom no-connection))

(defn connected-config-dir []
  (case @active-config-dir
    ::no-connection (throw (ex-info "No todo daemon world is connected. Start a connected helper REPL with `todo daemon repl`, or call (connect!) / (connect! \"/path/to/config-dir\") before using todo.repl helpers."
                                   {:helper 'connect!}))
    ::default-world nil
    @active-config-dir))

(defn- config-dir []
  (connected-config-dir))

(defn connect!
  ([]
   (connect! nil))
  ([config-dir]
   (reset! active-config-dir no-connection)
   (when (and config-dir (.isFile (java.io.File. config-dir)))
     (throw (ex-info "connect! expects a daemon config directory, not a database file" {:config-dir config-dir})))
   (let [world (daemon-config/world config-dir)]
     (if config-dir
       (do
         (client/status-world (:config-dir world))
         (reset! active-config-dir (:config-dir world)))
       (do
         (client/status-world nil)
         (reset! active-config-dir default-world)))
     (:config-dir world))))

(defn- daemon [op & args]
  (apply client/call-world (config-dir) {} op args))

(defn init! []
  (daemon :init))

(defn task!
  ([title]
   (task! title {}))
  ([title attributes]
   (daemon :add {:title title :attributes attributes}))
  ([title status attributes]
   (daemon :add {:title title :status status :attributes attributes})))

(defn update!
  ([id patch]
   (daemon :update id patch)))

(defn task [id]
  (daemon :show id))

(defn- call-daemon [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (if-let [daemon-message (:daemon-message (ex-data e))]
        (throw (ex-info daemon-message (:daemon-data (ex-data e))))
        (throw e)))))

(defn defquery! [query-name query-def]
  (let [dir (config-dir)]
    (call-daemon #(client/call-world dir {} :register-query query-name query-def))))

(defn load-queries! [path]
  (let [dir (config-dir)
        registry (query/read-edn-file path)]
    (when-not (map? registry)
      (throw (ex-info "Query file must contain one EDN map of query names to query definitions" {:path path})))
    (call-daemon #(client/call-world dir {} :load-queries registry))))

(defn queries []
  (let [dir (config-dir)]
    (call-daemon #(client/call-world dir {} :queries))))

(defn- named-query? [query-or-def]
  (or (symbol? query-or-def) (keyword? query-or-def)))

(defn- run-query [dir query-or-def params ad-hoc named]
  (call-daemon #(if (named-query? query-or-def)
                  (client/call-world dir {} named query-or-def params)
                  (client/call-world dir {} ad-hoc query-or-def params))))

(defn query
  ([query-or-def]
   (query query-or-def {}))
  ([query-or-def params]
   (run-query (config-dir) query-or-def params :list :list-query)))

(defn tasks
  ([]
   (daemon :list))
  ([query-or-def]
   (query query-or-def))
  ([query-or-def params]
   (query query-or-def params)))

(defn ready
  ([]
   (daemon :ready))
  ([query-or-def]
   (ready query-or-def {}))
  ([query-or-def params]
   (run-query (config-dir) query-or-def params :ready :ready-query)))

(defn- eval-stdin! []
  (let [reader (java.io.PushbackReader. *in*)
        eof (Object.)]
    (loop []
      (let [form (read reader false eof)]
        (when-not (identical? eof form)
          (prn (eval form))
          (recur))))))

(defn -main [& args]
  (let [[mode config-dir] (case (count args)
                            0 [:repl nil]
                            1 (if (= "--stdin" (first args))
                                [:stdin nil]
                                [:repl (first args)])
                            2 (if (= "--stdin" (first args))
                                [:stdin (second args)]
                                (throw (ex-info "Usage: todo.repl [--stdin] [config-dir]" {:args args})))
                            (throw (ex-info "Usage: todo.repl [--stdin] [config-dir]" {:args args})))]
    (connect! config-dir)
    (binding [*ns* (the-ns 'todo.repl)]
      (require '[atom.libs.alpha :as libs])
      (case mode
        :stdin (try
                 (eval-stdin!)
                 (catch Throwable t
                   (binding [*out* *err*]
                     (println (or (ex-message t) (str t))))
                   (System/exit 1)))
        :repl (main/repl :prompt #(print "todo=> "))))))
