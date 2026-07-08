(ns skein.spools.format
  "Authoring helpers for `|`-margin doc blocks shared across spools.

  Reference spools carry a lot of prose guidance as data (op payloads, `about`
  surfaces). Writing that prose as `(str ...)` fragments is noisy and leaks
  trailing-space bookkeeping. These helpers let a block be authored as one
  readable `|`-margin string: the bar marks column 0, plain newlines soft-wrap,
  a bare `|` line separates items, and indentation past the bar is preserved
  verbatim for command samples and other intentional layout. The names here are
  the documented spool-authoring surface over `skein.api.format.alpha`."
  (:require [skein.api.format.alpha :as format-alpha]))

(defn fill
  "Reflow a `|`-margin doc block into a vector of item strings.

  Each line's content is whatever follows its first `|`, so the bar marks
  column 0 and the enclosing form may be indented freely. A bare `|` line
  separates items. Within an item, flush-left lines are prose soft-wrapped into
  a single line; if any line is indented past the bar the whole item is kept
  verbatim, so command samples and other intentional layout survive. Prose is
  the zero-marker default; indentation is what supplies structure. Throws when
  no line carries a bar — a bar-less block is an authoring error."
  [block]
  (format-alpha/fill block))

(defn reflow
  "Soft-wrap a single-paragraph `|`-margin block into one string.

  The single-item companion to `fill` for a lone prose value; item and verbatim
  semantics do not apply — every barred line is trimmed and space-joined.
  Throws when no line carries a bar, like `fill`."
  [block]
  (format-alpha/reflow block))
