(ns skein.repl
  (:require [clojure.main :as main]
            [clojure.string :as str]
            [skein.client :as client]
            [skein.weaver.config :as daemon-config]
            [skein.patterns.alpha :as patterns-alpha]
            [skein.query :as query]))

(def ^:private no-connection ::no-connection)
(def ^:private default-world ::default-world)
(defonce ^:private active-config-dir (atom no-connection))

(defn connected-config-dir []
  (case @active-config-dir
    ::no-connection (throw (ex-info "No Skein weaver world is connected. Start a connected helper REPL with `strand weaver repl`, or call (connect!) / (connect! \"/path/to/config-dir\") before using skein.repl helpers."
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

(declare call-daemon)

(defn- daemon [op & args]
  (let [dir (config-dir)]
    (call-daemon #(apply client/call-world dir {} op args))))

(defn init! []
  (daemon :init))

(defn strand!
  ([title]
   (strand! title {} {}))
  ([title attributes]
   (strand! title attributes {}))
  ([title attributes lifecycle]
   (daemon :add (merge {:title title :attributes attributes} lifecycle))))

(defn update!
  ([id patch]
   (daemon :update id patch)))

(defn supersede! [old-id replacement-id]
  (daemon :supersede old-id replacement-id))

(defn strand [id]
  (daemon :show id))

(defn declare-acyclic-relation! [relation]
  (daemon :declare-acyclic-relation! relation))

(defn acyclic-relations []
  (daemon :acyclic-relations))

(defn burn!
  ([id]
   (daemon :burn-by-id id))
  ([id & ids]
   (daemon :burn-by-ids (vec (cons id ids)))))

(defn burn-by-ids! [ids]
  (daemon :burn-by-ids (vec ids)))

(defn- call-daemon [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (if-let [weaver-message (:weaver-message (ex-data e))]
        (throw (ex-info weaver-message (:weaver-data (ex-data e))))
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

(defn strands
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

(defn defpattern!
  ([pattern-name fn-sym input-spec]
   (patterns-alpha/register-pattern! pattern-name fn-sym input-spec))
  ([pattern-name doc fn-sym input-spec]
   (patterns-alpha/register-pattern! pattern-name doc fn-sym input-spec)))

(defn patterns []
  (patterns-alpha/patterns))

(defn pattern [pattern-name]
  (patterns-alpha/pattern pattern-name))

(defn pattern-explain [pattern-name]
  (patterns-alpha/explain pattern-name))

(defn weave! [pattern-name input]
  (patterns-alpha/weave! pattern-name input))

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
                                (throw (ex-info "Usage: skein.repl [--stdin] [config-dir]" {:args args})))
                            (throw (ex-info "Usage: skein.repl [--stdin] [config-dir]" {:args args})))]
    (connect! config-dir)
    (binding [*ns* (the-ns 'skein.repl)]
      (case mode
        :stdin (try
                 (eval-stdin!)
                 (catch Throwable t
                   (binding [*out* *err*]
                     (println (or (ex-message t) (str t))))
                   (System/exit 1)))
        :repl (main/repl :prompt #(print "skein=> "))))))
