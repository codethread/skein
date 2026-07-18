
-----
# <a name="skein.api.patterns.alpha">skein.api.patterns.alpha</a>


Explicit-runtime API for registering, inspecting, and invoking weave patterns.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument. This namespace owns pattern validation, function resolution, input
  spec validation and caller guidance, and the transactional create-only batch a
  weave produces. The SQL batch engine lives in `skein.core.db`; the shared
  lifecycle and dispatch plumbing in `skein.core.weaver.*`.




## <a name="skein.api.patterns.alpha/explain">`explain`</a>
``` clojure
(explain runtime pattern-name)
```
Function.

Describe a registered weave pattern and its input contract in `runtime`.

  Missing patterns or unregistered input specs fail loudly.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/patterns/alpha.clj#L106-L119">Source</a></sub></p>

## <a name="skein.api.patterns.alpha/pattern">`pattern`</a>
``` clojure
(pattern & args)
```
Function.

Renamed to resolve-pattern (card d6xgt); this alias is removed before the v1 stamp.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/patterns/alpha.clj#L70-L73">Source</a></sub></p>

## <a name="skein.api.patterns.alpha/patterns">`patterns`</a>
``` clojure
(patterns runtime)
```
Function.

Return registered weave pattern metadata from `runtime`, ordered by name.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/patterns/alpha.clj#L54-L57">Source</a></sub></p>

## <a name="skein.api.patterns.alpha/register-pattern!">`register-pattern!`</a>
``` clojure
(register-pattern! runtime pattern-name fn-sym input-spec)
(register-pattern! runtime pattern-name doc fn-sym input-spec)
```
Function.

Register a trusted weaver pattern handler and input spec in `runtime`.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/patterns/alpha.clj#L43-L52">Source</a></sub></p>

## <a name="skein.api.patterns.alpha/resolve-pattern">`resolve-pattern`</a>
``` clojure
(resolve-pattern runtime pattern-name)
```
Function.

Return the registered weave pattern for a simple symbol or keyword name.

  Missing patterns fail loudly.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/patterns/alpha.clj#L59-L68">Source</a></sub></p>

## <a name="skein.api.patterns.alpha/weave!">`weave!`</a>
``` clojure
(weave! runtime pattern-name input)
(weave! runtime pattern-name input req-ctx)
```
Function.

Validate pattern input, invoke the pattern, and apply its create-only batch.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/patterns/alpha.clj#L191-L228">Source</a></sub></p>
