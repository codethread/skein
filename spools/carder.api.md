# Table of contents
-  [`skein.spools.carder`](#skein.spools.carder)  - Read-only graph hygiene reports for long-lived Skein strand graphs.
    -  [`blocked-by-failure`](#skein.spools.carder/blocked-by-failure) - Return active strands blocked by active failed or exhausted depends-on targets.
    -  [`default-days`](#skein.spools.carder/default-days) - Default age threshold, in days, used by <code>stale</code>.
    -  [`install!`](#skein.spools.carder/install!) - Return carder installation metadata for trusted registration by name.
    -  [`orphans`](#skein.spools.carder/orphans) - Return active strands with no incident edges and no <code>workflow/*</code> attributes.
    -  [`report`](#skein.spools.carder/report) - Return a JSON-compatible aggregate graph hygiene report.
    -  [`stale`](#skein.spools.carder/stale) - Return active strands older than the configured age threshold.

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

  A blocker is any active `depends-on` target whose `shuttle/phase` attribute is
  the string `failed` or `exhausted`. Rows include the blocked strand summary and
  a `:blockers` vector with compact blocker details.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/carder.clj#L157-L189">Source</a></sub></p>

## <a name="skein.spools.carder/default-days">`default-days`</a>




Default age threshold, in days, used by `stale`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/carder.clj#L18-L20">Source</a></sub></p>

## <a name="skein.spools.carder/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Return carder installation metadata for trusted registration by name.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/carder.clj#L209-L219">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/carder.clj#L125-L142">Source</a></sub></p>

## <a name="skein.spools.carder/report">`report`</a>
``` clojure
(report)
(report opts)
```
Function.

Return a JSON-compatible aggregate graph hygiene report.

  Options are passed to all sections. The result includes each section's rows and
  count under `:stale`, `:orphans`, and `:blocked-by-failure`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/carder.clj#L191-L207">Source</a></sub></p>

## <a name="skein.spools.carder/stale">`stale`</a>
``` clojure
(stale)
(stale opts)
```
Function.

Return active strands older than the configured age threshold.

  Options: `:days` positive integer threshold (default `default-days`) and
  `:include-plumbing? true` to include workflow plumbing and shuttle run records.
  Each row is a compact strand summary plus `:days-stale`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/carder.clj#L87-L105">Source</a></sub></p>
