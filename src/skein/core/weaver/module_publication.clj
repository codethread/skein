(ns skein.core.weaver.module-publication
  "Prevalidate and publish complete module-owner contributions across kinds.

  Core stores and runtime-owned `registry.alpha` handles use the same
  owner-registry snapshot grammar. This namespace builds every affected
  candidate first, so a malformed entry or collision retains the owner's prior
  contribution in every kind; publication only begins after validation passes."
  (:require [skein.api.registry.alpha :as registry]
            [skein.core.weaver.core-registry :as core-registry]
            [skein.core.weaver.owner-registry :as owner-registry]))

(def ^:private registry-state-key
  :skein.api.registry.alpha/state)

(defn- core-backends [runtime]
  {:ops {:kind :ops :type :core :store (:op-store runtime)}
   :queries {:kind :queries :type :core :store (:query-store runtime)}
   :patterns {:kind :patterns :type :core :store (:pattern-store runtime)}
   :hooks {:kind :hooks :type :core :store (:hook-store runtime)}
   :events {:kind :events
            :type :core
            :store (get-in runtime [:event-system :handler-store])}})

(defn- domain-backends [runtime]
  (reduce-kv
   (fn [result state-key value]
     (if (registry/registry? value)
       (reduce-kv
        (fn [m kind-id _declaration]
          (when (contains? m kind-id)
            (throw (ex-info "Registry kind is declared by more than one runtime store"
                            {:kind kind-id
                             :spool-state/key state-key
                             :existing (:spool-state/key (get m kind-id))})))
          (assoc m kind-id {:kind kind-id
                            :type :domain
                            :handle value
                            :spool-state/key state-key}))
        result
        (:kinds (registry/snapshot value)))
       result))
   {}
   @(:spool-state runtime)))

(defn backends
  "Return the runtime's unique kind-id to publication-backend map.

  The five core stores are always present. Each runtime-owned registry handle
  found directly in `:spool-state` contributes its declared open kinds. A kind
  declared by two stores fails loudly before contribution evaluation."
  [runtime]
  (let [core (core-backends runtime)
        domain (domain-backends runtime)
        duplicate (seq (filter (set (keys core)) (keys domain)))]
    (when duplicate
      (throw (ex-info "Registry kind conflicts with a core kind"
                      {:kinds (vec (sort-by pr-str duplicate))})))
    (merge core domain)))

(defn- backend-snapshot [{:keys [type store handle]}]
  (case type
    :core (core-registry/snapshot store)
    :domain (registry/snapshot handle)))

(defn candidates
  "Return current immutable candidate snapshots for every publication backend."
  [backends]
  (update-vals backends backend-snapshot))

(defn- without-owner [snapshot owner]
  (owner-registry/normalize
   (update snapshot :partitions
           (fn [partitions]
             (into {}
                   (keep (fn [[kind-id owner-map]]
                           (let [remaining (dissoc owner-map owner)]
                             (when (seq remaining) [kind-id remaining]))))
                   partitions)))))

(defn remove-owner
  "Return candidates with `owner` removed completely from every declared kind."
  [candidate-map owner]
  (update-vals candidate-map #(without-owner % owner)))

(defn stage-owner
  "Validate and stage one owner's complete workspace-layer contribution.

  `contribution` is `{kind-id {:entries {...} :overrides #{...}}}`. Kinds the
  owner previously supplied but now omits are removed. Any undeclared kind,
  invalid entry, same-layer collision, or missing override intent throws while
  leaving the caller's prior candidate map unchanged."
  [backends candidate-map owner contribution]
  (let [unknown (seq (remove (set (keys backends)) (keys contribution)))]
    (when unknown
      (throw (ex-info "Module contribution names undeclared registry kinds"
                      {:module/key owner
                       :kinds (vec (sort-by pr-str unknown))})))
    (reduce-kv
     (fn [result kind-id partition]
       (let [snapshot (get result kind-id)
             candidate (-> snapshot
                           (assoc-in [:partitions kind-id owner]
                                     {:layer :workspace
                                      :entries (:entries partition)
                                      :overrides (:overrides partition)})
                           owner-registry/normalize)]
         (assoc result kind-id candidate)))
     (remove-owner candidate-map owner)
     contribution)))

(defn- publish-core! [{:keys [store kind]} snapshot]
  (reset! (:kernel store) snapshot)
  (reset! (:effective store)
          (owner-registry/effective-values snapshot kind)))

(defn- publish-domain! [{:keys [handle]} snapshot]
  ;; `registry.alpha` deliberately publishes one immutable owner-registry
  ;; snapshot atom. The coordinator is the engine-side multi-kind transaction
  ;; boundary and swaps that same handle only after all candidate snapshots have
  ;; validated. Public callers continue to mutate through registry.alpha.
  (reset! (get handle registry-state-key) snapshot))

(defn changed-kinds
  "Return the kind ids whose candidate snapshot differs from the live backend.

  The effect-free counterpart of `publish!`, used by the dry-run plan path to
  diff intentions without swapping any registry snapshot."
  [backends candidate-map]
  (reduce-kv
   (fn [changed kind-id backend]
     (if (= (backend-snapshot backend) (get candidate-map kind-id))
       changed
       (conj changed kind-id)))
   []
   backends))

(defn publish!
  "Publish all changed candidate snapshots and return changed kind ids.

  Callers must build candidates solely through `remove-owner`/`stage-owner`,
  which normalize before this function runs. Each kind's readers observe one
  immutable before-or-after snapshot; no event lane is stopped or cleared."
  [backends candidate-map]
  (reduce-kv
   (fn [changed kind-id backend]
     (let [before (backend-snapshot backend)
           after (get candidate-map kind-id)]
       (if (= before after)
         changed
         (do
           (case (:type backend)
             :core (publish-core! backend after)
             :domain (publish-domain! backend after))
           (conj changed kind-id)))))
   []
   backends))
