(ns skein.cutover.vocab-reset-test
  "Focused coverage for the vocabulary-reset cutover against a disposable weaver
  world, so the rewrite is proved on the real schema rather than a hand-rolled
  fixture: the world is seeded through the weaver, migrated through the script's
  own datasource (the way an operator runs it), and read back through the weaver.

  The cases are the ones the table cannot make obvious by inspection — the
  guarded splits (a bare key that is only agent-run's word on a task strand, an
  overloaded bench/run that stays put on marker rows), the guarded drops (a
  roster/* key goes only where the bare twin it is redundant with survives it),
  the closed-strand scope boundary, idempotency, and the loud refusals."
  (:require
   [clojure.test :refer [deftest is testing]]
   [cutover.vocab-reset :as cut]
   [next.jdbc :as jdbc]
   [skein.api.weaver.alpha :as weaver]
   [skein.test.alpha :as test-alpha]))

(defn- add!
  "Seed one strand in `state` carrying `attributes`; return its id."
  [rt state attributes]
  (:id (weaver/add rt {:title "seed" :state state :attributes attributes})))

(defn- attrs
  "Return the strand's current attribute map, as the weaver reads it back.

  Durable keys are stored as strings; the weaver normalizes them to keywords on
  read, so expectations here are keyword-keyed — asserting through that read is
  what proves the migrated rows land back in the runtime's own view."
  [rt id]
  (:attributes (weaver/show rt id)))

(defn- datasource
  "Build the script's own datasource over the world's db, as -main does."
  [ctx]
  (jdbc/get-datasource {:dbtype "sqlite" :dbname (:db-path ctx)}))

(def ^:private world-opts
  "A bare disposable world: no spools, so nothing normalizes the seeded keys."
  {:spools-edn {:spools {}}})

(deftest rewrites-active-strands-to-the-published-vocabulary
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          run (add! rt "active" {"workflow/phase" "review"
                                 "workflow/role" "molecule"
                                 "workflow/wisp" "true"
                                 "workflow/hitl" "true"
                                 "workflow/checkpoint-kind" "human"
                                 "gate/run-id" "run-7"
                                 "shell/error" "boom"
                                 "agent-run/for" "task-1"
                                 "panel/fresh-prompt" "prompt text"
                                 "review/target" "board-1"
                                 "review/pass" "pass-1"
                                 "review/synthesis" "true"
                                 "review/roster" "reviewers"
                                 "ephemeral" "entry-1"})]
      (cut/rewrite! (datasource ctx))
      (is (= {:workflow/form "review"
              :workflow/role "root"
              :workflow/checkpoint-kind "human"
              :workflow/run-id "run-7"
              :gate/error "boom"
              :agent-run/completes-on "task-1"
              :agent-run/fresh-prompt "prompt text"
              :panel/blackboard "board-1"
              :panel/pass "pass-1"
              :panel/synthesis "true"
              :review/roster "reviewers"
              :ephemeral/entry "entry-1"}
             (attrs rt run))
          "every renamed key lands on its new word, values carried unchanged;
           the derivable markers are dropped and review/roster keeps its word"))))

(deftest leaves-closed-strands-as-written
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          closed (add! rt "closed" {"workflow/phase" "review" "review/target" "board-1"})]
      (cut/rewrite! (datasource ctx))
      (is (= {:workflow/phase "review" :review/target "board-1"} (attrs rt closed))
          "closed strands are historical memory, not authority"))))

(deftest namespaces-bare-keys-only-on-task-strands
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          task (add! rt "active" {"kind" "task"
                                  "harness" "opus"
                                  "cwd" "/tmp/wt"
                                  "max-attempts" "2"
                                  "backend" "claude"})
          other (add! rt "active" {"kind" "bench-entry" "harness" "opus"})
          {:keys [left-behind]} (cut/rewrite! (datasource ctx))]
      (is (= {:kind "task"
              :agent-run/harness "opus"
              :agent-run/cwd "/tmp/wt"
              :agent-run/max-attempts "2"
              :agent-run/backend "claude"}
             (attrs rt task))
          "a delegated task's bare keys move onto their owning spool")
      (is (= {:kind "bench-entry" :harness "opus"} (attrs rt other))
          "a bare harness on a non-task strand is not agent-run's word")
      (is (= {"harness -> agent-run/harness (guarded)" [other]} left-behind)
          "the declined row is reported rather than migrated blind or dropped"))))

(deftest folds-the-plan-root-marker-into-kind
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          root (add! rt "active" {"kind" "plan" "workflow" "agent-plan"})
          child (add! rt "active" {"kind" "task" "workflow" "agent-plan"})
          devflow (add! rt "active" {"kind" "plan" "workflow" "devflow"})]
      (cut/rewrite! (datasource ctx))
      (is (= {:kind "agent-plan"} (attrs rt root))
          "the plan root's kind carries its identity; the workflow marker goes")
      (is (= {:kind "task"} (attrs rt child))
          "children drop the workflow marker but keep their own kind")
      (is (= {:kind "plan" :workflow "devflow"} (attrs rt devflow))
          "another workflow's plan root is untouched — the guard is by value"))))

(deftest drops-the-blackboard-self-marker
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          board (add! rt "active" {"panel/role" "panel" "review/pass" "pass-1"})
          seat (add! rt "active" {"panel/role" "reviewer"})]
      (cut/rewrite! (datasource ctx))
      (is (= {:panel/pass "pass-1"} (attrs rt board))
          "the blackboard's self-marker goes; its pass tag is renamed")
      (is (= {:panel/role "reviewer"} (attrs rt seat))
          "the drop is guarded by value — another role is not this marker"))))

(deftest splits-the-overloaded-bench-run-key
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          judge (add! rt "active" {"bench/judge" "true" "bench/run" "root-42"})
          marker (add! rt "active" {"bench/run" "true"
                                    "bench/agent" "claude"
                                    "bench/aborted" "true"
                                    "bench/error" "aborted"})]
      (cut/rewrite! (datasource ctx))
      (is (= {:bench/judge "true" :bench/run-id "root-42"} (attrs rt judge))
          "a judge's root-id value moves to the id key")
      (is (= {:bench/run "true" :bench/harness "claude" :bench/error "aborted"}
             (attrs rt marker))
          "the boolean marker keeps bench/run; the redundant abort flag goes"))))

(deftest moves-the-roster-entry-onto-its-surviving-keys
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          entry (add! rt "active" {"roster/entry" "e1"
                                   "roster/status" "active"
                                   "roster/body" "the brief"
                                   ;; the twins: each dual-wrote a bare key.
                                   "roster/feature" "py7pm"
                                   "feature" "py7pm"
                                   "roster/owner" "ct"
                                   "owner" "ct"
                                   "roster/branch" "b"
                                   "branch" "b"
                                   "roster/worktree" "/tmp/wt"
                                   "worktree" "/tmp/wt"})
          {:keys [left-behind]} (cut/rewrite! (datasource ctx))]
      (is (= {:roster/entry "e1"
              :roster/phase "active"
              :body "the brief"
              :feature "py7pm"
              :owner "ct"
              :branch "b"
              :worktree "/tmp/wt"}
             (attrs rt entry))
          "status becomes phase, the body moves to its bare key rather than
           being lost, and each twin drops in favour of the surviving copy")
      (is (= {} left-behind)
          "a fully dual-written entry leaves nothing behind to read"))))

(deftest keeps-a-roster-identity-that-never-dual-wrote-its-twin
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          ds (datasource ctx)
          lone (add! rt "active" {"roster/entry" "e2"
                                  "roster/feature" "py7pm"
                                  "roster/owner" "ct"
                                  "roster/branch" "b"
                                  "roster/worktree" "/tmp/wt"})
          {:keys [left-behind total]} (cut/rewrite! ds)]
      (is (= {:roster/entry "e2"
              :roster/feature "py7pm"
              :roster/owner "ct"
              :roster/branch "b"
              :roster/worktree "/tmp/wt"}
             (attrs rt lone))
          "a strand holding the only copy of its identity keeps every key: the
           drop is redundant with a surviving twin, and there is no twin here")
      (is (zero? total) "nothing was rewritten, so nothing was lost")
      (is (= {"drop roster/feature (guarded)" [lone]
              "drop roster/owner (guarded)" [lone]
              "drop roster/branch (guarded)" [lone]
              "drop roster/worktree (guarded)" [lone]}
             left-behind)
          "every key left in the old vocabulary is named for the operator
           rather than skipped in silence")
      (is (= left-behind (:left-behind (cut/rewrite! ds)))
          "the decline is stable across re-runs, not a one-shot warning"))))

(deftest guards-each-roster-drop-on-its-own-twin
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          half (add! rt "active" {"roster/owner" "ct"
                                  "owner" "ct"
                                  "roster/branch" "b"})
          {:keys [left-behind]} (cut/rewrite! (datasource ctx))]
      (is (= {:owner "ct" :roster/branch "b"} (attrs rt half))
          "the guard is per key, not per strand: roster/owner is redundant with
           its twin and goes, while roster/branch is the only copy and stays")
      (is (= {"drop roster/branch (guarded)" [half]} left-behind)
          "only the key actually left behind is reported"))))

(deftest reports-rows-and-is-idempotent
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)
          ds (datasource ctx)
          run (add! rt "active" {"workflow/phase" "review"
                                 "workflow/role" "molecule"
                                 "workflow/wisp" "true"})
          first-run (cut/rewrite! ds)
          second-run (cut/rewrite! ds)]
      (is (= {"workflow/phase -> workflow/form" 1
              "workflow/role: molecule -> root" 1
              "drop workflow/wisp" 1}
             (:changes first-run))
          "the report names each entry that touched a row")
      (is (= 3 (:total first-run)))
      (is (= {:changes {} :total 0 :left-behind {}} second-run)
          "a re-run against a migrated world is a no-op")
      (is (= {:workflow/form "review" :workflow/role "root"} (attrs rt run))
          "the migrated shape is stable across a second run"))))

(deftest refuses-a-strand-carrying-both-sides-of-a-rename
  (test-alpha/with-weaver-world [ctx world-opts]
    (let [rt (:runtime ctx)]
      (add! rt "active" {"workflow/phase" "review" "workflow/form" "review"})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"carry both the old and the new key"
                            (cut/rewrite! (datasource ctx)))
          "an inconsistent world fails loudly rather than raising a raw
           SQLite constraint violation"))))

(deftest refuses-an-implicit-db-target
  (testing "no --db and no --workspace"
    (is (thrown? clojure.lang.ExceptionInfo (cut/resolve-db-path {}))))
  (testing "an ambiguous db/workspace pair"
    (is (thrown? clojure.lang.ExceptionInfo
                 (cut/resolve-db-path {:db "x" :workspace "y"}))))
  (testing "an explicit --db is honoured verbatim"
    (is (= "/tmp/x.sqlite" (cut/resolve-db-path {:db "/tmp/x.sqlite"})))))
