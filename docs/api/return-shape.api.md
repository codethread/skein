
-----
# <a name="skein.api.return-shape.alpha">skein.api.return-shape.alpha</a>


Pure declarations and checks for weaver operation return values.

  Return shapes are finite EDN data: JSON scalars, `[:nullable <scalar>]`,
  closed `{:type :map ...}` declarations, and homogeneous
  `{:type :collection ...}` sequences. Registry routing may wrap a shape in
  `:subcommands` or `:stream` declarations; this namespace has no registry
  or runtime state. Failures are `ex-info` whose data carries the published
  marker `:skein.api.return-shape.alpha/error`, a `:reason` keyword, and
  shape-local context such as `:path`.




## <a name="skein.api.return-shape.alpha/check!">`check!`</a>
``` clojure
(check! shape value)
```
Function.

Check `value` against one concrete return shape and return it unchanged.

  Throws structured `ex-info` on mismatch with `:path`, `:expected`, and
  `:actual`. Routing declarations must be selected by the caller first.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/return_shape/alpha.clj#L50-L62">Source</a></sub></p>

## <a name="skein.api.return-shape.alpha/explain">`explain`</a>
``` clojure
(explain declaration)
```
Function.

Render a return declaration as JSON-safe data.

  Shape and field names become strings; routing maps retain their structure
  so callers can render flat, subcommand, and stream declarations uniformly.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/return_shape/alpha.clj#L36-L48">Source</a></sub></p>

## <a name="skein.api.return-shape.alpha/validate!">`validate!`</a>
``` clojure
(validate! declaration)
```
Function.

Validate a return declaration and return it unchanged.

  Accepts a concrete shape, a `{:stream ...}` return case, or a
  `{:subcommands ...}` routed declaration. Throws structured `ex-info` for
  malformed or unsupported declarations.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/return_shape/alpha.clj#L19-L34">Source</a></sub></p>
