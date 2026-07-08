(ns skein.api.weaver.alpha
  "Trusted in-process API for manipulating strands and weaver runtime registries."
  (:refer-clojure :exclude [list update use])
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [skein.api.cli.alpha :as cli]
            [skein.core.db :as db]
            [skein.core.weaver.access :refer [ds normalize query-registry view-registry
                                              pattern-registry op-registry hook-registry
                                              approved-spool-sync-state module-use-state
                                              with-spool-classloader config-dir
                                              validate-fn-symbol!]]
            [skein.core.weaver.dispatch :as dispatch]
            [skein.core.weaver.lifecycle :refer [event-base request-context
                                                 run-validation-hooks! run-transform-hooks]]
            [skein.core.weaver.runtime :as runtime]
            [skein.core.weaver.scheduler :as scheduler]
            [skein.core.weaver.spool-sync :as spool-sync]
            [skein.core.query :as query]
            [skein.core.specs :as specs]))

(declare register-built-in-ops! apply-edges! op-detail)

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
  (let [approved (spool-sync/approved-spools runtime)
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
              (spool-sync/root-paths root)))
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
