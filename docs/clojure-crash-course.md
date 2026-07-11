# Clojure crash course for Skein users

This is a tiny Clojure primer for using `mill weaver repl`.

## Calls

```clojure
(strand! "Write docs")
```

means: call `strand!` with the string `"Write docs"`.

## Names

Common helper names:

```clojure
strand!
strand
strands
ready
update!
defquery!
```

Common row keys:

```clojure
:id
:title
:state
:attributes
```

Keywords such as `:owner` or `:example_outcome` inside `:attributes` are user-chosen.

## Bind a value

```clojure
(def s (:id (strand! "My first strand")))
```

That creates a strand, extracts its `:id`, and stores the id in `s`.

## Inspect and update

```clojure
(strand s)
(update! s {:state "closed"})
```

Closed strands stay in the store with `:state "closed"`. Use `burn!` for explicit deletion.

## Collections

```clojure
(strands)
(ready)
```

`ready` returns active strands whose direct `depends-on` targets are not active.

## Anonymous functions

```clojure
#(= "ct" (get-in % [:attributes :owner]))
```

means: for one strand row, check whether its user attribute `owner` is `"ct"`.

## Threading

```clojure
(->> (strands)
     (filter #(= "ct" (get-in % [:attributes :owner])))
     (filter #(= "active" (:state %)))
     vec)
```

That means: list strands, keep the ones owned by `ct`, keep active-state rows, and return a vector.

## def, defn, defn-, defonce, private

- `def` binds a name to a value (a top-level **var**), evaluated once at load time.
- `defn` is `def` + a function value: `(defn f [x] ...)`.
- `defn-` / `^:private` on a `def` marks the var **private** to its namespace — a visibility hint, not real security. From another namespace, `ns/name` is blocked, but `@#'ns/name` (deref the Var object) still reaches it.
- `defonce` binds only if the var isn't already bound; re-evaluating the form (REPL reload, hot reload) is a no-op. Used for state that must survive namespace reload, e.g. an `atom` holding runtime data.
- "Var" = the named storage cell `def`/`defn`/`defonce` create at the namespace top level (as opposed to a local `let`/fn-arg binding, which has no Var behind it).

## Namespaces

A namespace groups related names, like a module or file. `config.clj`'s `(ns config ...)` declares the `config` namespace; everything `def`/`defn`'d in that file lives under it. From elsewhere you reach it with a qualified symbol, `config/agent-plan`, or bring names in unqualified with `require`.

## Require helper namespaces

```clojure
(require '[skein.api.runtime.alpha :as runtime]
         '[skein.api.graph.alpha :as graph]
         '[skein.api.views.alpha :as views])
```

These are privileged built-in helper namespaces shipped with Skein. The aliases let you call functions like `runtime/sync!`, `graph/strands-by-ids`, and `views/view!`.

## Quick reference

```clojure
(strand! "Title")
(strand! "Title" {:owner "ct"})
(strand! "Scratch" {:temporary "true"})
(update! strand-id {:state "closed"})
(burn! strand-id)
(strand strand-id)
(strands)
(ready)
```

## Talking about the code

Terms to use when discussing Clojure with an agent (or another dev), so requests are unambiguous:

- **function** / **fn** — not "method". `strand!`, `ref-symbol`, `plan-strand` are all functions.
- **var** — a top-level name created by `def`/`defn`/`defonce`, e.g. "`devflow-workflows` is a var" or "check the `ref-symbol` fn". Say "the `X` var" only when `X` isn't a function.
- **atom** — mutable state held in a var, e.g. "`devflow-summary-notifications` is a defonce atom"; "reset the atom" / "check the atom".
- **namespace** — a named group of vars, e.g. `config`, `skein.api.runtime.alpha`.
- **keyword** — a `:like-this` token, usually a map key.
- **symbol** — a bare name like `foo` or `config/foo`, used to refer to a var or namespace.
- **macro** — code that generates code at compile time, e.g. `defn` itself is a macro (expands to a `def` of a function).

Prefer naming the exact var/fn over vague phrasing: "check `active-devflow-plan-roots`" rather than "check the def/function around devflow roots".

## Further reading

- [Learn X in Y minutes: Clojure](https://learnxinyminutes.com/clojure/)
- [Learn X in Y minutes: Clojure Macros](https://learnxinyminutes.com/clojure-macros/)
