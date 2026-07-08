(ns skein.api.weaver.alpha
  "Trusted in-process API for manipulating strands and weaver runtime registries."
  (:refer-clojure :exclude [list update use])
  (:require [clojure.java.io :as io]
            [clojure.repl.deps :as repl-deps]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [skein.api.cli.alpha :as cli]
            [skein.api.format.alpha :as format-alpha]
            [skein.core.db :as db]
            [skein.core.weaver.access :refer [ds normalize query-registry view-registry
                                              pattern-registry op-registry hook-registry
                                              approved-spool-sync-state module-use-state
                                              with-spool-classloader config-dir spools-file
                                              canonical-root cache-base validate-fn-symbol!]]
            [skein.core.weaver.dispatch :as dispatch]
            [skein.core.weaver.lifecycle :refer [event-base request-context
                                                 run-validation-hooks! run-transform-hooks]]
            [skein.core.weaver.runtime :as runtime]
            [skein.core.weaver.scheduler :as scheduler]
            [skein.core.query :as query]
            [skein.core.specs :as specs])
  (:import [java.nio.file FileSystemException FileVisitResult Files LinkOption SimpleFileVisitor StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(declare register-built-in-ops! apply-edges! op-detail)

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
       (not-any? #{".."} (deps-root-segments deps-root))))

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

(defn- legacy-config-present? [^java.io.File file]
  (or (.exists file)
      (java.nio.file.Files/isSymbolicLink (.toPath file))))

(defn- reject-legacy-spool-config! [runtime]
  (let [legacy-files (filter #(legacy-config-present? (spools-file runtime %)) ["libs.edn" "libs.local.edn"])]
    (when (seq legacy-files)
      (throw (ex-info (format-alpha/reflow
                       "|Legacy runtime library config files are no longer supported; rename
                         |libs.edn/libs.local.edn to spools.edn/spools.local.edn and change
                         |top-level :libs to :spools")
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

(defn- non-empty-directory? [^java.io.File file]
  (and (.isDirectory file)
       (boolean (seq (.list file)))))

(defn- delete-tree!
  "Delete a tree without following symlinks: links are removed as links, so a
  checkout containing a symlink can never delete content outside `file`."
  [^java.io.File file]
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
                  (-> (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String (cons "git" args)))
                      (.directory dir)
                      (.redirectErrorStream false)
                      (.start))
                  (catch java.io.IOException e
                    {:exit 127
                     :stderr (ex-message e)}))]
    (if (map? process)
      process
      (let [stderr (future (slurp (.getErrorStream ^Process process)))
            stdout (future (slurp (.getInputStream ^Process process)))
            exit (.waitFor ^Process process)]
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

(defn- cache-root-file ^java.io.File [entry]
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

(defn- git-cache-miss-failure [entry ^java.io.File cache-root result]
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

(defn- move-git-spool-to-cache! [^java.io.File tmp ^java.io.File cache-root]
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
    (doseq [^java.io.File path (root-paths root)]
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

      (or (not (.isDirectory root-file)) (not (.canRead root-file)))
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
     :searched-roots (mapv #(.getCanonicalPath ^java.io.File %) roots)}))

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

(defn init
  "Initialize the runtime database schema."
  [runtime]
  (db/init! (ds runtime))
  {:database "initialized"})

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
     (dispatch/enqueue! runtime (assoc (event-base :strand/added)
                                       :strand/id (:id created)
                                       :strand created))
     created)))

(defn- apply-edges! [tx id edges]
  (doseq [{:keys [to type attributes]} edges]
    (when-not (db/get-strand tx to)
      (throw (ex-info "Edge target strand not found" {:to to :type type})))
    (db/add-edge! tx {:from id :to to :type type :attributes (or attributes {})})))

(def ^:private update-patch-keys #{:title :state :attributes :edges})

(defn- reject-unknown-update-keys! [patch]
  (when-let [unknown (seq (remove update-patch-keys (keys patch)))]
    (throw (ex-info "Unknown strand update fields" {:fields (vec unknown)}))))

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
     (dispatch/enqueue! runtime (assoc (event-base :strand/updated)
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
     (dispatch/enqueue! runtime (merge (event-base :strand/superseded)
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

(defn- require-archive-result! [result]
  (when-not (s/valid? ::specs/attribute-archive-result result)
    (throw (ex-info "Attribute archive result is invalid"
                    {:result result
                     :explain (s/explain-str ::specs/attribute-archive-result result)})))
  result)

(defn- require-omitted-attribute-descriptor! [descriptor]
  (when-not (s/valid? ::specs/omitted-attribute-descriptor descriptor)
    (throw (ex-info "Omitted attribute descriptor is invalid"
                    {:descriptor descriptor
                     :explain (s/explain-str ::specs/omitted-attribute-descriptor descriptor)})))
  descriptor)

(defn- require-lean-result! [result]
  (doseq [strand result
          [_ value] (:attributes strand)
          :when (:skein/omitted value)]
    (require-omitted-attribute-descriptor! value))
  result)

(defn archive!
  "Archive all attributes, or an explicit non-empty key set, for one strand.

  A later write to an archived key makes that key hot again. Untouched archived
  keys remain archived.

  This is a trusted in-process primitive only; it has no socket or CLI surface."
  ([runtime strand-id]
   (require-archive-result! (db/archive-attributes! (ds runtime) strand-id)))
  ([runtime strand-id keys]
   (require-archive-result! (db/archive-attributes! (ds runtime) strand-id keys))))

(defn unarchive!
  "Unarchive all attributes, or an explicit non-empty key set, for one strand.

  A later write to an archived key has the same hot-data result for that key.
  Untouched archived keys remain archived.

  This is a trusted in-process primitive only; it has no socket or CLI surface."
  ([runtime strand-id]
   (require-archive-result! (db/unarchive-attributes! (ds runtime) strand-id)))
  ([runtime strand-id keys]
   (require-archive-result! (db/unarchive-attributes! (ds runtime) strand-id keys))))

(defn show
  "Return one normalized strand by id, or nil when absent."
  [runtime id]
  (normalize (db/get-strand (ds runtime) id)))

(defn list
  "Return strands visible to `runtime`, optionally filtered by a query definition."
  ([runtime]
   (normalize (db/all-strands (ds runtime))))
  ([runtime query-def params]
   (normalize (db/all-strands (ds runtime) query-def params))))

(defn list-lean
  "Return strands with oversized attributes replaced by descriptors.

  The optional limit arity is for the CLI/wire read surface; the trusted
  in-process arities remain unbounded by default."
  ([runtime lean-byte-floor]
   (require-lean-result! (normalize (db/all-strands-lean (ds runtime) lean-byte-floor))))
  ([runtime lean-byte-floor query-def params]
   (require-lean-result! (normalize (db/all-strands-lean (ds runtime) lean-byte-floor query-def params))))
  ([runtime lean-byte-floor query-def params limit]
   (require-lean-result! (normalize (db/all-strands-lean (ds runtime) lean-byte-floor query-def params limit)))))

(defn list-query
  "Return strands matching a registered query definition."
  [runtime query-name params]
  (list runtime (query/query-def @(query-registry runtime) query-name) params))

(defn ready
  "Return ready strands for `runtime`, optionally filtered by a query definition."
  ([runtime]
   (normalize (db/ready-strands (ds runtime))))
  ([runtime query-def params]
   (normalize (db/ready-strands (ds runtime) query-def params))))

(defn ready-lean
  "Return ready strands with oversized attributes replaced by descriptors.

  The optional limit arity is for the CLI/wire read surface; the trusted
  in-process arities remain unbounded by default."
  ([runtime lean-byte-floor]
   (require-lean-result! (normalize (db/ready-strands-lean (ds runtime) lean-byte-floor))))
  ([runtime lean-byte-floor query-def params]
   (require-lean-result! (normalize (db/ready-strands-lean (ds runtime) lean-byte-floor query-def params))))
  ([runtime lean-byte-floor query-def params limit]
   (require-lean-result! (normalize (db/ready-strands-lean (ds runtime) lean-byte-floor query-def params limit)))))

(defn ready-query
  "Return ready strands from the result set of a registered query definition."
  [runtime query-name params]
  (ready runtime (query/query-def @(query-registry runtime) query-name) params))

(defn- validate-op-fn-symbol! [fn-sym]
  (validate-fn-symbol! "Operation" fn-sym))

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
