(ns skein.spools.guild
  "Reference spool for declaring a versioned public weaver operation API.

  Guild ops are ordinary CLI operations registered in the weaver op registry.
  Names are documented as dotted, version-suffixed handles such as
  `gate.close.v1`; the underlying registry requires simple unqualified handles
  and therefore rejects namespaced keyword or symbol names. Optional input specs
  validate the parsed op input before the declared handler runs. Deprecation
  replaces an op with a stub that always fails loudly with structured data."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.weaver.alpha :as weaver]
            [skein.api.runtime.alpha :as runtime]))



(defn- state [rt]
  (runtime/spool-state rt ::state
                       #(hash-map :guild-ops (atom {})
                                  :deprecated-ops (atom {})
                                  :fallback-guild-name (atom nil))))

(defn- guild-ops [rt] (:guild-ops (state rt)))
(defn- deprecated-ops [rt] (:deprecated-ops (state rt)))
(defn- fallback-guild-name [rt] (:fallback-guild-name (state rt)))

(def ^:private defop-opt-keys #{:doc :spec})
(def ^:private deprecate-opt-keys #{:replacement :since})

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- reject-unknown-keys! [opts allowed context]
  (when-let [unknown (seq (remove allowed (keys opts)))]
    (fail! "Unknown guild option keys"
           {:context context :unknown (vec unknown) :allowed allowed}))
  opts)

(defn- require-spec-name! [spec-name]
  (when-not (or (keyword? spec-name) (symbol? spec-name))
    (fail! "Guild op spec must be a keyword or symbol" {:spec spec-name}))
  (when (= ::s/unknown (s/form spec-name))
    (fail! "Guild op spec is not registered" {:spec spec-name}))
  spec-name)

(defn- parse-input [argv]
  (case (count argv)
    0 {}
    1 (json/read-str (first argv) :key-fn keyword)
    (fail! "Guild ops accept zero args or one JSON input arg" {:argv argv})))

(defn- spec-summary [spec-name]
  (when spec-name
    {:spec (str spec-name)}))

(defn- validate-input! [name spec-name input]
  (when spec-name
    (require-spec-name! spec-name)
    (when-not (s/valid? spec-name input)
      (let [explain (s/explain-data spec-name input)]
        (fail! "Guild op input failed spec validation"
               {:code :op/input-invalid
                :op name
                :spec (str spec-name)
                :explain explain}))))
  input)

(defn- op-registered? [rt name]
  (try
    (weaver/resolve-op rt name)
    true
    (catch clojure.lang.ExceptionInfo _
      false)))

(defn- register-or-replace-op!
  "Upsert a guild op in the weaver registry.

  The guild owns its op lifecycle through its own state atoms and (re)declares
  ops as it installs, deprecates, and reloads; the registry's loud-collision
  default is the wrong policy here, so re-declaration is an explicit replace."
  [rt name doc handler-sym]
  (if (op-registered? rt name)
    (weaver/replace-op! rt name doc handler-sym)
    (weaver/register-op! rt name doc handler-sym)))

(defn dispatch-op
  "Dispatch a guild-declared operation after parsing and validating input."
  [{:op/keys [name argv] :as ctx}]
  (let [{:keys [handler spec]} (or (get @(guild-ops (:op/runtime ctx)) name)
                                  (fail! "Guild op is not registered" {:op name}))
        input (validate-input! name spec (parse-input argv))]
    ((requiring-resolve handler) (assoc ctx :guild/input input))))

(defn deprecated-op
  "Fail loudly for a deprecated guild operation."
  [{:op/keys [name] :as ctx}]
  (let [{:keys [replacement since]} (or (get @(deprecated-ops (:op/runtime ctx)) name)
                                       (fail! "Guild op is not deprecated" {:op name}))]
    (fail! "Guild op is deprecated"
           (cond-> {:code :op/deprecated
                    :op name
                    :replacement replacement}
             since (assoc :since since)))))

(defn defop!
  "Register a guild operation in the CLI operation registry.

  `name` is a simple unqualified registry handle, conventionally dotted and
  version-suffixed such as `gate.close.v1`. `opts` supports `:doc` and optional
  `:spec`; unknown options fail loudly. `handler-fn-sym` must be a fully
  qualified symbol resolving in the weaver JVM. The handler receives the usual
  op context plus parsed JSON input at `:guild/input`."
  [name opts handler-fn-sym]
  (when-not (map? opts)
    (fail! "Guild op opts must be a map" {:opts opts}))
  (reject-unknown-keys! opts defop-opt-keys :defop)
  (when-not (and (symbol? handler-fn-sym) (namespace handler-fn-sym))
    (fail! "Guild op handler must be a fully qualified symbol" {:handler handler-fn-sym}))
  (requiring-resolve handler-fn-sym)
  (when-let [spec (:spec opts)]
    (require-spec-name! spec))
  (let [registered (register-or-replace-op! (current/runtime) name (:doc opts) 'skein.spools.guild/dispatch-op)
        entry (cond-> {:name (:name registered)
                       :handler handler-fn-sym}
                (:doc opts) (assoc :doc (:doc opts))
                (:spec opts) (assoc :spec (:spec opts)))]
    (swap! (guild-ops (current/runtime)) assoc (:name entry) entry)
    (swap! (deprecated-ops (current/runtime)) dissoc (:name entry))
    entry))

(defn- guild-name [name]
  (when-not (or (and (symbol? name) (nil? (namespace name)))
                (and (keyword? name) (nil? (namespace name))))
    (fail! "Guild op names must be simple symbols or keywords" {:op name}))
  (clojure.core/name name))

(defn deprecate!
  "Replace a registered guild operation with a loud deprecation stub.

  `opts` requires `:replacement` and may include `:since`. Deprecated ops never
  return success; invocation throws ex-info with `:code :op/deprecated`."
  [name opts]
  (when-not (map? opts)
    (fail! "Guild deprecation opts must be a map" {:opts opts}))
  (reject-unknown-keys! opts deprecate-opt-keys :deprecate)
  (when-not (contains? opts :replacement)
    (fail! "Guild deprecation requires :replacement" {:opts opts}))
  (let [op-name (guild-name name)
        entry (or (get @(guild-ops (current/runtime)) op-name)
                  (fail! "Guild op is not registered" {:op name}))
        deprecated (select-keys opts [:replacement :since])]
    (register-or-replace-op! (current/runtime) name (:doc entry) 'skein.spools.guild/deprecated-op)
    (swap! (guild-ops (current/runtime)) dissoc (:name entry))
    (swap! (deprecated-ops (current/runtime)) assoc (:name entry) (assoc deprecated :doc (:doc entry)))
    (assoc deprecated :name (:name entry))))

(defn describe-op
  "Return JSON-safe metadata describing the installed guild API."
  [{:op/keys [runtime-metadata] :as ctx}]
  {:guild (or (:name runtime-metadata)
              (:friendly-name runtime-metadata)
              @(fallback-guild-name (:op/runtime ctx)))
   :active (mapv (fn [[name {:keys [doc spec]}]]
                   (cond-> {:name name}
                     doc (assoc :doc doc)
                     spec (merge (spec-summary spec))))
                 (sort-by key @(guild-ops (:op/runtime ctx))))
   :deprecated (mapv (fn [[name {:keys [replacement since doc]}]]
                       (cond-> {:name name :replacement replacement}
                         doc (assoc :doc doc)
                         since (assoc :since since)))
                     (sort-by key @(deprecated-ops (:op/runtime ctx))))})

(defn install!
  "Install the built-in `guild.describe` operation.

  The guild name is read from runtime metadata when available. Passing
  `guild-name` records a fallback value for contexts without runtime metadata.
  Re-running is reload-safe and clears prior guild declarations in this weaver JVM."
  ([]
   (install! nil))
  ([guild-name]
   (when (and (some? guild-name) (not (and (string? guild-name) (not (str/blank? guild-name)))))
     (fail! "Guild name must be a non-blank string" {:guild/name guild-name}))
   (reset! (guild-ops (current/runtime)) {})
   (reset! (deprecated-ops (current/runtime)) {})
   (reset! (fallback-guild-name (current/runtime)) guild-name)
   (register-or-replace-op! (current/runtime) 'guild.describe "Describe this weaver's guild operation API" 'skein.spools.guild/describe-op)))
