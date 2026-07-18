(ns skein.api.cli.internal.help
  "JSON-safe help projection plumbing for declarative CLI arg-specs.")

(defn render-flag
  "Render a named flag spec as JSON-safe help data."
  [flag-kw spec]
  {:name (name flag-kw)
   :flag (str "--" (name flag-kw))
   :type (name (:type spec :string))
   :required (boolean (:required? spec))
   :repeat (boolean (:repeat? spec))
   :parse (some-> (:parse spec) name)
   :doc (:doc spec)})

(defn render-positional
  "Render a positional spec as JSON-safe help data."
  [spec]
  {:name (name (:name spec))
   :type (name (:type spec :string))
   :required (boolean (:required? spec))
   :variadic (boolean (:variadic? spec))
   :parse (some-> (:parse spec) name)
   :doc (:doc spec)})

(defn explain-flat
  "Render one flat arg-spec as JSON-safe help data."
  [arg-spec]
  {:op (some-> (:op arg-spec) name)
   :doc (:doc arg-spec)
   :flags (mapv (fn [[key spec]] (render-flag key spec))
                (sort-by key (:flags arg-spec)))
   :positionals (mapv render-positional (:positionals arg-spec))})

(defn render-subcommand
  "Render one named subcommand and its nested flat arg-spec."
  [[subcommand nested]]
  (let [rendered (explain-flat nested)]
    {:name subcommand
     :doc (:doc rendered)
     :flags (:flags rendered)
     :positionals (:positionals rendered)}))
