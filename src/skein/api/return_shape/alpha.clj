(ns skein.api.return-shape.alpha
  "Pure declarations and checks for weaver operation return values.

  Return shapes are finite EDN data. They describe JSON scalars, closed maps,
  and homogeneous sequential collections. Registry routing may wrap shapes in
  `:subcommands` or `:stream` declarations; this namespace has no registry or
  runtime state. Failures are `ex-info` whose data carries the published
  marker `:skein.api.return-shape.alpha/error`, a `:reason` keyword, and
  shape-local context such as `:path`."
  (:require [skein.api.return-shape.internal :as internal]))

(defn validate!
  "Validate a return declaration and return it unchanged.

  Accepts a concrete shape, a `{:stream ...}` return case, or a
  `{:subcommands ...}` routed declaration. Throws structured `ex-info` for
  malformed or unsupported declarations."
  [declaration]
  (internal/validate-declaration! declaration))

(defn explain
  "Render a return declaration as JSON-safe data.

  Shape and field names become strings; routing maps retain their structure so
  callers can render flat, subcommand, and stream declarations uniformly."
  [declaration]
  (internal/explain-declaration declaration))

(defn check!
  "Check `value` against one concrete return shape and return it unchanged.

  Throws structured `ex-info` on mismatch with `:path`, `:expected`, and
  `:actual`. Routing declarations must be selected by the caller first."
  [shape value]
  (internal/check-value! shape value))
