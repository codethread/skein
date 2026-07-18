(ns skein.api.format.alpha-test
  "Behavior coverage for the blessed `|`-margin doc-block contract.

  `fill` and `reflow` back every prose-as-data surface in the tree, so the
  v1 promise is pinned through the blessed namespace rather than the core
  implementation: item splitting on a bare `|`, soft-wrap of flush-left
  prose, verbatim preservation of indented layout, indentation freedom for
  the enclosing form, and the loud bar-less failure."
  (:require [clojure.test :refer [deftest is]]
            [skein.api.format.alpha :as fmt]))

(deftest reflow-soft-wraps-one-paragraph
  (is (= "one paragraph of prose joined into a single line"
         (fmt/reflow "|one paragraph of prose
                      |joined into a single line"))))

(deftest reflow-ignores-enclosing-indentation-and-blank-bars
  (is (= "prose survives margins" (fmt/reflow "   |prose
                                               |
                                               |survives margins"))))

(deftest fill-splits-items-on-bare-bar
  (is (= ["first item soft wrapped" "second item"]
         (fmt/fill "|first item
                    |soft wrapped
                    |
                    |second item"))))

(deftest fill-keeps-indented-items-verbatim
  (is (= ["Run it like this:" "  strand add \"title\"\n  strand ready"]
         (fmt/fill "|Run it like this:
                    |
                    |  strand add \"title\"
                    |  strand ready"))))

(deftest bar-less-blocks-fail-loudly
  (is (thrown? clojure.lang.ExceptionInfo (fmt/reflow "no bars here")))
  (is (thrown? clojure.lang.ExceptionInfo (fmt/fill "no bars here"))))
