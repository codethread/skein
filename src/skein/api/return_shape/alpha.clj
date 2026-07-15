(ns skein.api.return-shape.alpha
  "Pure declarations and checks for weaver operation return values.

  Return shapes are finite EDN data. They describe JSON scalars, closed maps,
  and homogeneous sequential collections. Registry routing may wrap shapes in
  `:subcommands` or `:stream` declarations; this namespace has no registry or
  runtime state."
  (:require [clojure.set :as set]))

(def ^:private scalar-shapes
  #{:string :integer :number :boolean :null :json})

(def ^:private nullable-scalar-shapes
  #{:string :integer :number :boolean})

(defn- fail!
  [reason message data]
  (throw (ex-info message (assoc data ::error true :reason reason))))

(defn- exact-keys!
  [value allowed path kind]
  (when-let [unknown (seq (remove allowed (keys value)))]
    (fail! :unknown-declaration-keys
           (str "Unknown " kind " declaration keys")
           {:path path :keys (vec unknown) :value value})))

(declare validate-shape!)

(defn- validate-key-shapes!
  [field shapes path]
  (when-not (map? shapes)
    (fail! :invalid-map-fields
           (str "Return map " (name field) " fields must be a map")
           {:path (conj path field) :field field :value shapes}))
  (doseq [[key shape] shapes]
    (when-not (keyword? key)
      (fail! :invalid-map-key
             "Return map field names must be keywords"
             {:path (conj path field key) :field field :key key}))
    (validate-shape! shape (conj path field key)))
  shapes)

(defn- validate-map-shape!
  [shape path]
  (exact-keys! shape #{:type :required :optional :extra} path "map shape")
  (let [required (if (contains? shape :required) (:required shape) {})
        optional (if (contains? shape :optional) (:optional shape) {})]
    (validate-key-shapes! :required required path)
    (validate-key-shapes! :optional optional path)
    (when-let [overlap (seq (set/intersection (set (keys required))
                                              (set (keys optional))))]
      (fail! :overlapping-map-keys
             "Return map required and optional keys must not overlap"
             {:path path :keys (vec (sort overlap))}))
    (when (contains? shape :extra)
      (validate-shape! (:extra shape) (conj path :extra))))
  shape)

(defn- validate-collection-shape!
  [shape path]
  (exact-keys! shape #{:type :items} path "collection shape")
  (when-not (contains? shape :items)
    (fail! :missing-declaration-key
           "Return collection shape requires :items"
           {:path (conj path :items) :key :items}))
  (validate-shape! (:items shape) (conj path :items))
  shape)

(defn- validate-shape!
  [shape path]
  (cond
    (contains? scalar-shapes shape)
    shape

    (vector? shape)
    (if (and (= 2 (count shape))
             (= :nullable (first shape))
             (contains? nullable-scalar-shapes (second shape)))
      shape
      (fail! :invalid-nullable
             "Nullable return shapes must be [:nullable <scalar>] with a non-null JSON scalar"
             {:path path :value shape
              :allowed (vec (sort nullable-scalar-shapes))}))

    (map? shape)
    (case (:type shape)
      :map (validate-map-shape! shape path)
      :collection (validate-collection-shape! shape path)
      (fail! :invalid-shape
             "Return shape maps require :type :map or :collection"
             {:path path :value shape}))

    :else
    (fail! :invalid-shape
           "Return shape must be a supported scalar, nullable scalar, map, or collection declaration"
           {:path path :value shape})))

(defn- validate-stream!
  [declaration path]
  (exact-keys! declaration #{:stream} path "stream return")
  (let [stream (:stream declaration)]
    (when-not (map? stream)
      (fail! :invalid-stream
             "Return :stream declaration must be a map"
             {:path (conj path :stream) :value stream}))
    (exact-keys! stream #{:emits :result} (conj path :stream) "stream channel")
    (doseq [channel [:emits :result]]
      (when-not (contains? stream channel)
        (fail! :missing-declaration-key
               (str "Return stream requires " channel)
               {:path (conj path :stream channel) :key channel}))
      (validate-shape! (get stream channel) (conj path :stream channel))))
  declaration)

(defn- validate-return-case!
  [return-case path]
  (if (and (map? return-case) (contains? return-case :stream))
    (validate-stream! return-case path)
    (validate-shape! return-case path)))

(defn- validate-subcommands!
  [declaration]
  (exact-keys! declaration #{:subcommands} [] "subcommand return")
  (let [subcommands (:subcommands declaration)]
    (when-not (map? subcommands)
      (fail! :invalid-subcommands
             "Return :subcommands declaration must be a map"
             {:path [:subcommands] :value subcommands}))
    (doseq [[name return-case] subcommands]
      (when-not (string? name)
        (fail! :invalid-subcommand-name
               "Return subcommand names must be strings"
               {:path [:subcommands name] :name name}))
      (validate-return-case! return-case [:subcommands name])))
  declaration)

(defn validate!
  "Validate a return declaration and return it unchanged.

  Accepts a concrete shape, a `{:stream ...}` return case, or a
  `{:subcommands ...}` routed declaration. Throws structured `ex-info` for
  malformed or unsupported declarations."
  [declaration]
  (cond
    (and (map? declaration) (contains? declaration :subcommands))
    (validate-subcommands! declaration)

    (and (map? declaration) (contains? declaration :stream))
    (validate-stream! declaration [])

    :else
    (validate-shape! declaration [])))

(defn- explain-shape
  [shape]
  (cond
    (keyword? shape) (name shape)
    (vector? shape) ["nullable" (name (second shape))]
    (= :map (:type shape))
    (cond-> {:type "map"
             :required (into (sorted-map) (map (fn [[k v]] [(name k) (explain-shape v)])) (:required shape {}))
             :optional (into (sorted-map) (map (fn [[k v]] [(name k) (explain-shape v)])) (:optional shape {}))}
      (contains? shape :extra) (assoc :extra (explain-shape (:extra shape))))
    (= :collection (:type shape))
    {:type "collection" :items (explain-shape (:items shape))}))

(defn- explain-case
  [return-case]
  (if (and (map? return-case) (contains? return-case :stream))
    {:stream {:emits (explain-shape (get-in return-case [:stream :emits]))
              :result (explain-shape (get-in return-case [:stream :result]))}}
    (explain-shape return-case)))

(defn explain
  "Render a return declaration as JSON-safe data.

  Shape and field names become strings; routing maps retain their structure so
  callers can render flat, subcommand, and stream declarations uniformly."
  [declaration]
  (let [declaration (validate! declaration)]
    (if (and (map? declaration) (contains? declaration :subcommands))
      {:subcommands (into (sorted-map)
                          (map (fn [[name return-case]] [name (explain-case return-case)]))
                          (:subcommands declaration))}
      (explain-case declaration))))

(defn- json-compatible?
  [value]
  (cond
    (or (nil? value) (string? value) (number? value) (boolean? value)) true
    (map? value) (and (every? #(or (keyword? %) (string? %)) (keys value))
                      (every? json-compatible? (vals value)))
    (sequential? value) (every? json-compatible? value)
    :else false))

(defn- scalar-match?
  [shape value]
  (case shape
    :string (string? value)
    :integer (integer? value)
    :number (number? value)
    :boolean (boolean? value)
    :null (nil? value)
    :json (json-compatible? value)))

(defn- mismatch!
  [path expected actual reason]
  (fail! reason
         (str "Return value does not match declaration at " (pr-str path))
         {:path path :expected expected :actual actual}))

(declare check-shape!)

(defn- check-map!
  [shape value path]
  (when-not (map? value)
    (mismatch! path shape value :type-mismatch))
  (let [required (:required shape {})
        optional (:optional shape {})
        declared (set (concat (keys required) (keys optional)))]
    (doseq [[key child-shape] required]
      (when-not (contains? value key)
        (mismatch! (conj path key) child-shape nil :missing-required-key))
      (check-shape! child-shape (get value key) (conj path key)))
    (doseq [[key child-shape] optional
            :when (contains? value key)]
      (check-shape! child-shape (get value key) (conj path key)))
    (doseq [[key child-value] value
            :when (not (contains? declared key))]
      (if (contains? shape :extra)
        (check-shape! (:extra shape) child-value (conj path key))
        (mismatch! (conj path key) :no-extra-keys child-value :undeclared-key)))))

(defn- check-shape!
  [shape value path]
  (cond
    (keyword? shape)
    (when-not (scalar-match? shape value)
      (mismatch! path shape value :type-mismatch))

    (vector? shape)
    (when-not (or (nil? value) (scalar-match? (second shape) value))
      (mismatch! path shape value :type-mismatch))

    (= :map (:type shape))
    (check-map! shape value path)

    (= :collection (:type shape))
    (if (sequential? value)
      (doseq [[index item] (map-indexed vector value)]
        (check-shape! (:items shape) item (conj path index)))
      (mismatch! path shape value :type-mismatch)))
  value)

(defn check!
  "Check `value` against one concrete return shape and return it unchanged.

  Throws structured `ex-info` on mismatch with `:path`, `:expected`, and
  `:actual`. Routing declarations must be selected by the caller first."
  [shape value]
  (when (and (map? shape)
             (or (contains? shape :subcommands) (contains? shape :stream)))
    (fail! :routed-declaration
           "check! requires a selected concrete return shape"
           {:path [] :value shape}))
  (validate-shape! shape [])
  (check-shape! shape value []))
