(ns skein.spools.test-support
  "Shared fixtures for spool and weaver tests: disposable temp config-dir
  workspaces, a started weaver runtime wrapper, the shared await-budget-ms
  poll-deadline knob, and the poll-until predicate poller that every
  wait-until/await-eventually/await-* helper across the suite wraps instead
  of reinventing its own deadline/sleep/recur loop."
  (:require [clojure.java.io :as io]
            [clojure.test :as t]
            [skein.api.weaver.alpha :as api]
            [skein.core.db-test :as db-test]
            [skein.core.weaver.config :as daemon-config]
            [skein.core.weaver.runtime :as runtime]))

(defn test-world [config-dir]
  (daemon-config/world config-dir
                       (str config-dir "/state")
                       (str config-dir "/data")))

(defn temp-config-dir
  "Create a fresh temp directory for a disposable weaver config/state/data
  workspace.

  opts: `:prefix` (temp-dir name prefix, for telling concurrent test runs
  apart; default \"skein-spools-config\") and `:nest-skein?` (default false).
  Some spool code (e.g. agent-run's `pinned-strand-command`) derives a
  \"workspace root\" from the config-dir's parent, matching the real
  repo-root/.skein layout; set `:nest-skein?` true so the returned config-dir
  is a `.skein` child of the temp root instead of the root itself, when a
  test exercises that derivation."
  ([] (temp-config-dir {}))
  ([{:keys [prefix nest-skein?] :or {prefix "skein-spools-config"}}]
   (let [root (.toFile (java.nio.file.Files/createTempDirectory
                        (.toPath (io/file "/tmp"))
                        prefix
                        (make-array java.nio.file.attribute.FileAttribute 0)))
         config-dir (if nest-skein? (io/file root ".skein") root)]
     (doto config-dir (.mkdirs)))))

(defn await-budget-ms
  "Poll deadline for cross-thread/subprocess readiness waits, in ms. Scales
  `base-ms` (default 10000) via the SKEIN_TEST_AWAIT_SCALE env var (a
  multiplier, default 1) so a single knob widens every test's fixed poll
  deadlines under fork-heavy or otherwise loaded machines instead of editing
  each call site."
  ([] (await-budget-ms 10000))
  ([base-ms]
   (long (* base-ms (if-let [scale (System/getenv "SKEIN_TEST_AWAIT_SCALE")]
                      (try (Double/parseDouble scale)
                           (catch NumberFormatException _
                             (throw (ex-info "SKEIN_TEST_AWAIT_SCALE must be a number"
                                             {:env "SKEIN_TEST_AWAIT_SCALE" :value scale}))))
                      1.0)))))

(defn await-budget-secs
  "await-budget-ms in whole seconds, for CLI/API surfaces that take a
  :timeout-secs rather than a millisecond budget."
  ([] (long (/ (await-budget-ms) 1000)))
  ([base-secs] (long (/ (await-budget-ms (* base-secs 1000)) 1000))))

(defn assert-state-shape
  "Assert that versioned-spool-state builder `new-state-fn` produces exactly
  `expected-keys`.

  The drift alarm for the versioned spool-state convention
  (docs/writing-shared-spools.md 'Versioned spool state'): a spool declares a
  `state-version` alongside `new-state`, and spool-state reuses a preserved map
  across `reload!` until that version changes. If `new-state` gains or loses a
  key without a matching version bump, a post-upgrade reload would silently reuse
  a shape-mismatched map, so each versioned spool pins its key set here and bumps
  both together. Call from a deftest with the spool's private `new-state` var,
  e.g. `(assert-state-shape #'chime/new-state #{:notifier-binding ...})`."
  [new-state-fn expected-keys]
  (t/is (= (set expected-keys) (set (keys (new-state-fn))))
        "spool-state key set drifted — bump the spool's state-version and this expected key set together"))

(defn with-runtime
  "Run `f` (a `(fn [rt config-dir] ...)`) against a fresh, disposable weaver
  runtime.

  opts: `:publish?` (default false — the runtime is thread-bound for `f` via
  `with-runtime-binding` rather than published as the process ambient
  runtime), and `:prefix`/`:nest-skein?`, threaded straight to
  `temp-config-dir`. Set `:publish? true` when `f` (or code it calls, e.g.
  spawned worker threads that resolve the runtime via `current/runtime`
  rather than a per-call binding) needs the ambient singleton to actually
  exist."
  ([f] (with-runtime {} f))
  ([opts f]
   (let [{:keys [publish?] :or {publish? false}} opts
         db-file (db-test/temp-db-file)
         config-dir (temp-config-dir (select-keys opts [:prefix :nest-skein?]))]
     (try
       (let [rt (runtime/start! db-file {:world (test-world (.getCanonicalPath config-dir))
                                         :publish? publish?})]
         (try
           (runtime/with-runtime-binding rt #(f rt config-dir))
           (finally
             (runtime/stop! rt))))
       (finally
         (db-test/delete-sqlite-family! db-file)
         ;; Runtime-added local roots are retained for the process lifetime by tools.deps.
         ;; Keep temp config dirs so later add-libs calls do not see stale basis entries.
         nil)))))

(defn poll-until
  "Poll `pred` (a no-arg fn) every `interval-ms` until it returns a truthy
  value or the deadline elapses, returning that value.

  opts: `:timeout-ms` (default `(await-budget-ms)`), `:interval-ms` (default
  50), and `:on-timeout` (a no-arg fn called once the deadline elapses without
  a truthy result; defaults to throwing a \"Timed out waiting for predicate\"
  ex-info). The canonical poll loop for cross-thread/subprocess readiness
  waits: every wait-until/await-eventually/await-* helper in this suite
  wraps this instead of reinventing the deadline/sleep/recur skeleton."
  ([pred] (poll-until pred {}))
  ([pred {:keys [timeout-ms interval-ms on-timeout]
          :or {timeout-ms (await-budget-ms)
               interval-ms 50
               on-timeout #(throw (ex-info "Timed out waiting for predicate" {}))}}]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (if-let [v (pred)]
         v
         (if (> (System/currentTimeMillis) deadline)
           (on-timeout)
           (do (Thread/sleep interval-ms) (recur))))))))

(defn await-phase
  "Poll until the strand's agent-run/phase is in `phases` or timeout; return it.

  Shared by agent-run and delegation spool tests so neither requires the other's
  test namespace for a wait helper."
  ([rt id phases] (await-phase rt id phases (await-budget-ms)))
  ([rt id phases timeout-ms]
   (poll-until
    #(let [strand (api/show rt id)]
       (when (contains? phases (get-in strand [:attributes :agent-run/phase])) strand))
    {:timeout-ms timeout-ms
     :on-timeout #(throw (ex-info "Timed out waiting for run phase"
                                  {:id id :want phases :strand (api/show rt id)}))})))
