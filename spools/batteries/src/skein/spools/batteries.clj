(ns skein.spools.batteries
  "Shipped core strand command surface as parser-backed weaver ops.

  Batteries registers the everyday strand operations — add/update/show/supersede/
  burn/list/ready/subgraph, spool coordinate helpers, the create-only `weave`
  op, and the read-only `query`/`pattern` registry-introspection ops — as
  `register-op!` ops whose
  `:arg-spec` is parsed by `skein.api.cli.alpha`. Each op delegates to the same
  `skein.api.*.alpha` calls the JSON socket dispatch uses today and returns
  the same JSON shapes, so the ops are reachable through `strand <name>` at the
  CLI root. The namespace owns no module-level state:
  op handlers read the runtime from their invocation context (`:op/runtime`).

  Attribute/edge flag semantics reproduce old SPEC-002.C6–C11: `--attr key=value`
  is a repeatable, highest-precedence string map whose values may be payload
  references; `--attributes` references a JSON object of typed bulk attributes at
  lowest precedence; `--edge edge-type:to-id` adds outgoing edges. `--state`
  accepts `active|closed` for mutations and `active|closed|replaced` for `list`
  filtering."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [skein.api.current.alpha :as current]
            [skein.api.format.alpha :as format-alpha]
            [skein.api.graph.alpha :as graph]
            [skein.api.notes.alpha :as notes]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.runtime.alpha :as runtime-api]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.query :as query]
            [skein.core.specs :as specs])
  (:import [java.io PushbackReader StringReader]
           [java.nio.file FileVisitResult Files LinkOption SimpleFileVisitor]
           [java.nio.file.attribute FileAttribute]))

(def ^:private generic-states #{"active" "closed"})
(def ^:private lean-attribute-byte-floor 1024)
(def ^:private default-read-limit 500)
(def ^:private readable-states #{"active" "closed" "replaced"})
(def ^:private release-tag-pattern #"v([1-9][0-9]*)")

(defn- exact-keys? [expected value]
  (and (map? value) (= expected (set (keys value)))))

;; Public data contracts for the spool op. The manifest spec owns the producer
;; input shape; the result specs own the three JSON-bearing command results.
(s/def ::non-blank-string (s/and string? (complement str/blank?)))
(s/def ::root ::non-blank-string)
(s/def ::manifest-root
  (s/and (s/keys :req-un [::root])
         #(exact-keys? #{:root} %)))
(s/def ::roots (s/map-of symbol? ::manifest-root :min-count 1))
(s/def ::requires (s/map-of symbol? ::specs/release-marker-claim))
(s/def :spool/format #{1})
(s/def ::advisory-manifest
  (s/and (s/keys :req [:spool/format]
                 :req-un [::roots]
                 :opt [:skein/min]
                 :opt-un [::requires])
         #(exact-keys? #{:spool/format :skein/min :roots :requires}
                       (merge {:skein/min nil :requires nil} %))))

(s/def ::status #{:inserted :updated})
(s/def ::operation ::non-blank-string)
(s/def ::family symbol?)
(s/def ::entry ::runtime-api/spool-entry)
(s/def ::requirements
  #(s/valid? ::runtime-api/declared-result {:families {} :requirements %}))
(s/def ::spool-add-result
  (s/and (s/keys :req-un [::operation ::status ::family ::entry ::requirements])
         #(exact-keys? #{:operation :status :family :entry :requirements} %)))
(s/def ::tag ::specs/release-marker-claim)
(s/def ::sha #(and (string? %) (boolean (re-matches #"[0-9a-f]{40}" %))))
(s/def ::coordinate
  (s/and (s/keys :req-un [::tag ::sha])
         #(exact-keys? #{:tag :sha} %)))
(s/def ::old ::coordinate)
(s/def ::new ::coordinate)
(s/def ::compare-url ::non-blank-string)
(s/def ::spool-bump-result
  (s/and (s/keys :req-un [::operation ::status ::family ::old ::new ::compare-url ::requirements])
         #(exact-keys? #{:operation :status :family :old :new :compare-url :requirements} %)))
(s/def ::status-family
  (s/and map?
         #(exact-keys? #{:declared :effective-coordinate :provenance :claims :syncs :uses} %)
         #(s/valid? ::runtime-api/spool-entry (:declared %))
         #(map? (:effective-coordinate %))
         #(keyword? (:provenance %))
         #(or (nil? (:claims %)) (string? (:claims %)))
         #(map? (:syncs %))
         #(map? (:uses %))))
(s/def ::families (s/map-of symbol? ::status-family))
(s/def ::pending-generation (s/nilable ::runtime-api/pending-generation))
(s/def ::release-marker ::specs/release-marker-result)
(s/def ::spool-status-result
  (s/and (s/keys :req-un [::operation ::families ::requirements ::pending-generation ::release-marker])
         #(exact-keys? #{:operation :families :requirements :pending-generation :release-marker} %)))

(defn- require-valid! [spec value message]
  (when-not (s/valid? spec value)
    (throw (ex-info message
                    {:spec spec
                     :value value
                     :explain (s/explain-data spec value)})))
  value)

(defn- validate-generic-state
  "Return state when it is active|closed, else fail loudly (mutations)."
  [state]
  (when-not (generic-states state)
    (throw (ex-info "Strand state must be active or closed"
                    {:state state :allowed (vec (sort generic-states))})))
  state)

(defn- validate-readable-state
  "Return state when it is active|closed|replaced, else fail loudly (list filter)."
  [state]
  (when-not (readable-states state)
    (throw (ex-info "Strand state must be active, closed, or replaced"
                    {:state state :allowed (vec (sort readable-states))})))
  state)

(defn- validate-read-limit
  "Return limit when it is a positive integer, else fail loudly."
  [limit]
  (when-not (s/valid? ::specs/read-limit limit)
    (throw (ex-info "Read result limit must be a positive integer"
                    {:limit limit :explain (s/explain-str ::specs/read-limit limit)})))
  limit)

(defn- validate-vocab-kind
  "Return the --kind value as its declaration-kind keyword, else fail loudly.
  Reuses the vocab registry's own `:kind` enum as the allow-list."
  [kind]
  (let [k (keyword kind)]
    (when-not (contains? vocab/declaration-kinds k)
      (throw (ex-info "vocab --kind must be attr-namespace or edge"
                      {:kind kind :allowed (mapv name (sort vocab/declaration-kinds))})))
    k))

(defn- read-limit-state [rt]
  (runtime-api/spool-state rt ::read-limit #(atom default-read-limit)))

(defn read-limit
  "Return the runtime's batteries read-result cap for CLI list/ready ops."
  [rt]
  @(read-limit-state rt))

(defn set-read-limit!
  "Set the runtime's batteries read-result cap for CLI list/ready ops.

  Intended for trusted workspace config. Invalid values fail loudly instead of
  falling back to the default cap."
  [rt limit]
  (let [limit (validate-read-limit limit)]
    (reset! (read-limit-state rt) limit)
    limit))

(defn- effective-read-limit [rt explicit-limit]
  (validate-read-limit (or explicit-limit (read-limit rt))))

(defn- request-context
  "Build the mutation request context so hooks and events see the operation."
  [operation]
  {:request/source :json-socket
   :request/operation operation})

(defn- json-safe-value
  "Coerce query-introspection payloads (which carry EDN query expressions with
  keywords, symbols, and sets) into JSON-safe data, matching the JSON socket's
  `query-list`/`query-explain` projection so `strand query …` returns identical
  shapes to the old builtin."
  [value]
  (cond
    (nil? value) nil
    (or (string? value) (number? value) (boolean? value)) value
    (keyword? value) (subs (str value) 1)
    (symbol? value) (str value)
    (map? value) (into {} (map (fn [[k v]] [(json-safe-value k) (json-safe-value v)])) value)
    (sequential? value) (mapv json-safe-value value)
    (set? value) (mapv json-safe-value (sort-by pr-str value))
    :else (pr-str value)))

;; --- spool Git boundary -----------------------------------------------------

(defn- delete-tree!
  "Delete a temporary tree without following symbolic links."
  [^java.io.File root]
  (let [path (.toPath root)]
    (when (Files/exists path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
      (Files/walkFileTree
       path
       (proxy [SimpleFileVisitor] []
         (visitFile [file _attrs]
           (Files/delete file)
           FileVisitResult/CONTINUE)
         (postVisitDirectory [dir error]
           (when error (throw error))
           (Files/delete dir)
           FileVisitResult/CONTINUE))))))

(defn- run-git
  "Run Git with argv and return its exit, stdout, and stderr."
  [dir & args]
  (let [process (try
                  (-> (ProcessBuilder. ^java.util.List (vec (cons "git" args)))
                      (.directory dir)
                      (.start))
                  (catch java.io.IOException error
                    {:exit 127 :stdout "" :stderr (ex-message error)}))]
    (if (map? process)
      process
      (let [stdout (future (slurp (.getInputStream ^Process process)))
            stderr (future (slurp (.getErrorStream ^Process process)))
            exit (.waitFor ^Process process)]
        {:exit exit :stdout @stdout :stderr @stderr}))))

(defn- checked-git
  "Run Git, returning stdout or failing with command diagnostics."
  [dir & args]
  (let [result (apply run-git dir args)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Git command failed"
                      {:argv (vec (cons "git" args))
                       :exit (:exit result)
                       :stderr (str/trim (:stderr result))})))
    (:stdout result)))

(defn- ls-remote [git-url]
  (checked-git nil "ls-remote" "--tags" git-url))

(defn- manifest-at [git-url sha]
  (let [tmp (.toFile (Files/createTempDirectory "skein-spool-manifest-"
                                                (make-array FileAttribute 0)))]
    (try
      (checked-git tmp "init" "--quiet")
      (checked-git tmp "fetch" "--quiet" "--depth=1" git-url sha)
      (let [probe (run-git tmp "cat-file" "-e" "FETCH_HEAD:spool.edn")]
        (when (zero? (:exit probe))
          (checked-git tmp "show" "FETCH_HEAD:spool.edn")))
      (finally
        (delete-tree! tmp)))))

(def ^:private default-git-client
  {:ls-remote ls-remote
   :manifest-at manifest-at})

(defn- git-client-state [rt]
  (runtime-api/spool-state rt ::git-client #(atom default-git-client)))

(defn- git-client [rt]
  @(git-client-state rt))

(defn- release-number [tag]
  (when-let [[_ n] (and (string? tag) (re-matches release-tag-pattern tag))]
    (bigint n)))

(defn- available-tags-description [available]
  (if (seq available)
    (str/join ", " available)
    "none found"))

(defn- release-tag-diagnostics [available]
  {:accepted-format "vN where N is a positive integer"
   :available (vec available)})

(defn- require-release-tag [tag role available]
  (when-not (release-number tag)
    (throw (ex-info
            (str role " must match vN where N is a positive integer; v0 is reserved. "
                 "Available annotated tags: " (available-tags-description available))
            {:tag tag
             :reason :invalid-release-marker
             :accepted-format (:accepted-format (release-tag-diagnostics available))
             :available (:available (release-tag-diagnostics available))})))
  tag)

(defn- peeled-tags [output]
  (into (sorted-map-by #(compare (release-number %1) (release-number %2)))
        (keep (fn [line]
                (when-let [[_ sha tag]
                           (re-matches #"([0-9a-fA-F]{40})\s+refs/tags/(v[1-9][0-9]*)\^\{\}"
                                       line)]
                  [tag (str/lower-case sha)])))
        (str/split-lines output)))

(defn- select-tag [output requested]
  (let [tags (peeled-tags output)
        tag (if requested
              (require-release-tag requested "Requested tag" (keys tags))
              (last (keys tags)))]
    (when-not tag
      (if (re-find #"refs/tags/v0\^\{\}" output)
        (throw (ex-info
                (str "v0 is reserved; release tags must match vN where N is a positive integer. "
                     "Available annotated tags: " (available-tags-description (keys tags)))
                (merge {:reason :reserved-release-marker :tag "v0"}
                       (release-tag-diagnostics (keys tags)))))
        (throw (ex-info
                (str "Repository has no annotated release tags matching vN where N is a positive "
                     "integer; available annotated tags: none found. Pin a reviewed sha manually "
                     "in spools.edn")
                (merge {:reason :no-release-tags}
                       (release-tag-diagnostics (keys tags)))))))
    (when-not (contains? tags tag)
      (throw (ex-info
              (str "Release tag is missing or is not annotated; accepted format is vN where N is a "
                   "positive integer. Available annotated tags: "
                   (available-tags-description (keys tags)))
              (merge {:reason :annotated-tag-not-found :tag tag}
                     (release-tag-diagnostics (keys tags))))))
    {:tag tag :sha (get tags tag)}))

(defn- url-basename [git-url]
  (let [trimmed (str/replace git-url #"[/]+$" "")
        basename (last (str/split trimmed #"[/\\:]"))
        basename (str/replace basename #"\.git$" "")]
    (when (str/blank? basename)
      (throw (ex-info "Git URL must have a repository basename" {:git-url git-url})))
    (symbol basename)))

(defn- read-manifest [content]
  (when content
    (let [eof (Object.)
          rdr (PushbackReader. (StringReader. content))
          manifest (try
                     (edn/read {:eof eof} rdr)
                     (catch Exception cause
                       (throw (ex-info "Advisory spool.edn is not valid EDN" {} cause))))]
      (when (or (identical? eof manifest)
                (not (identical? eof (edn/read {:eof eof} rdr))))
        (throw (ex-info "Advisory spool.edn must contain exactly one EDN value" {})))
      (require-valid! ::advisory-manifest manifest
                      "Advisory spool.edn has an invalid manifest shape"))))

(defn- manifest-roots [manifest implicit-lib]
  (if manifest
    (into {} (map (fn [[lib opts]] [lib (:root opts)])) (:roots manifest))
    {implicit-lib "."}))

(defn- family-entry [git-url tag sha manifest lib]
  (cond-> {:git/url git-url
           :git/tag tag
           :git/sha sha
           :roots (manifest-roots manifest lib)}
    (contains? manifest :requires) (assoc :requires (:requires manifest))
    (contains? manifest :skein/min) (assoc :skein/min (:skein/min manifest))))

(defn- add-spool-op [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [git-url tag lib]} (:op/args ctx)
        lib (if lib (symbol lib) (url-basename git-url))
        client (git-client rt)
        {:keys [tag sha]} (select-tag ((:ls-remote client) git-url) tag)
        manifest (read-manifest ((:manifest-at client) git-url sha))
        entry (family-entry git-url tag sha manifest lib)
        write (runtime-api/upsert-spool-entry! rt lib entry)
        requirements (:requirements (runtime-api/declared rt))]
    {:status (:status write)
     :family lib
     :entry entry
     :requirements requirements}))

(defn- bump-target [declared family requested tags]
  (if requested
    (require-release-tag requested "Target tag" (keys tags))
    (let [requirements (:requirements declared)]
      (if (:valid? requirements)
        (last (keys tags))
        (or (get (:suggestions requirements) family)
            (throw (ex-info "Current floor failures do not suggest a bump for this family"
                            {:family family
                             :requirements requirements})))))))

(defn- compare-url [git-url old-sha new-sha]
  (str (str/replace git-url #"\.git$" "") "/compare/" old-sha "..." new-sha))

(defn- bump-spool-op [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [family to]} (:op/args ctx)
        family (symbol family)
        declared (runtime-api/declared rt)
        old-entry (get-in declared [:families family :declared])]
    (when-not old-entry
      (throw (ex-info "Spool family is not declared" {:family family})))
    (when-not (:git/url old-entry)
      (throw (ex-info "Only Git spool families can be bumped" {:family family :entry old-entry})))
    (let [tags (peeled-tags ((:ls-remote (git-client rt)) (:git/url old-entry)))
          target (bump-target declared family to tags)]
      (when-not target
        (throw (ex-info
                (str "Repository has no annotated release tags matching vN where N is a positive "
                     "integer; available annotated tags: none found")
                (merge {:family family :reason :no-release-tags}
                       (release-tag-diagnostics (keys tags))))))
      (when-not (contains? tags target)
        (throw (ex-info
                (str "Target release tag is missing or is not annotated; accepted format is vN "
                     "where N is a positive integer. Available annotated tags: "
                     (available-tags-description (keys tags)))
                (merge {:family family :reason :annotated-tag-not-found :tag target}
                       (release-tag-diagnostics (keys tags))))))
      (let [new-sha (get tags target)
            entry (assoc old-entry :git/tag target :git/sha new-sha)
            write (runtime-api/upsert-spool-entry! rt family entry)]
        {:status (:status write)
         :family family
         :old {:tag (:git/tag old-entry) :sha (:git/sha old-entry)}
         :new {:tag target :sha new-sha}
         :compare-url (compare-url (:git/url old-entry) (:git/sha old-entry) new-sha)
         :requirements (:requirements (runtime-api/declared rt))}))))

(defn- family-uses [uses roots]
  (into (sorted-map)
        (filter (fn [[_ use-entry]]
                  (seq (set/intersection roots (set (get-in use-entry [:opts :spools]))))))
        uses))

(defn- spool-status [rt]
  (let [declared (runtime-api/declared rt)
        sync-result (runtime-api/syncs rt)
        uses (runtime-api/uses rt)]
    {:families
     (into (sorted-map)
           (map (fn [[family projection]]
                  (let [roots (set (keys (get-in projection [:declared :roots]
                                                 {family "."})))]
                    [family
                     (assoc projection
                            :syncs (select-keys (:spools sync-result) roots)
                            :uses (family-uses uses roots))])))
           (:families declared))
     :requirements (:requirements declared)
     :pending-generation (:pending-generation sync-result)
     :release-marker (runtime-api/release-marker rt)}))

(defn- spool-about
  []
  {:operation "spool about"
   :purpose (format-alpha/reflow
             "|Add and bump annotated Git spool releases through the validated,
              |comment-preserving runtime write verb. Status joins declared,
              |overlay, sync, use, pending-generation, and release-marker state.")
   :commands
   [{:form "strand spool add <git-url> [--tag vN] [--lib family]"
     :behavior (format-alpha/reflow
                "|Queries remote annotated tags, fetches the optional advisory
                 |spool.edn at the selected peeled commit, then atomically writes
                 |the validated tag and commit pin to the workspace spools.edn.")}
    {:form "strand spool bump <family> [--to vN]"
     :behavior (format-alpha/reflow
                "|Queries remote annotated tags, selects a requested, floor-driven,
                 |or latest release, then atomically rewrites its tag and peeled
                 |commit pin together.")}
    {:form "strand spool status"
     :behavior (format-alpha/reflow
                "|Reads runtime and workspace state only. It performs no network,
                 |file write, sync, reload, or other adoption action.")}]
   :conventions
   [(format-alpha/reflow
     "|Add and bump resolve only peeled refs/tags/vN^{} commits. v0,
      |lightweight tags, and untagged repositories are refused.")
    (format-alpha/reflow
     "|An optional producer spool.edn supplies roots and floors. Without it,
      |add uses one root at . under the URL basename; --lib confirms or
      |overrides that family/root symbol.")
    (format-alpha/reflow
     "|Status performs no remote Git calls. It reports the running runtime's
      |declared and adopted state without attempting sync or reload.")]})

(defn spool-op
  "Dispatch parsed `strand spool ...` subcommands."
  [ctx]
  (case (:subcommand (:op/args ctx))
    "about" (spool-about)
    "add" (require-valid! ::spool-add-result (assoc (add-spool-op ctx) :operation "spool add")
                          "spool add returned an invalid result")
    "bump" (require-valid! ::spool-bump-result (assoc (bump-spool-op ctx) :operation "spool bump")
                           "spool bump returned an invalid result")
    "status" (require-valid! ::spool-status-result
                             (assoc (spool-status (:op/runtime ctx)) :operation "spool status")
                             "spool status returned an invalid result")
    (throw (ex-info "Unknown spool subcommand"
                    {:subcommand (:subcommand (:op/args ctx))
                     :allowed ["about" "add" "bump" "status"]}))))

;; The blessed parser's :parse :json uses clojure.data.json/read-str, which
;; silently returns the first value and ignores trailing input, so it cannot
;; enforce old C13a's "exactly one JSON value" contract. weave reads --input as
;; a raw string and parses it strictly here instead: empty, malformed, and
;; trailing-value inputs all fail loudly before any mutation.
(defn- read-single-json
  "Read exactly one JSON value from s, failing loudly on empty, malformed, or
  trailing input (reproduces old SPEC-002.C13a stdin parsing weaver-side)."
  [s]
  (let [eof (Object.)
        ;; data.json/read unreads several characters of lookahead while parsing,
        ;; so the reader needs a pushback buffer wider than the default of 1.
        rdr (PushbackReader. (StringReader. s) 64)
        value (try (json/read rdr :eof-error? false :eof-value eof)
                   (catch Exception e
                     (throw (ex-info (str "weave --input is not valid JSON: " (ex-message e))
                                     {:code "pattern/input-invalid"}))))]
    (when (identical? value eof)
      (throw (ex-info "weave --input requires exactly one JSON value"
                      {:code "pattern/input-invalid"})))
    (when-not (identical? (json/read rdr :eof-error? false :eof-value eof) eof)
      (throw (ex-info "weave --input must contain exactly one JSON value"
                      {:code "pattern/input-invalid"})))
    value))

;; The blessed parser's :map flag silently collapses duplicate keys, but old
;; C6e requires duplicate keys within a single --attr priority to fail loudly.
;; The parser guarantees each --attr is followed by a well-formed key=value
;; token, so the flag keys can be recovered from the raw argv to enforce it.
(defn- attr-flag-keys [argv]
  (keep (fn [[flag token]]
          (when (= "--attr" flag)
            (subs token 0 (str/index-of token "="))))
        (partition 2 1 argv)))

(defn- check-attr-duplicates! [argv]
  (when-let [dup (some (fn [[k n]] (when (> n 1) k))
                       (frequencies (attr-flag-keys argv)))]
    (throw (ex-info (str "Duplicate attribute key in --attr: " dup) {:key dup}))))

(defn- attributes->map
  "Coerce a parsed --attributes value (a JSON object) into an attribute map."
  [attributes]
  (cond
    (nil? attributes) {}
    (map? attributes)
    (do (doseq [k (keys attributes)]
          (when (str/blank? k)
            (throw (ex-info "--attributes contains a blank attribute key" {:key k}))))
        attributes)
    :else (throw (ex-info "--attributes must reference a JSON object" {:value attributes}))))

(defn- parse-edges
  "Parse repeatable --edge edge-type:to-id specs into edge maps."
  [edge-specs]
  (mapv (fn [spec]
          (let [idx (str/index-of spec ":")]
            (when (or (nil? idx) (zero? idx) (= idx (dec (count spec))))
              (throw (ex-info "Malformed --edge; expected edge-type:to-id" {:edge spec})))
            {:type (subs spec 0 idx) :to (subs spec (inc idx))}))
        edge-specs))

(defn- handle-name
  "Coerce a query name string from op args into a registry lookup symbol."
  [query-name]
  (symbol (query/query-lookup-name query-name)))

(defn- validate-query-params
  "Restrict provided string params to a query's declared keyword names, failing
  loudly on unknown params (mirrors the JSON socket dispatch contract)."
  [query-def params]
  (let [declared (set (:params query-def))
        declared-names (set (map name declared))]
    (when-let [unknown (seq (remove declared-names (keys params)))]
      (throw (ex-info "Unknown query parameters"
                      {:params (vec unknown) :declared (vec declared)})))
    (into {} (keep (fn [k]
                     (when (contains? params (name k))
                       [k (get params (name k))]))
                   declared))))

(defn- run-named-query
  "Resolve a named query, validate params, overlay an optional state filter, and
  invoke the runtime list/ready fn exactly as the socket dispatch does."
  [rt query-fn query-name raw-params state limit]
  (let [query-def (graph/resolve-query rt (handle-name query-name))
        params (validate-query-params query-def raw-params)
        query-def (if state
                    [:and (query/query-expr query-def params) [:= :state state]]
                    query-def)]
    (query-fn rt lean-attribute-byte-floor query-def params limit)))

(defn- run-named-ready-query-lean [rt query-name raw-params limit]
  (let [query-def (graph/resolve-query rt (handle-name query-name))
        params (validate-query-params query-def raw-params)]
    (weaver/ready-lean rt lean-attribute-byte-floor query-def params limit)))

;; --- op handlers ------------------------------------------------------------

(defn add-op
  "Create a strand with merged attributes, optional state, and outgoing edges."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [title state attr attributes edge]} (:op/args ctx)]
    (check-attr-duplicates! (:op/argv ctx))
    (let [merged (merge (attributes->map attributes) (or attr {}))
          edges (parse-edges edge)]
      (weaver/add rt
                  (cond-> {:title title :attributes merged}
                    (some? state) (assoc :state (validate-generic-state state))
                    (seq edges) (assoc :edges edges))
                  (request-context :add)))))

(defn update-op
  "Patch one strand's title, state, attributes, and outgoing edges."
  [ctx]
  (let [rt (:op/runtime ctx)
        args (:op/args ctx)
        {:keys [id title state attr edge]} args]
    (check-attr-duplicates! (:op/argv ctx))
    (let [edges (parse-edges edge)
          patch (cond-> {}
                  (seq edges) (assoc :edges edges)
                  (some? title) (assoc :title title)
                  (some? state) (assoc :state (validate-generic-state state))
                  (contains? args :attr) (assoc :attributes attr))]
      (weaver/update rt id patch (request-context :update)))))

(defn show-op
  "Return one normalized strand by id."
  [ctx]
  (weaver/show (:op/runtime ctx) (:id (:op/args ctx))))

(defn supersede-op
  "Replace one strand with another and return the supersession result."
  [ctx]
  (let [{:keys [old-id replacement-id]} (:op/args ctx)]
    (weaver/supersede (:op/runtime ctx) old-id replacement-id (request-context :supersede))))

(defn burn-op
  "Physically delete one strand by id and return the burn summary."
  [ctx]
  (graph/burn-by-ids! (:op/runtime ctx) [(:id (:op/args ctx))] (request-context :burn)))

(defn list-op
  "List lean-projected strands, optionally filtered by lifecycle state and/or a named query."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [state query param limit]} (:op/args ctx)
        params (or param {})
        limit (effective-read-limit rt limit)]
    (when state (validate-readable-state state))
    (if query
      (do (when (str/blank? query)
            (throw (ex-info "--query requires a non-empty name" {})))
          (run-named-query rt weaver/list-lean query params state limit))
      (do (when (seq params)
            (throw (ex-info "--param requires --query" {})))
          (weaver/list-lean rt lean-attribute-byte-floor (if state [:= :state state] [:exists :id]) {} limit)))))

(defn ready-op
  "List lean-projected ready strands, optionally from the result set of a named query."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [query param limit]} (:op/args ctx)
        params (or param {})
        limit (effective-read-limit rt limit)]
    (if query
      (do (when (str/blank? query)
            (throw (ex-info "--query requires a non-empty name" {})))
          (run-named-ready-query-lean rt query params limit))
      (do (when (seq params)
            (throw (ex-info "--param requires --query" {})))
          (weaver/ready-lean rt lean-attribute-byte-floor [:exists :id] {} limit)))))

(defn subgraph-op
  "Return a relation-scoped subgraph rooted at one strand."
  [ctx]
  (let [{:keys [root-id relation]} (:op/args ctx)
        {:keys [root-ids strands edges]}
        (graph/subgraph (:op/runtime ctx) [root-id]
                        (cond-> {} relation (assoc :type relation)))]
    {"root_ids" root-ids
     "strands" strands
     "edges" edges}))

(defn weave-op
  "Apply a registered create-only weave pattern to one JSON input value."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [pattern input]} (:op/args ctx)]
    (patterns/weave! rt
                     (handle-name pattern)
                     (walk/keywordize-keys (read-single-json input))
                     (request-context :weave))))

(defn query-op
  "Introspect registered named queries: list all metadata or explain one."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [subcommand] nm :name} (:op/args ctx)]
    (case subcommand
      "list" (json-safe-value (graph/query-metadata rt))
      "explain" (do (when (str/blank? nm)
                      (throw (ex-info "query explain requires a query name" {})))
                    (json-safe-value (graph/query-explain rt (handle-name nm)))))))

(defn pattern-op
  "Introspect registered weave patterns: list all metadata or explain one."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [subcommand] nm :name} (:op/args ctx)]
    (case subcommand
      "list" (patterns/patterns rt)
      "explain" (do (when (str/blank? nm)
                      (throw (ex-info "pattern explain requires a pattern name" {})))
                    (patterns/explain rt (handle-name nm))))))

(defn note-op
  "Append a note to a target strand's memory via the note primitive.

  Its `note/text`/`note/at` content is storage-enforced write-once (SPEC-001.P4);
  the note strand stays open to decorating attrs. Returns the primitive's
  `{:id :target}` shape, where `target` is a projection of the `notes` edge rather
  than a stored attribute."
  [ctx]
  (let [{:keys [id text by round attr]} (:op/args ctx)]
    (check-attr-duplicates! (:op/argv ctx))
    ;; note! folds every non-:by/:round opt into decorating attrs, so the
    ;; string-keyed --attr map lands as ordinary strand attrs on the note.
    (notes/note! (:op/runtime ctx) id text (merge (or attr {}) {:by by :round round}))))

(defn notes-op
  "Return a target strand's notes from every primitive writer in note/at order,
  optionally filtered to one review round."
  [ctx]
  (let [{:keys [id round]} (:op/args ctx)]
    (notes/notes (:op/runtime ctx) id {:round round})))

(defn vocab-op
  "List the runtime's vocabulary declarations as an ordered array of C1 maps,
  string-keyed at the wire boundary, optionally narrowed to one --kind."
  [ctx]
  (let [rt (:op/runtime ctx)
        {:keys [kind]} (:op/args ctx)]
    (json-safe-value
     (vocab/declarations rt (when kind {:kind (validate-vocab-kind kind)})))))

;; --- arg-specs --------------------------------------------------------------

(def ^:private add-arg-spec
  {:op "add"
   :doc "Create a strand with attributes, lifecycle state, and outgoing edges."
   :flags {:state {:type :string
                   :doc "Lifecycle state: active (default) or closed."}
           :attr {:type :map
                  :doc "String attribute key=value; repeatable, highest precedence. Values may be payload references."}
           :attributes {:type :string
                        :parse :json
                        :doc "Payload reference to a JSON object of typed bulk attributes (lowest precedence)."}
           :edge {:type :string
                  :repeat? true
                  :doc "Outgoing edge edge-type:to-id; repeatable."}}
   :positionals [{:name :title :type :string :required? true :doc "Strand title."}]})

(def ^:private update-arg-spec
  {:op "update"
   :doc "Update one strand's title, state, attributes, and outgoing edges."
   :flags {:title {:type :string
                   :doc "New strand title."}
           :state {:type :string
                   :doc "Lifecycle state: active or closed (cannot set replaced)."}
           :attr {:type :map
                  :doc "String attribute key=value; repeatable, replaces attributes. Values may be payload references."}
           :edge {:type :string
                  :repeat? true
                  :doc "Outgoing edge edge-type:to-id; repeatable."}}
   :positionals [{:name :id :type :string :required? true :doc "Strand id."}]})

(def ^:private show-arg-spec
  {:op "show"
   :doc "Return one strand by id."
   :positionals [{:name :id :type :string :required? true :doc "Strand id."}]})

(def ^:private supersede-arg-spec
  {:op "supersede"
   :doc "Replace one strand with another, marking the old replaced and rewiring dependencies."
   :positionals [{:name :old-id :type :string :required? true :doc "Strand being replaced."}
                 {:name :replacement-id :type :string :required? true :doc "Replacement strand."}]})

(def ^:private burn-arg-spec
  {:op "burn"
   :doc "Physically delete one strand and its incident edges."
   :positionals [{:name :id :type :string :required? true :doc "Strand id."}]})

(def ^:private list-arg-spec
  {:op "list"
   :doc "List lean-projected strands, optionally filtered by state and/or a named query."
   :flags {:state {:type :string
                   :doc "Filter by lifecycle state: active, closed, or replaced."}
           :query {:type :string
                   :doc "Weaver-registered named query."}
           :param {:type :map
                   :doc "Named-query parameter key=value; repeatable."}
           :limit {:type :int
                   :doc "Explicit maximum result count; set above the total for an intentional full read."}}})

(def ^:private ready-arg-spec
  {:op "ready"
   :doc "List lean-projected ready strands, optionally from a named query result set."
   :flags {:query {:type :string
                   :doc "Weaver-registered named query."}
           :param {:type :map
                   :doc "Named-query parameter key=value; repeatable."}
           :limit {:type :int
                   :doc "Explicit maximum result count; set above the total for an intentional full read."}}})

(def ^:private subgraph-arg-spec
  {:op "subgraph"
   :doc "Return a relation-scoped subgraph rooted at a strand."
   :flags {:relation {:type :string
                      :doc "Declared acyclic relation type (defaults to parent-of)."}}
   :positionals [{:name :root-id :type :string :required? true :doc "Root strand id."}]})

(def ^:private weave-arg-spec
  {:op "weave"
   :doc "Apply a registered create-only weave pattern to one JSON input value."
   :flags {:pattern {:type :string
                     :required? true
                     :doc "Registered weave pattern name."}
           :input {:type :string
                   :required? true
                   :doc "Payload reference (e.g. :stdin) to exactly one JSON value for the pattern."}}})

(def ^:private query-arg-spec
  {:op "query"
   :doc "Introspect registered named queries: list all or explain one."
   :subcommands {"list" {:doc "List registered named query metadata."}
                 "explain" {:doc "Explain one registered named query."
                            :positionals [{:name :name
                                           :type :string
                                           :required? true
                                           :doc "Query name."}]}}})

(def ^:private pattern-arg-spec
  {:op "pattern"
   :doc "Introspect registered weave patterns: list all or explain one."
   :subcommands {"list" {:doc "List registered weave pattern metadata."}
                 "explain" {:doc "Explain one registered weave pattern."
                            :positionals [{:name :name
                                           :type :string
                                           :required? true
                                           :doc "Pattern name."}]}}})

(def ^:private note-arg-spec
  {:op "note"
   :doc "Append a note to a target strand's memory; its note/text/note/at content is write-once."
   :flags {:by {:type :string
                :doc "Author attribution recorded on the note."}
           :round {:type :int
                   :doc "Review round the note belongs to."}
           :attr {:type :map
                  :doc "Decorating attribute key=value on the note strand (e.g. note/kind); repeatable. Values may be payload references."}}
   :positionals [{:name :id :type :string :required? true :doc "Target strand id."}
                 {:name :text :type :string :required? true :doc "Note text."}]})

(def ^:private notes-arg-spec
  {:op "notes"
   :doc "Return a target strand's notes in note/at order from every writer."
   :flags {:round {:type :int
                   :doc "Filter to notes from one review round."}}
   :positionals [{:name :id :type :string :required? true :doc "Target strand id."}]})

(def ^:private vocab-arg-spec
  {:op "vocab"
   :doc "List the declared attribute-namespace and edge vocabulary."
   :flags {:kind {:type :string
                  :doc "Narrow to one declaration kind: attr-namespace or edge."}}})

(def ^:private spool-arg-spec
  {:op "spool"
   :doc "Add, bump, and inspect validated spool family coordinates. Run `strand spool about` for conventions."
   :subcommands
   {"about" {:doc "Return spool helper conventions and status semantics."}
    "add" {:doc "Add one annotated Git spool release to spools.edn."
           :flags {:tag {:type :string
                         :doc "Annotated release tag vN; defaults to the highest release."}
                   :lib {:type :string
                         :doc "Consumer family symbol; defaults to the Git URL basename."}}
           :positionals [{:name :git-url
                          :type :string
                          :required? true
                          :doc "Git repository URL."}]}
    "bump" {:doc "Bump one Git spool family atomically to an annotated release."
            :flags {:to {:type :string
                         :doc "Target annotated release tag vN; defaults from floors or latest."}}
            :positionals [{:name :family
                           :type :string
                           :required? true
                           :doc "Declared spool family symbol."}]}
    "status" {:doc "Join declared, overlay, sync, use, pending, and running release state without network access."}}})

(def ^:private attributes-return
  {:type :map :extra :json})

(def ^:private strand-return
  {:type :map
   :required {:id :string
              :title :string
              :state :string
              :created_at :string
              :updated_at :string
              :attributes attributes-return}})

(def ^:private edge-return
  {:type :map
   :required {:from_strand_id :string
              :to_strand_id :string
              :edge_type :string
              :attributes attributes-return}})

(def ^:private strand-collection-return
  {:type :collection :items strand-return})

(def ^:private query-metadata-return
  {:type :map
   :required {:name :string
              :params {:type :collection :items :string}
              :referenced-params {:type :collection :items :string}}})

(def ^:private pattern-key-return
  {:type :map
   :required {:key :string :spec :string :spec-form :string}})

(def ^:private op-returns
  {'add strand-return
   'update strand-return
   'show strand-return
   'supersede
   {:type :map
    :required {:old {:type :map :required {:before strand-return :after strand-return}}
               :replacement-id :string
               :supersedes-edge edge-return
               :rewired-dependencies
               {:type :collection
                :items {:type :map
                        :required {:from :string :old-to :string :new-to :string :type :string
                                   :deleted-edge edge-return :edge edge-return}}}}}
   'burn {:type :map
          :required {:burned {:type :collection :items :string}
                     :count :integer}}
   'list strand-collection-return
   'ready strand-collection-return
   'subgraph {:type :map
              :required {:root_ids {:type :collection :items :string}
                         :strands strand-collection-return
                         :edges {:type :collection :items edge-return}}}
   'weave {:type :map
           :required {:created strand-collection-return
                      :refs {:type :map :extra :string}}}
   'query {:subcommands
           {"list" {:type :collection :items query-metadata-return}
            "explain" {:type :map
                       :required {:name :string
                                  :operation :string
                                  :params {:type :collection :items :string}
                                  :referenced-params {:type :collection :items :string}
                                  :where :json
                                  :definition :json
                                  :where-form :string
                                  :definition-form :string
                                  :summary :string}}}}
   'pattern {:subcommands
             {"list" {:type :collection
                      :items {:type :map
                              :required {:name :string :fn :string :input-spec :string}
                              :optional {:doc :string}}}
              "explain" {:type :map
                         :required {:name :string :operation :string :fn :string :input-spec :string
                                    :spec-form :string :summary :string}
                         :optional {:doc :string
                                    :required {:type :collection :items pattern-key-return}
                                    :optional {:type :collection :items pattern-key-return}}}}}
   'note {:type :map :required {:id :string :target :string}}
   'notes {:type :collection
           :items {:type :map
                   :required {:id :string :note :string :at :string}
                   :optional {:by :string :round :integer}}}
   'vocab {:type :collection
           :items {:type :map
                   :required {:kind :string :name :string :owner :string}
                   :optional {:keys {:type :collection :items :string}
                              :doc :string
                              :family :string
                              :direction :string
                              :declared-acyclic? :boolean}}}
   'spool {:subcommands
           {"about" {:type :map :required {:operation :string} :extra :json}
            "add" {:type :map
                   :required {:operation :string :status :string :family :string
                              :entry :json :requirements :json}}
            "bump" {:type :map
                    :required {:operation :string :status :string :family :string :old :json :new :json
                               :compare-url :string :requirements :json}}
            "status" {:type :map
                      :required {:operation :string :families :json :requirements :json
                                 :pending-generation :json :release-marker :json}}}}})

(def ^:private op-registrations
  "Each shipped op: [op-name arg-spec hook-class handler-symbol]."
  [['add add-arg-spec :mutating 'skein.spools.batteries/add-op]
   ['update update-arg-spec :mutating 'skein.spools.batteries/update-op]
   ['show show-arg-spec :read 'skein.spools.batteries/show-op]
   ['supersede supersede-arg-spec :mutating 'skein.spools.batteries/supersede-op]
   ['burn burn-arg-spec :mutating 'skein.spools.batteries/burn-op]
   ['list list-arg-spec :read 'skein.spools.batteries/list-op]
   ['ready ready-arg-spec :read 'skein.spools.batteries/ready-op]
   ['subgraph subgraph-arg-spec :read 'skein.spools.batteries/subgraph-op]
   ['weave weave-arg-spec :mutating 'skein.spools.batteries/weave-op]
   ['query query-arg-spec :read 'skein.spools.batteries/query-op]
   ['pattern pattern-arg-spec :read 'skein.spools.batteries/pattern-op]
   ['note note-arg-spec :mutating 'skein.spools.batteries/note-op]
   ['notes notes-arg-spec :read 'skein.spools.batteries/notes-op]
   ['vocab vocab-arg-spec :read 'skein.spools.batteries/vocab-op]
   ['spool spool-arg-spec :mutating 'skein.spools.batteries/spool-op]])

(defn install!
  "Register the batteries core strand ops into a weaver runtime.

  The no-arg arity registers into the active runtime for `use!`-style
  installation; the explicit-runtime arity is for tests and trusted callers."
  ([] (install! (current/runtime)))
  ([rt]
   {:installed true
    :namespace 'skein.spools.batteries
    :ops (mapv (fn [[op-name arg-spec hook-class handler]]
                 (weaver/register-op! rt op-name
                                      {:doc (:doc arg-spec)
                                       :arg-spec arg-spec
                                       :returns (get op-returns op-name)
                                       :hook-class hook-class}
                                      handler))
               op-registrations)}))
