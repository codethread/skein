
-----
# <a name="skein.api.notes.alpha">skein.api.notes.alpha</a>


Explicit-runtime cross-spool note primitive: the blessed writer and reader
  for strand memory.

  A note is an immutable, born-closed strand (memory, not work) linked to its
  target by an outgoing `notes` annotation edge. `note!` writes that strand and
  edge; `notes` walks the incoming `notes` edges to a target and projects each
  note in `note/at` order. The link is the edge alone — no `note/for` attribute
  — so a target's deletion cascades the edge and the note becomes unreachable
  through the read with no dangling pointer, and every writer that uses this
  primitive is visible to every reader regardless of the decorating attributes a
  caller layers on its notes.

  Note content is immutable by storage enforcement, not convention: `note/text`
  and `note/at` are declared write-once keys (SPEC-001.P4), so once a note is
  written its content and timestamp cannot be rewritten, deleted, or archived on
  any mutation path. Only the caller's decorating attributes stay mutable.

  Callers own runtime selection and pass the target weaver runtime as the first
  argument, per the blessed-namespace convention.

  Beside the primitive lives a writer *value* family: `writer` carries a target
  (or a thunk resolving one), a default decoration, and an author; `write!`
  appends through `note!` merging per-call decoration over the default;
  `writer-ref` freezes the writer to a plain-data ref that ships into
  subprocesses; and `writer-ref->prompt` is the single renderer of the
  note-writing CLI instruction fragment. There is deliberately no `ref->writer`
  — the constructor reconstructs from a ref.

  The writer and writer-ref shapes are specced (`::writer`, `::writer-ref`) and
  every entry validates through one boundary (`check-shape!`), so the family has
  a single fail-loud, field-named contract instead of hand-rolled checks.




## <a name="skein.api.notes.alpha/note!">`note!`</a>
``` clojure
(note! runtime target-id text {:keys [by round], :as opts})
```
Function.

Append an immutable note strand to `target-id`'s memory and return its id.

  The note is born closed, carries `note/text`, a sub-second `note/at`
  timestamp, optional `note/by`/`note/round`, and any caller-supplied decorating
  attrs, and links to the target by an outgoing `notes` edge — never a
  `note/for` attribute. `note/text` and `note/at` are storage-enforced
  write-once (SPEC-001.P4): the birth write here is legal, but no later mutation
  path can rewrite, delete, or archive them. Fails loudly on blank text, a
  missing target, or a non-integer `:round` (the `note/round` contract is
  single-typed).
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/notes/alpha.clj#L70-L99">Source</a></sub></p>

## <a name="skein.api.notes.alpha/notes">`notes`</a>
``` clojure
(notes runtime target-id {:keys [round]})
```
Function.

Return `target-id`'s notes in `note/at` order, optionally one `:round`.

  Walks the incoming `notes` edges to the target, so it returns notes from every
  writer that used the primitive regardless of their decorating attrs. Projects
  each note as `{:id :note :at}` plus `:by`/`:round` when present. `:round` must
  be an integer (fails loudly otherwise); ordering parses `note/at` so mixed
  fractional-precision timestamps still sort chronologically.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/notes/alpha.clj#L101-L120">Source</a></sub></p>

## <a name="skein.api.notes.alpha/write!">`write!`</a>
``` clojure
(write! w text {:keys [decoration by round]})
```
Function.

Append a note through `w`, returning `note!`'s `{:id :target}`.

  Per-call `:decoration` shallow-merges per key OVER the writer default; per-call
  `:by` overrides the writer default; `:round` passes through. A thunk target
  resolves at each call; a missing or deleted target fails loudly with the
  primitive's "Note target strand not found".
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/notes/alpha.clj#L185-L201">Source</a></sub></p>

## <a name="skein.api.notes.alpha/writer">`writer`</a>
``` clojure
(writer runtime target-or-thunk {:keys [decoration by]})
```
Function.

Return a writer value bound to `runtime`, wrapping the `note!` primitive.

  `target-or-thunk` is a strand-id string or a 0-arg fn returning one — the
  target may not exist at construction time, so a thunk resolves lazily at each
  `write!`/`writer-ref`. `:decoration` is a map of string attr-key → string
  value defaulted onto every write; `:by` is the default author. Validates these
  shapes and fails loudly naming the offending field. `note!`/`notes` are
  untouched; the writer wraps the low-level primitive.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/notes/alpha.clj#L166-L183">Source</a></sub></p>

## <a name="skein.api.notes.alpha/writer-ref">`writer-ref`</a>
``` clojure
(writer-ref w)
```
Function.

Freeze `w` to the plain-data ref `{:target :decoration :by}`.

  A thunk target resolves exactly once, here, and the ref freezes that id — refs
  ship into subprocesses, so late rebinding across a process boundary is out of
  scope. The constructor reconstructs a writer from this ref, so no `ref->writer`
  sugar ships.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/notes/alpha.clj#L203-L213">Source</a></sub></p>

## <a name="skein.api.notes.alpha/writer-ref->prompt">`writer-ref->prompt`</a>
``` clojure
(writer-ref->prompt ref)
```
Function.

Render `ref` as the note-writing CLI instruction fragment.

  This is the single renderer of the write fragment
  `agent note <target> "<text>" --by <author> --attr k=v …` — `<text>` stays a
  placeholder the agent fills in. Validates the ref shape and fails loudly naming
  the offending field; a malformed ref never renders silently. Renders only the
  write instruction — no read/`agent notes` string.
<p><sub><a href="https://github.com/codethread/skein/blob/main/src/skein/api/notes/alpha.clj#L215-L229">Source</a></sub></p>
