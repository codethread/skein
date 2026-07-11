(require '[quickdoc.api :as quickdoc])

(def github-repo "https://github.com/codethread/skein")
(def git-branch "main")

(def spool-docs
  [{:name "batteries" :source "spools/src/skein/spools/batteries.clj" :outfile "spools/batteries.api.md"}
   {:name "workflow" :source "spools/workflow/src/skein/spools/workflow.clj" :outfile "spools/workflow.api.md"}
   {:name "ephemeral" :source "spools/ephemeral/src/skein/spools/ephemeral.clj" :outfile "spools/ephemeral.api.md"}
   {:name "guild" :source "spools/src/skein/spools/guild.clj" :outfile "spools/guild.api.md"}
   {:name "bobbin" :source "spools/bobbin/src/skein/spools/bobbin.clj" :outfile "spools/bobbin.api.md"}
   {:name "selvage" :source "spools/src/skein/spools/selvage.clj" :outfile "spools/selvage.api.md"}
   {:name "carder" :source "spools/carder/src/skein/spools/carder.clj" :outfile "spools/carder.api.md"}
   {:name "roster" :source "spools/roster/src/skein/spools/roster.clj" :outfile "spools/roster.api.md"}
   {:name "loom" :source "spools/loom/src/skein/spools/loom.clj" :outfile "spools/loom.api.md"}
   {:name "text-search" :source "spools/text-search/src/skein/spools/text_search.clj" :outfile "spools/text-search.api.md"}
   {:name "agent-run" :source "spools/agent-run/src/skein/spools/agent_run.clj" :outfile "spools/agent-run.api.md"}
   {:name "delegation" :source "spools/delegation/src/skein/spools/delegation.clj" :outfile "spools/delegation.api.md"}
   {:name "shell" :source "spools/workflow/src/skein/spools/executors/shell.clj" :outfile "spools/executors/shell.api.md"}
   {:name "subagent" :source "spools/agent-run/src/skein/spools/executors/subagent.clj" :outfile "spools/executors/subagent.api.md"}
   {:name "chime" :source "spools/chime/src/skein/spools/chime.clj" :outfile "spools/chime.api.md"}
   {:name "kanban" :source "spools/kanban/src/skein/spools/kanban.clj" :outfile "spools/kanban.api.md"}
   {:name "cron" :source "spools/cron/src/skein/spools/cron.clj" :outfile "spools/cron.api.md"}
   {:name "bench" :source "spools/bench/src/skein/spools/bench.clj" :outfile "spools/bench.api.md"}])

;; The blessed spool-facing API tier (SPEC-005.C2). Generated reference only —
;; the behavior contracts stay in the root specs.
(def alpha-api-docs
  (for [nm ["batch" "cli" "current" "events" "format" "graph" "hooks" "notes"
            "patterns" "peers" "relations" "runtime" "scheduler" "views"
            "vocab" "weaver"]]
    {:name nm
     :source (str "src/skein/api/" nm "/alpha.clj")
     :outfile (str "docs/api/" nm ".api.md")}))

(doseq [{:keys [source outfile]} (concat spool-docs alpha-api-docs)]
  (quickdoc/quickdoc
   {:source-paths [source]
    :outfile outfile
    :github/repo github-repo
    :git/branch git-branch
    ;; quickdoc v0.2.6 links backticked var-shaped tokens even when they name
    ;; private helpers intentionally omitted from public API docs. There is no
    ;; public-only link filter, and including private vars would publish
    ;; internals, so use the wikilink detector; these docstrings use backticks,
    ;; which remain code-styled text instead of becoming dead links.
    :var-pattern :wikilinks
    ;; Suppress quickdoc's in-body "# Table of contents". It emits a leading H1
    ;; before the namespace H1, and mkdocs-material's right-hand TOC collapses to
    ;; the first H1's child headings — which for that TOC H1 are none — leaving
    ;; API pages with an empty sidebar TOC. Dropping it makes the namespace the
    ;; sole leading H1 so the sidebar lists every var, matching the other docs.
    :toc false}))

(System/exit 0)
