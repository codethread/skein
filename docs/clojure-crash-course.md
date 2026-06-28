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
(strand! "Scratch" {:temporary "true"})
(update! strand-id {:state "closed"})
(burn! strand-id)
(strand strand-id)
(strands)
(ready)
```
