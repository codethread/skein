(ns skein.api.return-shape.internal
  "Leaf mechanics beneath `skein.api.return-shape.alpha` (SPEC-003.C19a):
  error construction and JSON scalar semantics.

  The story — grammar validation, rendering, value checking — reads in the
  alpha namespace; this file holds the details it composes. Thrown ex-data
  keeps the published alpha-qualified marker
  `:skein.api.return-shape.alpha/error` even though the code lives here —
  the key is contract, the location is not.")

(def scalar-shapes
  #{:string :integer :number :boolean :null :json})

(def nullable-scalar-shapes
  #{:string :integer :number :boolean})

(defn fail!
  [reason message data]
  (throw (ex-info message (assoc data
                                 :skein.api.return-shape.alpha/error true
                                 :reason reason))))

(defn exact-keys!
  [value allowed path kind]
  (when-let [unknown (seq (remove allowed (keys value)))]
    (fail! :unknown-declaration-keys
           (str "Unknown " kind " declaration keys")
           {:path path :keys (vec unknown) :value value})))

(defn mismatch!
  [path expected actual reason]
  (fail! reason
         (str "Return value does not match declaration at " (pr-str path))
         {:path path :expected expected :actual actual}))

(defn json-compatible?
  [value]
  (cond
    (or (nil? value) (string? value) (number? value) (boolean? value)) true
    (map? value) (and (every? #(or (keyword? %) (string? %)) (keys value))
                      (every? json-compatible? (vals value)))
    (sequential? value) (every? json-compatible? value)
    :else false))

(defn scalar-match?
  [shape value]
  (case shape
    :string (string? value)
    :integer (integer? value)
    :number (number? value)
    :boolean (boolean? value)
    :null (nil? value)
    :json (json-compatible? value)))
