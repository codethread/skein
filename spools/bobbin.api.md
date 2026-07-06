# Table of contents
-  [`skein.spools.bobbin`](#skein.spools.bobbin)  - Assemble compact, self-contained context packs for delegated strand work.
    -  [`install!`](#skein.spools.bobbin/install!) - Return bobbin installation metadata for trusted registration by name.
    -  [`pack`](#skein.spools.bobbin/pack) - Return a JSON-compatible bobbin context bundle for strand-id.
    -  [`render`](#skein.spools.bobbin/render) - Render a bobbin bundle as deterministic prompt text.

-----
# <a name="skein.spools.bobbin">skein.spools.bobbin</a>


Assemble compact, self-contained context packs for delegated strand work.

  Bobbin is a reference spool that composes explicit-runtime public graph and
  weaver helper surfaces. It projects the strand graph around one target strand
  into a JSON-compatible bundle and renders that bundle as deterministic prompt
  text.




## <a name="skein.spools.bobbin/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Return bobbin installation metadata for trusted registration by name.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/bobbin.clj#L177-L189">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/bobbin.clj#L112-L139">Source</a></sub></p>

## <a name="skein.spools.bobbin/render">`render`</a>
``` clojure
(render bundle)
```
Function.

Render a bobbin bundle as deterministic prompt text.

  Output uses stable section order and sorted related strands. The target strand
  is one compact line plus its `body` attribute in full when present.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/bobbin.clj#L155-L175">Source</a></sub></p>
