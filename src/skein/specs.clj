(ns skein.specs
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(defn non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(def generated-id-pattern #"[a-z0-9]+")

(defn generated-id? [x]
  (and (string? x) (boolean (re-matches generated-id-pattern x))))

(def relation-name-pattern #"[a-z0-9][a-z0-9._/-]*")

(defn relation-name? [x]
  (and (string? x) (boolean (re-matches relation-name-pattern x))))

(s/def ::id non-blank-string?)
(s/def ::generated-id generated-id?)
(s/def ::from ::id)
(s/def ::to ::id)
(s/def ::edge-type relation-name?)
(s/def ::type ::edge-type)
(s/def ::title non-blank-string?)
(s/def ::attr-key keyword?)
(s/def ::cli-attr-value string?)
(s/def ::cli-attributes (s/map-of ::attr-key ::cli-attr-value))
(s/def ::attributes (s/nilable map?))
(s/def ::state #{"active" "closed" "replaced"})
(s/def ::generic-state #{"active" "closed"})
(s/def ::format #{"human" "edn" "json"})
(s/def ::db non-blank-string?)
(s/def ::opts (s/keys :req-un [::db ::format]))

(s/def ::add-command (s/cat :title ::title :opts (s/* string?)))
(s/def ::update-command (s/cat :id ::id :opts (s/* string?)))
(s/def ::one-id-command (s/cat :id ::id))
(s/def ::empty-command (s/cat))

(s/def ::strand-input
  (s/and (s/keys :req-un [::title] :opt-un [::attributes ::state])
         #(or (not (contains? % :state)) (s/valid? ::generic-state (:state %)))))
(s/def ::edge-input (s/keys :req-un [::from ::to ::type] :opt-un [::attributes]))
