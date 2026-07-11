(ns skein.spools.bench.exec
  "Host-side deterministic workspace preparation and container execution for the
  bench spool.

  This namespace owns everything below the strand graph: cached git mirrors, the
  per-entry clone/checkout/overlay/commit, engine argv compilation, the three
  sequential container invocations (setup, agent, validation), image-digest
  recording, the wall-clock timeout kill, and the post-run diff capture. It
  speaks the docker/podman `run`/`inspect`/`kill` CLI dialect through
  `ProcessBuilder`, so an injected fake-engine script exercises the whole path
  without a container runtime present.

  All process interop is type-hinted so `make reflect-check` stays clean; every
  function fails loudly (TEN-003) rather than papering over a git or engine
  error."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [skein.api.spool.alpha :refer [fail!]])
  (:import [java.io File]
           [java.lang ProcessBuilder$Redirect]
           [java.util Map]
           [java.util.concurrent ConcurrentHashMap TimeUnit]
           [java.util.function Function]))

;; ---------------------------------------------------------------------------
;; Low-level process helpers

(defn capture!
  "Run `argv` and return `{:exit :out :err}`, capturing stdout/stderr fully.

  Redirects both streams to temp files so a chatty command never deadlocks on a
  full pipe buffer. `opts` may carry `:dir` (working directory) and `:stdin`
  (a string written to the process then closed). Blocks until the process exits."
  [argv {:keys [dir stdin]}]
  (let [out (File/createTempFile "bench-capture-out" ".log")
        err (File/createTempFile "bench-capture-err" ".log")]
    (try
      (let [pb (ProcessBuilder. ^java.util.List (vec argv))]
        (when dir (.directory pb (io/file dir)))
        (.redirectOutput pb (ProcessBuilder$Redirect/to out))
        (.redirectError pb (ProcessBuilder$Redirect/to err))
        (let [^Process p (.start pb)]
          (with-open [in (.getOutputStream p)]
            (when stdin (.write in (.getBytes ^String stdin "UTF-8"))))
          (.waitFor p)
          {:exit (.exitValue p) :out (slurp out) :err (slurp err)}))
      (finally
        (.delete out)
        (.delete err)))))

(defn kill-container!
  "Best-effort `<engine> kill <container-name>`; swallow any failure.

  Used on timeout, abort, and reconciliation where a container may already be
  gone (`--rm`); the kill is opportunistic, never load-bearing."
  [engine container-name]
  (try
    (capture! (into (vec engine) ["kill" container-name]) {})
    (catch Throwable _ nil)))

(defn- run-redirected!
  "Run `argv`, redirecting stdout/stderr to `out-file`/`err-file`, with a
  wall-clock `timeout-ms`.

  When `out-file` and `err-file` name the same file the streams are merged into
  one (`redirectErrorStream`), so a single log file is never opened by two
  independent truncating handles that clobber each other's bytes; distinct files
  keep separate redirects. On start, `on-start` (when supplied) receives the live
  `Process` so a caller can track it for abort. On timeout the container is
  killed by name and the process force-destroyed. Returns `{:exit :timed-out?}`
  (`:exit` nil on timeout)."
  [{:keys [engine container-name argv stdin out-file err-file timeout-ms on-start]}]
  (let [^File out (io/file out-file)
        ^File err (io/file err-file)
        pb (ProcessBuilder. ^java.util.List (vec argv))]
    (.redirectOutput pb (ProcessBuilder$Redirect/to out))
    (if (= (.getCanonicalPath out) (.getCanonicalPath err))
      (.redirectErrorStream pb true)
      (.redirectError pb (ProcessBuilder$Redirect/to err)))
    (let [^Process p (.start pb)]
      (when on-start (on-start p))
      (with-open [in (.getOutputStream p)]
        (when stdin (.write in (.getBytes ^String stdin "UTF-8"))))
      (if (.waitFor p (long timeout-ms) TimeUnit/MILLISECONDS)
        {:exit (.exitValue p) :timed-out? false}
        (do
          (kill-container! engine container-name)
          (.destroyForcibly p)
          (.waitFor p 5 TimeUnit/SECONDS)
          {:exit nil :timed-out? true})))))

;; ---------------------------------------------------------------------------
;; Git host-side preparation

(defn- ^File file [& parts] (apply io/file parts))

(defn- git!
  "Run a git subcommand, failing loudly on non-zero exit.

  `dir` is the working directory (nil for clone). Returns the capture map. A
  failure's message and ex-data both carry the full command and trimmed
  stderr, so a caller never needs filesystem forensics to see what git did."
  [dir & args]
  (let [res (capture! (into ["git"] args) {:dir dir})]
    (when-not (zero? (:exit res))
      (let [cmd (str/join " " (cons "git" args))
            stderr (str/trim (:err res))
            first-line (first (str/split-lines stderr))]
        (fail! (str cmd ": " first-line)
               {:cmd cmd :args (vec args) :dir (some-> dir str) :exit (:exit res) :err stderr})))
    res))

(defonce ^:private mirror-locks
  ;; Deliberately JVM-global, not runtime-owned spool state: mirrors live at
  ;; filesystem paths shared across runtimes in one JVM, so a runtime-scoped
  ;; lock could not serialize two runtimes cloning the same mirror path. Keyed
  ;; by canonical path; entries are plain lock Objects, one per distinct repo.
  (ConcurrentHashMap.))

(defn- mirror-lock
  "Return the JVM-level lock object for `mirror`'s canonical path, creating one
  on first use.

  Concurrent prepares for the same repo URL serialize on this lock instead of
  racing an uncached `git clone --mirror` onto a shared destination path."
  [^File mirror]
  (.computeIfAbsent ^ConcurrentHashMap mirror-locks
                     (.getCanonicalPath mirror)
                     (reify Function
                       (apply [_ _] (Object.)))))

(defn- sanitize-url [repo]
  (let [slug (-> (str repo)
                 (str/replace #"[^a-zA-Z0-9]+" "-")
                 (str/replace #"^-+|-+$" ""))
        slug (subs slug 0 (min 40 (count slug)))]
    (str slug "-" (Integer/toHexString (Math/abs (long (hash (str repo))))))))

(defn mirror-dir
  "Return the cached mirror directory `File` for `repo` under `data-dir`."
  [data-dir repo]
  (file data-dir "mirrors" (sanitize-url repo)))

(defn ensure-mirror!
  "Return the mirror `File` for `repo`, cloning `--mirror` once on first use.

  The mirror is a per-URL cache under `<data-dir>/mirrors/`; entries clone from
  it so they never share working state. Creation is serialized on a per-mirror
  lock so two entries prepping concurrently for the same uncached repo URL
  never both race a `git clone --mirror` onto the same destination; the
  second waiter re-checks under the lock and finds the mirror already there."
  [data-dir repo]
  (let [^File m (mirror-dir data-dir repo)]
    (locking (mirror-lock m)
      (when-not (.exists (file m "HEAD"))
        (.mkdirs ^File (.getParentFile m))
        (git! nil "clone" "--mirror" (str repo) (.getCanonicalPath m))))
    m))

(defn resolve-rev!
  "Fetch `repo`'s mirror and resolve `rev` to a concrete 40-hex sha.

  Called at run time for a `:rev` suite so the resolved sha can be stamped on
  the run root and reproduce the run. The fetch is serialized on the same
  per-mirror lock as `ensure-mirror!` so it never races a concurrent clone or
  fetch of the same mirror."
  [data-dir repo rev]
  (let [m (ensure-mirror! data-dir repo)]
    (locking (mirror-lock m)
      (git! m "fetch" "--all" "--prune"))
    (str/trim (:out (git! m "rev-parse" rev)))))

(defn- expand-home [path]
  (if (str/starts-with? path "~")
    (str (System/getProperty "user.home") (subs path 1))
    path))

(defn- resolve-host-path [workspace-root path]
  (let [f (io/file path)]
    (if (.isAbsolute f) f (io/file workspace-root path))))

(defn- copy-dir! [^File src ^File dest]
  (.mkdirs dest)
  (doseq [^File child (.listFiles src)]
    (let [target (io/file dest (.getName child))]
      (if (.isDirectory child)
        (copy-dir! child target)
        (io/copy child target)))))

(defn- apply-overlay!
  "Apply the `:files` overlay onto the checked-out workspace, returning the list
  of applied paths."
  [^File workspace files workspace-root]
  (mapv (fn [[path spec]]
          (let [dest (io/file workspace path)]
            (.mkdirs ^File (.getParentFile dest))
            (cond
              (contains? spec :content) (spit dest (:content spec))
              (:path spec) (io/copy (resolve-host-path workspace-root (:path spec)) dest)
              (:dir spec) (copy-dir! (resolve-host-path workspace-root (:dir spec)) dest)
              :else (fail! "bench overlay entry must have :content, :path, or :dir"
                           {:path path :spec spec}))
            (str path)))
        files))

(defn- delete-recursively! [^File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [^File child (.listFiles f)] (delete-recursively! child)))
    (.delete f)))

(defn- apply-remove!
  "Delete each `:remove` path from the workspace, returning the removed paths."
  [^File workspace remove-paths]
  (mapv (fn [path]
          (delete-recursively! (io/file workspace path))
          (str path))
        remove-paths))

(defn- overlay-dirty? [^File workspace]
  (not (zero? (:exit (capture! ["git" "diff" "--cached" "--quiet"] {:dir workspace})))))

(defn prepare-workspace!
  "Clone `repo`'s mirror into `<entry-dir>/workspace`, check out `sha`, apply the
  `:files` overlay and `:remove` list, and commit the overlay.

  Leaves the workspace clean at a single `bench: pinned overlay` commit so the
  agent's own diff is cleanly separable. `home/` is created empty for the
  container HOME mount. Returns `{:workspace :home :overlay}` (the overlay
  listing for the manifest)."
  [{:keys [data-dir repo sha entry-dir files remove-paths workspace-root]}]
  (let [ws (io/file entry-dir "workspace")
        home (io/file entry-dir "home")
        ^File m (ensure-mirror! data-dir repo)]
    ;; A retry reuses the same entry dir; start from a clean slate so the clone
    ;; lands in an empty directory and no prior attempt's artifacts leak in.
    (delete-recursively! (io/file entry-dir))
    (.mkdirs ^File home)
    (git! nil "clone" (.getCanonicalPath m) (.getCanonicalPath ^File ws))
    (git! ws "checkout" "--detach" sha)
    (let [applied (apply-overlay! ws files workspace-root)
          removed (apply-remove! ws remove-paths)]
      (git! ws "add" "-A")
      (when (overlay-dirty? ws)
        (git! ws "-c" "user.email=bench@skein" "-c" "user.name=skein-bench"
              "commit" "-m" "bench: pinned overlay"))
      {:workspace ws
       :home home
       :overlay {:files applied :removed removed}})))

(defn capture-diff!
  "Stage everything and return the agent's full diff against the overlay commit.

  Runs `git add -A` then `git diff --cached`, writing the patch to `diff.patch`
  and returning `{:files :insertions :deletions}` parsed from `--numstat`."
  [{:keys [^File workspace entry-dir]}]
  (git! workspace "add" "-A")
  (let [patch (:out (git! workspace "diff" "--cached"))
        numstat (:out (git! workspace "diff" "--cached" "--numstat"))]
    (spit (io/file entry-dir "diff.patch") patch)
    (reduce (fn [acc line]
              (let [[ins del _] (str/split line #"\t" 3)]
                (if (str/blank? line)
                  acc
                  (-> acc
                      (update :files inc)
                      (update :insertions + (if (= "-" ins) 0 (parse-long ins)))
                      (update :deletions + (if (= "-" del) 0 (parse-long del)))))))
            {:files 0 :insertions 0 :deletions 0}
            (str/split-lines numstat))))

;; ---------------------------------------------------------------------------
;; Container engine argv

(defn container-name
  "Return the deterministic container name `skein-bench-<run-id>-<slug>`."
  [run-id slug]
  (str "skein-bench-" run-id "-" slug))

(defn- env-args [env]
  (into [] (mapcat (fn [[k v]] ["-e" (str (name k) "=" v)]) env)))

(defn compile-argv
  "Compile the engine `run` argv for one container invocation.

  `cmd` is the in-container command vector (agent argv, setup, or validation);
  `prompt-arg`, when present, is appended last (for `:prompt-via :arg`). Returns
  `{:argv :redacted}` — identical apart from auth env, whose real host values
  appear only in `:argv` and are masked in `:redacted` for the manifest."
  [{:keys [engine run-id slug image cmd env auth entry-dir prompt-arg]}]
  (let [name (container-name run-id slug)
        home-mount (str (.getCanonicalPath (io/file entry-dir "home")) ":/bench/home")
        ws-mount (str (.getCanonicalPath (io/file entry-dir "workspace")) ":/bench/workspace")
        base-env (merge {"HOME" "/bench/home" "SKEIN_BENCH_RUN" run-id} env)
        auth-names (:env auth)
        present-auth (filter #(System/getenv %) auth-names)
        auth-real (into [] (mapcat (fn [n] ["-e" (str n "=" (System/getenv n))]) present-auth))
        auth-masked (into [] (mapcat (fn [n] ["-e" (str n "=<redacted>")]) present-auth))
        auth-mounts (into [] (mapcat (fn [{:keys [host container]}]
                                       ["-v" (str (expand-home host) ":" container ":ro")])
                                     (:mounts auth)))
        prefix (into (vec engine) ["run" "--rm" "-i" "--name" name])
        tail (-> ["-v" home-mount "-v" ws-mount]
                 (into auth-mounts)
                 (into ["-w" "/bench/workspace" image]))
        cmd-argv (cond-> (vec cmd) prompt-arg (conj prompt-arg))
        assemble (fn [auth-env]
                   (-> prefix (into (env-args base-env)) (into auth-env) (into tail) (into cmd-argv)))]
    {:argv (assemble auth-real)
     :redacted (assemble auth-masked)}))

(defn image-digest!
  "Return `image`'s digest via `<engine> image inspect`, failing loudly when the
  image cannot be inspected (a missing image is an entry failure at launch)."
  [engine image]
  (let [res (capture! (into (vec engine) ["image" "inspect" "--format" "{{.Id}}" image]) {})]
    (if (zero? (:exit res))
      (str/trim (:out res))
      (fail! "bench container image inspect failed"
             {:image image :exit (:exit res) :err (:err res)}))))

;; ---------------------------------------------------------------------------
;; Entry execution: setup -> agent -> validation

(defn- write-manifest!
  [{:keys [entry-dir sha image-digest redacted-argv overlay prompt]}]
  (spit (io/file entry-dir "manifest.json")
        (json/write-str {:resolved-sha sha
                         :image-digest image-digest
                         :argv redacted-argv
                         :overlay overlay
                         :prompt prompt})))

(defn execute-entry!
  "Run one entry's three container invocations against a prepared workspace.

  `plan` carries the engine, `:run-id`/`:slug`/`:entry-dir`, container `:image`,
  agent `:env`/`:auth`, the resolved `:agent-cmd`/`:agent-prompt`/`:prompt-via`,
  optional `:setup`/`:validation` command vectors, `:timeout-ms`, the `:overlay`
  listing and `:sha` for the manifest, and an optional `:on-start` `(fn [proc])`
  for abort tracking.

  Records the image digest and manifest at launch, fails the entry loudly on
  setup failure or timeout, and on success returns the generic post-exit
  collection: `{:exit :duration-ms :stdout :diff :validation :image-digest}`."
  [{:keys [engine run-id slug entry-dir image env auth agent-cmd agent-prompt
           prompt-via setup validation timeout-ms overlay sha on-start]}]
  (let [name (container-name run-id slug)
        digest (image-digest! engine image)
        agent-compiled (compile-argv {:engine engine :run-id run-id :slug slug :image image
                                      :cmd agent-cmd :env env :auth auth :entry-dir entry-dir
                                      :prompt-arg (when (= :arg prompt-via) agent-prompt)})
        run-invocation (fn [cmd log-file prompt-arg stdin]
                         (run-redirected!
                          {:engine engine
                           :container-name name
                           :argv (:argv (compile-argv {:engine engine :run-id run-id :slug slug
                                                        :image image :cmd cmd :env env :auth auth
                                                        :entry-dir entry-dir :prompt-arg prompt-arg}))
                           :stdin stdin
                           :out-file log-file
                           :err-file log-file
                           :timeout-ms timeout-ms
                           :on-start on-start}))]
    (write-manifest! {:entry-dir entry-dir :sha sha :image-digest digest
                      :redacted-argv (:redacted agent-compiled) :overlay overlay
                      :prompt agent-prompt})
    ;; setup: any failure means the agent never runs
    (when (seq setup)
      (let [res (run-invocation setup (io/file entry-dir "setup.log") nil nil)]
        (when (:timed-out? res)
          (fail! "bench setup timed out" {:slug slug :timeout-ms timeout-ms}))
        (when-not (zero? (:exit res))
          (fail! "bench setup failed" {:slug slug :exit (:exit res)}))))
    ;; agent: prompt on stdin or argv per :prompt-via; stdout/stderr captured
    (let [stdout-file (io/file entry-dir "stdout")
          stderr-file (io/file entry-dir "stderr")
          started (System/currentTimeMillis)
          res (run-redirected!
               {:engine engine
                :container-name name
                :argv (:argv agent-compiled)
                :stdin (when (= :stdin prompt-via) agent-prompt)
                :out-file stdout-file
                :err-file stderr-file
                :timeout-ms timeout-ms
                :on-start on-start})
          duration-ms (- (System/currentTimeMillis) started)]
      (when (:timed-out? res)
        (fail! "bench agent timed out" {:slug slug :timeout-ms timeout-ms}))
      (let [validation-result
            (when (seq validation)
              (let [vres (run-invocation validation (io/file entry-dir "validation.log") nil nil)]
                {:exit (:exit vres) :cmd (str/join " " validation)}))
            diff (capture-diff! {:workspace (io/file entry-dir "workspace") :entry-dir entry-dir})
            stdout (slurp stdout-file)
            exit (:exit res)]
        ;; A non-zero agent exit still finalizes done when there is something to
        ;; measure (stdout or a diff); with nothing to measure it is a failure.
        (when (and (not (zero? exit)) (str/blank? stdout) (zero? (:files diff)))
          (fail! (str "agent exited " exit " with no artifacts") {:slug slug :exit exit}))
        (cond-> {:exit exit
                 :duration-ms duration-ms
                 :stdout stdout
                 :diff diff
                 :image-digest digest}
          validation-result (assoc :validation validation-result))))))
