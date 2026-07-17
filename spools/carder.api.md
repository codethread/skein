
-----
# <a name="skein.spools.carder">skein.spools.carder</a>


Read-only graph hygiene reports for long-lived Skein strand graphs.

  Carder composes the public weaver/graph helper surfaces for strand reads and
  uses the active runtime datasource only to inspect edge incidence, because the
  shipped public graph helpers expose relation-scoped traversal rather than a
  workspace-wide edge listing. It never mutates strands, edges, runtime config,
  or registered operations.




## <a name="skein.spools.carder/blocked-by-failure">`blocked-by-failure`</a>
``` clojure
(blocked-by-failure)
(blocked-by-failure opts)
```
Function.

Return active strands blocked by active failed or exhausted depends-on targets.

  A blocker is any active `depends-on` target that is an agent-run record
  (`agent-run/run "true"`) whose `agent-run/phase` attribute is the string
  `failed` or `exhausted` — the same failure concept the `agent-failures` query
  publishes. Rows include the blocked strand summary and a `:blockers` vector
  with compact blocker details.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/carder/src/skein/spools/carder.clj#L146-L180">Source</a></sub></p>

## <a name="skein.spools.carder/default-days">`default-days`</a>




Default age threshold, in days, used by `stale`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/carder/src/skein/spools/carder.clj#L20-L22">Source</a></sub></p>

## <a name="skein.spools.carder/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Return carder installation metadata for trusted registration by name.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/carder/src/skein/spools/carder.clj#L244-L255">Source</a></sub></p>

## <a name="skein.spools.carder/orphans">`orphans`</a>
``` clojure
(orphans)
(orphans opts)
```
Function.

Return active strands with no incident edges and no `workflow/*` attributes.

  An orphan has zero incoming and zero outgoing edges across every relation in
  `strand_edges`, including declared acyclic and annotation relations. Workflow
  attribute carriers are excluded from this section even when they have no edges.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/carder/src/skein/spools/carder.clj#L118-L135">Source</a></sub></p>

## <a name="skein.spools.carder/report">`report`</a>
``` clojure
(report)
(report opts)
```
Function.

Return a JSON-compatible aggregate graph hygiene report.

  Options are passed to all sections. The result includes each section's rows and
  count under `:stale`, `:orphans`, `:blocked-by-failure`, and `:undeclared`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/carder/src/skein/spools/carder.clj#L224-L242">Source</a></sub></p>

## <a name="skein.spools.carder/stale">`stale`</a>
``` clojure
(stale)
(stale opts)
```
Function.

Return active strands older than the configured age threshold.

  Options: `:days` positive integer threshold (default `default-days`) and
  `:include-plumbing? true` to include workflow plumbing and agent-run run records.
  Each row is a compact strand summary plus `:days-stale`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/carder/src/skein/spools/carder.clj#L80-L98">Source</a></sub></p>

## <a name="skein.spools.carder/undeclared">`undeclared`</a>
``` clojure
(undeclared)
(undeclared opts)
```
Function.

Return active strands carrying an attribute whose namespace segment is owned
  by no vocabulary declaration.

  Flags by namespace, not exact key: a fresh key under a declared attribute
  namespace is clean, while a bare key or an unowned namespace is flagged. The
  declared set comes from `vocab/declarations`; each row is a compact strand
  summary plus `:undeclared-attrs`, the sorted vector of offending attribute
  keys in their string wire form. Read-only — it surfaces strays, never blocks a
  write.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/carder/src/skein/spools/carder.clj#L200-L222">Source</a></sub></p>
