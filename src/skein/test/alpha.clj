(ns skein.test.alpha
  "Blessed author-side clojure.test helpers for disposable weaver worlds.

  This namespace runs in the author's test JVM and orchestrates real weaver
  runtimes in isolated temporary workspaces: it writes requested config
  fixtures (`config.json`, `spools.edn`, `init.clj`, arbitrary workspace
  files), starts an unpublished in-process weaver runtime with explicit
  storage selection, exposes an orchestration context map, and stops/cleans up
  afterwards. Weaver-side behavior is exercised through `repl!`, which
  evaluates weaver-routed forms over the runtime's real nREPL transport.

  Deliberately out of scope: strand/query wrappers, assertion DSLs, spool
  activation wrappers, CLI subprocess helpers, and any use of the user's
  default config/data/state workspaces. Generated worlds are isolated and
  disposable by default."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [skein.core.client :as client]
            [skein.core.weaver.config :as config]
            [skein.core.weaver.runtime :as runtime])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *weaver-world*
  "Context map for the current `weaver-world-fixture` weaver world, or nil."
  nil)

(def ^:private default-timeout-ms 10000)

;; Unix domain socket paths have a small platform limit (~104 bytes on macOS),
;; so generated worlds live under a short /tmp root rather than java.io.tmpdir.
(def ^:private temp-parent "/tmp")

(defn- create-temp-root []
  (-> (Files/createTempDirectory (Path/of temp-parent (make-array String 0))
                                 "skw"
                                 (make-array FileAttribute 0))
      .toFile
      .getCanonicalFile))

(defn- require-inside-root! [root file relative-path]
  (when-not (str/starts-with? (.getCanonicalPath file)
                              (str (.getCanonicalPath root) java.io.File/separator))
    (throw (ex-info "Workspace fixture files must stay inside the generated workspace root"
                    {:root (.getPath root) :path relative-path})))
  file)

(defn- write-fixture! [root relative-path content]
  (when-not (string? content)
    (throw (ex-info "Workspace fixture content must be a string"
                    {:path relative-path :content content})))
  (let [file (require-inside-root! root (io/file root relative-path) relative-path)]
    (io/make-parents file)
    (spit file content)
    file))

(defn- write-fixtures! [root {:keys [config-json spools-edn init files]}]
  (when (some? config-json)
    (when-not (string? config-json)
      (throw (ex-info ":config-json must be a string of JSON text" {:config-json config-json})))
    (write-fixture! root "config.json" config-json))
  (when (some? spools-edn)
    (when-not (map? spools-edn)
      (throw (ex-info ":spools-edn must be an EDN map, e.g. {:spools {...}}" {:spools-edn spools-edn})))
    (write-fixture! root "spools.edn" (pr-str spools-edn)))
  (when (some? init)
    (when-not (string? init)
      (throw (ex-info ":init must be a string of Clojure source for init.clj" {:init init})))
    (write-fixture! root "init.clj" init))
  (when (some? files)
    (when-not (map? files)
      (throw (ex-info ":files must be a map of workspace-relative path to string content" {:files files})))
    (doseq [[relative-path content] files]
      (write-fixture! root relative-path content))))

(defn- source-checkout
  "Best-effort path of the Skein source checkout on this test JVM's classpath."
  []
  (when-let [url (io/resource "skein/test/alpha.clj")]
    (when (= "file" (.getProtocol url))
      ;; src/skein/test/alpha.clj -> checkout root is four parents up
      (-> (io/file (.toURI url))
          .getParentFile .getParentFile .getParentFile .getParentFile
          .getCanonicalPath))))

(defn- delete-tree! [root]
  (doseq [file (reverse (file-seq root))]
    (when (and (.exists file) (not (.delete file)))
      (throw (ex-info "Failed to delete weaver world file"
                      {:root (.getPath root) :file (.getPath file)})))))

(defn- stop-and-clean! [rt root delete?]
  (runtime/stop! rt)
  (when delete?
    (delete-tree! root))
  nil)

(def ^:private known-option-keys
  #{:storage :root :delete? :name :timeout-ms :source :config-json :spools-edn :init :files})

(defn run-with-weaver-world
  "Start a disposable weaver world from `opts`, call `f` with its context map,
  then stop the weaver and clean up. Functional core of `with-weaver-world`.

  Options: `:storage` (`:sqlite-file` default, or `:sqlite-memory`), `:root`
  (explicit workspace root; default short temp dir), `:delete?` (remove the
  root afterwards; default true, always false for an explicit `:root`),
  `:name` (weaver name), `:timeout-ms` (`repl!` default), `:source` (source
  checkout override), and the fixture options `:config-json`, `:spools-edn`,
  `:init`, `:files`.

  The context map exposes orchestration facts only: `:config-dir`,
  `:state-dir`, `:data-dir`, `:db-path` (file storage only), `:storage`,
  `:source`, `:runtime`, `:metadata`, and `:timeout-ms`."
  [opts f]
  (when-let [unknown (seq (remove known-option-keys (keys opts)))]
    (throw (ex-info "Unknown weaver world options" {:keys (vec unknown)})))
  (let [explicit-root (some-> (:root opts) io/file .getCanonicalFile)
        root (or explicit-root (create-temp-root))
        delete? (if (contains? opts :delete?)
                  (boolean (:delete? opts))
                  (nil? explicit-root))
        storage (:storage opts :sqlite-file)]
    (try
      (write-fixtures! root opts)
      (let [world (config/world (.getPath root))
            rt (runtime/start! nil (cond-> {:world world :publish? false :storage storage}
                                     (:name opts) (assoc :name (:name opts))))
            ctx (cond-> {:config-dir (:config-dir world)
                         :state-dir (:state-dir world)
                         :data-dir (:data-dir world)
                         :storage storage
                         :source (or (:source opts) (source-checkout))
                         :runtime rt
                         :metadata (:metadata rt)
                         :timeout-ms (:timeout-ms opts default-timeout-ms)}
                  (= :sqlite-file storage) (assoc :db-path (get-in rt [:metadata :canonical-db-path])))]
        (try
          (let [result (f ctx)]
            (stop-and-clean! rt root delete?)
            result)
          (catch Throwable t
            (try
              (stop-and-clean! rt root delete?)
              (catch Throwable cleanup-failure
                (.addSuppressed t cleanup-failure)))
            (throw t))))
      (catch Throwable t
        ;; Startup/fixture failures never leave a generated root behind.
        (when (and delete? (.exists root))
          (try
            (delete-tree! root)
            (catch Throwable cleanup-failure
              (.addSuppressed t cleanup-failure))))
        (throw t)))))

(defmacro with-weaver-world
  "Run `body` with `ctx-sym` bound to a disposable weaver world context.

  (with-weaver-world [ctx {:spools-edn {:spools {}}}]
    (is (= [] (repl! ctx \"(skein.api.weaver.alpha/list (skein.api.current.alpha/runtime))\"))))"
  [[ctx-sym opts] & body]
  `(run-with-weaver-world ~opts (fn [~ctx-sym] ~@body)))

(defn weaver-world-fixture
  "Return a clojure.test fixture that binds *weaver-world* to a fresh
  disposable weaver world context for each wrapped test."
  [opts]
  (fn [test-fn]
    (run-with-weaver-world opts (fn [ctx]
                                  (binding [*weaver-world* ctx]
                                    (test-fn))))))

(defn repl!
  "Evaluate a weaver-routed form against ctx's weaver world and return data.

  `form` is a string of Clojure source or a form to render with pr-str. It
  evaluates in the weaver runtime over its real nREPL transport with the
  runtime ambiently bound, so `(skein.api.current.alpha/runtime)` resolves to
  the test weaver. Results must be EDN-readable; weaver-side and transport
  failures throw ExceptionInfo."
  [ctx form]
  (client/eval-in-world (:config-dir ctx)
                        {:timeout-ms (:timeout-ms ctx default-timeout-ms)}
                        (if (string? form) form (pr-str form))))
