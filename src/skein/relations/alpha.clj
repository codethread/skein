(ns skein.relations.alpha
  "Advisory relation catalog for common Skein relation vocabulary.

  The catalog is source-visible data for agents, config, and REPL workflows. It
  is not a storage allowlist or runtime relation-semantics registry; valid
  relation names outside this catalog remain valid userland annotations.")

(def catalog
  "Shipped operational relation batteries and behavior-free annotation
  conventions."
  [{:relation "depends-on"
    :family :operational
    :direction "blocked --depends-on--> blocker"
    :declared-acyclic? true
    :help "Readiness battery: active targets block readiness."}
   {:relation "parent-of"
    :family :operational
    :direction "parent --parent-of--> child"
    :declared-acyclic? true
    :help "Structural hierarchy battery used by graph traversal helpers."}
   {:relation "supersedes"
    :family :operational
    :direction "replacement --supersedes--> replaced"
    :declared-acyclic? true
    :help "Replacement lineage battery written by the core supersession transaction."}
   {:relation "related-to"
    :family :annotation
    :direction "source --related-to--> target"
    :declared-acyclic? false
    :help "Behavior-free loose association convention."}
   {:relation "duplicates"
    :family :annotation
    :direction "duplicate --duplicates--> canonical-or-other-duplicate"
    :declared-acyclic? false
    :help "Behavior-free duplicate marker convention; use supersedes for lifecycle replacement."}
   {:relation "references"
    :family :annotation
    :direction "source --references--> referenced"
    :declared-acyclic? false
    :help "Behavior-free citation/reference convention."}
   {:relation "implements"
    :family :annotation
    :direction "implementation --implements--> requirement-or-design"
    :declared-acyclic? false
    :help "Behavior-free implementation trace convention."}
   {:relation "verifies"
    :family :annotation
    :direction "check-or-evidence --verifies--> claim-or-requirement"
    :declared-acyclic? false
    :help "Behavior-free verification/evidence convention."}
   {:relation "tracks"
    :family :annotation
    :direction "tracker --tracks--> tracked-subject"
    :declared-acyclic? false
    :help "Behavior-free tracking convention."}
   {:relation "caused-by"
    :family :annotation
    :direction "effect --caused-by--> cause"
    :declared-acyclic? false
    :help "Behavior-free causal-note convention."}])

(defn relation
  "Returns the advisory catalog entry for relation-name, or nil when uncataloged."
  [relation-name]
  (first (filter #(= (:relation %) relation-name) catalog)))

(defn annotation-relations []
  (filterv #(= :annotation (:family %)) catalog))

(defn operational-relations []
  (filterv #(= :operational (:family %)) catalog))
