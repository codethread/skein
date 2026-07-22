(ns skein.api.runtime.help-transform.alpha
  "Explicit-runtime at-most-one slot for the default help transform.

  The slot holds at most one transform: a function from the full canonical help
  response envelope (DELTA-Dtf-001.CC1) to the rendered string the CLI relays
  verbatim (JSON or text — the transform's choice). The `help` op renders through
  it when present, else emits the raw canonical envelope; `--json` always bypasses
  it, so a broken transform never bricks help (DELTA-Dtf-001.CC4). `about`/`prime`
  output is never transformed.

  The slot is runtime-owned and **reload-cleared**: it follows the op-registry
  lifecycle (SPEC-004.C46/C63a/C63c), cleared by `reload!` before config re-runs,
  not the reload-surviving `spool-state` (SPEC-004.C95). It is registered only by
  trusted `init.clj`/REPL config — no spool `install!` auto-registers it, so a
  fresh world (batteries absent, or present but not electing) keeps the raw-JSON
  floor (DELTA-Dtf-002.D1). This is the deliberate contrast with the glossary
  registry (`skein.api.runtime.glossary.alpha`), which each owning spool registers
  from its own `install!`.

  Discipline (TEN-002): the slot is at-most-one and set explicitly.
  `register-default-help-transform!` fails loudly when the slot is occupied,
  naming both registrants; `replace-default-help-transform!` is the deliberate
  override and requires a transform to already be registered.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument; nothing here reads the published ambient runtime."
  (:require [clojure.spec.alpha :as s]
            [skein.api.spool.alpha :refer [reject-unknown-keys! require-valid!]]
            [skein.core.weaver.access :as access]))

(declare set-transform replace-transform validate-registration!)

(def ^:private registration-keys
  "The closed key set of a default-help-transform registration map."
  #{:transform :owner})

(s/def ::transform ifn?)
(s/def ::owner (s/or :keyword keyword? :symbol symbol? :string string?))
(s/def ::registration (s/keys :req-un [::transform ::owner]))

(defn register-default-help-transform!
  "Register `registration` as `runtime`'s default help transform and return it.

  `registration` is a closed map `{:transform :owner}`: a `:transform` fn (full
  envelope → rendered string) and an `:owner` naming the registrant. Registering
  when the slot is already occupied fails loudly, naming both the existing and
  attempting owners; use `replace-default-help-transform!` for a deliberate
  override. The occupancy check runs inside the `swap!`, so two racing
  registrations cannot both clear a stale read."
  [runtime registration]
  (let [registration (validate-registration! registration)]
    (swap! (access/help-transform-slot runtime) set-transform registration)
    registration))

(s/fdef register-default-help-transform!
  :args (s/cat :runtime map? :registration ::registration)
  :ret ::registration)

(defn replace-default-help-transform!
  "Replace `runtime`'s registered default help transform, failing loudly when
  the slot is empty.

  Same map shape as `register-default-help-transform!`. This is the deliberate
  override for an occupied slot; unlike the register path it requires a transform
  to already be registered."
  [runtime registration]
  (let [registration (validate-registration! registration)]
    (swap! (access/help-transform-slot runtime) replace-transform registration)
    registration))

(s/fdef replace-default-help-transform!
  :args (s/cat :runtime map? :registration ::registration)
  :ret ::registration)

(defn default-help-transform
  "Return `runtime`'s registered default-help-transform registration map, or nil.

  The read/introspection projection: reports whether a transform is registered
  (`some?`) and its provenance (`:owner`), reading the runtime store explicitly."
  [runtime]
  @(access/help-transform-slot runtime))

(s/fdef default-help-transform
  :args (s/cat :runtime map?)
  :ret (s/nilable ::registration))

(defn default-help-transform-registered?
  "True when `runtime`'s default-help-transform slot holds a transform."
  [runtime]
  (some? @(access/help-transform-slot runtime)))

(s/fdef default-help-transform-registered?
  :args (s/cat :runtime map?)
  :ret boolean?)

;; --- validation and slot update fns -----------------------------------------

(defn- validate-registration!
  "Return `registration` after validating its closed shape, or fail loudly.

  Rejects a non-map, unknown keys, and a missing/invalid transform or owner up
  front — the validate half of the validate-then-record pattern."
  [registration]
  (when-not (map? registration)
    (require-valid! map? registration "Help transform registration must be a map"))
  (reject-unknown-keys! "register-default-help-transform!" registration-keys registration)
  (require-valid! ::registration registration
                  "Help transform registration has an invalid shape"))

(defn- set-transform
  "Slot `swap!` update fn: record `registration`, failing loudly when the slot is
  already occupied, naming both owners.

  Living inside the `swap!` keeps the occupancy check and the write atomic, so two
  racing registrations cannot both clear a stale read."
  [slot {:keys [owner] :as registration}]
  (when-let [existing slot]
    (throw (ex-info "Default help transform already registered"
                    {:existing-owner (:owner existing)
                     :attempted-owner owner})))
  registration)

(defn- replace-transform
  "Slot `swap!` update fn: replace `registration`, failing loudly when the slot is
  empty."
  [slot {:keys [owner] :as registration}]
  (when-not slot
    (throw (ex-info "No default help transform registered; cannot replace"
                    {:attempted-owner owner})))
  registration)
