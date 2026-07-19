(ns skein.api.runtime.internal.shapes
  "Component sub-specs behind skein.api.runtime.alpha's promised result shapes.

  Every spec here registers an alpha-qualified key: published qualified keys
  keep their `:skein.api.runtime.alpha/*` names wherever the defining code
  lands (SPEC-003.C19a). The alpha module owns and documents the top-level
  interface specs; these are the component shapes they compose."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(defn exact-keys?
  "True when `value` is a map whose key set equals `allowed` exactly."
  [allowed value]
  (and (map? value) (= allowed (set (keys value)))))

;; --- sync-diff components: the shapes inside sync!'s refusal contract

(s/def :skein.api.runtime.alpha/ns symbol?)
(s/def :skein.api.runtime.alpha/file (s/and string? (complement str/blank?)))
(s/def :skein.api.runtime.alpha/status keyword?)
(s/def :skein.api.runtime.alpha/lib symbol?)
(s/def :skein.api.runtime.alpha/kind keyword?)
(s/def :skein.api.runtime.alpha/root any?)
(s/def :skein.api.runtime.alpha/source any?)
(s/def :skein.api.runtime.alpha/previous-root any?)
(s/def :skein.api.runtime.alpha/new-root any?)
(s/def :skein.api.runtime.alpha/loaded-namespaces (s/coll-of symbol? :kind vector?))
(s/def :skein.api.runtime.alpha/coordinate symbol?)
(s/def :skein.api.runtime.alpha/previous-version string?)
(s/def :skein.api.runtime.alpha/new-version string?)
(s/def :skein.api.runtime.alpha/removed-root
  (s/keys :req-un [:skein.api.runtime.alpha/lib
                   :skein.api.runtime.alpha/kind
                   :skein.api.runtime.alpha/root]
          :opt-un [:skein.api.runtime.alpha/source]))
(s/def :skein.api.runtime.alpha/changed-root
  (s/keys :req-un [:skein.api.runtime.alpha/lib
                   :skein.api.runtime.alpha/previous-root
                   :skein.api.runtime.alpha/new-root]))
(s/def :skein.api.runtime.alpha/redefinition
  (s/keys :req-un [:skein.api.runtime.alpha/lib
                   :skein.api.runtime.alpha/root
                   :skein.api.runtime.alpha/loaded-namespaces]))
(s/def :skein.api.runtime.alpha/maven-version-bump
  (s/keys :req-un [:skein.api.runtime.alpha/coordinate
                   :skein.api.runtime.alpha/previous-version
                   :skein.api.runtime.alpha/new-version]))
(s/def :skein.api.runtime.alpha/removed-roots
  (s/coll-of :skein.api.runtime.alpha/removed-root :kind vector?))
(s/def :skein.api.runtime.alpha/changed-roots
  (s/coll-of :skein.api.runtime.alpha/changed-root :kind vector?))
(s/def :skein.api.runtime.alpha/redefinitions
  (s/coll-of :skein.api.runtime.alpha/redefinition :kind vector?))
(s/def :skein.api.runtime.alpha/maven-version-bumps
  (s/coll-of :skein.api.runtime.alpha/maven-version-bump :kind vector?))
(s/def :skein.api.runtime.alpha/diff
  (s/keys :opt-un [:skein.api.runtime.alpha/removed-roots
                   :skein.api.runtime.alpha/changed-roots
                   :skein.api.runtime.alpha/redefinitions
                   :skein.api.runtime.alpha/maven-version-bumps]))
(s/def :skein.api.runtime.alpha/generation (s/or :id string? :unknown #{:unknown}))
(s/def :skein.api.runtime.alpha/approved-spools set?)
(s/def :skein.api.runtime.alpha/remedy string?)
(s/def :skein.api.runtime.alpha/reason keyword?)

;; --- retained spool-state rows on an older-generation sync result

(s/def :skein.api.runtime.alpha/key any?)
(s/def :skein.api.runtime.alpha/current-generation string?)
(s/def :skein.api.runtime.alpha/retained-spool-state-entry
  (s/keys :req-un [:skein.api.runtime.alpha/key
                   :skein.api.runtime.alpha/generation
                   :skein.api.runtime.alpha/current-generation]
          :opt-un [:skein.api.runtime.alpha/reason]))
(s/def :skein.api.runtime.alpha/retained-spool-state
  (s/coll-of :skein.api.runtime.alpha/retained-spool-state-entry :kind vector?))

;; --- spools.edn write-seam components

(s/def :skein.api.runtime.alpha/spool-write-status #{:inserted :updated :removed})

;; --- reload-spool! result components

(s/def :skein.api.runtime.alpha/reload-namespace
  (s/and #(exact-keys? #{:ns :file} %)
         #(s/valid? :skein.api.runtime.alpha/ns (:ns %))
         #(s/valid? :skein.api.runtime.alpha/file (:file %))))
(s/def :skein.api.runtime.alpha/namespaces
  (s/coll-of :skein.api.runtime.alpha/reload-namespace :kind vector?))
(s/def :skein.api.runtime.alpha/canonical-root (s/and string? (complement str/blank?)))

;; --- module-use option components

(s/def :skein.api.runtime.alpha/spools
  (s/and #(or (vector? %) (set? %))
         #(every? symbol? %)))
(s/def :skein.api.runtime.alpha/after (s/coll-of keyword? :kind vector?))
(s/def :skein.api.runtime.alpha/call (s/and symbol? (comp some? namespace)))
(s/def :skein.api.runtime.alpha/required? boolean?)

;; --- module-use registry entry variants

(s/def :skein.api.runtime.alpha/loaded
  (s/or :namespace (s/and #(or (exact-keys? #{:ns} %)
                               (exact-keys? #{:ns :file} %))
                          #(contains? % :ns)
                          #(s/valid? :skein.api.runtime.alpha/ns (:ns %))
                          #(or (not (contains? % :file))
                               (s/valid? :skein.api.runtime.alpha/file (:file %))))
        :file (s/and #(exact-keys? #{:file} %)
                     #(s/valid? :skein.api.runtime.alpha/file (:file %)))))
(s/def :skein.api.runtime.alpha/fn (s/and symbol? (comp some? namespace)))
(s/def :skein.api.runtime.alpha/call-result
  (s/and #(exact-keys? #{:fn :return} %)
         #(s/valid? :skein.api.runtime.alpha/fn (:fn %))))
(s/def :skein.api.runtime.alpha/message (s/nilable string?))
(s/def :skein.api.runtime.alpha/class string?)
(s/def :skein.api.runtime.alpha/error
  (s/and #(exact-keys? #{:message :class :data} %)
         #(s/valid? :skein.api.runtime.alpha/message (:message %))
         #(s/valid? :skein.api.runtime.alpha/class (:class %))))
(s/def :skein.api.runtime.alpha/loaded-use-entry
  (s/and #(or (exact-keys? #{:key :opts :status :loaded} %)
              (exact-keys? #{:key :opts :status :loaded :call} %))
         #(= :loaded (:status %))
         #(s/valid? :skein.api.runtime.alpha/use-key (:key %))
         #(s/valid? :skein.api.runtime.alpha/use-opts (:opts %))
         #(s/valid? :skein.api.runtime.alpha/loaded (:loaded %))
         #(or (not (contains? % :call))
              (s/valid? :skein.api.runtime.alpha/call-result (:call %)))))
(s/def :skein.api.runtime.alpha/failed-use-entry
  (s/and #(exact-keys? #{:key :opts :status :error} %)
         #(= :failed (:status %))
         #(s/valid? :skein.api.runtime.alpha/use-key (:key %))
         #(s/valid? :skein.api.runtime.alpha/use-opts (:opts %))
         #(s/valid? :skein.api.runtime.alpha/error (:error %))))
(s/def :skein.api.runtime.alpha/skipped-use-entry
  (s/and
   #(s/valid? :skein.api.runtime.alpha/use-key (:key %))
   #(s/valid? :skein.api.runtime.alpha/use-opts (:opts %))
   #(= :skipped (:status %))
   #(case (:reason %)
      (:not-approved :not-synced)
      (and (exact-keys? #{:key :opts :status :reason :lib} %)
           (symbol? (:lib %)))

      :sync-failed
      (and (exact-keys? #{:key :opts :status :reason :lib :sync} %)
           (symbol? (:lib %))
           (s/valid? :skein.core.weaver.spool-sync/sync-root-entry (:sync %)))

      :missing-after
      (and (exact-keys? #{:key :opts :status :reason :after :use} %)
           (keyword? (:after %))
           (or (nil? (:use %)) (s/valid? :skein.api.runtime.alpha/use-entry (:use %))))

      false)))

;; --- spool-state option components

(s/def :skein.api.runtime.alpha/migrate-fn ifn?)
