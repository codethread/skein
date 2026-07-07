(ns skein.spools.brief
  "One primitive substrate for two things every delegating workspace re-invents:
  the per-run **brief** (the structured contract a delegated run is told) and the
  durable **guide** (authoring knowledge an agent fetches before acting). Both are
  plain data validated loudly against a closed key set, and both render through
  the same deterministic prompt seam — so a scope/budget clause and a guide's
  constraints share one renderer instead of three hand-rolled `(str ...)` blocks.

  The unifying substrate is the **clause block**: a named, reusable prose
  fragment (`{:title :lines}`) registered once and referenced by key. A brief is
  a small closed map of well-known sections (`:context :mission :deliverable
  :scope :budgets :rules`) plus `:blocks` that pull registered clause blocks in
  by key — so the fixed part of a contract (source rules, a blocked-domain list,
  a worker preamble) lives in one registered block instead of copy-pasted prose
  in every prompt. A guide generalises devflow's guidance shape as-is
  (`:purpose :artifacts :prerequisites :knowledge :procedures :constraints
  :validation :templates :see-also`).

  Two runtime-owned registries (clause blocks, guides), keyed by a symbol this
  spool owns and re-registered by trusted config on reload like harnesses and
  rosters. Every public model fn takes `runtime` first and never resolves ambient
  runtime itself (docs/writing-shared-spools.md); only `install!` resolves the
  active runtime, and CLI op handlers read `:op/runtime` from their context.
  Briefs themselves are per-run and inline — they earn no registry (like panel
  seats), only the durable/named clause blocks and guides do.

  TEN-006 boundary: rich brief data is trusted-Clojure/payload territory — the
  CLI never authors a brief. Only durable *named* things (guides, blocks) ride
  argv, via read-only `strand brief guide|guides|block|blocks` fetches. Rendering
  a brief is `brief->prompt` in trusted Clojure and weave patterns.

  Projection, not enforcement: `brief-attrs` mirrors a brief's owned paths and
  budgets into `brief/owns`/`brief/budgets` attrs so a `describe`/projection view
  can show what a run will own and spend before it is poured, and
  `overlapping-owns` is a pure detector of owned-path collisions across sibling
  tasks. Neither is wired into any delegate/pour path — disjoint-scope
  *enforcement* is a behaviour change that stays a userland opt-in.

  Workflow steps advertise the guide an agent should read via the `guide/key`
  strand attribute (generalising devflow's `devflow/guide`); `strand-guide`
  resolves it, failing loudly on an unknown key."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.runtime.alpha :as runtime-alpha]
            [skein.api.weaver.alpha :as api]
            [skein.spools.format :as fmt]
            [skein.spools.util :refer [attr-get fail! reject-unknown-keys! require-valid!]]))

;; ---------------------------------------------------------------------------
;; Runtime-owned registries (clause blocks + guides)
;; ---------------------------------------------------------------------------

(def ^:private state-version
  "Bump whenever new-state's key set changes (writing-shared-spools.md)."
  1)

(defn- new-state []
  {:blocks (atom {})
   :guides (atom {})})

(defn- state [runtime]
  (runtime-alpha/spool-state runtime ::state {:version state-version} new-state))

(defn- blocks* [runtime] (:blocks (state runtime)))
(defn- guides* [runtime] (:guides (state runtime)))

;; ---------------------------------------------------------------------------
;; Clause blocks — the shared substrate
;; ---------------------------------------------------------------------------

(s/def ::title string?)
(s/def ::lines (s/coll-of string? :kind vector? :min-count 1))
(s/def ::block (s/keys :req-un [::lines] :opt-un [::title]))

(def ^:private block-keys #{:title :lines})

(defn defblock!
  "Register clause block `block` under keyword `k` in `runtime`, returning `k`.

  A clause block is `{:lines [\"...\"] :title \"...\"?}` — a named reusable prose
  fragment (source rules, a blocked-domain list, a worker preamble). Referenced
  from a brief's `:blocks` and rendered verbatim. Fails loudly on a non-keyword
  key, unknown keys, or a shape the `::block` spec rejects."
  [runtime k block]
  (when-not (keyword? k)
    (fail! "defblock! key must be a keyword" {:key k}))
  (when-not (map? block)
    (fail! "defblock! block must be a map" {:block block}))
  (reject-unknown-keys! "defblock!" block-keys block)
  (require-valid! ::block block "defblock! block is malformed")
  (swap! (blocks* runtime) assoc k block)
  k)

(defn block
  "Return the clause block registered under `k`, failing loudly on an unknown
  key with the available keys."
  [runtime k]
  (or (get @(blocks* runtime) k)
      (fail! "Unknown clause block" {:block k :available (vec (sort (keys @(blocks* runtime))))})))

(defn blocks
  "Return every registered clause block key mapped to its title (or nil)."
  [runtime]
  (into (sorted-map) (map (fn [[k v]] [k (:title v)])) @(blocks* runtime)))

;; ---------------------------------------------------------------------------
;; Brief — the per-run contract as specced data
;; ---------------------------------------------------------------------------

(s/def ::ref string?)
(s/def ::context (s/or :text string? :ref (s/keys :req-un [::ref])))
(s/def ::mission (s/coll-of string? :kind vector? :min-count 1))
(s/def ::body string?)
(s/def ::path string?)
(s/def ::format keyword?)
(s/def ::end-with string?)
(s/def ::validate (s/coll-of string? :kind vector?))
(s/def ::deliverable (s/keys :opt-un [::path ::format ::end-with ::validate]))
(s/def ::owns (s/coll-of string? :kind vector?))
(s/def ::forbid-reads (s/coll-of string? :kind vector?))
(s/def ::commit? boolean?)
(s/def ::scope (s/keys :opt-un [::owns ::forbid-reads ::commit?]))
(s/def ::budgets (s/map-of keyword? nat-int?))
(s/def ::rules (s/coll-of string? :kind vector?))
(s/def ::blocks (s/coll-of keyword? :kind vector?))

(s/def ::brief
  (s/keys :opt-un [::context ::mission ::body ::deliverable ::scope
                   ::budgets ::rules ::blocks]))

(def ^:private brief-keys
  #{:context :mission :body :deliverable :scope :budgets :rules :blocks})
(def ^:private deliverable-keys #{:path :format :end-with :validate})
(def ^:private scope-keys #{:owns :forbid-reads :commit?})

(defn validate-brief
  "Return `brief`, failing loudly when it is not a map, carries keys outside the
  closed set, or violates the `::brief` spec. Sub-maps `:deliverable`/`:scope`
  are held to their own closed key sets so a typo like `:owned` fails at
  authoring time instead of silently vanishing from the rendered prompt."
  [brief]
  (when-not (map? brief)
    (fail! "brief must be a map" {:brief brief}))
  (reject-unknown-keys! "brief" brief-keys brief)
  (when-let [d (:deliverable brief)] (reject-unknown-keys! "brief :deliverable" deliverable-keys d))
  (when-let [sc (:scope brief)] (reject-unknown-keys! "brief :scope" scope-keys sc))
  (require-valid! ::brief brief "brief is malformed"))

(defn- bullets [xs] (mapv #(str "- " %) xs))

(defn- section
  "A titled prompt section, or nil when it has no body lines."
  [title lines]
  (when (seq lines)
    (str "## " title "\n" (str/join "\n" (remove nil? lines)))))

(defn- context-lines [ctx]
  (cond
    (nil? ctx) nil
    (string? ctx) [ctx]
    (map? ctx) [(str "See strand " (:ref ctx) " for the decision this feeds.")]))

(defn- deliverable-lines [{:keys [path format end-with validate]}]
  (concat
   (when path [(str "Path: " path)])
   (when format [(str "Format: " (name format))])
   (when end-with [(str "End with: " end-with)])
   (when (seq validate)
     (cons "Validate with:" (bullets validate)))))

(defn- scope-lines [{:keys [owns forbid-reads commit?] :as sc}]
  (concat
   (when (seq owns) (cons "You OWN only these files:" (bullets owns)))
   (when (seq forbid-reads) (cons "Do NOT read (sibling isolation):" (bullets forbid-reads)))
   (when (contains? sc :commit?)
     [(if commit? "Commit your work when the gate is green."
          "Do NOT commit; the coordinator fans in.")])))

(defn- budget-lines [budgets]
  (map (fn [[k n]] (str "- " (name k) ": " n)) (sort-by key budgets)))

(defn brief->prompt
  "Render `brief` to the single deterministic prompt string every consumer
  shares (treadle gate prompts, delegate task bodies, roster contracts, panel
  seat briefs, pipeline-task-prompt). Validates first, then emits fixed-order
  sections, resolving each `:blocks` key against `runtime`'s clause-block
  registry (an unknown block fails loudly). Sections with no content are omitted
  so a sparse brief renders a tight prompt."
  [runtime brief]
  (validate-brief brief)
  (let [named (map (fn [k]
                     (let [b (block runtime k)]
                       (section (or (:title b) (name k)) (:lines b))))
                   (:blocks brief))
        parts (concat
               [(section "Context" (context-lines (:context brief)))
                ;; Render both when present rather than letting one silently
                ;; win: :mission is the objective list, :body its freeform
                ;; elaboration (TEN-003 — no silent drop).
                (section "Mission"
                         (concat (when (:mission brief) (bullets (:mission brief)))
                                 (when (:body brief) [(:body brief)])))
                (section "Deliverable" (deliverable-lines (:deliverable brief)))
                (section "Scope" (scope-lines (:scope brief)))
                (section "Budgets" (budget-lines (:budgets brief)))
                (section "Rules" (bullets (:rules brief)))]
               named)]
    (str/join "\n\n" (remove nil? parts))))

;; ---------------------------------------------------------------------------
;; Projection attrs + owned-path collision detector (projection, not policy)
;; ---------------------------------------------------------------------------

(def owns-attr
  "Projection attribute carrying a brief's `:scope :owns` paths so a describe /
  projection view can show what a run will own before it is poured."
  "brief/owns")

(def budgets-attr
  "Projection attribute carrying a brief's `:budgets` map so a describe /
  projection view can show what a run may spend before it is poured."
  "brief/budgets")

(defn brief-attrs
  "Return the projection attributes for validated `brief`: `brief/owns` (its
  scoped owned paths) and `brief/budgets` (its budget map), each present only
  when the brief declares it. Pure — projects scalars a `describe`/projection
  view reads, never the rich brief itself (that stays trusted-Clojure payload).
  Validates first, so a malformed brief never projects a half-formed attr set."
  [brief]
  (validate-brief brief)
  (cond-> {}
    (seq (get-in brief [:scope :owns])) (assoc owns-attr (get-in brief [:scope :owns]))
    (seq (:budgets brief)) (assoc budgets-attr (:budgets brief))))

(defn overlapping-owns
  "Return owned-path collisions across sibling task maps, as a vector of
  `{:path <str> :tasks [<id> ...]}` — one entry per path claimed by more than
  one task. Each task is `{:id .. :attributes {..}}` carrying a `brief/owns`
  path vector (keyword- or string-keyed, tolerating a JSON round-trip). Pure and
  advisory: it *detects* the collision a userland disjoint-scope check would act
  on; it wires no enforcement into any delegate/pour path (open Q3 — enforcement
  stays a userland opt-in)."
  [tasks]
  (->> tasks
       (mapcat (fn [{:keys [id attributes]}]
                 (map (fn [path] [path id])
                      (or (get attributes owns-attr)
                          (get attributes (keyword owns-attr))))))
       (group-by first)
       (keep (fn [[path pairs]]
               (let [ids (distinct (map second pairs))]
                 (when (< 1 (count ids)) {:path path :tasks (vec ids)}))))
       vec))

;; ---------------------------------------------------------------------------
;; Guide — devflow's guidance shape, generalised into a registry
;; ---------------------------------------------------------------------------

(def ^:private guide-keys
  #{:purpose :artifacts :prerequisites :knowledge :procedures
    :constraints :validation :templates :see-also})

(s/def ::purpose string?)
(s/def ::guide (s/keys :req-un [::purpose]))

(defn defguide!
  "Register authoring guide `guide` under keyword `k` in `runtime`, returning `k`.

  A guide is the generalised devflow guidance shape: `:purpose` is required, the
  rest of the closed set (`:artifacts :prerequisites :knowledge :procedures
  :constraints :validation :templates :see-also`) is optional freeform data.
  Fails loudly on a non-keyword key, keys outside the closed set, or a missing
  `:purpose`."
  [runtime k guide]
  (when-not (keyword? k)
    (fail! "defguide! key must be a keyword" {:key k}))
  (when-not (map? guide)
    (fail! "defguide! guide must be a map" {:guide guide}))
  (reject-unknown-keys! "defguide!" guide-keys guide)
  (require-valid! ::guide guide "defguide! guide is missing :purpose")
  (swap! (guides* runtime) assoc k guide)
  k)

(defn guide
  "Return the guide registered under `k`, failing loudly on an unknown key with
  the available keys."
  [runtime k]
  (or (get @(guides* runtime) k)
      (fail! "Unknown guide" {:guide k :available (vec (sort (keys @(guides* runtime))))})))

(defn guides
  "Return every registered guide key mapped to its `:purpose` — the index a
  step-view or `strand brief guides` renders."
  [runtime]
  (into (sorted-map) (map (fn [[k g]] [k (:purpose g)])) @(guides* runtime)))

;; ---------------------------------------------------------------------------
;; Step-advertising attribute convention
;; ---------------------------------------------------------------------------

(def guide-attr
  "The strand attribute a workflow step sets to advertise the guide its driving
  agent should fetch before acting (generalises devflow's `devflow/guide`)."
  :guide/key)

(defn strand-guide
  "Resolve the guide advertised by `strand`'s `guide/key` attribute, or nil when
  the strand advertises none. Returns `{:key <kw> :guide <map>}`. A `guide/key`
  attribute naming an unregistered guide fails loudly rather than silently
  yielding no guidance (TEN-003)."
  [runtime strand]
  (when-let [raw (attr-get strand guide-attr)]
    (let [k (keyword raw)]
      {:key k :guide (guide runtime k)})))

;; ---------------------------------------------------------------------------
;; Discovery: about / prime
;; ---------------------------------------------------------------------------

(defn about
  "Return the brief/guide conventions and installed helper surface."
  [runtime]
  {:operation "brief about"
   :summary (fmt/reflow "
            |brief and guide are one substrate: per-run contracts (brief) and durable
            |authoring knowledge (guide), both closed-key data rendered through one prompt
            |seam, both composed from named clause blocks. The CLI fetches named guides and
            |blocks; briefs are rich trusted-Clojure/payload data (TEN-006).")
   :brief-sections {:context "the decision this run feeds (string or {:ref strand-id})"
                    :mission "objectives/questions (vector) — or :body for a freeform string"
                    :deliverable "{:path :format :end-with :validate [cmd...]}"
                    :scope "{:owns [..] :forbid-reads [..] :commit? bool}"
                    :budgets "{:web-searches 18 ...} — the single most effective control found"
                    :rules "extra inline rules (vector)"
                    :blocks "keys of registered clause blocks to append verbatim"}
   :guide-shape (vec (sort guide-keys))
   :attributes {:guide/key "step advertises the guide an agent should fetch before acting"
                :brief/owns "projection: the owned paths a brief scopes a run to"
                :brief/budgets "projection: the budget map a brief caps a run with"}
   :api {:defblock! "(defblock! runtime k block) register a reusable clause block"
         :block "(block runtime k) fetch one clause block"
         :defguide! "(defguide! runtime k guide) register a durable authoring guide"
         :guide "(guide runtime k) fetch one guide"
         :brief->prompt "(brief->prompt runtime brief) render a brief to the shared prompt string"
         :brief-attrs "(brief-attrs brief) project a brief's owns/budgets to attrs"
         :overlapping-owns "(overlapping-owns tasks) detect owned-path collisions across siblings"
         :strand-guide "(strand-guide runtime strand) resolve the guide advertised on a strand"}
   :guides (guides runtime)
   :blocks (blocks runtime)
   :commands [{:usage "strand brief about — this manual"}
              {:usage "strand brief prime — full agent priming"}
              {:usage "strand brief guides — list registered guide keys and purposes"}
              {:usage "strand brief guide <key> — fetch one guide as JSON"}
              {:usage "strand brief blocks — list registered clause-block keys"}
              {:usage "strand brief block <key> — fetch one clause block"}]})

(defn prime
  "Return full agent priming for the brief/guide surface: `about` plus the
  working discipline for authoring contracts and fetching guidance."
  [runtime]
  (assoc (about runtime)
         :operation "brief prime"
         :working-agreement
         (fmt/fill "
              |Author a run's contract as a brief (data), never a hand-concatenated prompt
              |string: put the fixed part (source rules, blocked domains, worker preamble) in a
              |registered clause block and reference it by key from :blocks, so the same clause
              |never drifts across two prompts. Give every research/exploration run an explicit
              |:budgets clause — an uncapped run is the single most expensive failure mode found.
              |
              |Before acting on a workflow step, check its guide/key attribute (strand-guide);
              |fetch that guide and satisfy its :prerequisites, :constraints, and :validation
              |before writing the artifact.")))

;; ---------------------------------------------------------------------------
;; CLI op — thin JSON, name-based fetches only (TEN-006)
;; ---------------------------------------------------------------------------

(defn brief-op
  "Dispatch parsed `strand brief ...` subcommands."
  [ctx]
  (let [rt (:op/runtime ctx)
        args (:op/args ctx)]
    (case (:subcommand args)
      "about" (about rt)
      "prime" (prime rt)
      "guides" (guides rt)
      "guide" (guide rt (keyword (:key args)))
      "blocks" (blocks rt)
      "block" (block rt (keyword (:key args))))))

(def ^:private brief-arg-spec
  {:op "brief"
   :doc "Fetch durable authoring guides and reusable clause blocks by name."
   :subcommands
   {"about" {:doc "Return the brief/guide conventions and installed surface."}
    "prime" {:doc "Return full agent priming for authoring briefs and fetching guides."}
    "guides" {:doc "List registered guide keys and their purposes."}
    "guide" {:doc "Fetch one registered guide as JSON."
             :positionals [{:name :key :required? true :doc "Guide key."}]}
    "blocks" {:doc "List registered clause-block keys and titles."}
    "block" {:doc "Fetch one registered clause block."
             :positionals [{:name :key :required? true :doc "Clause-block key."}]}}})

(defn install!
  "Install the `brief` op into the active weaver. The clause-block and guide
  registries are runtime-owned and start empty; trusted config populates them
  with `defblock!`/`defguide!`."
  []
  (let [rt (current/runtime)]
    ;; Touch state once so the registries exist for immediate fetches.
    (state rt)
    {:installed true
     :namespace 'skein.spools.brief
     :ops [(api/register-op! rt 'brief
                             {:doc (:doc brief-arg-spec)
                              :arg-spec brief-arg-spec}
                             'skein.spools.brief/brief-op)]}))
