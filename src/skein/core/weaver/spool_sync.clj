(ns skein.core.weaver.spool-sync
  "Approved-spool config parsing, git/local materialization, and runtime sync.

  Reads and validates the `spools.edn`/`spools.local.edn` allowlist, materializes
  git-pinned spool roots into the content-addressed cache, vets each root's
  Maven dependencies, and loads approved roots into a runtime's spool classloader
  while recording per-spool sync outcomes. Also resolves and loads module-use
  targets — `:file` paths confined to the config-dir and `:ns` sources located
  under synced roots — for `skein.api.runtime.alpha/use!`. Internal tier: the
  trusted `skein.api.runtime.alpha` surface delegates its
  `approved`/`sync!`/`syncs` publics and its module loading here and owns the
  blessed contract (SPEC-004, SPEC-005.C5)."
  (:require [clojure.java.io :as io]
            [clojure.repl.deps :as repl-deps]
            [clojure.string :as str]
            [skein.core.format :as format]
            [skein.core.query :as query]
            [skein.core.weaver.access :refer [approved-spool-sync-state
                                              with-spool-classloader config-dir spools-file
                                              canonical-root cache-base]])
  (:import [java.nio.file FileSystemException FileVisitResult Files LinkOption SimpleFileVisitor StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

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
      (throw (ex-info (format/reflow
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

(defn root-paths
  "Return a spool root's canonical classpath directories from its `deps.edn`.

  Reads `:paths` (defaulting to `[\"src\"]`) and resolves each entry against
  `root`. Approval covers the root only, so a path escaping it via `..` or a
  symlink fails loudly rather than loading unconsented code."
  [root]
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

(defn module-file
  "Resolve module-use `path` against `runtime`'s config-dir, failing on escape.

  Module-use `:file` targets are approved only within the selected config-dir, so
  a path resolving outside it (via `..` or a symlink) would load code the
  operator never consented to and fails loudly."
  [runtime path]
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

(defn load-synced-namespace!
  "Load `ns-sym` from `runtime`'s synced spool roots.

  An already-loaded namespace is a no-op. Otherwise the source is located under
  the synced approved roots and `load-file`d; a namespace whose source is in no
  root fails loudly with the roots that were searched."
  [runtime ns-sym]
  (if (find-ns ns-sym)
    {:ns ns-sym}
    (let [{:keys [file relative-path searched-roots]} (locate-synced-namespace-file runtime ns-sym)]
      (if file
        (do
          (load-file file)
          {:ns ns-sym :file file})
        (throw (ex-info "Could not locate namespace source in synced spool roots"
                        {:ns ns-sym
                         :relative-path relative-path
                         :searched-roots searched-roots}))))))

(defn- clojure-source? [^java.io.File file]
  (let [n (.getName file)]
    (and (.isFile file)
         (or (str/ends-with? n ".clj") (str/ends-with? n ".cljc")))))

(defn- source-file->ns-sym
  "Derive a namespace symbol from a source `file` relative to its classpath `dir`."
  [^java.io.File dir ^java.io.File file]
  (let [prefix (str (.getCanonicalPath dir) java.io.File/separator)
        relative (subs (.getCanonicalPath file) (count prefix))]
    (-> relative
        (str/replace #"\.cljc?$" "")
        (str/replace java.io.File/separator ".")
        (str/replace "_" "-")
        symbol)))

(defn- spool-namespace-sources
  "Discover `{:ns sym :file path}` for every `.clj`/`.cljc` under `root`'s consented
  `root-paths` classpath dirs. The reload file set is exactly that classpath, no wider."
  [root]
  (vec
   (for [^java.io.File dir (root-paths root)
         ^java.io.File file (file-seq dir)
         :when (clojure-source? file)]
     {:ns (source-file->ns-sym dir file)
      :file (.getCanonicalPath file)})))

(defn reload-synced-spool!
  "Make coordinate `coord`'s latest synced source live under the spool classloader.

  Resolves `coord`'s synced root from the runtime's approved-spool sync state and
  approved allowlist (a coordinate can be approved yet unsynced or sync-failed),
  discovers its namespace sources under the consented `root-paths` classpath, and
  `load-file`s each inside `with-spool-classloader`. Unlike `load-synced-namespace!`
  there is no load-once short-circuit — already-loaded namespaces are reloaded. Load
  order is provisional here; dependency ordering lands separately.

  Fails loudly with a reused `:reason` in ex-data (mirroring `sync-failed`'s
  `{:status :failed :reason …}` shape), checked in fixed order:
  `:not-approved` → `:not-synced` → `:sync-failed` → `:missing-root` →
  `:unreadable-root` → `:no-namespaces`. The on-disk root re-check mirrors
  `sync-approved-spool!`'s `exists`/`isDirectory`/`canRead` gate, so a root replaced
  by a file or permission-stripped since sync fails with `:missing-root`/
  `:unreadable-root` rather than a raw `load-file` exception carrying no `:reason`.

  Returns a data-first map naming the coordinate, its resolved canonical root, and
  the namespaces reloaded with their source files."
  [runtime coord]
  (let [approved (approved-spools runtime)
        syncs @(approved-spool-sync-state runtime)
        sync (get syncs coord)]
    (when-not (contains? (:spools approved) coord)
      (throw (ex-info "Spool coordinate is not approved"
                      {:status :failed :reason :not-approved :coord coord})))
    (when-not (contains? syncs coord)
      (throw (ex-info "Spool coordinate is not synced"
                      {:status :failed :reason :not-synced :coord coord})))
    (when-not (#{:loaded :already-available} (:status sync))
      (throw (ex-info "Spool coordinate did not sync successfully"
                      {:status :failed :reason :sync-failed :coord coord :sync sync})))
    (let [root (:root sync)
          root-file (io/file root)]
      (when-not (.exists root-file)
        (throw (ex-info "Synced spool root is missing on disk"
                        {:status :failed :reason :missing-root :coord coord :root root})))
      (when (or (not (.isDirectory root-file)) (not (.canRead root-file)))
        (throw (ex-info "Synced spool root is not a readable directory"
                        {:status :failed :reason :unreadable-root :coord coord :root root})))
      (let [canonical-root (.getCanonicalPath root-file)
            sources (spool-namespace-sources root)]
        (when (empty? sources)
          (throw (ex-info "Synced spool root has no namespace sources"
                          {:status :failed :reason :no-namespaces :coord coord :root canonical-root})))
        (with-spool-classloader
          runtime
          (fn [] (doseq [{:keys [file]} sources]
                   (load-file file))))
        {:coord coord
         :root canonical-root
         :namespaces (mapv #(select-keys % [:ns :file]) sources)}))))
