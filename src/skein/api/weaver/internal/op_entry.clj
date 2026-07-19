(ns skein.api.weaver.internal.op-entry
  "Registration-time validation and entry construction for weaver CLI ops.

  Plumbing behind `skein.api.weaver.alpha`'s `register-op!`/`replace-op!`: it
  normalizes the metadata argument, validates every declared field, and builds
  the validated registry entry with derived provenance and metadata defaults.
  These are plain public defns on an internal tier (SPEC-005.C5b); the alpha
  module composes `build-op-entry` into its registration story.

  The arg-spec and return-shape structural checks reach the
  `skein.api.cli.alpha` and `skein.api.return-shape.alpha` surfaces through
  `requiring-resolve`: an internal namespace must not statically require an
  alpha namespace (SPEC-003.C19a), so the cross-tier reach is dynamic."
  (:require [clojure.string :as str]
            [skein.core.query :as query]
            [skein.core.weaver.access :as access]))

(defn canonical-op-name
  "Return the registry key string for `op-name` (a simple symbol or keyword)."
  [op-name]
  (query/canonical-query-name op-name))

(defn validate-op-fn-symbol!
  "Require `fn-sym` to be a fully qualified handler symbol, returning it."
  [fn-sym]
  (access/validate-fn-symbol! "Operation" fn-sym))

(defn validate-op-doc!
  "Require `doc` to be a non-blank string, returning it."
  [doc]
  (when-not (and (string? doc) (not (str/blank? doc)))
    (throw (ex-info "Operation doc must be a non-blank string" {:doc doc})))
  doc)

(def op-metadata-keys
  "Metadata keys a caller may supply to register-op!/replace-op!."
  #{:doc :arg-spec :returns :stream? :deadline-class :hook-class})

(def op-deadline-classes
  "Accepted :deadline-class values."
  #{:standard :unbounded})

(def op-hook-classes
  "Accepted :hook-class values."
  #{:read :mutating})

(defn normalize-op-opts
  "Coerce a register-op! metadata argument into an options map.

  A string is the legacy positional doc; a map is the full metadata map; nil is
  the no-metadata case."
  [opts]
  (cond
    (nil? opts) {}
    (string? opts) {:doc opts}
    (map? opts) opts
    :else (throw (ex-info "Operation metadata must be a doc string or options map"
                          {:opts opts}))))

(defn validate-op-metadata!
  "Validate a normalized op metadata map, returning it.

  Rejects caller-supplied `:provenance` (registry-recorded from the handler
  namespace), unknown keys, and malformed `:stream?`/`:deadline-class`/
  `:hook-class` values."
  [opts]
  ;; Provenance is registry-recorded from the handler namespace; a caller must
  ;; never assert it. Reject it explicitly so the error is unambiguous even
  ;; though it would also trip the unknown-key check below.
  (when (contains? opts :provenance)
    (throw (ex-info
            "Operation :provenance is registry-recorded and cannot be supplied by the caller"
            {:provenance (:provenance opts)})))
  (when-let [unknown (seq (remove op-metadata-keys (keys opts)))]
    (throw (ex-info "Operation metadata contains unknown keys" {:keys (vec unknown)})))
  (when (and (contains? opts :stream?) (not (boolean? (:stream? opts))))
    (throw (ex-info "Operation :stream? must be a boolean" {:stream? (:stream? opts)})))
  (when (and (contains? opts :deadline-class)
             (not (op-deadline-classes (:deadline-class opts))))
    (throw (ex-info "Operation :deadline-class must be :standard or :unbounded"
                    {:deadline-class (:deadline-class opts)})))
  (when (and (contains? opts :hook-class)
             (not (op-hook-classes (:hook-class opts))))
    (throw (ex-info "Operation :hook-class must be :read or :mutating"
                    {:hook-class (:hook-class opts)})))
  opts)

(defn validate-op-arg-spec!
  "Structurally validate `arg-spec` for `op-name` through the CLI parser.

  Reached via requiring-resolve because an internal namespace must not
  statically require the `skein.api.cli.alpha` surface (SPEC-003.C19a)."
  [op-name arg-spec]
  (try
    ((requiring-resolve 'skein.api.cli.alpha/validate!) arg-spec)
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info "Operation arg-spec is invalid"
                      (assoc (ex-data e) :operation (canonical-op-name op-name))
                      e)))))

(defn invalid-returns!
  "Throw a canonicalized `:returns` validation error for `op-name`."
  [op-name reason message data]
  (throw (ex-info message
                  (merge {:operation (canonical-op-name op-name)
                          :reason reason}
                         data))))

(defn stream-return-case?
  "True when `return-case` is a stream case (a map declaring `:stream`)."
  [return-case]
  (and (map? return-case) (contains? return-case :stream)))

(defn validate-return-case-alignment!
  "Require one return case's stream marker to match the op's `stream?` flag."
  [op-name stream? return-case context]
  (when (not= stream? (stream-return-case? return-case))
    (invalid-returns! op-name
                      :return-stream-misalignment
                      "Operation :returns does not align with :stream?"
                      (assoc context :stream? stream? :returns return-case))))

(defn validate-op-returns!
  "Validate the `returns` declaration for `op-name`, returning it.

  Checks the return-shape structure, that `:subcommands` routing matches the
  arg-spec's subcommands exactly, and that each case's stream marker aligns
  with `stream?`. Reaches the return-shape surface via requiring-resolve
  (internal must not require an alpha namespace, SPEC-003.C19a)."
  [op-name arg-spec stream? returns]
  (try
    ((requiring-resolve 'skein.api.return-shape.alpha/validate!) returns)
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info "Operation :returns declaration is invalid"
                      (assoc (ex-data e) :operation (canonical-op-name op-name))
                      e))))
  (let [arg-subcommands (:subcommands arg-spec)
        return-subcommands (when (and (map? returns) (contains? returns :subcommands))
                             (:subcommands returns))]
    (if arg-subcommands
      (do
        (when-not return-subcommands
          (invalid-returns! op-name
                            :return-routing-misalignment
                            "Subcommand operation :returns must declare :subcommands"
                            {:returns returns}))
        (let [expected (set (keys arg-subcommands))
              actual (set (keys return-subcommands))]
          (when-not (= expected actual)
            (invalid-returns! op-name
                              :return-subcommand-misalignment
                              "Operation :returns subcommands must exactly match :arg-spec"
                              {:expected-subcommands (vec (sort expected))
                               :actual-subcommands (vec (sort actual))})))
        (doseq [[subcommand return-case] return-subcommands]
          (validate-return-case-alignment! op-name stream? return-case
                                           {:subcommand subcommand})))
      (do
        (when return-subcommands
          (invalid-returns! op-name
                            :return-routing-misalignment
                            "Flat operation :returns cannot declare :subcommands"
                            {:returns returns}))
        (validate-return-case-alignment! op-name stream? returns {}))))
  returns)

(defn build-op-entry
  "Build a validated op registry entry with metadata defaults and provenance.

  Provenance is derived from the handler symbol's namespace. `:deadline-class`
  defaults to `:unbounded` for stream ops and `:standard` otherwise;
  `:hook-class` defaults to `:mutating`, preserving today's hook-gated
  behavior."
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
      (some? (:arg-spec opts)) (assoc :arg-spec (validate-op-arg-spec! op-name
                                                                       (:arg-spec opts)))
      (contains? opts :returns) (assoc :returns (validate-op-returns! op-name
                                                                      (:arg-spec opts)
                                                                      stream?
                                                                      (:returns opts))))))
