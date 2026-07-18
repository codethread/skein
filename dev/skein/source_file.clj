(ns skein.source-file
  "Render Clojure forms as source-file text for tests and smoke suites.

  Workspace startup files (init.clj, generated spool and namespace sources)
  exercised by tests are authored as quoted forms, not hand-concatenated
  strings, so the reader checks their syntax when the authoring file compiles
  and the conventions gate can resolve requires embedded in quoted data.
  Keep require forms plain-quoted: syntax-quote reads into seq/concat calls
  the gate's quoted-data walker cannot see. Use syntax-quote only on forms
  that interpolate runtime values, escaping local binding names as ~'sym."
  (:require [clojure.string :as str]))

(defn render-forms
  "Return `forms` printed as top-level source text, one form per line.

  Binds *print-length* and *print-level* to nil so large literals are never
  silently truncated into unreadable source."
  [forms]
  (binding [*print-length* nil
            *print-level* nil]
    (str (str/join "\n" (map pr-str forms)) "\n")))

(defn spit-forms!
  "Write `forms` to `file` as top-level source text via `render-forms`."
  [file forms]
  (spit file (render-forms forms)))
