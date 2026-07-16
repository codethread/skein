
-----
# <a name="skein.spools.ephemeral">skein.spools.ephemeral</a>


Helpers for temporary, parent-owned work strands.

  This namespace is intentionally authorable example code: it composes the
  documented weaver, graph, and current helper surfaces and owns no privileged
  loader/config/runtime implementation.




## <a name="skein.spools.ephemeral/add">`add`</a>
``` clojure
(add parent-id title)
(add parent-id title attributes)
```
Function.

Create an ephemeral strand under parent-id.

  The strand is persistent, carries `:attr ephemeral/entry "true"`, and hangs
  off a parent-of edge from the parent. It can be burned later with
  `burn-all!`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/ephemeral/src/skein/spools/ephemeral.clj#L12-L25">Source</a></sub></p>

## <a name="skein.spools.ephemeral/burn-all!">`burn-all!`</a>
``` clojure
(burn-all!)
```
Function.

Burn all active ephemeral strands.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/ephemeral/src/skein/spools/ephemeral.clj#L36-L42">Source</a></sub></p>

## <a name="skein.spools.ephemeral/ids">`ids`</a>
``` clojure
(ids)
```
Function.

Return active ephemeral strand ids.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/ephemeral/src/skein/spools/ephemeral.clj#L31-L34">Source</a></sub></p>

## <a name="skein.spools.ephemeral/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install ephemeral strand helpers into the active weaver.

  Declares the `ephemeral` attribute namespace this spool owns and returns the
  installation metadata: the marker attribute plus the `add`/`burn` fns as a
  symbol map.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/ephemeral/src/skein/spools/ephemeral.clj#L53-L65">Source</a></sub></p>

## <a name="skein.spools.ephemeral/query">`query`</a>




Query form selecting active ephemeral strands.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/ephemeral/src/skein/spools/ephemeral.clj#L27-L29">Source</a></sub></p>
