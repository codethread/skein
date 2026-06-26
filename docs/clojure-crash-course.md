# Clojure crash course for Skein users

This is a tiny Clojure primer for using `strand weaver repl`.

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
:active
:ephemeral
:inactive_at
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
(update! s {:active false})
```

Inactive persistent strands stay in the store and receive `:inactive_at`.

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
     (filter :active)
     vec)
```

That means: list strands, keep the ones owned by `ct`, keep active rows, and return a vector.

## Require helper namespaces

```clojure
(require '[skein.libs.alpha :as libs]
         '[skein.graph.alpha :as graph]
         '[skein.views.alpha :as views])
```

The aliases let you call functions like `libs/sync!`, `graph/strands-by-ids`, and `views/view!`.

## Quick reference

```clojure
(strand! "Title")
(strand! "Title" {:owner "ct"})
(strand! "Scratch" {} {:ephemeral true})
(update! strand-id {:active false})
(strand strand-id)
(strands)
(ready)
```
