
-----
# <a name="skein.api.return-shape.alpha">skein.api.return-shape.alpha</a>


Pure declarations and checks for weaver operation return values.

  Return shapes are finite EDN data. They describe JSON scalars, closed maps,
  and homogeneous sequential collections. Registry routing may wrap shapes in
  `:subcommands` or `:stream` declarations; this namespace has no registry or
  runtime state.




## <a name="skein.api.return-shape.alpha/check!">`check!`</a>
``` clojure
(check! shape value)
```
Function.

Check `value` against one concrete return shape and return it unchanged.

  Throws structured `ex-info` on mismatch with `:path`, `:expected`, and
  `:actual`. Routing declarations must be selected by the caller first.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/return_shape/alpha.clj#L255-L267">Source</a></sub></p>

## <a name="skein.api.return-shape.alpha/explain">`explain`</a>
``` clojure
(explain declaration)
```
Function.

Render a return declaration as JSON-safe data.

  Shape and field names become strings; routing maps retain their structure so
  callers can render flat, subcommand, and stream declarations uniformly.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/return_shape/alpha.clj#L174-L185">Source</a></sub></p>

## <a name="skein.api.return-shape.alpha/validate!">`validate!`</a>
``` clojure
(validate! declaration)
```
Function.

Validate a return declaration and return it unchanged.

  Accepts a concrete shape, a `{:stream ...}` return case, or a
  `{:subcommands ...}` routed declaration. Throws structured `ex-info` for
  malformed or unsupported declarations.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/return_shape/alpha.clj#L137-L152">Source</a></sub></p>
