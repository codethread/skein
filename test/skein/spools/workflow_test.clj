(ns skein.spools.workflow-test
  "Tests for the skein.spools.workflow userland workflow engine: contract
  explain, compile semantics (calls, conditions, loops, splicing), and the
  run-driving surface (start!/complete!/choose!, gates, checkpoints, bonds)."
  (:require [clojure.test :refer [deftest is testing]]
            [skein.batch.alpha :as batch]
            [skein.graph.alpha :as graph]
            [skein.spools.test-support :refer [with-runtime]]
            [skein.spools.workflow :as workflow]
            [skein.repl :as repl]))

(deftest workflow-spool-explains-public-input-shapes
  (let [contract (workflow/explain)]
    (is (= :workflow (:topic contract)))
    (is (= 'skein.spools.workflow/checkpoint (get-in contract [:builders 'checkpoint])))
    (is (re-find #"skein.spools.workflow/workflow" (get-in contract [:contract :spec])))
    (is (= :step (get-in contract [:step :topic])))
    (is (= :checkpoint (get-in contract [:checkpoint :topic])))))

(deftest workflow-spool-compiles-and-materializes-molecules
  (with-runtime
    (fn [_ _]
      (let [with-feature (fn [prefix]
                           (fn [{:keys [feature]}]
                             (str prefix feature)))
            definition (workflow/workflow
                         (with-feature "Ship ")
                         {:params {:feature (workflow/param :required true)
                                   :owner (workflow/param :default "agent")
                                   :include-review (workflow/param :default true)}}
                         (workflow/step :design (with-feature "Design ")
                                        :attributes {:owner (fn [{:keys [owner]}] owner)})
                         (workflow/step :implement (with-feature "Implement ")
                                        :depends-on [:design])
                         (workflow/step :review (with-feature "Review ")
                                        :depends-on [:implement]
                                        :condition :include-review))
            result (workflow/pour! definition {:feature "workflow spool"})
            root-id (workflow/molecule-id result)
            root (repl/strand root-id)
            subgraph (graph/subgraph [root-id])]
        (is (= "Ship workflow spool" (:title root)))
        (is (= "molecule" (get-in root [:attributes :workflow/role])))
        (is (= #{"Design workflow spool" "Implement workflow spool" "Review workflow spool" "Ship workflow spool"}
               (set (map :title (:strands subgraph)))))
        (is (= 3 (count (filter #(= "parent-of" (:edge_type %)) (:edges subgraph)))))))))

(deftest workflow-spool-inlines-procedure-calls
  (let [review (fn [_]
                 (workflow/workflow
                   "Review"
                   {:params {:artifact (workflow/param :required true)}}
                   (workflow/step :inspect
                                  (fn [{:keys [artifact]}] (str "Inspect " artifact)))
                   (workflow/step :write-review
                                  (fn [{:keys [artifact]}] (str "Write review for " artifact))
                                  :depends-on [:inspect])))
        definition (workflow/workflow
                     "Procedure demo"
                     (workflow/step :write-artifact "Write artifact")
                     (workflow/call :review-artifact review {:artifact "proposal.md"}
                                    :depends-on [:write-artifact])
                     (workflow/step :continue "Continue"
                                    :depends-on [:review-artifact]))
        payload (workflow/compile definition)
        strands-by-ref (into {} (map (juxt :ref identity)) (:strands payload))
        edges (set (map (juxt :from :to :type) (:edges payload)))]
    (is (= #{:molecule :write-artifact :review-artifact--inspect
             :review-artifact--write-review :review-artifact :continue}
           (set (keys strands-by-ref))))
    (is (= "Inspect proposal.md" (get-in strands-by-ref [:review-artifact--inspect :title])))
    (is (contains? edges [:review-artifact--inspect :write-artifact "depends-on"]))
    (is (contains? edges [:review-artifact--write-review :review-artifact--inspect "depends-on"]))
    (is (contains? edges [:review-artifact :review-artifact--write-review "depends-on"]))
    (is (contains? edges [:continue :review-artifact "depends-on"]))))

(defn- toastie-quality-workflow [_]
  (workflow/workflow
    "Toastie quality check"
    (workflow/step :inspect "Check toastie melt and crunch")))

(defn- toastie-serve-workflow [{:keys [filling]}]
  (workflow/workflow
    (str "Serve " filling " toastie")
    {:params {:filling (workflow/param :required true)}}
    (workflow/step :plate (fn [{:keys [filling]}] (str "Plate " filling " toastie")))))

(deftest workflow-spool-runtime-drives-toastie-demo
  (with-runtime
    (fn [_ _]
      (let [toastie (workflow/workflow
                      (fn [{:keys [filling]}] (str "Make " filling " toastie"))
                      {:params {:filling (workflow/param :required true)}}
                      (workflow/step :butter-bread "Butter bread")
                      (workflow/call :quality toastie-quality-workflow {}
                                     :depends-on [:butter-bread])
                      (workflow/checkpoint :choose-finish "Choose toastie finish"
                                           :depends-on [:quality]
                                           :kind :agent
                                           :choices [{:key :serve
                                                      :label "Serve"
                                                      :description "Plate the toastie and serve it hot."
                                                      :next 'skein.spools.workflow-test/toastie-serve-workflow}
                                                     {:key :remake
                                                      :label "Remake"
                                                      :description "Start over with fresh bread."}]))]
        (is (= [{:title "Butter bread" :kind "step"}]
               (mapv #(select-keys % [:title :kind])
                     (workflow/start! "toastie-demo" toastie {:filling "cheese"}))))
        (is (= "Check toastie melt and crunch" (:title (first (workflow/complete! "toastie-demo")))))
        (is (= "Complete quality" (:title (first (workflow/complete! "toastie-demo")))))
        (is (= "Choose toastie finish" (:title (first (workflow/complete! "toastie-demo")))))
        (is (= ["serve" "remake"] (:choices (workflow/next-step "toastie-demo"))))
        (is (not (contains? (workflow/next-step "toastie-demo") :choice-details)))
        (is (= {"label" "Serve"
                "description" "Plate the toastie and serve it hot."
                "next" "skein.spools.workflow-test/toastie-serve-workflow"}
               (workflow/choice-detail "toastie-demo" :serve)))
        (is (= "Plate cheese toastie"
               (:title (first (workflow/choose! "toastie-demo" :serve {:filling "cheese"})))))
        (is (= [] (workflow/complete! "toastie-demo")))
        (is (workflow/done? "toastie-demo"))))))

;; Pull-request flow modelled without conditional edges: every branch is a
;; checkpoint choice the driving agent makes after observing the world (CI
;; verdict, review outcome), and every external wait is a gate. The CI round
;; is one reusable workflow recomposed via `call` by each stage that pushes
;; commits, and its verdict checkpoint always routes green to review and red
;; to the fix loop.
;;
;; The definitions are forge-agnostic: steps carry only semantic
;; workflow/action-ref names, and the concrete forge commands arrive as a
;; pure-data bindings map (action-ref -> attribute map) through params — pure
;; data so bindings survive workflow/context round-trips across routed loop
;; rounds. github ships as the reference; a user rebinds any subset from the
;; outside without touching a definition (see PLAN.md).

;; binding keys must be a fixed point of the weaver's JSON round-trip so they
;; survive workflow/context across routed loop rounds: map keys come back
;; keywordized, and NAMESPACED keyword keys lose their namespace on write
;; (data.json writes keyword keys via `name`). Hence simple keyword keys
;; only; bind-attrs maps them onto the canonical string attribute vocabulary.
(def ^:private github-pr-bindings
  {:pr.open           {:instruction "gh pr create --fill"}
   :pr.ci.wait        {:instruction "gh pr checks --watch --fail-fast"
                       :skills "ci-watch"}
   :pr.ci.fix         {:instruction "gh run view --log-failed to inspect the failing checks"}
   :pr.review.wait    {:instruction "gh pr view --comments"}
   :pr.review.address {:instruction "Reply with gh pr comment; push follow-up commits"}
   :pr.merge          {:instruction "gh pr merge --squash"}})

(def ^:private binding-attr-keys
  {:instruction "workflow/instruction"
   :skills "skills"})

(defn- bind-attrs
  "Merge the binding for action-ref into canonical step attributes, failing
  loudly (TEN-003) on an unbound action or a key outside the binding
  vocabulary — a typo in user bindings must not yield a silently bare step."
  [bindings action-ref]
  (let [bindings (or bindings github-pr-bindings)
        bound (or (get bindings action-ref)
                  (throw (ex-info "No binding for workflow action"
                                  {:action-ref action-ref :bound (vec (keys bindings))})))]
    (when-let [unknown (seq (remove binding-attr-keys (keys bound)))]
      (throw (ex-info "Unknown binding keys"
                      {:action-ref action-ref :unknown (vec unknown)
                       :allowed (vec (keys binding-attr-keys))})))
    (merge {"workflow/action-ref" (name action-ref)}
           (into {} (map (fn [[k v]] [(binding-attr-keys k) v])) bound))))

(defn- pr-ci-round-workflow [{:keys [bindings] :as _opts}]
  (workflow/workflow
    (fn [{:keys [feature]}] (str "CI round for " feature))
    {:params {:feature (workflow/param :required true)}}
    (workflow/gate :ci-wait (fn [{:keys [feature]}] (str "Wait for CI on " feature)) :ci
                   :attributes (bind-attrs bindings :pr.ci.wait))
    (workflow/checkpoint :ci-verdict "Judge CI result"
                         :depends-on [:ci-wait]
                         :kind :agent
                         :choices [{:key :green
                                    :label "CI green"
                                    :description "All checks passed; hand off to review."
                                    :next 'skein.spools.workflow-test/pr-review-round-workflow}
                                   {:key :red
                                    :label "CI red"
                                    :description "Checks failed; run the fix-CI loop."
                                    :next 'skein.spools.workflow-test/pr-fix-ci-workflow}])))

(defn- pr-fix-ci-workflow [{:keys [bindings] :as _opts}]
  (workflow/workflow
    (fn [{:keys [feature]}] (str "Fix CI for " feature))
    {:params {:feature (workflow/param :required true)}}
    (workflow/step :diagnose "Diagnose CI failure"
                   :attributes (bind-attrs bindings :pr.ci.fix))
    (workflow/step :push-fix "Push CI fix" :depends-on [:diagnose])
    (workflow/call :ci-round pr-ci-round-workflow {} :depends-on [:push-fix])))

(defn- pr-review-round-workflow [{:keys [bindings] :as _opts}]
  (workflow/workflow
    (fn [{:keys [feature]}] (str "Review round for " feature))
    {:params {:feature (workflow/param :required true)}}
    (workflow/gate :review-wait
                   (fn [{:keys [feature]}] (str "Wait for reviewer feedback on " feature))
                   :human
                   :attributes (bind-attrs bindings :pr.review.wait))
    (workflow/checkpoint :review-verdict "Judge review outcome"
                         :depends-on [:review-wait]
                         :kind :agent
                         :choices [{:key :approved
                                    :label "Approved"
                                    :description "All green and approved; merge."
                                    :next 'skein.spools.workflow-test/pr-merge-workflow}
                                   {:key :changes-requested
                                    :label "Changes requested"
                                    :description "Address comments, push, and re-run CI."
                                    :next 'skein.spools.workflow-test/pr-fix-and-push-workflow}])))

(defn- pr-fix-and-push-workflow [{:keys [bindings] :as _opts}]
  (workflow/workflow
    (fn [{:keys [feature]}] (str "Address review feedback for " feature))
    {:params {:feature (workflow/param :required true)}}
    (workflow/step :address-comments "Address review comments"
                   :attributes (bind-attrs bindings :pr.review.address))
    (workflow/call :ci-round pr-ci-round-workflow {} :depends-on [:address-comments])))

(defn- pr-merge-workflow [{:keys [bindings] :as _opts}]
  (workflow/workflow
    (fn [{:keys [feature]}] (str "Merge " feature))
    {:params {:feature (workflow/param :required true)}}
    (workflow/step :merge (fn [{:keys [feature]}] (str "Merge " feature))
                   :attributes (bind-attrs bindings :pr.merge))))

(defn- pr-dev-workflow [{:keys [bindings] :as _opts}]
  (workflow/workflow
    (fn [{:keys [feature]}] (str "Pull request: " feature))
    {:params {:feature (workflow/param :required true)}}
    (workflow/step :dev (fn [{:keys [feature]}] (str "Implement " feature)))
    (workflow/step :open "Open the change for review" :depends-on [:dev]
                   :attributes (bind-attrs bindings :pr.open))
    (workflow/call :ci-round pr-ci-round-workflow {} :depends-on [:open])))

(deftest workflow-models-pull-request-flow-without-conditional-edges
  (with-runtime
    (fn [_ _]
      (workflow/start! "pr-flow" (pr-dev-workflow {}) {:feature "pr-42"}
                       {:family "pull-request"
                        :definition 'skein.spools.workflow-test/pr-dev-workflow
                        :context {:feature "pr-42"}})
      (is (= "Implement pr-42" (:title (workflow/next-step "pr-flow"))))
      (is (= "Open the change for review" (:title (first (workflow/complete! "pr-flow")))))
      ;; the CI round is inlined by call; its gate tells the driver to wait
      ;; (e.g. run a blocking `gh pr checks --watch`), not to do work
      (let [gate (first (workflow/complete! "pr-flow"))]
        (is (= "Wait for CI on pr-42" (:title gate)))
        (is (= "ci" (:gate gate)))
        (is (= "Judge CI result" (:title (first (workflow/complete! "pr-flow" {:by "ci-bot"})))))
        (is (= "ci-bot" (get-in (repl/strand (:id gate)) [:attributes :workflow/outcome-by]))))
      ;; red verdict routes into the fix-CI loop, which recomposes the CI round
      (is (= "Diagnose CI failure" (:title (first (workflow/choose! "pr-flow" :red)))))
      (is (= "Push CI fix" (:title (first (workflow/complete! "pr-flow")))))
      (is (= "Wait for CI on pr-42" (:title (first (workflow/complete! "pr-flow")))))
      (is (= "Judge CI result" (:title (first (workflow/complete! "pr-flow" {:by "ci-bot"})))))
      ;; green verdict hands off to the review round
      (let [review-gate (first (workflow/choose! "pr-flow" :green))]
        (is (= "Wait for reviewer feedback on pr-42" (:title review-gate)))
        (is (= "human" (:gate review-gate))))
      (is (= "Judge review outcome" (:title (first (workflow/complete! "pr-flow" {:by "reviewer"})))))
      ;; changes requested: fix-and-push recomposes the same CI round, whose
      ;; green verdict flows back into review — the nested loop the flow needs
      (is (= "Address review comments" (:title (first (workflow/choose! "pr-flow" :changes-requested)))))
      (is (= "Wait for CI on pr-42" (:title (first (workflow/complete! "pr-flow")))))
      (is (= "Judge CI result" (:title (first (workflow/complete! "pr-flow" {:by "ci-bot"})))))
      (is (= "Wait for reviewer feedback on pr-42" (:title (first (workflow/choose! "pr-flow" :green)))))
      (is (= "Judge review outcome" (:title (first (workflow/complete! "pr-flow" {:by "reviewer"})))))
      ;; approval routes to merge; the run closes itself when merge completes
      (is (= "Merge pr-42" (:title (first (workflow/choose! "pr-flow" :approved {} {:by "agent-driver"})))))
      (is (= [] (workflow/complete! "pr-flow")))
      (is (workflow/done? "pr-flow")))))

(def ^:private gitlab-pr-bindings
  ;; what a gitlab user writes in their own config: a partial override
  ;; deep-merged over the shipped reference — only the rebound fields of the
  ;; rebound actions change (:pr.ci.wait keeps its reference :skills)
  (merge-with merge github-pr-bindings
              {:pr.open    {:instruction "glab mr create --fill"}
               :pr.ci.wait {:instruction "glab ci status --live"}}))

(deftest workflow-pr-flow-rebinds-forge-without-spool-changes
  (with-runtime
    (fn [_ _]
      ;; reference run: no bindings passed, the github reference applies
      (workflow/start! "pr-forge-ref" (pr-dev-workflow {}) {:feature "ref-feat"}
                       {:family "pull-request" :context {:feature "ref-feat"}})
      (workflow/complete! "pr-forge-ref")
      (let [open-step (workflow/next-step "pr-forge-ref")]
        (is (= "pr.open" (:action-ref open-step)))
        (is (= "gh pr create --fill" (:instruction open-step))))
      (let [gate (first (workflow/complete! "pr-forge-ref"))]
        (is (= "pr.ci.wait" (:action-ref gate)))
        (is (= "gh pr checks --watch --fail-fast" (:instruction gate)))
        (is (= "ci-watch" (:skills gate))))
      ;; gitlab run: the same untouched definitions, driven by user-supplied
      ;; pure-data overrides passed through params and context
      (workflow/start! "pr-forge-gl" (pr-dev-workflow {:bindings gitlab-pr-bindings})
                       {:feature "gl-feat" :bindings gitlab-pr-bindings}
                       {:family "pull-request"
                        :context {:feature "gl-feat" :bindings gitlab-pr-bindings}})
      (workflow/complete! "pr-forge-gl")
      (is (= "glab mr create --fill" (:instruction (workflow/next-step "pr-forge-gl"))))
      (let [gate (first (workflow/complete! "pr-forge-gl"))]
        (is (= "pr.ci.wait" (:action-ref gate)))
        (is (= "glab ci status --live" (:instruction gate)))
        ;; per-field override: only :instruction was rebound, the reference
        ;; :skills field on the same action survives
        (is (= "ci-watch" (:skills gate))))
      ;; red verdict routes into the fix loop: the non-overridden fix action
      ;; keeps the github reference (partial override at work)
      (workflow/complete! "pr-forge-gl" {:by "gitlab-ci"})
      (let [diagnose (first (workflow/choose! "pr-forge-gl" :red))]
        (is (= "Diagnose CI failure" (:title diagnose)))
        (is (= "pr.ci.fix" (:action-ref diagnose)))
        (is (= "gh run view --log-failed to inspect the failing checks"
               (:instruction diagnose))))
      (workflow/complete! "pr-forge-gl")
      ;; the rebound CI gate survives the routed loop round: bindings rode
      ;; workflow/context into the recompiled continuation
      (let [gate (first (workflow/complete! "pr-forge-gl"))]
        (is (= "Wait for CI on gl-feat" (:title gate)))
        (is (= "glab ci status --live" (:instruction gate)))))))

(deftest workflow-runtime-closes-empty-runs-at-start
  (with-runtime
    (fn [_ _]
      (let [empty-workflow (workflow/workflow "Nothing to do")]
        (is (= [] (workflow/start! "empty-run" empty-workflow {})))
        (is (workflow/done? "empty-run"))
        (is (nil? (workflow/current-root "empty-run")))
        (is (= [] (workflow/start! "empty-run" empty-workflow {})))))))

(deftest workflow-run-not-done-while-blocked-by-external-dependency
  (with-runtime
    (fn [_ _]
      (let [blocker (repl/strand! "External blocker")
            definition (workflow/workflow
                        "Blocked run"
                        (workflow/step :a "Do A")
                        (workflow/step :b "Do B" :depends-on [:a]))
            result (workflow/pour! definition {} {:run-id "blocked-run"})
            b-id (get-in result [:refs :b])]
        (repl/update! b-id {:edges [{:type "depends-on" :to (:id blocker)}]})
        (is (= {:title "Do A" :kind "step"}
               (select-keys (workflow/next-step "blocked-run") [:title :kind])))
        (is (= [] (workflow/complete! "blocked-run")))
        (is (not (workflow/done? "blocked-run")))
        (is (some? (workflow/current-root "blocked-run")))
        (is (= "active" (:state (repl/strand b-id))))))))

(deftest workflow-done-fails-loudly-for-unknown-run
  (with-runtime
    (fn [_ _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow run"
                            (workflow/done? "no-such-run"))))))

(deftest workflow-run-auto-closes-root-when-last-step-completes
  (with-runtime
    (fn [_ _]
      (let [definition (workflow/workflow
                        "Linear run"
                        (workflow/step :a "Do A")
                        (workflow/step :b "Do B" :depends-on [:a]))]
        (workflow/start! "linear-run" definition {})
        (is (= [{:title "Do B" :kind "step"}]
               (mapv #(select-keys % [:title :kind]) (workflow/complete! "linear-run"))))
        (is (= [] (workflow/complete! "linear-run")))
        (is (workflow/done? "linear-run"))
        (is (nil? (workflow/current-root "linear-run")))))))

(deftest workflow-runtime-supports-parallel-ready-steps
  (with-runtime
    (fn [_ _]
      (let [definition (workflow/workflow
                        "Parallel entry"
                        (workflow/step :a "Do A")
                        (workflow/step :b "Do B"))
            started (workflow/start! "parallel-run" definition {})
            a-id (:id (first (filter #(= "Do A" (:title %)) started)))
            b-id (:id (first (filter #(= "Do B" (:title %)) started)))]
        (is (= #{"Do A" "Do B"} (set (map :title started))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Multiple workflow next steps are ready"
                              (workflow/complete! "parallel-run")))
        (is (= "active" (:state (repl/strand a-id))))
        (is (= "active" (:state (repl/strand b-id))))
        (let [remaining (workflow/complete! "parallel-run" {:step a-id})]
          (is (= "closed" (:state (repl/strand a-id))))
          (is (= "active" (:state (repl/strand b-id))))
          (is (= [{:title "Do B" :kind "step"}]
                 (mapv #(select-keys % [:title :kind]) remaining))))))))

(deftest workflow-complete-records-notes-and-attributes
  (with-runtime
    (fn [_ _]
      (let [definition (workflow/workflow "Notes run" (workflow/step :a "Do A"))
            [step] (workflow/start! "notes-run" definition {})]
        (workflow/complete! "notes-run" {:notes "done via automation" :attributes {"outcome" "ok"}})
        (let [strand (repl/strand (:id step))]
          (is (= "closed" (:state strand)))
          (is (= "done via automation" (get-in strand [:attributes :workflow/notes])))
          (is (= "ok" (get-in strand [:attributes :outcome]))))))))

(deftest workflow-complete-fails-loudly-on-invalid-step-and-mutates-nothing
  (with-runtime
    (fn [_ _]
      (let [definition (workflow/workflow "Bad step run" (workflow/step :a "Do A"))]
        (workflow/start! "bad-step-run" definition {})
        (let [a-id (:id (workflow/next-step "bad-step-run"))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Requested workflow step is not ready"
                                (workflow/complete! "bad-step-run" {:step "no-such-step"})))
          (is (= "active" (:state (repl/strand a-id)))))))))

(deftest workflow-gate-requires-by-and-records-who-closed-it
  (with-runtime
    (fn [_ _]
      (let [definition (workflow/workflow
                        "Gated run"
                        (workflow/step :push "Push branch")
                        (workflow/gate :ci "Wait for CI to go green" :ci :depends-on [:push])
                        (workflow/step :deploy "Deploy" :depends-on [:ci]))]
        (workflow/start! "gated-run" definition {})
        ;; the non-gate :push step closes without :by, reaching the gate
        (let [gate (first (workflow/complete! "gated-run"))
              gate-id (:id gate)]
          (is (= "ci" (:gate gate)))
          (is (= "step" (:kind gate)))
          ;; the gate refuses to close without :by and stays active
          (try
            (workflow/complete! "gated-run")
            (is false "expected gate complete to fail without :by")
            (catch clojure.lang.ExceptionInfo e
              (is (re-find #"Gate steps require a non-blank :by" (ex-message e)))
              (is (= "ci" (:gate (ex-data e))))
              (is (= "ci" (get-in (ex-data e) [:step :gate])))))
          ;; a nil or blank :by is no better than a missing one
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Gate steps require a non-blank :by"
                                (workflow/complete! "gated-run" {:by nil})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Gate steps require a non-blank :by"
                                (workflow/complete! "gated-run" {:by "  "})))
          (is (= "active" (:state (repl/strand gate-id))))
          ;; an external actor closes the gate with :by; :deploy becomes ready
          (let [remaining (workflow/complete! "gated-run" {:by "ci" :notes "green"})
                closed (repl/strand gate-id)]
            (is (= "closed" (:state closed)))
            (is (= "ci" (get-in closed [:attributes :workflow/outcome-by])))
            (is (= "green" (get-in closed [:attributes :workflow/notes])))
            (is (= [{:title "Deploy" :kind "step"}]
                   (mapv #(select-keys % [:title :kind]) remaining)))))))))

(deftest workflow-non-gate-step-closes-without-by
  (with-runtime
    (fn [_ _]
      (let [definition (workflow/workflow "Plain run" (workflow/step :a "Do A"))
            [step] (workflow/start! "plain-gate-run" definition {})]
        (is (= [] (workflow/complete! "plain-gate-run")))
        (let [closed (repl/strand (:id step))]
          (is (= "closed" (:state closed)))
          (is (nil? (get-in closed [:attributes :workflow/outcome-by]))))))))

(defn- empty-continuation-workflow [_]
  (workflow/workflow "Empty continuation"))

(deftest workflow-routed-choice-closes-workless-continuation-run
  (with-runtime
    (fn [_ _]
      (let [definition (workflow/workflow
                        "Route to empty"
                        (workflow/checkpoint :route "Route somewhere"
                                             :choices [{:key :finish
                                                        :label "Finish"
                                                        :next 'skein.spools.workflow-test/empty-continuation-workflow}]))]
        (workflow/start! "route-to-empty" definition {})
        (is (= [] (workflow/choose! "route-to-empty" :finish)))
        (is (true? (workflow/done? "route-to-empty")))
        (is (nil? (workflow/current-root "route-to-empty")))))))

(defn- routed-continuation-workflow [_]
  (workflow/workflow
    "Continuation"
    (workflow/step :follow-up "Do follow up work")))

(deftest workflow-routed-choice-swaps-to-single-active-continuation-root
  (with-runtime
    (fn [_ _]
      (let [definition (workflow/workflow
                        "Route to work"
                        (workflow/checkpoint :route "Route somewhere"
                                             :choices [{:key :continue
                                                        :label "Continue"
                                                        :next 'skein.spools.workflow-test/routed-continuation-workflow}]))]
        (workflow/start! "route-to-work" definition {})
        (let [old-root-id (:id (workflow/current-root "route-to-work"))
              remaining (workflow/choose! "route-to-work" :continue)]
          (is (= "closed" (:state (repl/strand old-root-id))))
          (is (= [{:title "Do follow up work" :kind "step"}]
                 (mapv #(select-keys % [:title :kind]) remaining)))
          ;; current-root throws on more than one active root, so a non-nil
          ;; result asserts exactly one active root remains for the run-id
          (let [new-root (workflow/current-root "route-to-work")]
            (is (some? new-root))
            (is (not= old-root-id (:id new-root)))
            (is (= "active" (:state new-root)))))))))

(deftest workflow-choose-records-outcome-by
  (with-runtime
    (fn [_ _]
      (let [definition (workflow/workflow
                        "Signoff run"
                        (workflow/checkpoint :approve "Approve it"
                                             :choices [{:key :approved :label "Approve"}]))
            [step] (workflow/start! "signoff-run" definition {})]
        (workflow/choose! "signoff-run" :approved {} {:by "agent:reviewer"})
        (let [strand (repl/strand (:id step))]
          (is (= "closed" (:state strand)))
          (is (= "approved" (get-in strand [:attributes :workflow/outcome])))
          (is (= "agent:reviewer" (get-in strand [:attributes :workflow/outcome-by]))))))))

(defn- loopy-workflow [{:keys [revision]}]
  (workflow/workflow
    "Loopy"
    {:params {:revision (workflow/param :default (boolean revision))}}
    (workflow/step :orient "Orient" :condition [:!= :revision true])
    (workflow/step :work "Do work" :depends-on [:orient])
    (workflow/checkpoint :signoff "Sign off"
                         :depends-on [:work]
                         :kind :agent
                         :choices [{:key :approved :label "Approve"}
                                   {:key :revise
                                    :label "Revise"
                                    :next 'skein.spools.workflow-test/loopy-revision-workflow}])))

(defn- loopy-revision-workflow [opts]
  (loopy-workflow (assoc opts :revision true)))

(deftest workflow-revise-choice-loops-back-to-a-fresh-revision-round
  (with-runtime
    (fn [_ _]
      (is (= [{:title "Orient" :kind "step"}]
             (mapv #(select-keys % [:title :kind])
                   (workflow/start! "loopy" (loopy-workflow {}) {}))))
      (is (= [{:title "Do work" :kind "step"}]
             (mapv #(select-keys % [:title :kind]) (workflow/complete! "loopy"))))
      (is (= [{:title "Sign off" :kind "checkpoint"}]
             (mapv #(select-keys % [:title :kind]) (workflow/complete! "loopy"))))
      (let [signoff (workflow/next-step "loopy")
            signoff-id (:id signoff)
            old-root-id (:id (workflow/current-root "loopy"))]
        (is (= "checkpoint" (:kind signoff)))
        ;; revise routes back to a fresh revision round under the same run-id
        (let [remaining (workflow/choose! "loopy" :revise)]
          (is (= "closed" (:state (repl/strand signoff-id))))
          (is (= "revise" (get-in (repl/strand signoff-id) [:attributes :workflow/outcome])))
          (is (= "closed" (:state (repl/strand old-root-id))))
          (let [new-root (workflow/current-root "loopy")]
            (is (some? new-root))
            (is (not= old-root-id (:id new-root))))
          ;; :orient is condition-skipped on the revision round, so :work is ready
          (is (= [{:title "Do work" :kind "step"}]
                 (mapv #(select-keys % [:title :kind]) remaining))))
        (is (= [{:title "Sign off" :kind "checkpoint"}]
               (mapv #(select-keys % [:title :kind]) (workflow/complete! "loopy"))))
        (is (= [] (workflow/choose! "loopy" :approved)))
        (is (workflow/done? "loopy"))))))

(deftest workflow-routed-choose-failure-keeps-run-resumable
  (with-runtime
    (fn [_ _]
      (workflow/start! "loopy-fail" (loopy-workflow {}) {})
      (workflow/complete! "loopy-fail")
      (workflow/complete! "loopy-fail")
      (let [old-root-id (:id (workflow/current-root "loopy-fail"))
            signoff-id (:id (workflow/next-step "loopy-fail"))]
        ;; a failed continuation apply must not leave the run in a false
        ;; terminal state; the checkpoint close and continuation pour are folded
        ;; into one batch, so a failing apply commits nothing
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"batch boom"
                              (with-redefs [batch/apply! (fn [_] (throw (ex-info "batch boom" {})))]
                                (workflow/choose! "loopy-fail" :revise))))
        (let [root (workflow/current-root "loopy-fail")]
          (is (some? root))
          (is (= old-root-id (:id root)))
          (is (= "active" (:state root))))
        (is (= "active" (:state (repl/strand signoff-id))))
        (is (false? (workflow/done? "loopy-fail")))
        ;; the run stays resumable: retrying the same choice now succeeds
        (is (= [{:title "Do work" :kind "step"}]
               (mapv #(select-keys % [:title :kind])
                     (workflow/choose! "loopy-fail" :revise))))))))

(deftest workflow-runtime-selects-among-parallel-ready-checkpoints
  (with-runtime
    (fn [_ _]
      (let [definition (workflow/workflow
                        "Parallel checkpoints"
                        (workflow/checkpoint :x "Pick X"
                                             :choices [{:key :go :label "Go X"}])
                        (workflow/checkpoint :y "Pick Y"
                                             :choices [{:key :go :label "Go Y"}]))
            started (workflow/start! "parallel-checkpoints" definition {})
            x-id (:id (first (filter #(= "Pick X" (:title %)) started)))
            y-id (:id (first (filter #(= "Pick Y" (:title %)) started)))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Multiple workflow next steps are ready"
                              (workflow/choose! "parallel-checkpoints" :go)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Multiple workflow next steps are ready"
                              (workflow/choice-details "parallel-checkpoints")))
        (is (= "active" (:state (repl/strand x-id))))
        (is (= "active" (:state (repl/strand y-id))))
        ;; choice-details string-keys choice names and detail maps, agreeing
        ;; with choice-detail's shape (archived workflow-engine review, R2)
        (is (= {"go" {"label" "Go X"}}
               (workflow/choice-details "parallel-checkpoints" {:step x-id})))
        (is (= {"label" "Go Y"}
               (workflow/choice-detail "parallel-checkpoints" :go {:step y-id})))
        (let [remaining (workflow/choose! "parallel-checkpoints" :go {} {:step x-id})]
          (is (= "closed" (:state (repl/strand x-id))))
          (is (= "go" (get-in (repl/strand x-id) [:attributes :workflow/outcome])))
          (is (= "active" (:state (repl/strand y-id))))
          (is (= [y-id] (mapv :id remaining))))))))

(deftest workflow-spool-supports-wisps-bonds-and-squash
  (with-runtime
    (fn [_ _]
      (let [left-result (workflow/wisp! {:name "Left" :steps [{:id :a :title "A"}]})
            right-result (workflow/wisp! {:name "Right" :steps [{:id :b :title "B"}]})
            left-id (workflow/molecule-id left-result)
            right-id (workflow/molecule-id right-result)]
        (is (= "true" (get-in (repl/strand left-id) [:attributes :workflow/wisp])))
        (workflow/bond! left-id right-id)
        (let [digest (workflow/squash! left-id "Left digest" {:summary "done"})]
          (is (= "closed" (:state digest)))
          (is (= "digest" (get-in digest [:attributes :workflow/role])))
          (is (nil? (repl/strand left-id))))))))

(deftest workflow-bond-parent-blocks-the-bonded-run
  (with-runtime
    (fn [_ _]
      (workflow/start! "bond-left" {:name "Left" :steps [{:id :a :title "Do A"}]} {})
      (workflow/start! "bond-right" {:name "Right" :steps [{:id :b :title "Do B"}]} {})
      (let [left-root-id (:id (workflow/current-root "bond-left"))
            right-root-id (:id (workflow/current-root "bond-right"))]
        (workflow/bond! left-root-id right-root-id)
        ;; the right step has no deps of its own, but the dep-blocked root
        ;; hides the whole run until the left root closes
        (is (= [] (workflow/next-steps "bond-right")))
        (is (false? (workflow/done? "bond-right")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No ready workflow step"
                              (workflow/complete! "bond-right")))
        (is (= "Do A" (:title (workflow/next-step "bond-left"))))
        (workflow/complete! "bond-left")
        (is (true? (workflow/done? "bond-left")))
        (is (= ["Do B"] (mapv :title (workflow/next-steps "bond-right"))))))))

(deftest workflow-checkpoint-rejects-duplicate-choice-keys
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"choice keys must be unique"
                        (workflow/checkpoint :gate "Gate"
                                             :choices [{:key :abort :label "A"}
                                                       {:key :abort :label "B"}]))))

(deftest workflow-builders-reject-unknown-option-keys
  (testing "each builder and the choice map fail loudly on a mistyped option key"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/param :requird true)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/step :a "A" :depend-on [:b])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/gate :a "A" :ci :dependson [:b])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/checkpoint :a "A" :choicez [:x])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/call :a 'x {} :dependson [:b])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/workflow "W" {:param {:x true}} (workflow/step :a "A"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/checkpoint :a "A"
                                               :choices [{:key :ok :labl "Bad"}]))))
  (testing "ex-data carries the offending and allowed keys"
    (try
      (workflow/step :a "A" :depend-on [:b])
      (is false "expected step to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= [:depend-on] (:unknown (ex-data e))))
        (is (contains? (:allowed (ex-data e)) :depends-on))))))

(defn- cyclic-procedure-workflow [_params]
  (workflow/workflow "Cyclic procedure"
                     (workflow/step :work "Do work")
                     ;; recursive edge by symbol while the entry call passes the
                     ;; fn value: both must canonicalize to one identity
                     (workflow/call :again 'skein.spools.workflow-test/cyclic-procedure-workflow {}
                                    :depends-on [:work])))

(deftest workflow-compile-fails-loudly-on-cyclic-procedure-call
  ;; conditions filter steps only after procedure expansion, so a cyclic
  ;; procedure reference can never terminate — compile must throw, not overflow
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Workflow procedure call is cyclic"
                        (workflow/compile
                         (workflow/workflow "Cyclic root"
                                            (workflow/call :outer cyclic-procedure-workflow {}))))))

(deftest workflow-compile-resolves-symbol-procedures
  (let [payload (workflow/compile
                 (workflow/workflow "Symbol procedure demo"
                                    (workflow/call :quality 'skein.spools.workflow-test/toastie-quality-workflow {})))]
    (is (= #{:molecule :quality :quality--inspect}
           (set (map :ref (:strands payload)))))))

(deftest workflow-step-view-reads-keyword-and-string-keyed-attributes
  ;; strand attributes arrive keyword-keyed in-memory but string-keyed after a
  ;; JSON round-trip through the weaver; step-view reads through the single attr
  ;; boundary so both key forms yield the same view (archived workflow-engine review, R2)
  (let [keyworded (workflow/step-view
                   {:id "s1" :title "Do it" :state "active"
                    :attributes {:workflow/role "checkpoint"
                                 :workflow/choices ["a" "b"]
                                 :skills "clojure"}})
        stringed (workflow/step-view
                  {:id "s1" :title "Do it" :state "active"
                   :attributes {"workflow/role" "checkpoint"
                                "workflow/choices" ["a" "b"]
                                "skills" "clojure"}})]
    (is (= {:id "s1" :title "Do it" :state "active" :kind "checkpoint"
            :choices ["a" "b"] :skills "clojure"}
           keyworded))
    (is (= keyworded stringed))))

(deftest workflow-spool-fails-loudly-on-bad-definitions
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"required params"
                        (workflow/compile {:name "Missing" :params {:x {:required true}} :steps []})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow definition"
                        (workflow/compile {:name "Bad params" :params {"x" {:required true}} :steps []})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow definition"
                        (workflow/compile {:name "Bad param def" :params {:x {:required "yes"}} :steps []})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow params"
                        (workflow/compile {:name "Bad params" :steps []} {"x" true})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"step ids must be unique"
                        (workflow/compile {:name "Duplicate" :steps [{:id :a :title "A"}
                                                                      {:id :a :title "Again"}]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow definition"
                        (workflow/compile {:name "Bad condition" :steps [{:id :a :title "A" :condition '(bad)}]}))))

(deftest workflow-compile-splices-condition-excluded-step-deps
  (let [definition (workflow/workflow
                     "Splice"
                     (workflow/step :design "Design")
                     (workflow/step :review "Review" :depends-on [:design] :condition :include-review)
                     (workflow/step :implement "Implement" :depends-on [:review]))
        payload (workflow/compile definition)
        refs (set (map :ref (:strands payload)))
        edges (set (map (juxt :from :to :type) (:edges payload)))]
    (is (not (contains? refs :review)))
    (is (contains? edges [:implement :design "depends-on"]))
    (is (not (contains? edges [:implement :review "depends-on"])))))

(deftest workflow-compile-splices-transitively-through-two-excluded-steps
  (let [definition (workflow/workflow
                     "Transitive splice"
                     (workflow/step :base "Base")
                     (workflow/step :mid1 "Mid 1" :depends-on [:base] :condition :skip)
                     (workflow/step :mid2 "Mid 2" :depends-on [:mid1] :condition :skip)
                     (workflow/step :consumer "Consumer" :depends-on [:mid2]))
        payload (workflow/compile definition)
        refs (set (map :ref (:strands payload)))
        edges (set (map (juxt :from :to :type) (:edges payload)))]
    (is (= #{:molecule :base :consumer} refs))
    (is (contains? edges [:consumer :base "depends-on"]))
    (is (not (contains? edges [:consumer :mid1 "depends-on"])))
    (is (not (contains? edges [:consumer :mid2 "depends-on"])))))

(deftest workflow-compile-fails-loudly-on-unknown-depends-on-ref
  (let [definition (workflow/workflow
                     "Typo"
                     (workflow/step :design "Design")
                     (workflow/step :implement "Implement" :depends-on [:desgin]))]
    (try
      (workflow/compile definition)
      (is false "expected compile to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :implement (:step (ex-data e))))
        (is (= :desgin (:missing (ex-data e))))))))

(deftest workflow-compile-attributes-unknown-ref-to-the-excluded-step-that-names-it
  (let [definition (workflow/workflow
                     "Typo in excluded step"
                     (workflow/step :design "Design")
                     (workflow/step :review "Review" :depends-on [:desgin] :condition :include-review)
                     (workflow/step :implement "Implement" :depends-on [:review]))]
    (try
      (workflow/compile definition)
      (is false "expected compile to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :review (:step (ex-data e))))
        (is (= :desgin (:missing (ex-data e))))))))

(deftest workflow-compile-fails-loudly-on-root-ref-collision
  (let [definition (workflow/workflow
                     "Root collision"
                     (workflow/step :molecule "Steal the root ref"))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collides with the root ref"
                          (workflow/compile definition)))))

(deftest workflow-loop-steps-render-item-index-and-params
  (let [definition (workflow/workflow
                     "Loop render"
                     {:params {:feature (workflow/param :required true)
                               :envs (workflow/param :default [:dev :prod])}}
                     (workflow/step :deploy
                                    (fn [{:keys [feature item i]}]
                                      (str "Deploy " feature " to " (name item) " #" i))
                                    :loop {:each :envs}))
        titles (into {} (map (juxt :ref :title)) (:strands (workflow/compile definition {:feature "checkout"})))]
    (is (= "Deploy checkout to dev #0" (get titles :deploy-1)))
    (is (= "Deploy checkout to prod #1" (get titles :deploy-2)))))

(deftest workflow-loop-each-accepts-param-keyword-and-fn-of-params
  (let [from-keyword (workflow/workflow
                       "Each keyword"
                       {:params {:regions (workflow/param :default ["us" "eu"])}}
                       (workflow/step :ship (fn [{:keys [item]}] (str "Ship " item)) :loop {:each :regions}))
        from-fn (workflow/workflow
                  "Each fn"
                  {:params {:regions (workflow/param :default ["us" "eu"])}}
                  (workflow/step :ship (fn [{:keys [item]}] (str "Ship " item))
                                 :loop {:each (fn [{:keys [regions]}] (reverse regions))}))]
    (is (= #{:molecule :ship-1 :ship-2} (set (map :ref (:strands (workflow/compile from-keyword))))))
    (is (= ["Ship us" "Ship eu"]
           (mapv :title (rest (:strands (workflow/compile from-keyword))))))
    (is (= ["Ship eu" "Ship us"]
           (mapv :title (rest (:strands (workflow/compile from-fn))))))))

(deftest workflow-loop-each-fails-loudly-on-non-sequential-param
  (let [definition (workflow/workflow
                     "Bad each"
                     {:params {:n (workflow/param :default 5)}}
                     (workflow/step :s "S" :loop {:each :n}))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":each must resolve to a sequential"
                          (workflow/compile definition)))))

(deftest workflow-loop-suffix-rules
  (let [count-def (workflow/workflow "Count" (workflow/step :ping "Ping" :loop {:count 3}))
        map-def (workflow/workflow
                  "Map ids"
                  {:params {:tasks (workflow/param :default [{:id "alpha"} {:id "beta"}])}}
                  (workflow/step :run (fn [{:keys [item]}] (str "Run " (:id item))) :loop {:each :tasks}))
        position-def (workflow/workflow
                       "Positions"
                       (workflow/step :s (fn [{:keys [item]}] (str "S " item)) :loop {:each ["x" "y"]}))]
    (is (= [:molecule :ping-1 :ping-2 :ping-3] (map :ref (:strands (workflow/compile count-def)))))
    (is (= [:molecule :run-alpha :run-beta] (map :ref (:strands (workflow/compile map-def)))))
    (is (= [:molecule :s-1 :s-2] (map :ref (:strands (workflow/compile position-def)))))))

(deftest workflow-loop-fans-in-base-id-dependents
  (let [definition (workflow/workflow
                     "Fan in"
                     {:params {:shards (workflow/param :default ["a" "b" "c"])}}
                     (workflow/step :migrate (fn [{:keys [item]}] (str "Migrate " item)) :loop {:each :shards})
                     (workflow/step :verify "Verify migrations" :depends-on [:migrate]))
        payload (workflow/compile definition)
        refs (set (map :ref (:strands payload)))
        edges (set (map (juxt :from :to :type) (:edges payload)))]
    (is (= #{:molecule :migrate-1 :migrate-2 :migrate-3 :verify} refs))
    (is (contains? edges [:verify :migrate-1 "depends-on"]))
    (is (contains? edges [:verify :migrate-2 "depends-on"]))
    (is (contains? edges [:verify :migrate-3 "depends-on"]))
    ;; the pre-expansion base id is not itself a strand, only its fan-in edges
    (is (not (contains? edges [:verify :migrate "depends-on"])))))

(deftest workflow-loop-does-not-mask-unknown-depends-on-refs
  (let [definition (workflow/workflow
                     "Loop plus typo"
                     {:params {:shards (workflow/param :default ["a" "b"])}}
                     (workflow/step :migrate "Migrate" :loop {:each :shards})
                     (workflow/step :verify "Verify" :depends-on [:migrate :migrat]))]
    (try
      (workflow/compile definition)
      (is false "expected compile to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :verify (:step (ex-data e))))
        (is (= :migrat (:missing (ex-data e))))))))

(deftest workflow-loop-base-id-collisions-fail-loudly
  ;; Fan-in keys deps on the pre-expansion base id, so a base-id collision must
  ;; be rejected before it can silently misroute a dependency.
  (let [dup-base (workflow/workflow
                   "Dup base"
                   {:params {:xs (workflow/param :default ["a" "b"])}}
                   (workflow/step :run "Run once" :loop {:each :xs})
                   (workflow/step :run "Run again" :loop {:count 3}))
        base-vs-plain (workflow/workflow
                        "Base vs plain"
                        {:params {:xs (workflow/param :default ["a" "b"])}}
                        (workflow/step :run "Loop" :loop {:each :xs})
                        (workflow/step :run "Plain"))
        base-vs-root (workflow/workflow
                       "Base vs root"
                       {:params {:xs (workflow/param :default ["a" "b"])}}
                       (workflow/step :molecule "Steal root" :loop {:each :xs}))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"step ids must be unique"
                          (workflow/compile dup-base)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"step ids must be unique"
                          (workflow/compile base-vs-plain)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collides with the root ref"
                          (workflow/compile base-vs-root)))))

(deftest workflow-loop-condition-and-fan-in-splice-interact
  ;; A condition on a loop step is evaluated against workflow params for every
  ;; expanded copy; excluding all copies leaves a base-id dependent to splice
  ;; through the fanned-in (now excluded) ids onto their own deps.
  (let [definition (workflow/workflow
                     "Loop conditions"
                     {:params {:shards (workflow/param :default ["a" "b"])
                               :do-migrate (workflow/param :default false)}}
                     (workflow/step :migrate "Migrate" :loop {:each :shards} :condition :do-migrate)
                     (workflow/step :verify "Verify" :depends-on [:migrate]))
        payload (workflow/compile definition)
        refs (set (map :ref (:strands payload)))
        edges (set (map (juxt :from :to :type) (:edges payload)))]
    (is (= #{:molecule :verify} refs))
    (is (not (some (fn [[_ to _]] (= :migrate to)) edges)))))
