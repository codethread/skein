
-----
# <a name="skein.spools.bobbin">skein.spools.bobbin</a>


Assemble compact, self-contained context packs for delegated strand work.

  Bobbin is a reference spool that composes public graph and weaver helper
  surfaces. It projects the strand graph around one target strand into a
  JSON-compatible bundle and renders that bundle as deterministic prompt text.

  Sections it did not invent are read through the primitive that owns them:
  `skein.api.notes.alpha` orders the notes section, `skein.api.spool.alpha`
  projects every strand row, and `skein.spools.workflow` resolves the active
  workflow root. The bundle's own vocabulary — the section shapes, `pack`, and
  `render` — is bobbin's.




## <a name="skein.spools.bobbin/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Return bobbin installation metadata for trusted registration by name.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bobbin/src/skein/spools/bobbin.clj#L228-L240">Source</a></sub></p>

## <a name="skein.spools.bobbin/pack">`pack`</a>
``` clojure
(pack strand-id)
(pack strand-id opts)
```
Function.

Return a JSON-compatible bobbin context bundle for strand-id.

  `opts` may include `:include`, a set drawn from `:strand`, `:blockers`,
  `:dependents`, `:parents`, `:children`, `:notes`, and `:workflow`. Unknown
  sections fail loudly with the allowed set in ex-data. Missing strand ids fail
  loudly. Every edge returned by a section references only strands summarized in
  that same section.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bobbin/src/skein/spools/bobbin.clj#L163-L190">Source</a></sub></p>

## <a name="skein.spools.bobbin/render">`render`</a>
``` clojure
(render bundle)
```
Function.

Render a bobbin bundle as deterministic prompt text.

  Output uses stable section order and sorted related strands. The target strand
  is one compact line plus its `body` attribute in full when present.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/bobbin/src/skein/spools/bobbin.clj#L206-L226">Source</a></sub></p>
