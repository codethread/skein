
-----
# <a name="skein.spools.ephemeral">skein.spools.ephemeral</a>


Userland helpers for temporary, parent-owned work strands.

  This namespace is intentionally authorable example code: it composes the
  documented explicit-runtime weaver and graph helper surfaces and owns no
  privileged loader/config/runtime implementation.




## <a name="skein.spools.ephemeral/burn-ephemeral!">`burn-ephemeral!`</a>
``` clojure
(burn-ephemeral!)
```
Function.

Burn all active userland ephemeral strands.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/ephemeral.clj#L37-L43">Source</a></sub></p>

## <a name="skein.spools.ephemeral/ephemeral!">`ephemeral!`</a>
``` clojure
(ephemeral! parent-id title)
(ephemeral! parent-id title attributes)
```
Function.

Create a userland ephemeral strand under parent-id.

  This uses userland attributes, not core :ephemeral lifecycle. The strand is
  persistent with `:attr ephemeral true` and a parent-of edge from the parent.
  It can be burned later with `burn-ephemeral!`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/ephemeral.clj#L11-L24">Source</a></sub></p>

## <a name="skein.spools.ephemeral/ephemeral-ids">`ephemeral-ids`</a>
``` clojure
(ephemeral-ids)
(ephemeral-ids _opts)
```
Function.

Return active userland ephemeral strand ids.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/ephemeral.clj#L30-L35">Source</a></sub></p>

## <a name="skein.spools.ephemeral/ephemeral-query">`ephemeral-query`</a>




Query form selecting active userland ephemeral strands.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/ephemeral.clj#L26-L28">Source</a></sub></p>

## <a name="skein.spools.ephemeral/install!">`install!`</a>
``` clojure
(install!)
```
Function.

Install ephemeral strand helpers into the active weaver.
<p><sub><a href="https://github.com/codethread/skein/blob/main/spools/src/skein/spools/ephemeral.clj#L45-L52">Source</a></sub></p>
