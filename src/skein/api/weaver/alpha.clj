(ns skein.api.weaver.alpha
  "Trusted in-process API for manipulating strands and weaver runtime registries."
  (:refer-clojure :exclude [list update use])
  (:require [clojure.java.io :as io]
            [clojure.repl.deps :as repl-deps]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [skein.api.cli.alpha :as cli]
            [skein.core.db :as db]
            [skein.core.weaver.runtime :as runtime]
            [skein.core.weaver.scheduler :as scheduler]
            [skein.core.query :as query])
  (:import [java.time Instant]
           [java.util UUID]
           [java.nio.file FileSystemException FileVisitResult Files LinkOption SimpleFileVisitor StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(defn- normalize-row
  "Decode JSON-backed row fields returned by persistence."
  [row]
  (cond-> row
    (string? (:attributes row)) (clojure.core/update :attributes db/<-json)))

(defn- normalize
  "Recursively decode persistence-shaped rows into Clojure data."
  [result]
  (cond
    (map? result) (into {} (map (fn [[k v]] [k (normalize v)])) (normalize-row result))
    (sequential? result) (mapv normalize result)
    :else result))

(declare enqueue-event! register-built-in-ops! runtime? apply-edges! op-detail)

(defn- ds [runtime]
  (:datasource runtime))

(defn- query-registry [runtime]
  (:query-registry runtime))

(defn- view-registry [runtime]
  (:view-registry runtime))

(defn- pattern-registry [runtime]
  (:pattern-registry runtime))

(defn- op-registry [runtime]
  (:op-registry runtime))

(defn- hook-registry [runtime]
  (:hook-registry runtime))

(defn- approved-spool-sync-state [runtime]
  (:approved-spool-sync-state runtime))

(defn- module-use-state [runtime]
  (:module-use-state runtime))

(defn- event-system [runtime]
  (:event-system runtime))

(defn- with-spool-classloader [runtime f]
  (runtime/with-runtime-and-spool-classloader runtime f))

(defn- config-dir [runtime]
  (get-in runtime [:metadata :config-dir]))

(defn- spools-file [runtime name]
  (io/file (config-dir runtime) name))

(defn- expand-user-home [path]
  (cond
    (= "~" path) (System/getProperty "user.home")
    (str/starts-with? path "~/") (str (System/getProperty "user.home") (subs path 1))
    :else path))

(defn- canonical-root [runtime path]
  (let [expanded-path (expand-user-home path)
        file (io/file expanded-path)
        resolved (if (.isAbsolute file)
                   file
                   (io/file (config-dir runtime) expanded-path))]
    (.getCanonicalPath resolved)))

(defn- cache-base
  "Return Skein's cache base for git-backed spool materialization."
  []
  (io/file (let [xdg-cache-home (System/getenv "XDG_CACHE_HOME")]
             (if (and (string? xdg-cache-home) (not (str/blank? xdg-cache-home)))
               xdg-cache-home
               (str (System/getProperty "user.home") java.io.File/separator ".cache")))))

(def ^:private local-spool-keys #{:local/root})
(def ^:private git-spool-keys #{:git/url :git/sha :git/tag :deps/root})
(def ^:private approved-spool-keys (into local-spool-keys git-spool-keys))
(def ^:private git-sha-pattern #"[0-9a-f]{40}")
(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- deps-root-segments [deps-root]
  (str/split deps-root #"/"))

(defn- relative-deps-root? [deps-root]
  (and (non-blank-string? deps-root)
       (not (.isAbsolute (io/file deps-root)))
       (not (str/starts-with? deps-root "~"))
       (not (some #{".."} (deps-root-segments deps-root)))))

(defn- validate-approved-spool-entry! [source lib entry]
  (when-not (symbol? lib)
    (throw (ex-info "Spool coordinate must be a symbol" (assoc source :lib lib))))
  (when-not (map? entry)
    (throw (ex-info "Spool entry must be a map" (assoc source :lib lib :entry entry))))
  (when-let [unknown (seq (remove approved-spool-keys (keys entry)))]
    (throw (ex-info "Spool entry contains unknown keys" (assoc source :lib lib :keys (vec unknown)))))
  (let [local? (contains? entry :local/root)
        git? (some #(contains? entry %) git-spool-keys)]
    (when (and (not local?) (not git?))
      (throw (ex-info "Spool entry requires non-blank string :local/root"
                      (assoc source :lib lib :local/root (:local/root entry) :entry entry))))
    (when (and local? git?)
      (throw (ex-info "Spool entry requires exactly one coordinate kind"
                      (assoc source :lib lib :entry entry))))
    (if local?
      (when-not (and (= local-spool-keys (set (keys entry)))
                     (non-blank-string? (:local/root entry)))
        (throw (ex-info "Spool entry requires non-blank string :local/root"
                        (assoc source :lib lib :local/root (:local/root entry) :entry entry))))
      (do
        (when-not (non-blank-string? (:git/url entry))
          (throw (ex-info "Git spool entry requires non-blank string :git/url"
                          (assoc source :lib lib :git/url (:git/url entry)))))
        (when-not (and (string? (:git/sha entry)) (re-matches git-sha-pattern (:git/sha entry)))
          (throw (ex-info "Git spool entry requires 40 lowercase hex characters :git/sha"
                          (assoc source :lib lib :git/sha (:git/sha entry)))))
        (when (and (contains? entry :git/tag) (not (non-blank-string? (:git/tag entry))))
          (throw (ex-info "Git spool entry :git/tag must be a non-blank string"
                          (assoc source :lib lib :git/tag (:git/tag entry)))))
        (when (and (contains? entry :deps/root) (not (relative-deps-root? (:deps/root entry))))
          (throw (ex-info "Git spool entry :deps/root must be a relative path with no ~ or .. segments"
                          (assoc source :lib lib :deps/root (:deps/root entry)))))))))

(defn- normalize-approved-spools-file
  "Validate one approved-spool config file and resolve roots for this runtime."
  [runtime name source config]
  (when-not (map? config)
    (throw (ex-info (str name " must contain a map") (assoc source :config config))))
  (when-let [unknown (seq (remove #{:spools} (keys config)))]
    (throw (ex-info (str name " contains unknown top-level keys") (assoc source :keys (vec unknown)))))
  (when-not (map? (:spools config))
    (throw (ex-info (str name " requires :spools map") (assoc source :spools (:spools config)))))
  {:spools (into {}
               (map (fn [[lib entry]]
                      (validate-approved-spool-entry! source lib entry)
                      (if (contains? entry :local/root)
                        [lib {:kind :local
                              :local/root (:local/root entry)
                              :root (canonical-root runtime (:local/root entry))
                              :source source}]
                        (let [cache-root (io/file (cache-base) "skein" "spools" (:git/sha entry))
                              root (cond-> cache-root
                                     (:deps/root entry) (io/file (:deps/root entry)))]
                          [lib (cond-> {:kind :git
                                        :git/url (:git/url entry)
                                        :git/sha (:git/sha entry)
                                        :root (.getPath root)
                                        :source source}
                                 (contains? entry :git/tag) (assoc :git/tag (:git/tag entry))
                                 (contains? entry :deps/root) (assoc :deps/root (:deps/root entry)))]))))
               (:spools config))})

(defn- approved-spools-file [runtime name kind]
  (let [file (spools-file runtime name)
        source {:kind kind
                :file (.getPath file)}]
    (cond
      (and (not (.exists file))
           (not (java.nio.file.Files/isSymbolicLink (.toPath file))))
      {:spools {}}

      (not (.isFile file))
      (throw (ex-info (str name " is malformed or unreadable") source))

      :else
      (normalize-approved-spools-file
       runtime
       name
       source
       (try
         (query/read-edn-file file)
         (catch Throwable t
           (throw (ex-info (str name " is malformed or unreadable") source t))))))))

(defn- legacy-config-present? [file]
  (or (.exists file)
      (java.nio.file.Files/isSymbolicLink (.toPath file))))

(defn- reject-legacy-spool-config! [runtime]
  (let [legacy-files (filter #(legacy-config-present? (spools-file runtime %)) ["libs.edn" "libs.local.edn"])]
    (when (seq legacy-files)
      (throw (ex-info "Legacy runtime library config files are no longer supported; rename libs.edn/libs.local.edn to spools.edn/spools.local.edn and change top-level :libs to :spools"
                      {:legacy-files (vec legacy-files)
                       :config-dir (config-dir runtime)})))))

(defn approved-spools
  "Read and validate the effective runtime spool allowlist.

  The effective allowlist is `spools.edn` overlaid by `spools.local.edn`; local
  entries replace shared entries with the same coordinate. Missing files
  contribute no spools, while malformed present files fail loudly."
  [runtime]
  (reject-legacy-spool-config! runtime)
  {:spools (merge (:spools (approved-spools-file runtime "spools.edn" :shared))
                  (:spools (approved-spools-file runtime "spools.local.edn" :local)))})

(defn- spool-source-fields [entry]
  (case (:kind entry)
    :local (select-keys entry [:local/root])
    :git (select-keys entry [:git/url :git/sha :git/tag :deps/root])))

(defn- sync-result-base [lib entry]
  (merge {:lib lib
          :kind (:kind entry)
          :root (:root entry)
          :source (:source entry)}
         (spool-source-fields entry)))

(defn- sync-failed [lib entry reason data]
  [lib (merge (sync-result-base lib entry)
              {:status :failed
               :reason reason}
              data)])

(defn- root-paths [root]
  (let [deps-file (io/file root "deps.edn")]
    (when-not (.isFile deps-file)
      (throw (ex-info "Spool root must contain deps.edn" {:root root})))
    (let [deps (query/read-edn-file deps-file)
          paths (or (:paths deps) ["src"])
          ;; Approval covers the root only; a :paths entry resolving outside it
          ;; (via .. or a symlink) would load code the user never consented to.
          canonical-root (.getCanonicalPath (io/file root))
          root-prefix (str canonical-root java.io.File/separator)]
      (when-not (and (vector? paths) (every? string? paths))
        (throw (ex-info "Spool root deps.edn :paths must be a vector of strings" {:root root :paths paths})))
      (mapv (fn [path]
              (let [resolved (.getCanonicalFile (io/file root path))]
                (when-not (or (= (str resolved) canonical-root)
                              (str/starts-with? (str resolved) root-prefix))
                  (throw (ex-info "Spool root deps.edn :paths must stay inside the spool root"
                                  {:root root :path path :resolved (str resolved)})))
                resolved))
            paths))))

(defn- non-empty-directory? [file]
  (and (.isDirectory file)
       (boolean (seq (.list file)))))

(defn- delete-tree!
  "Delete a tree without following symlinks: links are removed as links, so a
  checkout containing a symlink can never delete content outside `file`."
  [file]
  (let [path (.toPath file)]
    (when (Files/exists path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
      (Files/walkFileTree
       path
       (proxy [SimpleFileVisitor] []
         (visitFile [p _attrs]
           (Files/delete p)
           FileVisitResult/CONTINUE)
         (postVisitDirectory [p exc]
           (when exc (throw exc))
           (Files/delete p)
           FileVisitResult/CONTINUE))))))

(defn- run-git [dir & args]
  (let [process (try
                  (-> (ProcessBuilder. (into-array String (cons "git" args)))
                      (.directory dir)
                      (.redirectErrorStream false)
                      (.start))
                  (catch java.io.IOException e
                    {:exit 127
                     :stderr (ex-message e)}))]
    (if (map? process)
      process
      (let [stderr (future (slurp (.getErrorStream process)))
            stdout (future (slurp (.getInputStream process)))
            exit (.waitFor process)]
        {:exit exit
         :stdout @stdout
         :stderr @stderr}))))

(defn- stderr-tail [stderr]
  (let [trimmed (str/trim (or stderr ""))]
    (if (<= (count trimmed) 4000)
      trimmed
      (subs trimmed (- (count trimmed) 4000)))))

(defn- fetch-failure [result]
  {:exit (:exit result)
   :stderr (stderr-tail (:stderr result))})

(defn- checked-git [dir & args]
  (let [result (apply run-git dir args)]
    (when-not (zero? (:exit result))
      (throw (ex-info "git command failed" (fetch-failure result))))
    result))

(defn- cache-root-file [entry]
  (io/file (cache-base) "skein" "spools" (:git/sha entry)))

(defn- tag-refs [tag]
  [tag (str tag "^{}")])

(defn- ls-remote-ref-shas [stdout]
  (->> (str/split-lines stdout)
       (keep (fn [line]
               (let [[sha ref] (str/split line #"\s+" 2)]
                 (when (and (not (str/blank? sha)) (not (str/blank? ref)))
                   [ref sha]))))
       (into {})))

(defn- verify-git-tag! [entry]
  (let [result (apply run-git nil "ls-remote" "--tags" (:git/url entry) (tag-refs (:git/tag entry)))]
    (when-not (zero? (:exit result))
      (throw (ex-info "git tag lookup failed" (fetch-failure result))))
    (let [refs (ls-remote-ref-shas (:stdout result))
          tag-ref (str "refs/tags/" (:git/tag entry))
          peeled-ref (str tag-ref "^{}")
          actual (or (get refs peeled-ref) (get refs tag-ref))]
      (when-not (= (:git/sha entry) actual)
        (throw (ex-info "git tag does not match pinned sha"
                        {:reason :tag-mismatch
                         :tag (:git/tag entry)
                         :expected (:git/sha entry)
                         :actual actual}))))))

(defn- git-cache-miss-failure [entry cache-root result]
  (assoc (fetch-failure result)
         :remote (:git/url entry)
         :cache-path (.getPath cache-root)))

(defn- fetch-advertised-refs! [entry tmp cache-root]
  (let [refetch (run-git tmp
                         "fetch"
                         "--tags"
                         "--prune"
                         (:git/url entry)
                         "+refs/heads/*:refs/remotes/origin/*")]
    (when-not (zero? (:exit refetch))
      (throw (ex-info "git fetch failed"
                      (git-cache-miss-failure entry cache-root refetch))))
    (let [present (run-git tmp "cat-file" "-e" (str (:git/sha entry) "^{commit}"))]
      (when-not (zero? (:exit present))
        (throw (ex-info "git pinned sha is unreachable after refetch"
                        (git-cache-miss-failure entry cache-root present)))))))

(defn- checkout-git-spool! [entry tmp cache-root]
  (checked-git tmp "init")
  (let [shallow (run-git tmp "fetch" "--depth" "1" (:git/url entry) (:git/sha entry))]
    (when-not (zero? (:exit shallow))
      (let [full (run-git tmp "fetch" (:git/url entry) (:git/sha entry))]
        (when-not (zero? (:exit full))
          (fetch-advertised-refs! entry tmp cache-root)))))
  (checked-git tmp "checkout" "--detach" (:git/sha entry))
  (delete-tree! (io/file tmp ".git")))

(defn- move-git-spool-to-cache! [tmp cache-root]
  (try
    (Files/move (.toPath tmp) (.toPath cache-root)
                (into-array java.nio.file.CopyOption [StandardCopyOption/ATOMIC_MOVE]))
    :fetched
    (catch FileSystemException t
      ;; Concurrent materializers for the same pinned SHA can both fetch into
      ;; temporary directories, then race to publish the content-addressed cache
      ;; root. Platform-specific target-exists failures for non-empty
      ;; directories all extend FileSystemException; if the winner left a usable
      ;; tree, the loser has a cache hit. Otherwise preserve the move failure
      ;; loudly.
      (if (non-empty-directory? cache-root)
        :cached
        (throw t)))))

(defn- materialize-git-spool! [entry]
  (let [cache-root (cache-root-file entry)]
    (if (non-empty-directory? cache-root)
      :cached
      (let [parent (.getParentFile cache-root)
            _ (.mkdirs parent)
            tmp-path (Files/createTempDirectory (.toPath parent) (str (:git/sha entry) ".tmp.") (make-array FileAttribute 0))
            tmp (.toFile tmp-path)]
        (try
          (when-let [_ (:git/tag entry)]
            (verify-git-tag! entry))
          (checkout-git-spool! entry tmp cache-root)
          (let [outcome (move-git-spool-to-cache! tmp cache-root)]
            (when (= :cached outcome)
              (delete-tree! tmp))
            outcome)
          (catch Throwable t
            (delete-tree! tmp)
            (throw t)))))))

(defn- add-root-paths-to-spool-loader! [runtime root]
  (let [loader (:spool-classloader runtime)]
    (doseq [path (root-paths root)]
      (when-not (.isDirectory path)
        (throw (ex-info "Local root classpath entry must be a directory" {:root root :path (.getPath path)})))
      (.addURL ^clojure.lang.DynamicClassLoader loader (.toURL (.toURI path))))))

(def ^:private allowed-spool-maven-coordinate-keys
  #{:mvn/version :exclusions :classifier :extension})

(def ^:private rejected-spool-deps-keys
  #{:git/url :git/sha :local/root})

(defn- mutable-maven-version? [version]
  (or (str/ends-with? version "-SNAPSHOT")
      (#{"RELEASE" "LATEST"} version)))

(defn- read-spool-deps-edn [entry]
  (let [deps-file (io/file (:root entry) "deps.edn")]
    (when (.isFile deps-file)
      (assoc (query/read-edn-file deps-file)
             ::deps-file (.getPath deps-file)))))

(defn- validate-spool-maven-deps! [entry deps]
  (when (contains? deps :mvn/repos)
    (throw (ex-info "Spool deps.edn must not declare top-level :mvn/repos"
                    {:root (:root entry)
                     :deps-file (::deps-file deps)
                     :key :mvn/repos})))
  (when (contains? deps :mvn/local-repo)
    (throw (ex-info "Spool deps.edn must not declare top-level :mvn/local-repo"
                    {:root (:root entry)
                     :deps-file (::deps-file deps)
                     :key :mvn/local-repo})))
  (when-not (or (nil? (:deps deps)) (map? (:deps deps)))
    (throw (ex-info "Spool deps.edn :deps must be a map"
                    {:root (:root entry)
                     :deps-file (::deps-file deps)
                     :deps (:deps deps)})))
  (doseq [[lib coord] (:deps deps)]
    (when-not (symbol? lib)
      (throw (ex-info "Spool deps.edn :deps keys must be symbols"
                      {:root (:root entry)
                       :deps-file (::deps-file deps)
                       :lib lib})))
    (when-not (map? coord)
      (throw (ex-info "Spool deps.edn :deps entries must be Maven coordinate maps"
                      {:root (:root entry)
                       :deps-file (::deps-file deps)
                       :lib lib
                       :coord coord})))
    (when-let [source-keys (seq (filter #(contains? coord %) rejected-spool-deps-keys))]
      (throw (ex-info "Spool deps.edn :deps entries must not declare source-bearing coordinates"
                      {:root (:root entry)
                       :deps-file (::deps-file deps)
                       :lib lib
                       :keys (vec source-keys)})))
    (when-not (string? (:mvn/version coord))
      (throw (ex-info "Spool deps.edn :deps entries must declare string :mvn/version"
                      {:root (:root entry)
                       :deps-file (::deps-file deps)
                       :lib lib
                       :coord coord})))
    (when (mutable-maven-version? (:mvn/version coord))
      (throw (ex-info "Spool deps.edn :deps entries must not use mutable Maven versions"
                      {:root (:root entry)
                       :deps-file (::deps-file deps)
                       :lib lib
                       :mvn/version (:mvn/version coord)})))
    (when-let [unknown (seq (remove allowed-spool-maven-coordinate-keys (keys coord)))]
      (throw (ex-info "Spool deps.edn :deps entries contain unsupported Maven coordinate keys"
                      {:root (:root entry)
                       :deps-file (::deps-file deps)
                       :lib lib
                       :keys (vec unknown)}))))
  (:deps deps))

(defn- spool-maven-deps! [entry]
  (when-let [deps (read-spool-deps-edn entry)]
    (not-empty (validate-spool-maven-deps! entry deps))))

(defn- materialize-git-spool-outcome
  "Materialize a git entry, returning {:fetch ...} or {:failed <sync-failed>}.

  Only the materialization call may translate throws into fetch/tag outcomes;
  anything thrown elsewhere in sync is a real error and must stay loud."
  [lib entry]
  (try
    {:fetch (materialize-git-spool! entry)}
    (catch clojure.lang.ExceptionInfo e
      (if (= :tag-mismatch (:reason (ex-data e)))
        {:failed (sync-failed lib entry :tag-mismatch (select-keys (ex-data e) [:tag :expected :actual]))}
        (let [data (ex-data e)]
          {:failed (sync-failed lib entry :fetch-failed
                                (cond-> (fetch-failure data)
                                  (:remote data) (assoc :remote (:remote data))
                                  (:cache-path data) (assoc :cache-path (:cache-path data))))})))
    (catch Throwable t
      {:failed (sync-failed lib entry :fetch-failed {:exit 1
                                                     :stderr (stderr-tail (ex-message t))})})))

(defn- sync-approved-spool! [runtime lib entry]
  (let [{:keys [fetch failed]} (when (= :git (:kind entry))
                                 (materialize-git-spool-outcome lib entry))
        root-file (io/file (:root entry))]
    (cond
      failed
      failed

      (not (.exists root-file))
      (sync-failed lib entry :missing-root (cond-> {} fetch (assoc :fetch fetch)))

      (not (.isDirectory root-file))
      (sync-failed lib entry :unreadable-root (cond-> {} fetch (assoc :fetch fetch)))

      (not (.canRead root-file))
      (sync-failed lib entry :unreadable-root (cond-> {} fetch (assoc :fetch fetch)))

      :else
      (try
        (let [maven-deps (spool-maven-deps! entry)
              added-maven (when maven-deps
                            (with-spool-classloader
                              runtime
                              #(binding [clojure.core/*repl* true]
                                 (repl-deps/add-libs maven-deps))))
              added (with-spool-classloader
                      runtime
                      #(binding [clojure.core/*repl* true]
                         (repl-deps/add-libs {lib {:local/root (:root entry)}})))]
          (add-root-paths-to-spool-loader! runtime (:root entry))
          [lib (cond-> (assoc (sync-result-base lib entry)
                              :status (if (or (seq added-maven) (seq added))
                                        :loaded
                                        :already-available))
                 fetch (assoc :fetch fetch))])
        (catch Throwable t
          (sync-failed lib entry :runtime-add-failed (cond-> {:message (ex-message t)
                                                              :class (str (class t))}
                                                       (ex-data t) (assoc :data (ex-data t))
                                                       fetch (assoc :fetch fetch))))))))

(defn- successful-sync? [sync]
  (#{:loaded :already-available} (:status sync)))

(defn sync-approved-spools
  "Load approved local spools into the runtime classloader and record sync status."
  [runtime]
  (reset! (approved-spool-sync-state runtime) {})
  (let [approved (approved-spools runtime)
        results (into (sorted-map)
                      (map (fn [[lib entry]] (sync-approved-spool! runtime lib entry)))
                      (:spools approved))]
    (reset! (approved-spool-sync-state runtime) results)
    {:spools results}))

(defn approved-spool-syncs
  "Return the most recent approved spool sync results."
  [runtime]
  {:spools (into (sorted-map) @(approved-spool-sync-state runtime))})

(defn- clear-reload-state! [runtime]
  (reset! (approved-spool-sync-state runtime) {})
  (reset! (module-use-state runtime) {})
  (reset! (query-registry runtime) {})
  (reset! (view-registry runtime) {})
  (reset! (pattern-registry runtime) {})
  (reset! (op-registry runtime) {})
  (reset! (hook-registry runtime) {})
  (runtime/clear-event-system-for-reload! runtime)
  (runtime/with-runtime-binding runtime #(register-built-in-ops! runtime)))

(defn reload-config!
  "Reload selected config-dir startup files after clearing runtime registries."
  [runtime]
  (try
    (clear-reload-state! runtime)
    (let [world {:config-dir (config-dir runtime)}
          files (runtime/load-startup-files! runtime world)]
      (runtime/resume-event-system! runtime)
      ;; Re-arm after config reload so handlers newly supplied by reloaded
      ;; spools/config resolve; rearm! also discards fire envelopes the reload
      ;; flushed from the event queue (DELTA-weaver-scheduler-runtime-001.CC5).
      (scheduler/rearm! runtime)
      {:status :loaded
       :files files
       :returns (mapv :return files)})
    (catch Throwable t
      ;; Do not re-clear on failure. The initial clear-reload-state! already
      ;; reinstalled the built-in ops, and startup files register userland ops
      ;; incrementally, so a spool install that throws midway would otherwise
      ;; take every already-registered op down with it — the "zero useful ops
      ;; until a manual atom reset" cliff. Leave whatever loaded so the world
      ;; stays operable, resume dispatch, and rethrow the failure loudly.
      (runtime/resume-event-system! runtime)
      (scheduler/rearm! runtime)
      (throw t))))

(def ^:private allowed-use-keys #{:ns :file :spools :after :call :required?})

(defn- validate-use-opts! [key opts]
  (when-not (keyword? key)
    (throw (ex-info "Module use key must be a keyword" {:key key})))
  (when-not (map? opts)
    (throw (ex-info "Module use opts must be a map" {:key key :opts opts})))
  (when-let [unknown (seq (remove allowed-use-keys (keys opts)))]
    (throw (ex-info "Module use opts contain unknown keys" {:key key :keys (vec unknown)})))
  (when (= (contains? opts :ns) (contains? opts :file))
    (throw (ex-info "Module use opts require exactly one of :ns or :file" {:key key :opts opts})))
  (when (and (contains? opts :ns) (not (symbol? (:ns opts))))
    (throw (ex-info "Module use :ns must be a symbol" {:key key :ns (:ns opts)})))
  (when (and (contains? opts :file) (not (and (string? (:file opts)) (not (str/blank? (:file opts))))))
    (throw (ex-info "Module use :file must be a non-blank string" {:key key :file (:file opts)})))
  (when (and (contains? opts :file) (.isAbsolute (io/file (:file opts))))
    (throw (ex-info "Module use :file must be relative to selected config-dir" {:key key :file (:file opts)})))
  (when (and (contains? opts :spools)
             (not (or (vector? (:spools opts)) (set? (:spools opts)))))
    (throw (ex-info "Module use :spools must be a vector or set of symbols" {:key key :spools (:spools opts)})))
  (doseq [lib (:spools opts)]
    (when-not (symbol? lib)
      (throw (ex-info "Module use :spools entries must be symbols" {:key key :lib lib}))))
  (when (and (contains? opts :after) (not (vector? (:after opts))))
    (throw (ex-info "Module use :after must be a vector" {:key key :after (:after opts)})))
  (doseq [after (:after opts)]
    (when-not (keyword? after)
      (throw (ex-info "Module use :after entries must be keywords" {:key key :after after}))))
  (when (and (contains? opts :call) (not (symbol? (:call opts))))
    (throw (ex-info "Module use :call must be a fully qualified symbol" {:key key :call (:call opts)})))
  (when (and (symbol? (:call opts)) (nil? (namespace (:call opts))))
    (throw (ex-info "Module use :call must be a fully qualified symbol" {:key key :call (:call opts)})))
  (when (and (contains? opts :required?) (not (boolean? (:required? opts))))
    (throw (ex-info "Module use :required? must be boolean" {:key key :required? (:required? opts)}))))

(defn- record-use! [runtime key result]
  (swap! (module-use-state runtime) assoc key result)
  result)

(defn- skip-use [runtime key opts reason data]
  (let [result (record-use! runtime key (merge {:key key :opts opts :status :skipped :reason reason} data))]
    (when (and (:required? opts) (#{:not-approved :not-synced :sync-failed} reason))
      (throw (ex-info "Required module use was skipped" result)))
    result))

(defn- use-spool-skip [runtime opts]
  (let [approved (approved-spools runtime)
        syncs @(approved-spool-sync-state runtime)]
    (some (fn [lib]
            (let [sync (get syncs lib)]
              (cond
                (not (contains? (:spools approved) lib))
                [:not-approved {:lib lib}]

                (not (contains? syncs lib))
                [:not-synced {:lib lib}]

                (= :failed (:status sync))
                [:sync-failed {:lib lib :sync sync}]

                :else
                nil)))
          (:spools opts))))

(defn- use-after-skip [runtime opts]
  (let [uses @(module-use-state runtime)]
    (some (fn [after]
            (when-not (= :loaded (:status (get uses after)))
              [:missing-after {:after after :use (get uses after)}]))
          (:after opts))))

(defn- module-file [runtime path]
  (let [base (.getCanonicalFile (io/file (config-dir runtime)))
        file (.getCanonicalFile (io/file base path))
        base-path (.getPath base)
        file-path (.getPath file)]
    (when-not (or (= base-path file-path)
                  (str/starts-with? file-path (str base-path java.io.File/separator)))
      (throw (ex-info "Module use :file must stay within selected config-dir"
                      {:file path
                       :config-dir base-path
                       :resolved file-path})))
    file-path))

(defn- ns-relative-path [ns-sym]
  (str (-> (name ns-sym)
           (str/replace "-" "_")
           (str/replace "." java.io.File/separator))
       ".clj"))

(defn- synced-root-paths [runtime]
  (mapcat (fn [[_ {:keys [root status]}]]
            (when (#{:loaded :already-available} status)
              (root-paths root)))
          @(approved-spool-sync-state runtime)))

(defn- locate-synced-namespace-file [runtime ns-sym]
  (let [relative (ns-relative-path ns-sym)
        roots (vec (synced-root-paths runtime))
        file (some (fn [root]
                     (let [candidate (io/file root relative)]
                       (when (.isFile candidate)
                         (.getCanonicalPath candidate))))
                   roots)]
    {:file file
     :relative-path relative
     :searched-roots (mapv #(.getCanonicalPath %) roots)}))

(defn- load-synced-namespace! [runtime ns-sym]
  (if (find-ns ns-sym)
    {:ns ns-sym}
    (let [{:keys [file relative-path searched-roots]} (locate-synced-namespace-file runtime ns-sym)]
      (if file
        (do
          (load-file file)
          {:ns ns-sym :file file})
        (try
          (require ns-sym)
          {:ns ns-sym}
          (catch java.io.FileNotFoundException _
            (throw (ex-info "Could not locate namespace source in synced spool roots"
                            {:ns ns-sym
                             :relative-path relative-path
                             :searched-roots searched-roots}))))))))

(defn- exception-data [t]
  {:message (ex-message t)
   :class (str (class t))
   :data (ex-data t)})

(defn use!
  "Load a runtime module and record its module-use state under keyword key.

  Opts load either a synced namespace via `:ns` or a file via `:file`, and may
  include `:call` to invoke a no-arg function after load. Returns a registry
  entry with status `:loaded`, `:skipped`, or `:failed`; failed required uses
  rethrow after recording failure metadata."
  [runtime key opts]
  (validate-use-opts! key opts)
  (when-let [file (:file opts)]
    (module-file runtime file))
  (if-let [[reason data] (use-spool-skip runtime opts)]
    (skip-use runtime key opts reason data)
    (if-let [[reason data] (use-after-skip runtime opts)]
      (skip-use runtime key opts reason data)
      (try
        (let [load-result (with-spool-classloader
                            runtime
                            #(if-let [ns-sym (:ns opts)]
                               (load-synced-namespace! runtime ns-sym)
                               (let [file (module-file runtime (:file opts))]
                                 (load-file file)
                                 {:file file})))
              call-result (when-let [call-sym (:call opts)]
                            (with-spool-classloader
                              runtime
                              #((requiring-resolve call-sym))))]
          (record-use! runtime key (cond-> {:key key
                                            :opts opts
                                            :status :loaded
                                            :loaded load-result}
                                     (contains? opts :call) (assoc :call {:fn (:call opts)
                                                                          :return call-result}))))
        (catch Exception t
          (let [result (record-use! runtime key {:key key
                                                 :opts opts
                                                 :status :failed
                                                 :error (exception-data t)})]
            (when (:required? opts)
              (throw t))
            result))))))

(defn uses
  "Return module-use registry entries keyed by keyword."
  [runtime]
  (into (sorted-map) @(module-use-state runtime)))

(defn use
  "Return the module-use registry entry for key, or nil when absent."
  [runtime key]
  (get @(module-use-state runtime) key))

(defn- validated-query-entry [[query-name query-def]]
  [(query/canonical-query-name query-name)
   (query/validate-query-def! query-def)])

(defn register-query
  "Register a named query definition in the runtime query registry."
  [runtime query-name query-def]
  (let [entry (validated-query-entry [query-name query-def])]
    (swap! (query-registry runtime) conj entry)
    (into {} [entry])))

(defn load-queries
  "Merge validated named query definitions into the runtime query registry."
  [runtime query-defs]
  (let [validated-query-defs (into {} (map validated-query-entry) query-defs)]
    (swap! (query-registry runtime) merge validated-query-defs)
    validated-query-defs))

(defn register-query!
  "Register a named query definition and return its canonical API shape."
  [runtime query-name query-def]
  (register-query runtime query-name query-def))

(defn load-queries!
  "Load multiple named query definitions and return their canonical API shape."
  [runtime query-defs]
  (load-queries runtime query-defs))

(defn queries
  "Return registered query definitions keyed by canonical string name."
  [runtime]
  (into (sorted-map) @(query-registry runtime)))

(defn resolve-query
  "Return the registered query definition for a simple symbol or keyword name."
  [runtime query-name]
  (query/query-def @(query-registry runtime) query-name))

(defn- query-where [query-def]
  (if (map? query-def)
    (:where query-def)
    query-def))

(defn- query-metadata-entry [[name query-def]]
  {:name name
   :params (if (map? query-def) (vec (:params query-def)) [])
   :referenced-params (query/referenced-params (query-where query-def))})

(defn query-metadata
  "Return registered query caller metadata ordered by canonical name."
  [runtime]
  (mapv query-metadata-entry (queries runtime)))

(defn query-explain
  "Describe a registered query definition and how CLI callers invoke it."
  [runtime query-name]
  (let [query-def (resolve-query runtime query-name)
        name (query/query-lookup-name query-name)
        where (query-where query-def)]
    (assoc (query-metadata-entry [name query-def])
           :where where
           :definition query-def
           :where-form (pr-str where)
           :definition-form (pr-str query-def)
           :summary "Invoke this query with `strand list --query <name>` or `strand ready --query <name>` and pass runtime values with repeated `--param key=value` arguments.")))

(defn init
  "Initialize the runtime database schema."
  [runtime]
  (db/init! (ds runtime))
  {:database "initialized"})

(defn- event-base [type]
  {:event/type type
   :event/id (str (UUID/randomUUID))
   :event/at (str (Instant/now))
   :event/source :skein.api.weaver.alpha})

(defn- hooks-for-type [runtime hook-type]
  (filter #(contains? (:types %) hook-type)
          (sort-by (juxt :order (comp pr-str :key)) (vals @(hook-registry runtime)))))

(defn- cause-code [throwable]
  (loop [t throwable]
    (when t
      (let [data (ex-data t)]
        (or (:code data)
            (recur (ex-cause t)))))))

;; :fn is renamed on destructure: a local named `fn` shadows the fn macro.
(defn- hook-failure-data [hook-type {:keys [key] fn-sym :fn} throwable]
  (let [data (ex-data throwable)
        code (cause-code throwable)]
    (cond-> {:code "hook/failed"
             :hook/type hook-type
             :hook/key key
             :hook/fn fn-sym
             :exception/class (str (class throwable))
             :exception/message (ex-message throwable)}
      data (assoc :exception/data data)
      code (assoc :hook/cause-code code))))

(defn- hook-context [hook-type hook ctx]
  (assoc ctx
         :hook/type hook-type
         :hook/key (:key hook)
         :hook/fn (:fn hook)))

(defn- invoke-hook! [runtime hook-type hook ctx]
  (try
    (with-spool-classloader runtime #((:fn-value hook) ctx))
    (catch Throwable t
      (throw (ex-info "Lifecycle hook failed"
                      (hook-failure-data hook-type hook t)
                      t)))))

(defn- run-validation-hooks! [runtime hook-type ctx]
  (doseq [hook (hooks-for-type runtime hook-type)]
    (invoke-hook! runtime hook-type hook (hook-context hook-type hook ctx)))
  nil)

(defn run-payload-received-hooks!
  "Run validation-only hooks for a decoded JSON socket request payload."
  [runtime ctx]
  (run-validation-hooks! runtime :payload/received ctx))

(defn- require-transform-wrapper! [hook-type hook result]
  (when-not (and (map? result) (contains? result :hook/value))
    (throw (ex-info "Transform hook must return {:hook/value replacement}"
                    {:code "hook/invalid-return"
                     :hook/type hook-type
                     :hook/key (:key hook)
                     :hook/fn (:fn hook)
                     :hook/return result})))
  result)

(defn- require-json-attributes! [attrs]
  (db/->json attrs)
  attrs)

(defn- invoke-transform-hook! [runtime hook-type hook ctx]
  (try
    (require-json-attributes!
     (:hook/value
      (require-transform-wrapper!
       hook-type
       hook
       (with-spool-classloader runtime #((:fn-value hook) ctx)))))
    (catch Throwable t
      (throw (ex-info "Lifecycle hook failed"
                      (hook-failure-data hook-type hook t)
                      t)))))

(defn- run-transform-hooks [runtime hook-type ctx]
  (reduce (fn [value hook]
            (invoke-transform-hook! runtime hook-type hook (assoc (hook-context hook-type hook ctx) :hook/value value)))
          (require-json-attributes! (:hook/value ctx))
          (hooks-for-type runtime hook-type)))

(defn- request-context [operation]
  {:request/source :weaver-api
   :request/operation operation})

(defn add
  "Create a strand, enqueue a creation event, and return the normalized strand."
  ([runtime strand]
   (add runtime strand (request-context :add)))
  ([runtime strand req-ctx]
   (let [created (jdbc/with-transaction [tx (ds runtime)]
                   (let [edges (:edges strand)
                         strand (cond-> strand
                                  true (dissoc :edges)
                                  (some? (:attributes strand))
                                  (assoc :attributes (run-transform-hooks runtime
                                                                           :attributes/normalize
                                                                           (merge req-ctx
                                                                                  {:hook/value (:attributes strand)
                                                                                   :mutation/operation :strand/add
                                                                                   :strand/patch strand}))))
                         created (normalize (db/add-strand! tx strand))]
                     (apply-edges! tx (:id created) edges)
                     (run-validation-hooks! runtime
                                            :strand/add-before-commit
                                            (merge req-ctx
                                                   {:mutation/operation :strand/add
                                                    :strand/before nil
                                                    :strand/after created
                                                    :strand/edge-ops (vec edges)}))
                     created))]
     (enqueue-event! runtime (assoc (event-base :strand/added)
                                    :strand/id (:id created)
                                    :strand created))
     created)))

(defn- strand-patch-for-ref [payload ref]
  (some (fn [strand]
          (when (= ref (:ref strand))
            (dissoc strand :ref)))
        (:strands payload)))

(defn- enqueue-batch-fanout! [runtime batch-id payload result]
  (doseq [created (:created result)]
    (enqueue-event! runtime (assoc (event-base :strand/added)
                                   :batch/id batch-id
                                   :strand/id (:id created)
                                   :strand created)))
  (doseq [{:keys [ref id before after]} (:updated result)]
    (enqueue-event! runtime (assoc (event-base :strand/updated)
                                   :batch/id batch-id
                                   :strand/id id
                                   :strand/patch (strand-patch-for-ref payload ref)
                                   :strand/before before
                                   :strand/after after)))
  (when (seq (:burned result))
    (enqueue-event! runtime (assoc (event-base :strand/burned)
                                   :batch/id batch-id
                                   :strand/requested-ids (mapv :id (:burned result))
                                   :strand/burned-ids (mapv :id (:burned result))
                                   :strand/before (mapv :before (:burned result))))))

(defn- normalize-batch-strand-attributes [runtime req-ctx payload]
  (clojure.core/update payload :strands
                       (fn [strands]
                         (mapv (fn [{:keys [ref attributes] :as strand}]
                                 (if (nil? attributes)
                                   strand
                                   (assoc strand :attributes
                                          (run-transform-hooks runtime
                                                               :attributes/normalize
                                                               (merge req-ctx
                                                                      {:hook/value attributes
                                                                       :mutation/operation :batch/apply
                                                                       :batch/ref ref
                                                                       :strand/patch strand})))))
                               strands))))

(defn- batch-apply-context [req-ctx payload result]
  (merge req-ctx
         {:mutation/operation :batch/apply
          :batch/source :apply
          :batch/payload payload
          :batch/refs (:refs result)
          :batch/created (:created result)
          :batch/updated (:updated result)
          :batch/burned (:burned result)
          :batch/edge-ops (:edges result)}))

(defn apply-batch
  "Apply a graph batch atomically and enqueue batch plus strand fanout events."
  ([runtime payload]
   (apply-batch runtime payload (request-context :apply-batch)))
  ([runtime payload req-ctx]
  (let [submitted-payload payload
        normalized-payload (normalize-batch-strand-attributes runtime req-ctx (db/normalize-batch-payload! payload))
        result (jdbc/with-transaction [tx (ds runtime)]
                 (let [result (normalize (db/apply-batch-in-transaction! tx normalized-payload))]
                   (run-validation-hooks! runtime
                                          :batch/apply-before-commit
                                          (batch-apply-context req-ctx submitted-payload result))
                   result))
        batch-id (str (UUID/randomUUID))]
    (enqueue-event! runtime (assoc (event-base :batch/applied)
                                   :batch/id batch-id
                                   :batch/refs (:refs result)
                                   :batch/created (:created result)
                                   :batch/updated (:updated result)
                                   :batch/burned (:burned result)
                                   :batch/edges (:edges result)))
    (enqueue-batch-fanout! runtime batch-id normalized-payload result)
    result)))

(defn- apply-edges! [tx id edges]
  (doseq [{:keys [to type attributes]} edges]
    (when-not (db/get-strand tx to)
      (throw (ex-info "Edge target strand not found" {:to to :type type})))
    (db/add-edge! tx {:from id :to to :type type :attributes (or attributes {})})))

(def ^:private update-patch-keys #{:title :state :attributes :edges})

(defn- reject-unknown-update-keys! [patch]
  (let [unknown (seq (remove update-patch-keys (keys patch)))]
    (when unknown
      (throw (ex-info "Unknown strand update fields" {:fields (vec unknown)})))))

(defn update
  "Update a strand and/or add edges atomically, then enqueue an update event."
  ([runtime id patch]
   (update runtime id patch (request-context :update)))
  ([runtime id patch req-ctx]
  (reject-unknown-update-keys! patch)
  (let [{:keys [title state edges]} patch
        result (jdbc/with-transaction [tx (ds runtime)]
                 (let [before (or (some-> (db/get-strand tx id) normalize)
                                  (throw (ex-info "Strand not found" {:strand-id id})))
                       patch (if (some? (:attributes patch))
                               (assoc patch :attributes (run-transform-hooks runtime
                                                                              :attributes/normalize
                                                                              (merge req-ctx
                                                                                     {:hook/value (:attributes patch)
                                                                                      :mutation/operation :strand/update
                                                                                      :strand/id id
                                                                                      :strand/before before
                                                                                      :strand/patch patch})))
                               patch)
                       attributes (:attributes patch)]
                   (apply-edges! tx id edges)
                   (let [after (normalize (db/update-strand! tx id (cond-> {}
                                                                     (contains? patch :title) (assoc :title title)
                                                                     (contains? patch :state) (assoc :state state)
                                                                     (contains? patch :attributes) (assoc :attributes attributes))))]
                     (run-validation-hooks! runtime
                                            :strand/update-before-commit
                                            (merge req-ctx
                                                   {:mutation/operation :strand/update
                                                    :strand/id id
                                                    :strand/patch patch
                                                    :strand/before before
                                                    :strand/after after
                                                    :strand/edge-ops (vec edges)}))
                     {:before before :after after :patch patch})))]
    (enqueue-event! runtime (assoc (event-base :strand/updated)
                                   :strand/id id
                                   :strand/patch (:patch result)
                                   :strand/before (:before result)
                                   :strand/after (:after result)))
    (:after result))))

(defn- supersede-context [old-id replacement-id result]
  {:strand/id old-id
   :strand/old-id old-id
   :strand/replacement-id replacement-id
   :strand/before (get-in result [:old :before])
   :strand/after (get-in result [:old :after])
   :supersession/supersedes-edge (:supersedes-edge result)
   :supersession/rewired-dependencies (:rewired-dependencies result)})

(defn supersede
  "Replace one strand with another and enqueue a supersession event."
  ([runtime old-id replacement-id]
   (supersede runtime old-id replacement-id (request-context :supersede)))
  ([runtime old-id replacement-id req-ctx]
  (let [result (jdbc/with-transaction [tx (ds runtime)]
                 (let [result (normalize (db/supersede-strand-in-transaction! tx old-id replacement-id))]
                   (run-validation-hooks! runtime
                                          :strand/supersede-before-commit
                                          (merge req-ctx
                                                 {:mutation/operation :strand/supersede}
                                                 (supersede-context old-id replacement-id result)))
                   result))]
    (enqueue-event! runtime (merge (event-base :strand/superseded)
                                   (supersede-context old-id replacement-id result)))
    result)))

(defn declare-acyclic-relation!
  "Declare an edge relation as acyclic for future graph writes."
  [runtime relation]
  (db/declare-acyclic-relation! (ds runtime) relation))

(defn acyclic-relations
  "Return declared acyclic edge relation names."
  [runtime]
  (db/list-acyclic-relations (ds runtime)))

(defn show
  "Return one normalized strand by id, or nil when absent."
  [runtime id]
  (normalize (db/get-strand (ds runtime) id)))

(defn burn-by-ids
  "Delete strands by id and enqueue burn events for removed rows."
  ([runtime ids]
   (burn-by-ids runtime ids (request-context :burn)))
  ([runtime ids req-ctx]
  (let [requested-ids (vec ids)
        {:keys [before result]} (jdbc/with-transaction [tx (ds runtime)]
                                  (let [before (normalize (db/strands-by-ids tx requested-ids))]
                                    (run-validation-hooks! runtime
                                                           :strand/burn-before-commit
                                                           (merge req-ctx
                                                                  {:mutation/operation :strand/burn
                                                                   :strand/requested-ids requested-ids
                                                                   :strand/before before}))
                                    {:before before
                                     :result (db/burn-by-ids! tx requested-ids)}))]
    (enqueue-event! runtime (assoc (event-base :strand/burned)
                                   :strand/requested-ids requested-ids
                                   :strand/burned-ids (:burned result)
                                   :strand/before before))
    result)))

(defn burn-by-id
  "Delete one strand by id and return burn metadata."
  ([runtime id]
   (burn-by-ids runtime [id]))
  ([runtime id req-ctx]
   (burn-by-ids runtime [id] req-ctx)))

(defn list
  "Return strands visible to `runtime`, optionally filtered by a query definition."
  ([runtime]
   (normalize (db/all-strands (ds runtime))))
  ([runtime query-def params]
   (normalize (db/all-strands (ds runtime) query-def params))))

(defn list-query
  "Return strands matching a registered query definition."
  [runtime query-name params]
  (list runtime (resolve-query runtime query-name) params))

(defn ready
  "Return ready strands for `runtime`, optionally filtered by a query definition."
  ([runtime]
   (normalize (db/ready-strands (ds runtime))))
  ([runtime query-def params]
   (normalize (db/ready-strands (ds runtime) query-def params))))

(defn ready-query
  "Return ready strands from the result set of a registered query definition."
  [runtime query-name params]
  (ready runtime (resolve-query runtime query-name) params))

(defn query-ids
  "Return strand ids matching a query expression or registered query definition."
  [runtime query-or-name params]
  (let [query-def (if (or (vector? query-or-name) (map? query-or-name))
                    query-or-name
                    (resolve-query runtime query-or-name))]
    (db/query-strand-ids (ds runtime) query-def params)))

(defn strands-by-ids
  "Return normalized strands for ids, preserving first-seen input order."
  [runtime ids]
  (normalize (db/strands-by-ids (ds runtime) ids)))

(defn ancestor-root-ids
  "Return ancestor root ids reachable from `seed-ids`."
  ([runtime seed-ids]
   (ancestor-root-ids runtime seed-ids {}))
  ([runtime seed-ids opts]
   (db/ancestor-root-ids (ds runtime) seed-ids opts)))

(defn subgraph
  "Return a normalized strand subgraph rooted at `root-ids`."
  ([runtime root-ids]
   (subgraph runtime root-ids {}))
  ([runtime root-ids opts]
   (let [{:keys [strands edges] :as result} (db/subgraph (ds runtime) root-ids opts)]
     (assoc result
            :strands (normalize strands)
            :edges (normalize edges)))))

(defn incoming-edges
  "Return normalized `edge-type` edges whose target is one of `to-ids`.

  One indexed lookup for a strand's parents/annotators; no graph traversal.
  Adjacency is lenient: an id absent from storage yields no rows rather than a
  missing-id error (unlike subgraph/ancestor-root-ids seeds)."
  [runtime to-ids edge-type]
  (normalize (db/incoming-edges (ds runtime) to-ids edge-type)))

(defn outgoing-edges
  "Return normalized `edge-type` edges whose source is one of `from-ids`.

  One indexed lookup for a strand's children; no graph traversal. Lenient
  adjacency: an absent id yields no rows rather than a missing-id error."
  [runtime from-ids edge-type]
  (normalize (db/outgoing-edges (ds runtime) from-ids edge-type)))

(defn- canonical-view-name [view-name]
  (query/canonical-query-name view-name))

(defn- validate-fn-symbol! [label fn-sym]
  (when-not (and (symbol? fn-sym) (namespace fn-sym))
    (throw (ex-info (str label " function must be a fully qualified symbol") {:fn fn-sym})))
  fn-sym)

(defn- validate-view-fn-symbol! [fn-sym]
  (validate-fn-symbol! "View" fn-sym))

(defn- validate-pattern-fn-symbol! [fn-sym]
  (validate-fn-symbol! "Pattern" fn-sym))

(defn- validate-op-fn-symbol! [fn-sym]
  (validate-fn-symbol! "Operation" fn-sym))

(defn register-view!
  "Register a named view function for trusted in-process rendering."
  [runtime view-name fn-sym]
  (let [name (canonical-view-name view-name)
        entry {:name name :fn (validate-view-fn-symbol! fn-sym)}]
    (swap! (view-registry runtime) assoc name entry)
    entry))

(defn views
  "Return registered view metadata ordered by name."
  [runtime]
  (mapv val (sort-by key @(view-registry runtime))))

(defn- resolve-view [runtime view-name]
  (let [canonical-name (canonical-view-name view-name)]
    (or (get @(view-registry runtime) canonical-name)
        (throw (ex-info "View not found" {:view view-name
                                           :canonical-view canonical-name
                                           :available (sort (keys @(view-registry runtime)))})))))

(defn view!
  "Invoke a registered view function with params."
  [runtime view-name params]
  (let [{fn-sym :fn} (resolve-view runtime view-name)]
    (with-spool-classloader
      runtime
      #((requiring-resolve fn-sym) {:params params}))))

(defn- canonical-op-name [op-name]
  (query/canonical-query-name op-name))

(defn- validate-op-doc! [doc]
  (when-not (and (string? doc) (not (str/blank? doc)))
    (throw (ex-info "Operation doc must be a non-blank string" {:doc doc})))
  doc)

(def ^:private op-metadata-keys #{:doc :arg-spec :stream? :deadline-class :hook-class})
(def ^:private op-deadline-classes #{:standard :unbounded})
(def ^:private op-hook-classes #{:read :mutating})

(defn- normalize-op-opts
  "Coerce a register-op! metadata argument into an options map.

  A string is the legacy positional doc; a map is the full metadata map; nil is
  the no-metadata case."
  [opts]
  (cond
    (nil? opts) {}
    (string? opts) {:doc opts}
    (map? opts) opts
    :else (throw (ex-info "Operation metadata must be a doc string or options map" {:opts opts}))))

(defn- validate-op-metadata! [opts]
  ;; Provenance is registry-recorded from the handler namespace; a caller must
  ;; never assert it. Reject it explicitly so the error is unambiguous even
  ;; though it would also trip the unknown-key check below.
  (when (contains? opts :provenance)
    (throw (ex-info "Operation :provenance is registry-recorded and cannot be supplied by the caller"
                    {:provenance (:provenance opts)})))
  (when-let [unknown (seq (remove op-metadata-keys (keys opts)))]
    (throw (ex-info "Operation metadata contains unknown keys" {:keys (vec unknown)})))
  (when (and (contains? opts :stream?) (not (boolean? (:stream? opts))))
    (throw (ex-info "Operation :stream? must be a boolean" {:stream? (:stream? opts)})))
  (when (and (contains? opts :deadline-class) (not (op-deadline-classes (:deadline-class opts))))
    (throw (ex-info "Operation :deadline-class must be :standard or :unbounded"
                    {:deadline-class (:deadline-class opts)})))
  (when (and (contains? opts :hook-class) (not (op-hook-classes (:hook-class opts))))
    (throw (ex-info "Operation :hook-class must be :read or :mutating"
                    {:hook-class (:hook-class opts)})))
  opts)

(defn- validate-op-arg-spec! [op-name arg-spec]
  (try
    (cli/validate! arg-spec)
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info "Operation arg-spec is invalid"
                      (assoc (ex-data e) :operation (canonical-op-name op-name))
                      e)))))

(defn- build-op-entry
  "Build a validated op registry entry with metadata defaults and provenance.

  Provenance is derived from the handler symbol's namespace. `:deadline-class`
  defaults to `:unbounded` for stream ops and `:standard` otherwise;
  `:hook-class` defaults to `:mutating`, preserving today's hook-gated behavior."
  [op-name opts fn-sym]
  (let [opts (validate-op-metadata! (normalize-op-opts opts))
        validated-fn (validate-op-fn-symbol! fn-sym)
        stream? (boolean (:stream? opts))]
    (cond-> {:name (canonical-op-name op-name)
             :fn validated-fn
             :stream? stream?
             :deadline-class (or (:deadline-class opts) (if stream? :unbounded :standard))
             :hook-class (or (:hook-class opts) :mutating)
             :provenance (symbol (namespace validated-fn))}
      (:doc opts) (assoc :doc (validate-op-doc! (:doc opts)))
      (some? (:arg-spec opts)) (assoc :arg-spec (validate-op-arg-spec! op-name (:arg-spec opts))))))

(defn register-op!
  "Register a trusted weaver-side CLI operation.

  Registered operations are invoked at the CLI root as `strand <name> [args...]`. The handler
  symbol must resolve to a function that accepts one context map (see `op!` for
  the context keys) and returns JSON-compatible data. The third positional
  argument is either a doc string or an op metadata map with keys `:doc`,
  `:arg-spec` (parser spec, structurally validated at registration),
  `:stream?` (default false), `:deadline-class`
  (`:standard`/`:unbounded`, defaulting to `:unbounded` for stream ops), and
  `:hook-class` (`:read`/`:mutating`, default `:mutating`); unknown keys fail
  loudly. Provenance (the registering namespace) is recorded from the handler
  symbol and must never be caller-supplied.

  Registering an already-registered name fails loudly, naming both the existing
  entry's provenance and the attempted registrant; use `replace-op!` to override
  deliberately. Registry contents live only for the current weaver lifetime and
  are normally installed from init.clj or a live REPL; `reload!` clears the
  registry before re-running init, so re-registration is collision-free."
  ([runtime op-name fn-sym]
   (register-op! runtime op-name nil fn-sym))
  ([runtime op-name opts fn-sym]
   (let [entry (build-op-entry op-name opts fn-sym)]
     (swap! (op-registry runtime)
            (fn [registry]
              (when-let [existing (get registry (:name entry))]
                (throw (ex-info "Operation already registered"
                                {:operation (:name entry)
                                 :existing-provenance (:provenance existing)
                                 :attempted-provenance (:provenance entry)})))
              (assoc registry (:name entry) entry)))
     entry)))

(defn replace-op!
  "Replace an already-registered op, failing loudly when the name is absent.

  Same signature as `register-op!`. This is the deliberate override for a name
  that already exists; unlike `register-op!` it requires the name to be present."
  ([runtime op-name fn-sym]
   (replace-op! runtime op-name nil fn-sym))
  ([runtime op-name opts fn-sym]
   (let [entry (build-op-entry op-name opts fn-sym)]
     (swap! (op-registry runtime)
            (fn [registry]
              (when-not (contains? registry (:name entry))
                (throw (ex-info "Operation not registered; cannot replace"
                                {:operation (:name entry)
                                 :available (sort (keys registry))})))
              (assoc registry (:name entry) entry)))
     entry)))

(defn ops
  "Return registered CLI operation entries for the current weaver runtime."
  [runtime]
  (mapv val (sort-by key @(op-registry runtime))))

(defn resolve-op
  "Return the registered CLI operation entry for `op-name`, or fail loudly."
  [runtime op-name]
  (let [canonical-name (canonical-op-name op-name)]
    (or (get @(op-registry runtime) canonical-name)
        (throw (ex-info "Operation not found" {:operation op-name
                                                :canonical-operation canonical-name
                                                :available (sort (keys @(op-registry runtime)))})))))

(def ^:private help-alias-tokens
  "Dispatch-level help alias argv tokens; the parser's reserved set is the
  single source of truth so validation and dispatch cannot drift."
  cli/reserved-subcommand-names)

(defn help-alias-result
  "Return an op detail projection when argv/envelope form a help alias.

  The alias applies only to ops whose arg-spec declares `:subcommands`, argv is
  exactly one reserved help token, and the envelope carries no payloads. Returns
  nil when the invocation must flow through normal parsing and handler dispatch."
  [entry argv envelope]
  (let [argv (vec argv)
        payloads (or (:payloads envelope) {})]
    (when (and (contains? (:arg-spec entry) :subcommands)
               (= 1 (count argv))
               (contains? help-alias-tokens (first argv))
               (empty? payloads))
      (op-detail entry))))

(defn op!
  "Invoke a registered CLI operation with raw string argv from a root-level `strand <name>` invoke.

  The handler receives a context map with `:op/name`, `:op/argv`, `:op/runtime`,
  `:op/runtime-metadata`, and `:op/payloads` (defaulting to `{}`). The envelope
  arity threads any present `:cwd`, `:worktree-root`, `:git-common-dir`, and
  `:timeout` fields into `:op/cwd`, `:op/worktree-root`, `:op/git-common-dir`,
  and `:op/timeout`, and an envelope `:emit!` fn (supplied by the streaming
  socket transport for `:stream? true` ops) into `:op/emit!`. When the resolved
  op declares an `:arg-spec`, `:op/argv` and
  the attached payloads are parsed through `skein.api.cli.alpha/parse` and the
  result is supplied as `:op/args`; a parse failure throws before the handler
  runs. For subcommand ops, sole-token `help`, `-h`, or `--help` invocations
  with no payloads return the op's help detail instead of running the handler.
  Raw-envelope ops (no `:arg-spec`) receive the context unchanged, still
  carrying the raw `:op/payloads` map."
  ([runtime op-name argv]
   (op! runtime op-name argv {}))
  ([runtime op-name argv envelope]
   (let [{fn-sym :fn name :name arg-spec :arg-spec :as entry} (resolve-op runtime op-name)
         argv (vec argv)
         payloads (or (:payloads envelope) {})]
     (if-let [alias (help-alias-result entry argv envelope)]
       alias
       (let [ctx (cond-> {:op/name name
                          :op/argv argv
                          :op/runtime runtime
                          :op/runtime-metadata (:metadata runtime)
                          :op/payloads payloads}
                   (contains? envelope :cwd) (assoc :op/cwd (:cwd envelope))
                   (contains? envelope :worktree-root) (assoc :op/worktree-root (:worktree-root envelope))
                   (contains? envelope :git-common-dir) (assoc :op/git-common-dir (:git-common-dir envelope))
                   (contains? envelope :timeout) (assoc :op/timeout (:timeout envelope))
                   (contains? envelope :emit!) (assoc :op/emit! (:emit! envelope))
                   (some? arg-spec) (assoc :op/args (cli/parse arg-spec argv payloads)))]
         (with-spool-classloader
           runtime
           #((requiring-resolve fn-sym) ctx)))))))

(def ^:private help-arg-spec
  "Arg-spec for the built-in `help` op: an optional positional op name.

  This makes `help` the first parser-consuming op, so `op!` parses its argv and
  supplies the resolved positional as `:op/args`."
  {:op "help"
   :doc "List registered weaver ops, or show one op's full detail."
   :positionals [{:name :op
                  :type :string
                  :required? false
                  :doc "Optional op name; when given, return that op's full detail instead of the listing."}]})

(defn- op-summary
  "Project one op registry entry to its help-listing summary."
  [entry]
  (cond-> {:name (:name entry)
           :provenance (:provenance entry)
           :stream? (:stream? entry)
           :deadline-class (:deadline-class entry)
           :hook-class (:hook-class entry)}
    (:doc entry) (assoc :doc (:doc entry))))

(defn- op-detail
  "Project one op registry entry to its full help detail.

  Arg-spec ops carry the parser `explain` rendering; raw-envelope ops carry a
  `:raw-envelope true` marker instead."
  [entry]
  (merge (op-summary entry)
         (if-let [arg-spec (:arg-spec entry)]
           {:arg-spec (cli/explain arg-spec)}
           {:raw-envelope true})))

(defn op-help-handler
  "Project the op registry as help.

  With no positional op name, return every registered op's summary (name, doc,
  provenance, stream?, deadline-class, hook-class) sorted by name. With one op
  name, return that op's full detail including the parser `explain` of its
  arg-spec (or a raw-envelope marker). Unknown names fail loudly through
  `resolve-op`, which carries the available names."
  [ctx]
  (let [runtime (:op/runtime ctx)
        op-name (:op (:op/args ctx))]
    (if op-name
      ;; The parsed positional is a raw string; resolve-op keys on simple
      ;; symbols/keywords, and its loud not-found error carries available names.
      (op-detail (resolve-op runtime (symbol op-name)))
      {:ops (mapv op-summary (ops runtime))})))

(defn register-built-in-ops!
  "Install Skein-provided CLI operations into the runtime op registry."
  [runtime]
  (register-op! runtime 'help
                {:doc (:doc help-arg-spec)
                 :hook-class :read
                 :arg-spec help-arg-spec}
                'skein.api.weaver.alpha/op-help-handler))

(defn- canonical-pattern-name [pattern-name]
  (query/canonical-query-name pattern-name))

(defn- validate-pattern-spec! [spec-name]
  (when-not (or (keyword? spec-name) (symbol? spec-name))
    (throw (ex-info "Pattern input spec must be a keyword or symbol" {:spec spec-name})))
  spec-name)

(defn- validate-pattern-doc! [doc]
  (when-not (and (string? doc) (not (str/blank? doc)))
    (throw (ex-info "Pattern doc must be a non-blank string" {:doc doc})))
  doc)

(defn- runtime? [x]
  (and (map? x) (contains? x :pattern-registry)))

(defn- pattern-entry [pattern-name doc fn-sym input-spec]
  (cond-> {:name (canonical-pattern-name pattern-name)
           :fn (validate-pattern-fn-symbol! fn-sym)
           :input-spec (validate-pattern-spec! input-spec)}
    doc (assoc :doc (validate-pattern-doc! doc))))

(defn register-pattern!
  "Register a trusted weaver pattern handler and input spec."
  ([runtime pattern-name fn-sym input-spec]
   (let [entry (pattern-entry pattern-name nil fn-sym input-spec)]
     (swap! (pattern-registry runtime) assoc (:name entry) entry)
     entry))
  ([runtime pattern-name doc fn-sym input-spec]
   (let [entry (pattern-entry pattern-name doc fn-sym input-spec)]
     (swap! (pattern-registry runtime) assoc (:name entry) entry)
     entry)))

(defn patterns
  "Return registered weave pattern metadata ordered by name."
  [runtime]
  (mapv val (sort-by key @(pattern-registry runtime))))

(defn resolve-pattern
  "Return the registered weave pattern for a simple symbol or keyword name."
  [runtime pattern-name]
  (let [canonical-name (canonical-pattern-name pattern-name)]
    (or (get @(pattern-registry runtime) canonical-name)
        (throw (ex-info "Pattern not found" {:pattern pattern-name
                                              :canonical-pattern canonical-name
                                              :available (sort (keys @(pattern-registry runtime)))})))))

(defn- spec-form [spec-name]
  (let [form (s/form spec-name)]
    (when (= ::s/unknown form)
      (throw (ex-info "Pattern input spec is not registered" {:input-spec spec-name})))
    form))

(defn- spec-summary [spec-name]
  {:spec (str spec-name)
   :spec-form (pr-str (spec-form spec-name))})

(defn- key-spec-summary [key-spec]
  (merge {:key (name key-spec)}
         (try
           (spec-summary key-spec)
           (catch clojure.lang.ExceptionInfo _
             {:spec (str key-spec)
              :spec-form "<unregistered>"}))))

(defn- keys-spec-summary [form]
  (when (and (seq? form) (= 'clojure.spec.alpha/keys (first form)))
    (let [opts (apply hash-map (rest form))]
      {:required (mapv key-spec-summary (concat (:req opts) (:req-un opts)))
       :optional (mapv key-spec-summary (concat (:opt opts) (:opt-un opts)))})))

(defn- pattern-input-contract [input-spec]
  (let [form (spec-form input-spec)
        keys-summary (keys-spec-summary form)]
    (cond-> (spec-summary input-spec)
      true (assoc :summary "Input must satisfy this clojure.spec contract. For key specs, see required/optional entries for each key's own predicate.")
      keys-summary (merge keys-summary))))

(defn pattern-explain
  "Describe a registered weave pattern and its input spec."
  [runtime pattern-name]
  ;; :fn is renamed on destructure: a local named `fn` shadows the fn macro.
  (let [{:keys [name doc input-spec] fn-sym :fn} (resolve-pattern runtime pattern-name)
        contract (pattern-input-contract input-spec)]
    (cond-> (merge {:name name
                    :fn (str fn-sym)
                    :input-spec (str input-spec)
                    :spec-form (:spec-form contract)}
                   (select-keys contract [:summary :required :optional]))
      doc (assoc :doc doc))))

(defn- missing-key [problem]
  (let [pred (pr-str (:pred problem))]
    (when (str/includes? pred "contains?")
      (or (last (:path problem))
          (some->> (re-find #"contains\? % (:?[A-Za-z0-9._/-]+)" pred) second keyword)))))

(defn- problem-message [contract problem]
  (if-let [key-spec (missing-key problem)]
    (let [key-contract (some #(when (= (name key-spec) (:key %)) %)
                             (:required contract))]
      (str "missing required key `" (name key-spec) "`"
           (when key-contract
             (str " (expected " (:spec key-contract) " " (:spec-form key-contract) ")"))))
    (str "value at " (pr-str (:in problem)) " failed predicate " (pr-str (:pred problem)))))

(defn- pattern-validation-message [pattern-name contract explain]
  (let [problems (::s/problems explain)]
    (str "Pattern input failed spec validation for `" (canonical-pattern-name pattern-name) "`"
         (when (seq problems)
           (str ": " (str/join "; " (map #(problem-message contract %) problems)))))))

(defn- require-pattern-batch-vector! [batch]
  (when-not (vector? batch)
    (throw (ex-info "Pattern must return a batch strand vector" {:value batch})))
  batch)

(defn- normalize-weave-strand-attributes [runtime req-ctx pattern-name input batch]
  (mapv (fn [{:keys [ref attributes] :as strand}]
          (if (nil? attributes)
            strand
            (assoc strand :attributes
                   (run-transform-hooks runtime
                                        :attributes/normalize
                                        (merge req-ctx
                                               {:hook/value attributes
                                                :mutation/operation :batch/apply
                                                :batch/ref ref
                                                :strand/patch strand
                                                :pattern/name pattern-name
                                                :pattern/input input})))))
        (require-pattern-batch-vector! batch)))

(defn- weave-payload [strands]
  {:refs {}
   :strands (mapv #(dissoc % :edges) strands)
   :edges (into []
                (mapcat (fn [{:keys [ref edges]}]
                          (map (fn [edge]
                                 (merge {:op :upsert
                                         :from (some-> ref str)
                                         :to (cond-> (:to edge)
                                               (symbol? (:to edge)) str)}
                                        (select-keys edge [:type :attributes])))
                               edges)))
                strands)
   :burn []})

(defn- weave-batch-context [req-ctx pattern-name input payload result]
  (merge req-ctx
         {:mutation/operation :batch/apply
          :batch/source :weave
          :batch/payload payload
          :batch/refs (:refs result)
          :batch/created (:created result)
          :batch/updated []
          :batch/burned []
          :batch/edge-ops (:edges result)
          :pattern/name pattern-name
          :pattern/input input}))

(defn weave!
  "Validate pattern input, invoke the pattern, and apply its create-only batch."
  ([runtime pattern-name input]
   (weave! runtime pattern-name input (request-context :weave)))
  ([runtime pattern-name input req-ctx]
  (let [{fn-sym :fn input-spec :input-spec} (resolve-pattern runtime pattern-name)
        canonical-name (canonical-pattern-name pattern-name)]
    (spec-form input-spec)
    (when-not (s/valid? input-spec input)
      (let [explain (s/explain-data input-spec input)
            contract (pattern-input-contract input-spec)]
        (throw (ex-info (pattern-validation-message pattern-name contract explain)
                        {:code "pattern/input-invalid"
                         :pattern canonical-name
                         :input-spec (str input-spec)
                         :contract contract
                         :problems (mapv #(problem-message contract %) (::s/problems explain))
                         :explain explain}))))
    (let [batch (with-spool-classloader
                  runtime
                  #((requiring-resolve fn-sym) {:input input}))
          normalized-batch (normalize-weave-strand-attributes runtime req-ctx canonical-name input batch)
          normalized-payload (weave-payload normalized-batch)
          result (jdbc/with-transaction [tx (ds runtime)]
                   (let [result (normalize (db/add-strand-batch-in-transaction! tx normalized-batch))]
                     (run-validation-hooks! runtime
                                            :batch/apply-before-commit
                                            (weave-batch-context req-ctx canonical-name input normalized-payload result))
                     result))]
      ;; a weave is a create-only batch apply; without this event, event-driven
      ;; spools (shuttle, treadle) never see pattern-created strands until an
      ;; unrelated mutation happens to trigger their next scan
      (enqueue-event! runtime (assoc (event-base :batch/applied)
                                     :batch/id (str (UUID/randomUUID))
                                     :pattern/name canonical-name
                                     :batch/refs (:refs result)
                                     :batch/created (:created result)))
      (select-keys result [:created :refs])))))

(declare data-first-value?)

(defn- validate-hook-key! [key]
  (when-not (or (keyword? key) (symbol? key) (string? key))
    (throw (ex-info "Hook key must be a keyword, symbol, or string" {:key key})))
  (when (and (string? key) (str/blank? key))
    (throw (ex-info "Hook key string must be non-blank" {:key key})))
  key)

(defn- validate-hook-types! [types]
  (when-not (set? types)
    (throw (ex-info "Hook types must be a set" {:types types})))
  (when-not (seq types)
    (throw (ex-info "Hook types must be non-empty" {:types types})))
  (doseq [type types]
    (when-not (keyword? type)
      (throw (ex-info "Hook types must be keywords" {:type type :types types}))))
  types)

(defn- resolve-hook-fn! [runtime fn-sym]
  (when-not (and (symbol? fn-sym) (namespace fn-sym))
    (throw (ex-info "Hook function must be a fully qualified symbol" {:fn fn-sym})))
  (let [resolved (with-spool-classloader runtime #(requiring-resolve fn-sym))
        value (if (var? resolved) @resolved resolved)]
    (when-not (ifn? value)
      (throw (ex-info "Hook symbol must resolve to a callable value" {:fn fn-sym :resolved-class (str (class value))})))
    resolved))

(defn- validate-hook-opts! [opts]
  (let [opts (or opts {})]
    (when-not (map? opts)
      (throw (ex-info "Hook opts must be a map" {:opts opts})))
    (when-not (data-first-value? opts)
      (throw (ex-info "Hook opts must contain only data-first values" {:opts opts})))
    (when (and (contains? opts :order) (not (integer? (:order opts))))
      (throw (ex-info "Hook :order must be an integer" {:order (:order opts)})))
    opts))

(defn register-hook!
  "Register a trusted lifecycle hook for selected hook types."
  ([runtime key types fn-sym]
   (register-hook! runtime key types fn-sym {}))
  ([runtime key types fn-sym opts]
   (let [opts (validate-hook-opts! opts)
         entry {:key (validate-hook-key! key)
                :types (validate-hook-types! types)
                :fn fn-sym
                :fn-value (resolve-hook-fn! runtime fn-sym)
                :order (get opts :order 0)
                :metadata (dissoc opts :order)}]
     (swap! (hook-registry runtime) assoc (:key entry) entry)
     (dissoc entry :fn-value))))

(defn unregister-hook!
  "Remove a registered lifecycle hook by key and return that key."
  [runtime key]
  (let [key (validate-hook-key! key)]
    (swap! (hook-registry runtime) dissoc key)
    key))

(defn hooks
  "Return lifecycle hook registry entries in deterministic execution order."
  [runtime]
  (mapv #(dissoc % :fn-value)
        (sort-by (juxt :order (comp pr-str :key)) (vals @(hook-registry runtime)))))

(defn- validate-event-handler-key! [key]
  (when-not (or (keyword? key) (symbol? key) (string? key))
    (throw (ex-info "Event handler key must be a keyword, symbol, or string" {:key key})))
  (when (and (string? key) (str/blank? key))
    (throw (ex-info "Event handler key string must be non-blank" {:key key})))
  key)

(defn- validate-event-types! [types]
  (when-not (set? types)
    (throw (ex-info "Event handler types must be a set" {:types types})))
  (when-not (seq types)
    (throw (ex-info "Event handler types must be non-empty" {:types types})))
  (doseq [type types]
    (when-not (keyword? type)
      (throw (ex-info "Event handler types must be keywords" {:type type :types types}))))
  types)

(defn- resolve-event-handler-fn! [runtime fn-sym]
  (when-not (and (symbol? fn-sym) (namespace fn-sym))
    (throw (ex-info "Event handler function must be a fully qualified symbol" {:fn fn-sym})))
  (let [resolved (try
                   (with-spool-classloader runtime #(requiring-resolve fn-sym))
                   (catch Throwable t
                     (throw (ex-info "Event handler function could not be resolved" {:fn fn-sym} t))))
        value (if (var? resolved) @resolved resolved)]
    (when-not (ifn? value)
      (throw (ex-info "Event handler symbol must resolve to a callable value" {:fn fn-sym :resolved-class (str (class value))})))
    value))

(defn- data-first-value? [value]
  (cond
    (nil? value) true
    (or (string? value)
        (number? value)
        (keyword? value)
        (symbol? value)
        (boolean? value)
        (inst? value)
        (uuid? value)) true
    (map? value) (and (every? data-first-value? (keys value))
                      (every? data-first-value? (vals value)))
    (vector? value) (every? data-first-value? value)
    (set? value) (every? data-first-value? value)
    :else false))

(defn- validate-event-handler-metadata! [metadata]
  (let [metadata (or metadata {})]
    (when-not (map? metadata)
      (throw (ex-info "Event handler metadata must be a map" {:metadata metadata})))
    (when-not (data-first-value? metadata)
      (throw (ex-info "Event handler metadata must contain only data-first values" {:metadata metadata})))
    metadata))

(defn register-event-handler!
  "Register a trusted event handler for selected event types."
  [runtime key types fn-sym metadata]
  (let [entry {:key (validate-event-handler-key! key)
               :types (validate-event-types! types)
               :fn fn-sym
               :fn-value (resolve-event-handler-fn! runtime fn-sym)
               :metadata (validate-event-handler-metadata! metadata)}]
    (swap! (:handler-registry (event-system runtime)) assoc (:key entry) entry)
    (dissoc entry :fn-value)))

(defn unregister-event-handler!
  "Remove a registered event handler by key."
  [runtime key]
  (let [key (validate-event-handler-key! key)]
    (swap! (:handler-registry (event-system runtime)) dissoc key)
    {:unregistered key}))

(defn event-handlers
  "Return registered event handler metadata."
  [runtime]
  (mapv #(dissoc % :fn-value)
        (sort-by (comp pr-str :key) (vals @(:handler-registry (event-system runtime))))))

(defn recent-event-failures
  "Return recent asynchronous event handler failures."
  [runtime]
  @(:recent-failures (event-system runtime)))

(defn enqueue-event!
  "Submit an event to the runtime event system."
  [runtime event]
  (when-not (map? event)
    (throw (ex-info "Event must be a map" {:event event})))
  (doseq [k [:event/type :event/id :event/at :event/source]]
    (when-not (contains? event k)
      (throw (ex-info "Event requires key" {:key k :event event}))))
  (when-not (keyword? (:event/type event))
    (throw (ex-info "Event :event/type must be a keyword" {:event/type (:event/type event)})))
  (when-not (.offer (:queue (event-system runtime)) event)
    (throw (ex-info "Event queue is full" {:event/type (:event/type event) :event/id (:event/id event)})))
  {:enqueued true :event/id (:event/id event) :event/type (:event/type event)})

;; Scheduler wakes: durable weaver-owned coordination state (RFC-009), not
;; strands. `skein.core.db` owns storage validation and persistence;
;; `skein.core.weaver.scheduler` owns timer arming and dispatch. This tier adds
;; the one check storage cannot make on its own (handler resolvability in the
;; runtime's spool classloader, matching hook/event-handler registration) and
;; re-arms the runtime timer after every mutation so an earlier wake-at than the
;; currently armed timer, or a cancelled row, is picked up immediately rather
;; than waiting for the previously armed tick.

(defn- normalize-wake
  "Decode a scheduler wake/history row's JSON payload and handler symbol."
  [row]
  (some-> row
          (clojure.core/update :payload db/<-json)
          (clojure.core/update :handler symbol)))

(defn- resolve-scheduler-handler-fn! [runtime handler]
  (when-not (and (symbol? handler) (namespace handler))
    (throw (ex-info "Scheduler handler must be a fully qualified symbol" {:handler handler})))
  (let [resolved (try
                   (with-spool-classloader runtime #(requiring-resolve handler))
                   (catch Throwable t
                     (throw (ex-info "Scheduler handler could not be resolved" {:handler handler} t))))
        value (if (var? resolved) @resolved resolved)]
    (when-not (ifn? value)
      (throw (ex-info "Scheduler handler symbol must resolve to a callable value"
                      {:handler handler :resolved-class (str (class value))})))
    value))

(defn schedule-wake!
  "Persist or replace a durable scheduler wake and arm it for dispatch.

  wake is a map of :key (non-blank string), :wake-at (java.time.Instant),
  :handler (fully qualified symbol resolvable in runtime's spool classloader),
  and optional :payload (nil or a JSON-object-encodable map). Malformed shapes
  and unresolvable handlers fail loudly before anything is persisted."
  [runtime wake]
  (when-not (map? wake)
    (throw (ex-info "Scheduler wake must be a map" {:wake wake})))
  (resolve-scheduler-handler-fn! runtime (:handler wake))
  (let [created (normalize-wake (db/schedule-wake! (ds runtime) wake))]
    (scheduler/arm! runtime)
    created))

(defn cancel-wake!
  "Cancel a pending scheduler wake by key and return its cancellation history row.

  Missing keys fail loudly."
  [runtime key]
  (let [cancelled (normalize-wake (db/cancel-wake! (ds runtime) key))]
    (scheduler/arm! runtime)
    cancelled))

(defn pending-wakes
  "Return all pending scheduler wakes, ordered by wake-at ascending."
  [runtime]
  (mapv normalize-wake (db/pending-wakes (ds runtime))))

(defn recent-fires
  "Return recently completed scheduler wakes, newest first, capped by the DB's retained scheduler-history limit."
  [runtime]
  (mapv normalize-wake (db/recent-fires (ds runtime))))

(defn recent-cancellations
  "Return recently cancelled scheduler wakes, newest first, capped by the DB's retained scheduler-history limit."
  [runtime]
  (mapv normalize-wake (db/recent-cancellations (ds runtime))))

(defn recent-failures
  "Return recently failed scheduler wakes, newest first, capped by the DB's retained scheduler-history limit."
  [runtime]
  (mapv normalize-wake (db/recent-failures (ds runtime))))
