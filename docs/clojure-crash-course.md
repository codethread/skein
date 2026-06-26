# Tiny Clojure crash course for Atom users

Atom is designed primarily for coding agents. Agents can read the specs and use the Clojure REPL naturally. This page is for humans who want just enough Clojure vocabulary to understand what is happening.

## Forms

Clojure code is written as forms:

```clojure
(function arg1 arg2)
```

So this:

```clojure
(task! "Write docs")
```

means: call `task!` with the string `"Write docs"`.

## Strings, symbols, and keywords

Strings use quotes:

```clojure
"ct"
"Write docs"
```

Symbols are names:

```clojure
task!
query
mine
```

Keywords start with `:` and often act like map keys:

```clojure
:id
:title
:status
:owner
```

Keywords can also be used as functions over maps:

```clojure
(:id {:id "abc" :title "Demo"})
;; => "abc"
```

## Maps and vectors

Maps use `{}`:

```clojure
{:owner "ct" :priority "high"}
```

Vectors use `[]`:

```clojure
[:= [:attr :owner] "ct"]
```

Atom's query DSL uses vectors because queries are data.

## Quoting names

A leading quote means “use this symbol as data; do not resolve it as a variable.”

```clojure
(query 'mine)
```

This asks Atom to run the named query called `mine`.

Without the quote:

```clojure
(query mine)
```

Clojure looks for a variable named `mine`, which may fail with “Unable to resolve symbol”.

## Defining temporary REPL names

`def` stores a value in the current REPL namespace:

```clojure
(def t (:id (task! "My first task")))
```

That creates a task, extracts its `:id`, and stores the id in `t`.

Then you can use:

```clojure
(task t)
(update! t {:status "done"})
```

## Anonymous functions

`#(...)` is a short anonymous function. `%` means “the current item”.

```clojure
#(= "done" (:status %))
```

Means: for one task row, check whether its status is `"done"`.

## Threading with `->>`

`->>` makes step-by-step data transformations easier to read. It passes the previous result as the last argument to the next form.

```clojure
(->> (query 'mine)
     (remove #(= "done" (:status %)))
     vec)
```

Read it as:

1. run `(query 'mine)`
2. remove tasks whose status is `"done"`
3. turn the result into a vector

## Requiring helper namespaces

Some helpers live in namespaces. In the Atom REPL, `libs` is preloaded for runtime-library workspace helpers:

```clojure
(libs/approved)
(libs/syncs)
(libs/uses)
```

In scripts or `init.clj`, require it explicitly:

```clojure
(require '[atom.libs.alpha :as libs])
```

## Common Atom REPL helpers

```clojure
(init!)
(task! "Title")
(task! "Title" {:owner "ct"})
(update! task-id {:status "done"})
(task task-id)
(tasks)
(ready)
(defquery! 'mine [:= [:attr :owner] "ct"])
(query 'mine)
```

The REPL is intentionally powerful because trusted humans and agents can compose these primitives directly.
