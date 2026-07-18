(ns skein.core.weaver.spool-sync
  "Approved-spool config parsing, git/local materialization, and runtime sync.

  Reads and validates the `spools.edn`/`spools.local.edn` allowlist, materializes
  git-pinned spool roots into the content-addressed cache, vets each root's
  Maven dependencies, and loads approved roots into a runtime's spool classloader
  while recording per-spool sync outcomes. Also resolves and loads module-use
  targets — `:file` paths confined to the config-dir and `:ns` sources located
  under synced roots — for `skein.api.runtime.alpha/use!`. Internal tier: the
  trusted `skein.api.runtime.alpha` surface delegates its
  `approved`/`declared`/`sync!`/`syncs` publics and its module loading here and
  owns the blessed contract (SPEC-004, SPEC-005.C5)."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.deps.interop :as deps-interop]
            [clojure.tools.namespace.dependency :as ns-dep]
            [clojure.tools.namespace.parse :as ns-parse]
            [skein.core.format :as format]
            [skein.core.query :as query]
            [skein.core.weaver.access :refer [approved-spool-sync-state
                                              with-spool-classloader config-dir spools-file
                                              canonical-root cache-base]])
  (:import [java.math BigInteger]
           [java.security MessageDigest]
           [java.nio.file FileSystemException FileVisitResult Files LinkOption SimpleFileVisitor StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(def ^:private local-spool-keys #{:local/root})
(def ^:private overlay-spool-keys #{:local/root :claims})
(def ^:private git-spool-keys #{:git/url :git/sha :git/tag :roots :requires :skein/min})
(def ^:private git-sha-pattern #"[0-9a-f]{40}")
(def ^:private release-marker-pattern #"v([1-9][0-9]*)")

(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- marker-ordinal
  "Return a release marker's positive integer ordinal.

  Markers are exactly `vN`, where N is a positive base-10 integer. `v0` has a
  dedicated policy failure because untagged repositories represent WIP."
  [marker]
  (when (= "v0" marker)
    (throw (ex-info (format/reflow
                     "|v0 is reserved; WIP repos stay untagged — v1 is the smallest
                       |promise")
                    {:marker marker})))
  (if-let [[_ ordinal] (and (string? marker)
                            (re-matches release-marker-pattern marker))]
    (BigInteger. ^String ordinal)
    (throw (ex-info "Release marker must match vN where N is a positive integer"
                    {:marker marker}))))

(defn- root-path-segments [root-path]
  (str/split root-path #"/"))

(defn- relative-root-path? [root-path]
  (and (non-blank-string? root-path)
       (not (.isAbsolute (io/file root-path)))
       (not (str/starts-with? root-path "~"))
       (not-any? #{".."} (root-path-segments root-path))))

(s/def ::non-blank-string non-blank-string?)
(s/def ::release-marker #(and (string? %) (boolean (re-matches release-marker-pattern %))))
(s/def ::root-path relative-root-path?)
(s/def ::roots (s/map-of symbol? ::root-path :min-count 1))
(s/def ::requires (s/map-of symbol? ::release-marker))
(s/def :git/url ::non-blank-string)
(s/def :git/sha #(and (string? %) (boolean (re-matches git-sha-pattern %))))
(s/def :git/tag ::release-marker)
(s/def :skein/min ::release-marker)
(s/def :local/root ::non-blank-string)
(s/def ::claims (s/nilable ::release-marker))

(defn- exact-keys? [allowed value]
  (and (map? value) (every? allowed (keys value))))

(s/def ::git-family-entry
  (s/and #(exact-keys? git-spool-keys %)
         (s/keys :req [:git/url :git/sha]
                 :opt [:git/tag :skein/min]
                 :opt-un [::roots ::requires])))
(s/def ::local-family-entry
  (s/and #(exact-keys? local-spool-keys %)
         (s/keys :req [:local/root])))
(s/def ::family-entry (s/or :git ::git-family-entry :local ::local-family-entry))
(s/def ::overlay-entry
  (s/and #(exact-keys? overlay-spool-keys %)
         (s/keys :req [:local/root] :req-un [::claims])
         #(s/valid? ::release-marker (:claims %))))

(s/def ::family symbol?)
(s/def ::coordinate
  (s/or :git (s/and #(= :git (:kind %))
                    #(exact-keys? #{:kind :git/url :git/sha :git/tag} %)
                    (s/keys :req [:git/url :git/sha] :opt [:git/tag]))
        :local (s/and #(= :local (:kind %))
                      #(exact-keys? #{:kind :local/root} %)
                      (s/keys :req [:local/root]))))
(s/def ::roots-map ::roots)
(s/def ::skein-min (s/nilable ::release-marker))
(s/def ::provenance #{:spools-edn :local-overlay})
(s/def ::source #(and (map? %) (#{:shared :local} (:kind %)) (non-blank-string? (:file %))))
(s/def ::normalized-family
  (s/keys :req-un [::family ::coordinate ::roots-map ::requires ::skein-min
                   ::provenance ::source]
          :opt-un [::claims]))

(defn- duplicate-root-owners [families]
  (->> families
       (mapcat (fn [{:keys [family roots-map]}]
                 (map (fn [root-lib] [root-lib family]) (keys roots-map))))
       (group-by first)
       (keep (fn [[root-lib owners]]
               (when (< 1 (count owners))
                 [root-lib (mapv second owners)])))
       (into {})))

(s/def ::normalized-families
  (s/and (s/coll-of ::normalized-family :kind vector?)
         #(empty? (duplicate-root-owners %))))

(s/def ::spools (s/map-of symbol? ::family-entry))
(s/def ::mvn-overrides map?)
(s/def ::shared-spools-config
  (s/and #(exact-keys? #{:spools :mvn-overrides} %)
         (s/keys :req-un [::spools] :opt-un [::mvn-overrides])))
(s/def ::families ::normalized-families)
(s/def ::normalized-shared-spools-config
  (s/and #(= #{:families :mvn-overrides} (set (keys %)))
         (s/keys :req-un [::families ::mvn-overrides])))

(defn- require-spec! [spec value message context]
  (when-not (s/valid? spec value)
    (throw (ex-info message
                    (assoc context :spec spec :explain (s/explain-data spec value)))))
  value)

(defn- validate-marker! [marker context]
  (try
    (marker-ordinal marker)
    marker
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info (ex-message e) (merge context (ex-data e)) e)))))

(defn- validate-family-name! [source family]
  (when-not (symbol? family)
    (throw (ex-info "Spool family must be a symbol" (assoc source :family family)))))

(defn- reject-legacy-root-shape! [source family entry]
  (when (contains? entry :deps/root)
    (throw (ex-info
            (format/reflow
             "|Legacy spool entry :deps/root is no longer supported; keep one entry
               |per family and replace :deps/root with :roots {root-lib \"relative-path\"}")
            (assoc source :family family :deps/root (:deps/root entry))))))

(defn- validate-entry-map! [source family entry allowed-keys]
  (validate-family-name! source family)
  (when-not (map? entry)
    (throw (ex-info "Spool entry must be a map" (assoc source :family family :entry entry))))
  (reject-legacy-root-shape! source family entry)
  (when-let [unknown (seq (remove allowed-keys (keys entry)))]
    (throw (ex-info "Spool entry contains unknown keys"
                    (assoc source :family family :keys (vec unknown))))))

(defn- normalize-roots-map [source family entry]
  (let [roots (if (contains? entry :roots) (:roots entry) {family "."})]
    (when-not (and (map? roots) (seq roots))
      (throw (ex-info "Git spool entry :roots must be a non-empty map"
                      (assoc source :family family :roots roots))))
    (doseq [[root-lib root-path] roots]
      (when-not (symbol? root-lib)
        (throw (ex-info "Spool root lib must be a symbol"
                        (assoc source :family family :root-lib root-lib))))
      (when-not (relative-root-path? root-path)
        (throw (ex-info "Spool root path must be relative with no ~ or .. segments"
                        (assoc source :family family :root-lib root-lib :root-path root-path)))))
    roots))

(defn- normalize-requires [source family entry]
  (let [requires (if (contains? entry :requires) (:requires entry) {})]
    (when-not (map? requires)
      (throw (ex-info "Git spool entry :requires must be a map"
                      (assoc source :family family :requires requires))))
    (doseq [[root-lib marker] requires]
      (when-not (symbol? root-lib)
        (throw (ex-info "Required spool root must be a symbol"
                        (assoc source :family family :requires root-lib))))
      (validate-marker! marker (assoc source :family family :field :requires :requires root-lib)))
    requires))

(defn- normalize-shared-family [source family entry]
  (validate-entry-map! source family entry (into local-spool-keys git-spool-keys))
  (let [local? (contains? entry :local/root)
        git? (some #(contains? entry %) git-spool-keys)]
    (when (= local? (boolean git?))
      (throw (ex-info "Spool entry requires exactly one coordinate kind"
                      (assoc source :family family :entry entry))))
    (if local?
      (do
        (when-not (non-blank-string? (:local/root entry))
          (throw (ex-info "Spool entry requires non-blank string :local/root"
                          (assoc source :family family :local/root (:local/root entry) :entry entry))))
        (require-spec! ::family-entry entry "Spool family entry has an invalid shape"
                       (assoc source :family family :entry entry))
        (require-spec!
         ::normalized-family
         {:family family
          :coordinate {:kind :local :local/root (:local/root entry)}
          :roots-map {family "."}
          :requires {}
          :skein-min nil
          :claims nil
          :provenance :spools-edn
          :source source}
         "Normalized spool family has an invalid shape"
         (assoc source :family family)))
      (do
        (when-not (non-blank-string? (:git/url entry))
          (throw (ex-info "Git spool entry requires non-blank string :git/url"
                          (assoc source :family family :git/url (:git/url entry)))))
        (when-not (and (string? (:git/sha entry))
                       (re-matches git-sha-pattern (:git/sha entry)))
          (throw (ex-info "Git spool entry requires 40 lowercase hex characters :git/sha"
                          (assoc source :family family :git/sha (:git/sha entry)))))
        (when (contains? entry :git/tag)
          (validate-marker! (:git/tag entry) (assoc source :family family :field :git/tag)))
        (when (contains? entry :skein/min)
          (validate-marker! (:skein/min entry) (assoc source :family family :field :skein/min)))
        (let [normalized {:family family
                          :coordinate (cond-> {:kind :git
                                               :git/url (:git/url entry)
                                               :git/sha (:git/sha entry)}
                                        (contains? entry :git/tag) (assoc :git/tag (:git/tag entry)))
                          :roots-map (normalize-roots-map source family entry)
                          :requires (normalize-requires source family entry)
                          :skein-min (:skein/min entry)
                          :claims nil
                          :provenance :spools-edn
                          :source source}]
          (require-spec! ::family-entry entry "Spool family entry has an invalid shape"
                         (assoc source :family family :entry entry))
          (require-spec! ::normalized-family normalized
                         "Normalized spool family has an invalid shape"
                         (assoc source :family family)))))))

(defn- normalize-overlay [source family entry]
  (validate-entry-map! source family entry overlay-spool-keys)
  (when-not (non-blank-string? (:local/root entry))
    (throw (ex-info "Spool overlay requires non-blank string :local/root"
                    (assoc source :family family :local/root (:local/root entry) :entry entry))))
  (when-not (contains? entry :claims)
    (throw (ex-info "Local spool override requires an explicit release claim"
                    (assoc source
                           :error :override-without-claim
                           :family family
                           :local/root (:local/root entry)
                           :fix "add :claims \"vN\" — the release whose contract this tree honors"))))
  (validate-marker! (:claims entry) (assoc source :family family :field :claims))
  (require-spec! ::overlay-entry entry "Local spool override has an invalid shape"
                 (assoc source :family family :entry entry))
  {:family family
   :coordinate {:kind :local :local/root (:local/root entry)}
   :claims (:claims entry)
   :provenance :local-overlay
   :source source})

(defn- reject-shared-git-urls! [families]
  (doseq [[git-url entries] (group-by #(get-in % [:coordinate :git/url])
                                      (filter #(= :git (get-in % [:coordinate :kind])) families))
          :when (> (count entries) 1)]
    (throw (ex-info "Two spool families must not share :git/url"
                    {:git/url git-url
                     :families (mapv :family entries)}))))

(defn- apply-overlays [shared-families overlays]
  (reduce
   (fn [families overlay]
     (let [family (:family overlay)
           base (get families family)]
       (when-not base
         (throw (ex-info "Local spool override must shadow a shared git family"
                         {:error :override-without-base
                          :family family
                          :source (:source overlay)})))
       (when-not (= :git (get-in base [:coordinate :kind]))
         (throw (ex-info "Local spool override must shadow a shared git family"
                         {:error :override-of-local-family
                          :family family
                          :source (:source overlay)})))
       (assoc families family
              (merge (select-keys base [:roots-map :requires :skein-min]) overlay))))
   (into {} (map (juxt :family identity)) shared-families)
   overlays))

(defn- effective-marker [{:keys [coordinate claims]}]
  (or claims (:git/tag coordinate)))

(defn- root-family-index [families]
  (into {}
        (mapcat (fn [{:keys [family roots-map] :as entry}]
                  (map (fn [root]
                         [root {:family family
                                :marker (effective-marker entry)}])
                       (keys roots-map))))
        families))

(defn- reject-duplicate-root-libs! [families]
  (when-let [[root-lib owners] (first (duplicate-root-owners families))]
    (throw (ex-info "Spool root lib must be owned by exactly one family"
                    {:reason :duplicate-spool-root
                     :root-lib root-lib
                     :families owners})))
  (require-spec! ::normalized-families (vec families)
                 "Approved spool families have an invalid shape" {})
  families)

(defn- requirement-findings [families]
  (let [index (root-family-index families)]
    (vec
     (keep identity
           (for [{:keys [family requires]} families
                 [root minimum] requires
                 :let [{required-family :family pinned :marker} (get index root)]]
             (cond
               (nil? required-family)
               {:error :required-root-not-approved
                :requirer family
                :requires root
                :minimum minimum}

               (nil? pinned)
               {:error :required-root-unmarked
                :requirer family
                :requires root
                :minimum minimum
                :family required-family}

               (< (marker-ordinal pinned) (marker-ordinal minimum))
               {:error :pin-below-minimum
                :requirer family
                :requires root
                :minimum minimum
                :family required-family
                :pinned pinned}))))))

(defn- skein-minimum-findings [families running-marker]
  (if running-marker
    (vec
     (for [{:keys [family skein-min]} families
           :when (and skein-min
                      (< (marker-ordinal running-marker)
                         (marker-ordinal skein-min)))]
       {:error :skein-below-minimum
        :spool family
        :skein/min skein-min
        :running running-marker}))
    []))

(defn- pending-skein-validations [families running-marker]
  (when-not running-marker
    (vec
     (for [{:keys [family skein-min]} families
           :when skein-min]
       {:check :skein/min
        :spool family
        :skein/min skein-min
        :status :pending
        :reason :running-marker-unavailable}))))

(defn- requirement-suggestions [findings]
  (reduce (fn [result {:keys [error family minimum]}]
            (if (= :pin-below-minimum error)
              (update result family
                      (fn [current]
                        (if (and current
                                 (>= (marker-ordinal current)
                                     (marker-ordinal minimum)))
                          current
                          minimum)))
              result))
          {}
          findings))

(defn- validate-family-requirements! [families running-marker]
  (when (= :none running-marker)
    (let [floors (vec (keep (fn [{:keys [family skein-min]}]
                              (when skein-min
                                {:family family :skein/min skein-min}))
                            families))]
      (when (seq floors)
        (throw (ex-info "Running Skein release marker is unavailable for declared floors"
                        {:reason :release-marker-unavailable
                         :floors floors
                         :remedy "start the runtime with an explicit :release-marker claim"})))))
  (when (and running-marker (not= :none running-marker))
    (validate-marker! running-marker {:field :running}))
  (let [findings (into (requirement-findings families)
                       (skein-minimum-findings families
                                               (when-not (= :none running-marker)
                                                 running-marker)))]
    (when (seq findings)
      (throw (ex-info "Approved spool requirements are not satisfied"
                      {:reason :spool-requirements-unsatisfied
                       :findings findings
                       :suggestions (requirement-suggestions findings)})))
    (pending-skein-validations families
                               (when-not (= :none running-marker)
                                 running-marker))))

(def ^:private allowed-spool-maven-coordinate-keys
  #{:mvn/version :exclusions :classifier :extension})

(def ^:private rejected-spool-deps-keys
  #{:git/url :git/sha :local/root})

(def ^:private allowed-spools-file-keys #{:spools :mvn-overrides})

(defn- mutable-maven-version? [version]
  (or (str/ends-with? version "-SNAPSHOT")
      (#{"RELEASE" "LATEST"} version)))

(defn- validate-maven-coordinate!
  "Apply the Maven-only dependency policy to one `lib`/`coord` pair, failing loudly.

  `context` is merged into every error's ex-data so the caller (spool `deps.edn`
  or `:mvn-overrides`) locates the offending declaration. Rejects non-symbol libs,
  non-map coords, source-bearing coordinates, non-string or mutable `:mvn/version`,
  and unsupported coordinate keys."
  [lib coord context]
  (when-not (symbol? lib)
    (throw (ex-info "Maven coordinate lib must be a symbol" (assoc context :lib lib))))
  (when-not (map? coord)
    (throw (ex-info "Maven coordinate must be a coordinate map" (assoc context :lib lib :coord coord))))
  (when-let [source-keys (seq (filter #(contains? coord %) rejected-spool-deps-keys))]
    (throw (ex-info "Maven coordinate must not declare source-bearing coordinates"
                    (assoc context :lib lib :keys (vec source-keys)))))
  (when-not (string? (:mvn/version coord))
    (throw (ex-info "Maven coordinate must declare string :mvn/version"
                    (assoc context :lib lib :coord coord))))
  (when (mutable-maven-version? (:mvn/version coord))
    (throw (ex-info "Maven coordinate must not use mutable Maven versions"
                    (assoc context :lib lib :mvn/version (:mvn/version coord)))))
  (when-let [unknown (seq (remove allowed-spool-maven-coordinate-keys (keys coord)))]
    (throw (ex-info "Maven coordinate contains unsupported Maven coordinate keys"
                    (assoc context :lib lib :keys (vec unknown))))))

(defn- resolved-maven-version
  "Return the resolved Maven version for `lib`, failing loudly when absent.

  Resolver output feeds the in-generation version-bump baseline. Missing versions
  are not comparable and must never be recorded as nil baseline entries."
  [lib coord]
  (let [version (:mvn/version coord)]
    (when-not (string? version)
      (throw (ex-info "Resolved Maven coordinate must declare string :mvn/version"
                      {:lib lib
                       :coord coord})))
    version))

(defn- normalize-mvn-overrides
  "Validate one config file's optional top-level `:mvn-overrides` map.

  Returns a `{lib coord}` map (empty when the key is absent). Fails loudly when
  the value is not a map, and applies the same Maven-only policy as spool `deps`
  to each pinned coordinate."
  [name source config]
  (let [overrides (:mvn-overrides config)]
    (cond
      (nil? overrides) {}
      (not (map? overrides))
      (throw (ex-info (str name " :mvn-overrides must be a map") (assoc source :mvn-overrides overrides)))
      :else
      (do
        (doseq [[lib coord] overrides]
          (validate-maven-coordinate! lib coord source))
        overrides))))

(defn- normalize-approved-spools-file
  "Validate one approved-spool config file into stage-1 family records."
  [name source config normalize-entry]
  (when-not (map? config)
    (throw (ex-info (str name " must contain a map") (assoc source :config config))))
  (when-let [unknown (seq (remove allowed-spools-file-keys (keys config)))]
    (throw (ex-info (str name " contains unknown top-level keys") (assoc source :keys (vec unknown)))))
  (when-not (map? (:spools config))
    (throw (ex-info (str name " requires :spools map") (assoc source :spools (:spools config)))))
  {:mvn-overrides (normalize-mvn-overrides name source config)
   :families (mapv (fn [[family entry]]
                     (normalize-entry source family entry))
                   (:spools config))})

(defn validate-shared-spools-config!
  "Return stage-1 normalized records for a shared `spools.edn` config.

  `file` names the source in validation failures. This is the shared validation
  seam used by runtime config writes; it applies the same entry specs and error
  classes as sync before any file is changed. Input conforms to
  `::shared-spools-config`; the result conforms to
  `::normalized-shared-spools-config`."
  [file config]
  (let [source {:kind :shared :file (.getPath (io/file file))}
        normalized (normalize-approved-spools-file
                    "spools.edn" source config normalize-shared-family)]
    (require-spec! ::shared-spools-config config
                   "spools.edn config has an invalid shape" source)
    (require-spec! ::normalized-shared-spools-config normalized
                   "spools.edn normalized config has an invalid shape" source)))

(defn- read-config-edn-file
  "Read an approved-spool EDN file, adding source context to expected failures."
  [file message context]
  (letfn [(contextual-message [t]
            (str message (when-let [detail (ex-message t)] (str ": " detail))))]
    (try
      (query/read-edn-file file)
      (catch java.io.IOException t
        (throw (ex-info (contextual-message t) context t)))
      (catch clojure.lang.ExceptionInfo t
        (throw (ex-info (contextual-message t) context t)))
      (catch RuntimeException t
        ;; clojure.edn reports syntax and unknown-tag failures as exactly
        ;; RuntimeException. RuntimeException subclasses are unexpected here and
        ;; keep their original type rather than becoming config diagnostics.
        (if (= RuntimeException (class t))
          (throw (ex-info (contextual-message t) context t))
          (throw t))))))

(defn- approved-spools-file [runtime name kind normalize-entry]
  (let [file (spools-file runtime name)
        source {:kind kind
                :file (.getPath file)}]
    (cond
      (and (not (.exists file))
           (not (java.nio.file.Files/isSymbolicLink (.toPath file))))
      {:spools {}
       :declared-spools {}}

      (not (.isFile file))
      (throw (ex-info (str name " is malformed or unreadable") source))

      :else
      (let [config (read-config-edn-file file (str name " is malformed or unreadable") source)]
        (assoc (normalize-approved-spools-file name source config normalize-entry)
               :declared-spools (:spools config))))))

(defn- legacy-config-present? [^java.io.File file]
  (or (.exists file)
      (java.nio.file.Files/isSymbolicLink (.toPath file))))

(defn- reject-legacy-spool-config! [runtime]
  (let [legacy-files (filter #(legacy-config-present? (spools-file runtime %)) ["libs.edn" "libs.local.edn"])]
    (when (seq legacy-files)
      (throw (ex-info (format/reflow
                       "|Legacy runtime library config files are no longer supported; rename
                         |libs.edn/libs.local.edn to spools.edn/spools.local.edn and change
                         |top-level :libs to :spools")
                      {:legacy-files (vec legacy-files)
                       :config-dir (config-dir runtime)})))))

(defn- kind-shaped-root? [entry]
  (case (:kind entry)
    :git (and (s/valid? :git/url (:git/url entry))
              (s/valid? :git/sha (:git/sha entry))
              (not (contains? entry :local/root)))
    :local (and (s/valid? :local/root (:local/root entry))
                (not-any? #(contains? entry %) [:git/url :git/sha :git/tag]))
    false))

(s/def ::root non-blank-string?)
(s/def ::approved-root-entry
  (s/and map?
         kind-shaped-root?
         #(non-blank-string? (:root %))
         #(s/valid? ::source (:source %))
         #(s/valid? ::provenance (:provenance %))))
(s/def ::approved-root-map
  (s/and (s/map-of symbol? ::approved-root-entry)
         #(every? (fn [[lib entry]]
                    (and (symbol? lib)
                         (symbol? (::family (meta entry)))
                         (s/valid? ::coordinate (::coordinate (meta entry)))
                         (s/valid? ::provenance (::provenance (meta entry)))))
                  %)))
(s/def ::declared ::family-entry)
(s/def ::effective-coordinate ::coordinate)
(s/def ::approved-family-entry
  (s/and #(exact-keys? #{:declared :effective-coordinate :provenance :claims} %)
         (s/keys :req-un [::declared ::effective-coordinate ::provenance ::claims])))
(s/def ::approved-family-map (s/map-of symbol? ::approved-family-entry))
(s/def ::pending-validations vector?)
(s/def ::valid? boolean?)
(s/def ::findings vector?)
(s/def ::suggestions map?)
(s/def ::valid-requirements
  (s/and #(exact-keys? #{:valid? :pending-validations} %)
         #(true? (:valid? %))
         #(s/valid? ::pending-validations (:pending-validations %))))
(s/def ::invalid-requirements
  (s/and #(exact-keys? #{:valid? :findings :suggestions} %)
         #(false? (:valid? %))
         #(s/valid? ::findings (:findings %))
         #(s/valid? ::suggestions (:suggestions %))))
(s/def ::requirements (s/or :valid ::valid-requirements
                            :invalid ::invalid-requirements))
(s/def ::declared-result
  (s/and #(exact-keys? #{:families :requirements} %)
         #(s/valid? ::approved-family-map (:families %))
         #(s/valid? ::requirements (:requirements %))))
(s/def ::approved-result
  (s/and #(exact-keys? #{:spools :families :mvn-overrides :pending-validations} %)
         #(contains? % :spools)
         #(s/valid? ::approved-root-map (:spools %))
         #(s/valid? ::approved-family-map (:families %))
         #(or (not (contains? % :mvn-overrides)) (map? (:mvn-overrides %)))
         #(or (not (contains? % :pending-validations)) (vector? (:pending-validations %)))))

(s/def ::status #{:loaded :already-available :failed})
(s/def ::reason keyword?)
(s/def ::sync-root-entry
  (s/and map?
         kind-shaped-root?
         #(symbol? (:lib %))
         #(symbol? (:family %))
         #(s/valid? ::coordinate (:coordinate %))
         #(s/valid? ::provenance (:provenance %))
         #(non-blank-string? (:root %))
         #(s/valid? ::source (:source %))
         #(s/valid? ::status (:status %))
         #(or (not= :failed (:status %)) (keyword? (:reason %)))))
(s/def ::sync-root-map
  (s/and (s/map-of symbol? ::sync-root-entry)
         #(every? (fn [[lib entry]] (= lib (:lib entry))) %)))
(s/def ::pending-generation map?)
(s/def ::retained-spool-state vector?)
(s/def ::sync-result
  (s/and #(exact-keys? #{:spools :pending-validations :pending-generation
                         :retained-spool-state} %)
         #(contains? % :spools)
         #(s/valid? ::sync-root-map (:spools %))
         #(or (not (contains? % :pending-validations)) (vector? (:pending-validations %)))
         #(or (not (contains? % :pending-generation)) (map? (:pending-generation %)))
         #(or (not (contains? % :retained-spool-state)) (vector? (:retained-spool-state %)))))

(defn- family-projection [shared families]
  (into {}
        (map (fn [{:keys [family coordinate claims provenance]}]
               [family {:declared (get (:declared-spools shared) family)
                        :effective-coordinate coordinate
                        :provenance provenance
                        :claims claims}]))
        families))

(defn- approved-stage-one [runtime]
  (reject-legacy-spool-config! runtime)
  (let [shared (approved-spools-file runtime "spools.edn" :shared normalize-shared-family)
        local (approved-spools-file runtime "spools.local.edn" :local normalize-overlay)
        _ (reject-shared-git-urls! (:families shared))
        families (vals (apply-overlays (:families shared) (:families local)))
        _ (reject-duplicate-root-libs! families)]
    {:families families
     :family-projection (family-projection shared families)
     :overrides (merge (:mvn-overrides shared) (:mvn-overrides local))}))

(defn- unavailable-marker-findings [floors]
  (mapv (fn [{:keys [family] :as floor}]
          {:error :release-marker-unavailable
           :family family
           :skein/min (:skein/min floor)})
        floors))

(defn- family-requirement-validation [families running-marker]
  (try
    {:requirements {:valid? true
                    :pending-validations (or (validate-family-requirements! families running-marker)
                                             [])}}
    (catch clojure.lang.ExceptionInfo ex
      (let [{:keys [reason findings suggestions floors]} (ex-data ex)]
        (case reason
          :spool-requirements-unsatisfied
          {:requirements {:valid? false
                          :findings findings
                          :suggestions suggestions}
           :exception ex}

          :release-marker-unavailable
          {:requirements {:valid? false
                          :findings (unavailable-marker-findings floors)
                          :suggestions {}}
           :exception ex}

          (throw ex))))))

(defn declared-spools
  "Return declared spool families with requirement validation as data.

  Stage-1 structural errors fail loudly. Unsatisfied release floors return under
  `:requirements` with `:valid? false`; they never hide the `:families`
  projection. Omitting `running-marker` leaves Skein floor checks pending."
  ([runtime]
   (declared-spools runtime nil))
  ([runtime running-marker]
   (let [{:keys [families family-projection]} (approved-stage-one runtime)
         {:keys [requirements]} (family-requirement-validation families running-marker)
         result {:families family-projection
                 :requirements requirements}]
     (require-spec! ::declared-result result
                    "Declared spool config has an invalid shape" {})
     result)))

(defn approved-spools
  "Read and validate the effective runtime spool allowlist.

  Stage 1 normalizes each `spools.edn` entry as one family, then applies claimed
  `spools.local.edn` coordinate overlays while inheriting family roots and floors.
  The returned `:spools` map remains keyed by root lib. `:families` maps each
  family to its declared `spools.edn` entry, effective post-overlay coordinate,
  provenance, and optional overlay claim. Missing files contribute no spools,
  while malformed present files fail loudly. Stage 2 checks every root floor
  before materialization. When `running-marker` is omitted, declared `:skein/min`
  checks are returned under `:pending-validations`; explicit `:none` rejects
  declared floors."
  ([runtime]
   (approved-spools runtime nil))
  ([runtime running-marker]
   (let [{:keys [families family-projection overrides]} (approved-stage-one runtime)
         {:keys [requirements exception]} (family-requirement-validation families running-marker)
         _ (when exception (throw exception))
         pending-validations (:pending-validations requirements)
         spools (into {}
                      (mapcat
                       (fn [{:keys [family coordinate roots-map claims provenance source]}]
                         (let [kind (:kind coordinate)
                               coordinate-root (if (= :git kind)
                                                 (io/file (cache-base) "skein" "spools" (:git/sha coordinate))
                                                 (io/file (canonical-root runtime (:local/root coordinate))))]
                           (map (fn [[lib root-path]]
                                  (let [root (if (= "." root-path)
                                               coordinate-root
                                               (io/file coordinate-root root-path))]
                                    [lib (with-meta
                                           (cond-> (assoc coordinate
                                                          :root (.getPath root)
                                                          :source source
                                                          :provenance provenance)
                                             claims (assoc :claims claims))
                                           {::family family
                                            ::coordinate coordinate
                                            ::provenance provenance})]))
                                roots-map)))
                       families))
         result (cond-> {:spools spools
                         :families family-projection}
                  (seq overrides) (assoc :mvn-overrides overrides)
                  (seq pending-validations) (assoc :pending-validations pending-validations))]
     (require-spec! ::approved-result result
                    "Normalized approved spool config has an invalid shape" {})
     result)))

(defn- spool-source-fields [entry]
  (case (:kind entry)
    :local (select-keys entry [:local/root])
    :git (select-keys entry [:git/url :git/sha :git/tag])))

(defn- sync-result-base [lib entry]
  (merge {:lib lib
          :family (::family (meta entry))
          :coordinate (::coordinate (meta entry))
          :kind (:kind entry)
          :root (:root entry)
          :source (:source entry)
          :provenance (::provenance (meta entry))}
         (select-keys entry [:claims])
         (spool-source-fields entry)))

(defn- sync-failed [lib entry reason data]
  [lib (merge (sync-result-base lib entry)
              {:status :failed
               :reason reason}
              data)])

(defn root-paths
  "Return a spool root's canonical classpath directories from its `deps.edn`.

  Reads `:paths` (defaulting to `[\"src\"]`) and resolves each entry against
  `root`. Approval covers the root only, so a path escaping it via `..` or a
  symlink fails loudly rather than loading unconsented code."
  [root]
  (let [deps-file (io/file root "deps.edn")]
    (when-not (.isFile deps-file)
      (throw (ex-info "Spool root must contain deps.edn" {:root root})))
    (let [deps (read-config-edn-file deps-file "Spool deps.edn is malformed or unreadable"
                                     {:root root :deps-file (.getPath deps-file)})
          paths (or (:paths deps) ["src"])
          ;; Approval covers the root only; a :paths entry resolving outside it
          ;; (via .. or a symlink) would load code the user never consented to.
          canonical-root (.getCanonicalPath (io/file root))
          root-prefix (str canonical-root java.io.File/separator)]
      (when-not (and (vector? paths) (every? string? paths))
        (throw (ex-info "Spool root deps.edn :paths must be a vector of strings" {:root root :paths paths})))
      (mapv (fn [path]
              (let [resolved (.getCanonicalFile (io/file root path))]
                (when-not (or (= (str resolved) canonical-root)
                              (str/starts-with? (str resolved) root-prefix))
                  (throw (ex-info "Spool root deps.edn :paths must stay inside the spool root"
                                  {:root root :path path :resolved (str resolved)})))
                resolved))
            paths))))

(defn- non-empty-directory? [^java.io.File file]
  (and (.isDirectory file)
       (boolean (seq (.list file)))))

(defn- delete-tree!
  "Delete a tree without following symlinks: links are removed as links, so a
  checkout containing a symlink can never delete content outside `file`."
  [^java.io.File file]
  (let [path (.toPath file)]
    (when (Files/exists path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
      (Files/walkFileTree
       path
       (proxy [SimpleFileVisitor] []
         (visitFile [p _attrs]
           (Files/delete p)
           FileVisitResult/CONTINUE)
         (postVisitDirectory [p exc]
           (when exc (throw exc))
           (Files/delete p)
           FileVisitResult/CONTINUE))))))

(defn- run-git [dir & args]
  (let [process (try
                  (-> (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String (cons "git" args)))
                      (.directory dir)
                      (.redirectErrorStream false)
                      (.start))
                  (catch java.io.IOException e
                    {:exit 127
                     :stderr (ex-message e)}))]
    (if (map? process)
      process
      (let [stderr (future (slurp (.getErrorStream ^Process process)))
            stdout (future (slurp (.getInputStream ^Process process)))
            exit (.waitFor ^Process process)]
        {:exit exit
         :stdout @stdout
         :stderr @stderr}))))

(defn- stderr-tail [stderr]
  (let [trimmed (str/trim (or stderr ""))]
    (if (<= (count trimmed) 4000)
      trimmed
      (subs trimmed (- (count trimmed) 4000)))))

(defn- fetch-failure [result]
  {:exit (:exit result)
   :stderr (stderr-tail (:stderr result))})

(defn- checked-git [dir & args]
  (let [result (apply run-git dir args)]
    (when-not (zero? (:exit result))
      (throw (ex-info "git command failed" (fetch-failure result))))
    result))

(defn- cache-root-file ^java.io.File [entry]
  (io/file (cache-base) "skein" "spools" (:git/sha entry)))

(defn- tag-refs [tag]
  [tag (str tag "^{}")])

(defn- ls-remote-ref-shas [stdout]
  (->> (str/split-lines stdout)
       (keep (fn [line]
               (let [[sha ref] (str/split line #"\s+" 2)]
                 (when (and (not (str/blank? sha)) (not (str/blank? ref)))
                   [ref sha]))))
       (into {})))

(defn- verify-git-tag! [entry]
  (let [result (apply run-git nil "ls-remote" "--tags" (:git/url entry) (tag-refs (:git/tag entry)))]
    (when-not (zero? (:exit result))
      (throw (ex-info "git tag lookup failed" (fetch-failure result))))
    (let [refs (ls-remote-ref-shas (:stdout result))
          tag-ref (str "refs/tags/" (:git/tag entry))
          peeled-ref (str tag-ref "^{}")
          actual (or (get refs peeled-ref) (get refs tag-ref))]
      (when-not (= (:git/sha entry) actual)
        (throw (ex-info "git tag does not match pinned sha"
                        {:reason :tag-mismatch
                         :tag (:git/tag entry)
                         :expected (:git/sha entry)
                         :actual actual}))))))

(defn- git-cache-miss-failure [entry ^java.io.File cache-root result]
  (assoc (fetch-failure result)
         :remote (:git/url entry)
         :cache-path (.getPath cache-root)))

(defn- fetch-advertised-refs! [entry tmp cache-root]
  (let [refetch (run-git tmp
                         "fetch"
                         "--tags"
                         "--prune"
                         (:git/url entry)
                         "+refs/heads/*:refs/remotes/origin/*")]
    (when-not (zero? (:exit refetch))
      (throw (ex-info "git fetch failed"
                      (git-cache-miss-failure entry cache-root refetch))))
    (let [present (run-git tmp "cat-file" "-e" (str (:git/sha entry) "^{commit}"))]
      (when-not (zero? (:exit present))
        (throw (ex-info "git pinned sha is unreachable after refetch"
                        (git-cache-miss-failure entry cache-root present)))))))

(defn- checkout-git-spool! [entry tmp cache-root]
  (checked-git tmp "init")
  (let [shallow (run-git tmp "fetch" "--depth" "1" (:git/url entry) (:git/sha entry))]
    (when-not (zero? (:exit shallow))
      (let [full (run-git tmp "fetch" (:git/url entry) (:git/sha entry))]
        (when-not (zero? (:exit full))
          (fetch-advertised-refs! entry tmp cache-root)))))
  (checked-git tmp "checkout" "--detach" (:git/sha entry))
  (delete-tree! (io/file tmp ".git")))

(defn- move-git-spool-to-cache! [^java.io.File tmp ^java.io.File cache-root]
  (try
    (Files/move (.toPath tmp) (.toPath cache-root)
                (into-array java.nio.file.CopyOption [StandardCopyOption/ATOMIC_MOVE]))
    :fetched
    (catch FileSystemException t
      ;; Concurrent materializers for the same pinned SHA can both fetch into
      ;; temporary directories, then race to publish the content-addressed cache
      ;; root. Platform-specific target-exists failures for non-empty
      ;; directories all extend FileSystemException; if the winner left a usable
      ;; tree, the loser has a cache hit. Otherwise preserve the move failure
      ;; loudly.
      (if (non-empty-directory? cache-root)
        :cached
        (throw t)))))

(defn- materialize-git-spool! [entry]
  (let [cache-root (cache-root-file entry)]
    (if (non-empty-directory? cache-root)
      :cached
      (let [parent (.getParentFile cache-root)
            _ (.mkdirs parent)
            tmp-path (Files/createTempDirectory (.toPath parent) (str (:git/sha entry) ".tmp.") (make-array FileAttribute 0))
            tmp (.toFile tmp-path)]
        (try
          (when-let [_ (:git/tag entry)]
            (verify-git-tag! entry))
          (checkout-git-spool! entry tmp cache-root)
          (let [outcome (move-git-spool-to-cache! tmp cache-root)]
            (when (= :cached outcome)
              (delete-tree! tmp))
            outcome)
          ;; Cleanup must run for every abnormal exit, including JVM Errors and
          ;; interruption, but the original throwable is always rethrown. This
          ;; broad catch is cleanup-only; it never translates failure to data.
          (catch Throwable t
            (when (instance? InterruptedException t)
              (.interrupt (Thread/currentThread)))
            (delete-tree! tmp)
            (throw t)))))))

(defn- read-spool-deps-edn [entry]
  (let [deps-file (io/file (:root entry) "deps.edn")]
    (when (.isFile deps-file)
      (assoc (read-config-edn-file deps-file "Spool deps.edn is malformed or unreadable"
                                   {:root (:root entry) :deps-file (.getPath deps-file)})
             ::deps-file (.getPath deps-file)))))

(defn- validate-spool-maven-deps! [entry deps]
  (when (contains? deps :mvn/repos)
    (throw (ex-info "Spool deps.edn must not declare top-level :mvn/repos"
                    {:root (:root entry)
                     :deps-file (::deps-file deps)
                     :key :mvn/repos})))
  (when (contains? deps :mvn/local-repo)
    (throw (ex-info "Spool deps.edn must not declare top-level :mvn/local-repo"
                    {:root (:root entry)
                     :deps-file (::deps-file deps)
                     :key :mvn/local-repo})))
  (when-not (or (nil? (:deps deps)) (map? (:deps deps)))
    (throw (ex-info "Spool deps.edn :deps must be a map"
                    {:root (:root entry)
                     :deps-file (::deps-file deps)
                     :deps (:deps deps)})))
  (doseq [[lib coord] (:deps deps)]
    (validate-maven-coordinate! lib coord {:root (:root entry) :deps-file (::deps-file deps)}))
  (:deps deps))

(defn- spool-maven-deps! [entry]
  (when-let [deps (read-spool-deps-edn entry)]
    (not-empty (validate-spool-maven-deps! entry deps))))

(defn- launch-basis
  "Read skein's immutable launch basis from the `clojure.basis` property file.

  Returns the parsed basis map, or nil when the property or file is absent (a
  process not started by the Clojure CLI). Reads only the immutable launch file,
  never the process-global runtime basis, so it observes no runtime mutation."
  []
  (when-let [path (System/getProperty "clojure.basis")]
    (let [file (io/file path)]
      (when (.exists file)
        (query/read-edn-file file)))))

(defn- resolve-spool-maven-libs
  "Resolve merged Maven `deps` against skein's launch classpath, returning `:added`.

  Reads skein's launch libs and `:mvn/repos`/`:mvn/local-repo` from the immutable
  `clojure.basis` property file and asks tools.deps — via the shipped
  `clojure -T:deps` subprocess seam — to resolve only the delta over that
  already-provided universe, returning the tools.deps `:added` lib-map (lib to a
  coordinate carrying resolved `:paths`). Libs skein already ships are excluded so
  nothing shadows the base classpath. An empty universe resolves to `{}` without a
  subprocess or basis read. Fails loudly (TEN-003) when Maven deps must be resolved
  but the launch basis is unavailable, since a missing provided universe would
  re-add coordinates skein already ships. This is the sole mockable resolver
  seam; resolution over the universe is atomic, so an unresolvable coordinate
  throws and fails the whole sync."
  [deps]
  (if (empty? deps)
    {}
    (let [basis (launch-basis)]
      (when-not basis
        (throw (ex-info (format/reflow
                         "|Cannot resolve spool Maven dependencies: skein's launch basis
                           |(clojure.basis property file) is unavailable, so the already-provided
                           |classpath universe is unknown. Start the weaver via the Clojure CLI so
                           |the launch basis is present.")
                        {:libs (vec (keys deps))
                         :clojure-basis (System/getProperty "clojure.basis")})))
      (let [existing (:libs basis)
            add (reduce-kv (fn [m lib coord]
                             (if (contains? existing lib) m (assoc m lib coord)))
                           {} deps)]
        (if (empty? add)
          {}
          (:added (deps-interop/invoke-tool
                   {:tool-alias :deps
                    :fn 'clojure.tools.deps/resolve-added-libs
                    :args {:existing existing
                           :add add
                           :procurer (select-keys basis [:mvn/repos :mvn/local-repo])}})))))))

(defn- materialize-git-family-outcome
  "Materialize a git family, returning `{:fetch ...}` or failure data.

  Only the materialization call may translate throws into fetch/tag outcomes;
  anything thrown elsewhere in sync is a real error and must stay loud."
  [entry]
  (try
    {:fetch (materialize-git-spool! entry)}
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (cond
          (= :tag-mismatch (:reason data))
          {:failure {:reason :tag-mismatch
                     :data (select-keys data [:tag :expected :actual])}}

          (integer? (:exit data))
          {:failure {:reason :fetch-failed
                     :data (cond-> (fetch-failure data)
                             (:remote data) (assoc :remote (:remote data))
                             (:cache-path data) (assoc :cache-path (:cache-path data)))}}

          :else
          (throw e))))
    (catch java.io.IOException t
      {:failure {:reason :fetch-failed
                 :data {:exit 1
                        :stderr (stderr-tail (ex-message t))}}})))

(defn- materialize-families
  "Materialize each approved git family once and index its outcome by family."
  [spools]
  (into {}
        (map (fn [[family entries]]
               (let [entry (val (first entries))]
                 [family (when (= :git (:kind entry))
                           (materialize-git-family-outcome entry))])))
        (group-by (comp ::family meta val) spools)))

(defn- runtime-add-failure [lib entry fetch t]
  {:failed (sync-failed lib entry :runtime-add-failed
                        (cond-> {:message (ex-message t)
                                 :class (str (class t))}
                          (ex-data t) (assoc :data (ex-data t))
                          fetch (assoc :fetch fetch)))})

(defn- materialize-and-validate-spool
  "Phase 1: materialize a git root, verify it on disk, and validate it per-root.

  Returns `{:failed [lib result]}` for a per-root materialization/validation
  failure (recorded exactly as before: fetch/tag, missing/unreadable root, or a
  `:runtime-add-failed` Maven-policy/source-path failure), or `{:survivor {...}}`
  carrying the lib, entry, validated Maven deps, vetted source-path `File`s, and
  any `:fetch` outcome for the shared resolution phase."
  [lib entry materialization]
  (let [{:keys [fetch failure]} materialization
        root-file (io/file (:root entry))]
    (cond
      failure
      {:failed (sync-failed lib entry (:reason failure) (:data failure))}

      (not (.exists root-file))
      {:failed (sync-failed lib entry :missing-root (cond-> {} fetch (assoc :fetch fetch)))}

      (or (not (.isDirectory root-file)) (not (.canRead root-file)))
      {:failed (sync-failed lib entry :unreadable-root (cond-> {} fetch (assoc :fetch fetch)))}

      :else
      (try
        (let [maven-deps (spool-maven-deps! entry)
              source-paths (root-paths (:root entry))]
          (doseq [^java.io.File path source-paths]
            (when-not (.isDirectory path)
              (throw (ex-info "Local root classpath entry must be a directory"
                              {:root (:root entry) :path (.getPath path)}))))
          {:survivor {:lib lib
                      :entry entry
                      :maven-deps maven-deps
                      :source-paths source-paths
                      :fetch fetch}})
        (catch clojure.lang.ExceptionInfo t
          (runtime-add-failure lib entry fetch t))
        (catch java.io.IOException t
          (runtime-add-failure lib entry fetch t))))))

(defn- merge-maven-universe
  "Merge every surviving root's declared Maven deps into one resolution universe.

  Returns a `{lib coord}` map. A lib declared by multiple roots with disagreeing
  coordinates fails the whole sync loudly (TEN-003), naming the lib, coordinates,
  and declaring roots, unless `mvn-overrides` pins it — an override replaces the
  lib's coordinate across the universe and silences its conflict. Overrides that
  no surviving root declares are ignored."
  [survivors mvn-overrides]
  (let [declarations (for [{:keys [lib maven-deps]} survivors
                           [dep coord] maven-deps]
                       {:root lib :lib dep :coord coord})]
    (reduce-kv
     (fn [universe dep decls]
       (if-let [override (get mvn-overrides dep)]
         (assoc universe dep override)
         (let [coords (into #{} (map :coord) decls)]
           (if (> (count coords) 1)
             (throw (ex-info (str "Cross-root Maven coordinate conflict for " dep ": "
                                  (str/join ", " (map (fn [{:keys [root coord]}]
                                                        (str (pr-str coord) " (" root ")"))
                                                      decls))
                                  "; pin " dep " in :mvn-overrides to resolve")
                             {:lib dep
                              :coordinates (vec coords)
                              :roots (mapv :root decls)}))
             (assoc universe dep (:coord (first decls)))))))
     {}
     (group-by :lib declarations))))

(defn- file-url-string [path]
  (str (.toURL (.toURI (io/file path)))))

(declare clojure-source? parse-source-ns source-file->ns-sym)

(def ^:private pending-generation-remedy
  "Operator-facing remedy for a non-additive spool sync diff."
  "recorded; takes effect at the next weaver generation (mill-supervised restart, user sign-off)")

(defn- added-jar-paths [added]
  (distinct (mapcat :paths (vals added))))

(defn- add-delta-jars!
  "Add the resolved jar URLs absent from `pre-urls` to the spool classloader."
  [^clojure.lang.DynamicClassLoader loader added pre-urls]
  (doseq [path (added-jar-paths added)
          :when (not (contains? pre-urls (file-url-string path)))]
    (.addURL loader (.toURL (.toURI (io/file path))))))

(defn- add-source-paths!
  "Add a surviving root's vetted source directories to the spool classloader."
  [^clojure.lang.DynamicClassLoader loader source-paths]
  (doseq [^java.io.File path source-paths]
    (.addURL loader (.toURL (.toURI path)))))

(defn- spool-load-status
  "Classify a surviving root `:loaded`/`:already-available` against `pre-urls`.

  A root is `:loaded` when this sync newly added any of its source directories or
  its directly-declared Maven jars to the classloader — otherwise every URL it
  contributes was already present and it is `:already-available`."
  [maven-deps source-paths added pre-urls]
  (let [source-urls (map (fn [^java.io.File p] (str (.toURL (.toURI p)))) source-paths)
        jar-urls (for [dep (keys maven-deps)
                       :let [coord (get added dep)]
                       :when coord
                       path (:paths coord)]
                   (file-url-string path))]
    (if (some (complement pre-urls) (concat source-urls jar-urls))
      :loaded
      :already-available)))

(defn- successful-sync? [[_ result]]
  (#{:loaded :already-available} (:status result)))

(defn- sha256-file [^java.io.File file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream file)]
      (let [buffer (byte-array 8192)]
        (loop []
          (let [n (.read in buffer)]
            (when (pos? n)
              (.update digest buffer 0 n)
              (recur))))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn- root-fingerprint [source-paths]
  (->> source-paths
       (mapcat file-seq)
       (filter clojure-source?)
       (map (fn [^java.io.File file]
              [(.getCanonicalPath file) (sha256-file file)]))
       sort
       vec))

(defn- root-namespace-set [source-paths]
  (set (keep (fn [^java.io.File file]
               (:ns (parse-source-ns {:file (.getCanonicalPath file)})))
             (for [^java.io.File dir source-paths
                   ^java.io.File file (file-seq dir)
                   :when (clojure-source? file)]
               file))))

(defn- non-additive-diff [previous previous-fingerprints previous-maven approved survivors resolved-maven]
  (let [previous-loaded (into {} (filter successful-sync?) previous)
        approved-libs (set (keys (:spools approved)))
        current-by-lib (into {} (map (juxt :lib identity)) survivors)
        removals (vec (for [[lib result] previous-loaded
                            :when (not (contains? approved-libs lib))]
                        (select-keys result [:lib :kind :root :source])))
        changed-roots (vec (for [[lib result] previous-loaded
                                 :let [survivor (get current-by-lib lib)]
                                 :when (and survivor (not= (:root result) (get-in survivor [:entry :root])))]
                             {:lib lib
                              :previous-root (:root result)
                              :new-root (get-in survivor [:entry :root])}))
        redefinitions (vec (for [[lib result] previous-loaded
                                 :let [survivor (get current-by-lib lib)
                                       fingerprint (when survivor (root-fingerprint (:source-paths survivor)))]
                                 :when (and survivor
                                            (= (:root result) (get-in survivor [:entry :root]))
                                            (some find-ns (root-namespace-set (:source-paths survivor)))
                                            (or (nil? (get previous-fingerprints lib))
                                                (not= (get previous-fingerprints lib) fingerprint)))]
                             {:lib lib
                              :root (:root result)
                              :loaded-namespaces (filterv find-ns (root-namespace-set (:source-paths survivor)))}))
        version-bumps (vec (for [[coord previous-version] previous-maven
                                 :let [new-version (get resolved-maven coord)]
                                 :when (and new-version (not= previous-version new-version))]
                             {:coordinate coord
                              :previous-version previous-version
                              :new-version new-version}))]
    (cond-> {}
      (seq removals) (assoc :removed-roots removals)
      (seq changed-roots) (assoc :changed-roots changed-roots)
      (seq redefinitions) (assoc :redefinitions redefinitions)
      (seq version-bumps) (assoc :maven-version-bumps version-bumps))))

(defn- stale-spool-state-report [runtime]
  (let [current (:generation-id runtime)]
    (vec (for [[key value] @(:spool-state runtime)
               :let [generation (:skein.runtime/generation (meta value))]
               :when (not= generation current)]
           (cond-> {:key key
                    :generation (or generation :unknown)
                    :current-generation current}
             (nil? generation) (assoc :reason :untagged))))))

(defn- record-pending-generation! [runtime diff approved]
  (let [pending {:status :pending
                 :generation (:generation-id runtime)
                 :diff diff
                 :approved-spools (set (keys (:spools approved)))
                 :remedy pending-generation-remedy}]
    (reset! (:pending-spool-generation runtime) pending)
    pending))

(defn- fail-non-additive-diff! [runtime diff approved]
  (let [pending (record-pending-generation! runtime diff approved)]
    (throw (ex-info (str "Non-additive spool sync diff recorded; " pending-generation-remedy)
                    {:status :failed
                     :reason :non-additive-sync-diff
                     :diff diff
                     :pending-generation pending
                     :remedy pending-generation-remedy}))))

(defn sync-approved-spools
  "Load approved spools into the runtime classloader and record per-spool status.

  Phase 1 materializes and validates each approved root independently, recording
  per-root `:failed` outcomes. Phase 2 resolves the union of every surviving root's
  Maven deps (with `:mvn-overrides` applied) once against skein's launch classpath,
  adds the delta jars and each surviving root's source paths to the single spool
  classloader, and classifies each root `:loaded`/`:already-available`. A
  non-additive diff restores the previous public sync state, records a
  `:pending-generation`, and throws before touching the classloader. Maven
  resolution is atomic: a cross-root version conflict or an unresolvable universe
  fails the whole sync loudly, leaving `{}` sync state rather than partial results.
  A running release marker enables `:skein/min` validation. An omitted marker
  leaves those checks visible in `:pending-validations`; explicit `:none` rejects
  declared floors."
  ([runtime]
   (sync-approved-spools runtime nil))
  ([runtime running-marker]
   ;; Stale state clears before anything that can throw — a structural spools.edn
   ;; failure or an atomic-resolution abort both leave {} rather than stale results.
   (let [public-previous @(approved-spool-sync-state runtime)
         previous @(:approved-spool-generation-state runtime)
         previous-fingerprints @(:approved-spool-generation-fingerprints runtime)
         previous-maven @(:approved-spool-generation-maven runtime)]
     (reset! (approved-spool-sync-state runtime) {})
     (let [approved (approved-spools runtime running-marker)
           materializations (materialize-families (:spools approved))
           phase1 (mapv (fn [[lib entry]]
                          (materialize-and-validate-spool
                           lib entry (get materializations (::family (meta entry)))))
                        (:spools approved))
           failed (into {} (keep :failed) phase1)
           survivors (into [] (keep :survivor) phase1)
           structural-diff (non-additive-diff previous previous-fingerprints {} approved survivors {})
           _ (when (seq structural-diff)
               (reset! (approved-spool-sync-state runtime) public-previous)
               (fail-non-additive-diff! runtime structural-diff approved))
           universe (merge-maven-universe survivors (:mvn-overrides approved))
           added (resolve-spool-maven-libs universe)
           resolved-maven (into {} (map (fn [[lib coord]] [lib (resolved-maven-version lib coord)])) added)
           diff (non-additive-diff previous previous-fingerprints previous-maven approved survivors resolved-maven)
           _ (when (seq diff)
               (reset! (approved-spool-sync-state runtime) public-previous)
               (fail-non-additive-diff! runtime diff approved))
           loader (:spool-classloader runtime)
           pre-urls (set (map str (.getURLs ^java.net.URLClassLoader loader)))
           _ (add-delta-jars! loader added pre-urls)
           survivor-results (into {}
                                  (map (fn [{:keys [lib entry maven-deps source-paths fetch]}]
                                         (add-source-paths! loader source-paths)
                                         [lib (cond-> (assoc (sync-result-base lib entry)
                                                             :status (spool-load-status maven-deps source-paths added pre-urls))
                                                fetch (assoc :fetch fetch))]))
                                  survivors)
           results (into (sorted-map) (concat failed survivor-results))
           fingerprints (into {} (map (juxt :lib (comp root-fingerprint :source-paths))) survivors)
           retained (stale-spool-state-report runtime)]
       (reset! (approved-spool-sync-state runtime) results)
       (reset! (:approved-spool-generation-state runtime)
               (merge (into (sorted-map) previous)
                      (into (sorted-map) (filter successful-sync?) results)))
       (reset! (:approved-spool-generation-fingerprints runtime) (merge previous-fingerprints fingerprints))
       (reset! (:approved-spool-generation-maven runtime) (merge previous-maven resolved-maven))
       (let [result (cond-> {:spools results}
                      (seq (:pending-validations approved))
                      (assoc :pending-validations (:pending-validations approved))
                      (seq retained) (assoc :retained-spool-state retained)
                      @(:pending-spool-generation runtime)
                      (assoc :pending-generation @(:pending-spool-generation runtime)))]
         (require-spec! ::sync-result result
                        "Approved spool sync result has an invalid shape" {})
         result)))))

(defn approved-spool-syncs
  "Return the most recent approved spool sync results."
  [runtime]
  (let [result (cond-> {:spools (into (sorted-map) @(approved-spool-sync-state runtime))}
                 @(:pending-spool-generation runtime)
                 (assoc :pending-generation @(:pending-spool-generation runtime)))]
    (require-spec! ::sync-result result
                   "Approved spool sync state has an invalid shape" {})
    result))

(defn module-file
  "Resolve module-use `path` against `runtime`'s config-dir, failing on escape.

  Module-use `:file` targets are approved only within the selected config-dir, so
  a path resolving outside it (via `..` or a symlink) would load code the
  operator never consented to and fails loudly."
  [runtime path]
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
              (root-paths root)))
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

(defn load-synced-namespace!
  "Load `ns-sym` from `runtime`'s synced spool roots.

  An already-loaded namespace is a no-op. Otherwise the source is located under
  the synced approved roots and `load-file`d; a namespace whose source is in no
  root fails loudly with the roots that were searched."
  [runtime ns-sym]
  (if (find-ns ns-sym)
    {:ns ns-sym}
    (let [{:keys [file relative-path searched-roots]} (locate-synced-namespace-file runtime ns-sym)]
      (if file
        (do
          (load-file file)
          {:ns ns-sym :file file})
        (throw (ex-info "Could not locate namespace source in synced spool roots"
                        {:ns ns-sym
                         :relative-path relative-path
                         :searched-roots searched-roots}))))))

(defn- clojure-source? [^java.io.File file]
  (let [n (.getName file)]
    (and (.isFile file)
         (or (str/ends-with? n ".clj") (str/ends-with? n ".cljc")))))

(defn- source-file->ns-sym
  "Derive a namespace symbol from a source `file` relative to its classpath `dir`.

  `root-paths` only vets top-level `:paths` entries, so a file reached through a
  deeper symlink can canonicalize outside `dir`'s prefix. That escape fails loudly
  with the offending file and root instead of a bare `StringIndexOutOfBoundsException`
  carrying no context."
  [^java.io.File dir ^java.io.File file]
  (let [canonical-dir (.getCanonicalPath dir)
        canonical-file (.getCanonicalPath file)
        prefix (str canonical-dir java.io.File/separator)]
    (when-not (str/starts-with? canonical-file prefix)
      (throw (ex-info "Synced spool source escapes its classpath dir via a symlink"
                      {:status :failed :reason :source-escapes-root
                       :file canonical-file :root canonical-dir})))
    (-> (subs canonical-file (count prefix))
        (str/replace #"\.cljc?$" "")
        (str/replace java.io.File/separator ".")
        (str/replace "_" "-")
        symbol)))

(defn- spool-namespace-sources
  "Discover `{:ns sym :file path}` for every `.clj`/`.cljc` under `root`'s consented
  `root-paths` classpath dirs. The reload file set is exactly that classpath, no wider."
  [root]
  (vec
   (for [^java.io.File dir (root-paths root)
         ^java.io.File file (file-seq dir)
         :when (clojure-source? file)]
     {:ns (source-file->ns-sym dir file)
      :file (.getCanonicalPath file)})))

(defn- parse-source-ns
  "Attach the declared namespace and its required deps to `source`.

  Reads the source's `ns` form with `org.clojure/tools.namespace` — the same
  battle-tested parser the compiler-agnostic `refresh` uses — rather than
  trusting the classpath-derived name. `:deps` is the set of every namespace this
  source requires/uses; the intra-root subset becomes the reload-order edges. A
  source with no readable `ns` form keeps its path-derived `:ns` and contributes
  no edges."
  [{:keys [file] :as source}]
  (with-open [reader (java.io.PushbackReader. (io/reader (io/file file)))]
    (let [decl (ns-parse/read-ns-decl reader)]
      (assoc source
             :ns (if decl (ns-parse/name-from-ns-decl decl) (:ns source))
             :deps (if decl (ns-parse/deps-from-ns-decl decl) #{})))))

(defn- index-sources-by-ns
  "Index parsed `sources` by declared `:ns`, failing loudly on a collision.

  Two files under `root-lib`'s consented root-paths that declare the same namespace
  would let a plain `(into {} …)` silently keep whichever parsed last and drop the
  other from the reload set. That violates the swallow-nothing contract, so a
  collision throws with the colliding namespace and both file paths instead of
  arbitrarily picking one."
  [root-lib sources]
  (reduce (fn [m source]
            (let [ns-sym (:ns source)]
              (if-let [prior (get m ns-sym)]
                (throw (ex-info "Two synced spool sources declare the same namespace"
                                {:status :failed :reason :duplicate-namespace :root-lib root-lib
                                 :namespace ns-sym :files [(:file prior) (:file source)]}))
                (assoc m ns-sym source))))
          {}
          sources))

(defn- dependency-graph
  "Build the intra-root dependency graph over `parsed`, restricted to `intra` edges.

  A genuine circular intra-root require makes `org.clojure/tools.namespace` throw a
  raw `::circular-dependency` ex-info carrying no `:status`/`:root-lib`. Catch it
  and rethrow under the same fixed
  `{:status :failed :reason … :root-lib …}` contract the root-resolution gates
  use, naming the cycle."
  [root-lib intra parsed]
  (try
    (reduce (fn [g {ns-sym :ns deps :deps}]
              (reduce (fn [g dep]
                        (if (contains? intra dep)
                          (ns-dep/depend g ns-sym dep)
                          g))
                      g
                      deps))
            (ns-dep/graph)
            parsed)
    (catch clojure.lang.ExceptionInfo e
      (let [{:keys [reason node dependency]} (ex-data e)]
        (if (= ::ns-dep/circular-dependency reason)
          (throw (ex-info "Synced spool sources have a circular intra-root require"
                          {:status :failed :reason :circular-requires :root-lib root-lib
                           :cycle {:namespace node :requires dependency}}
                          e))
          (throw e))))))

(defn- dependency-ordered-sources
  "Order `root-lib`'s `sources` dependencies-first within this root only.

  Each source is parsed for its `ns` form, then topologically sorted so a
  namespace reloads after every intra-root namespace it requires. External
  requires (`clojure.*`, blessed `skein.api.*`, other spools) are edges out of
  the set and are neither ordered nor reloaded. Namespaces with no intra-root
  relationship keep their discovery order and follow the sorted set. Two sources
  declaring the same namespace, or a circular intra-root require, fail loudly
  under the root lib's `{:status :failed :reason …}` contract."
  [root-lib sources]
  (let [parsed (mapv parse-source-ns sources)
        by-ns (index-sources-by-ns root-lib parsed)
        intra (set (keys by-ns))
        graph (dependency-graph root-lib intra parsed)
        sorted (filter intra (ns-dep/topo-sort graph))
        remaining (remove (set sorted) (map :ns parsed))]
    (mapv by-ns (concat sorted remaining))))

(defn reload-synced-spool!
  "Make `root-lib`'s latest synced source live under the spool classloader.

  Resolves `root-lib` from the runtime's root-lib-keyed approved-spool sync state
  and approved allowlist (a root can be approved yet unsynced or sync-failed),
  discovers its namespace sources under the consented `root-paths` classpath, sorts
  them dependencies-first with `org.clojure/tools.namespace` (intra-root edges only),
  and `load-file`s each inside `with-spool-classloader`. Unlike `load-synced-namespace!`
  there is no load-once short-circuit — already-loaded namespaces are reloaded. A
  bumped cross-namespace macro is therefore live for its consumers, which reload
  after it. External requires are edges out of the set and are not reloaded, so this
  is neither `refresh` (classloader-blind to spool roots) nor `require :reload-all`
  (reloads transitive non-spool deps).

  Fails loudly with a reused `:reason` in ex-data (mirroring `sync-failed`'s
  `{:status :failed :reason …}` shape), checked in fixed order:
  `:not-approved` → `:not-synced` → `:sync-failed` → `:missing-root` →
  `:unreadable-root` → `:no-namespaces`. The on-disk root re-check mirrors
  `sync-approved-spool!`'s `exists`/`isDirectory`/`canRead` gate, so a root replaced
  by a file or permission-stripped since sync fails with `:missing-root`/
  `:unreadable-root` rather than a raw `load-file` exception carrying no `:reason`.

  Two further reasons surface at reload-ordering time, once the root is confirmed
  loadable: `:duplicate-namespace` (two sources under the root declare the same
  namespace, which would otherwise silently drop one file) and `:circular-requires`
  (a circular intra-root require, rethrown from `org.clojure/tools.namespace` under
  this same `{:status :failed :reason … :root-lib …}` shape rather than escaping
  raw).

  Returns a data-first map naming the root lib, its resolved canonical root, and
  the namespaces reloaded with their source files."
  [runtime root-lib]
  (let [approved (approved-spools runtime)
        syncs (merge @(:approved-spool-generation-state runtime)
                     @(approved-spool-sync-state runtime))
        sync-entry (get syncs root-lib)]
    (when-not (contains? (:spools approved) root-lib)
      (throw (ex-info "Spool root lib is not approved"
                      {:status :failed :reason :not-approved :root-lib root-lib})))
    (when-not (contains? syncs root-lib)
      (throw (ex-info "Spool root lib is not synced"
                      {:status :failed :reason :not-synced :root-lib root-lib})))
    (when-not (#{:loaded :already-available} (:status sync-entry))
      (throw (ex-info "Spool root lib did not sync successfully"
                      {:status :failed :reason :sync-failed :root-lib root-lib :sync sync-entry})))
    (let [root (:root sync-entry)
          root-file (io/file root)]
      (when-not (.exists root-file)
        (throw (ex-info "Synced spool root is missing on disk"
                        {:status :failed :reason :missing-root :root-lib root-lib :root root})))
      (when (or (not (.isDirectory root-file)) (not (.canRead root-file)))
        (throw (ex-info "Synced spool root is not a readable directory"
                        {:status :failed :reason :unreadable-root :root-lib root-lib :root root})))
      (let [canonical-root (.getCanonicalPath root-file)
            sources (spool-namespace-sources root)]
        (when (empty? sources)
          (throw (ex-info "Synced spool root has no namespace sources"
                          {:status :failed :reason :no-namespaces :root-lib root-lib :root canonical-root})))
        (let [ordered (dependency-ordered-sources root-lib sources)]
          (with-spool-classloader
            runtime
            (fn [] (doseq [{:keys [file]} ordered]
                     (load-file file))))
          {:root-lib root-lib
           :root canonical-root
           :namespaces (mapv #(select-keys % [:ns :file]) ordered)})))))
