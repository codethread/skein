(ns skein.notes-test
  "Tests for the skein.api.notes.alpha cross-spool note primitive: the writer
  links notes by a `notes` edge (never `note/for`) and the reader walks that
  edge, ordered by the sub-second `note/at` stamp."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.api.graph.alpha :as graph]
            [skein.api.notes.alpha :as notes]
            [skein.api.weaver.alpha :as weaver]
            [skein.spools.test-support :refer [with-runtime]]
            [skein.test.alpha :as test-alpha])
  (:import (java.time Instant)))

(defn- fixed-clock!
  "Pin `rt`'s clock to `clock` so `note/at` is deterministic; reset `clock` to
  advance test time before each `note!`."
  [rt clock]
  (test-alpha/set-clock! rt (fn [] @clock)))

(defn- target! [rt]
  (:id (weaver/add rt {:title "target" :state "active"})))

(deftest note!-writes-content-attrs-and-edge-not-note-for
  (with-runtime
    (fn [rt _config-dir]
      (let [clock (atom (Instant/parse "2026-01-01T00:00:00.500Z"))
            _ (fixed-clock! rt clock)
            target (target! rt)
            {note-id :id target-out :target} (notes/note! rt target "remember this" {:by "alice" :round 3})
            note (weaver/show rt note-id)]
        (is (= target target-out))
        (testing "content attributes land"
          (is (= "remember this" (get-in note [:attributes :note/text])))
          (is (= "2026-01-01T00:00:00.500Z" (get-in note [:attributes :note/at])))
          (is (= "alice" (get-in note [:attributes :note/by])))
          (is (= 3 (get-in note [:attributes :note/round])))
          (is (= "closed" (:state note))))
        (testing "the link is the notes edge, never note/for"
          (is (nil? (get-in note [:attributes :note/for])))
          (is (= [note-id]
                 (mapv :from_strand_id (graph/incoming-edges rt [target] "notes")))))))))

(deftest note!-rejects-blank-text-and-missing-target
  (with-runtime
    (fn [rt _config-dir]
      (let [target (target! rt)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank"
                              (notes/note! rt target "   " {})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                              (notes/note! rt "no-such-strand" "hi" {})))))))

(deftest notes-orders-by-note-at-across-writers-and-filters-by-round
  (with-runtime
    (fn [rt _config-dir]
      (let [clock (atom nil)
            _ (fixed-clock! rt clock)
            target (target! rt)]
        ;; Created out of note/at order and by different writers with divergent
        ;; decorating attrs: creation/id order (A, B, C) disagrees with note/at
        ;; order (B, C, A), so a green read proves the sort keys on note/at and
        ;; the walk ignores decorating attrs rather than filtering on note/for.
        (reset! clock (Instant/parse "2026-01-01T00:00:00.300Z"))
        (notes/note! rt target "third by at" {:by "alice"})
        (reset! clock (Instant/parse "2026-01-01T00:00:00.100Z"))
        (notes/note! rt target "first by at" {:by "bob" :round 2})
        (reset! clock (Instant/parse "2026-01-01T00:00:00.200Z"))
        (notes/note! rt target "second by at" {:kanban/card "true"})
        (testing "every writer's note returns, ordered by note/at"
          (is (= ["first by at" "second by at" "third by at"]
                 (mapv :note (notes/notes rt target {}))))
          (is (= [{:note "first by at" :by "bob" :round 2}
                  {:note "second by at"}
                  {:note "third by at" :by "alice"}]
                 (mapv #(dissoc % :id :at) (notes/notes rt target {})))))
        (testing ":round filters to one writer's notes"
          (is (= ["first by at"] (mapv :note (notes/notes rt target {:round 2})))))))))

(deftest note-round-is-single-typed-and-ordering-is-chronological
  ;; regression (change-review-1a1d1cc7): a string round written through one
  ;; surface silently missed the other surface's int-round filter, and
  ;; lexicographic note/at comparison misordered mixed-precision timestamps
  ;; (Instant/toString drops trailing zero fraction digits).
  (with-runtime
    (fn [rt _config-dir]
      (let [clock (atom nil)
            _ (fixed-clock! rt clock)
            target (target! rt)]
        (testing "a non-integer round fails loudly on write and on read"
          (reset! clock (Instant/parse "2026-01-01T00:00:00.100Z"))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"integer"
                                (notes/note! rt target "typed" {:round "2"})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"integer"
                                (notes/notes rt target {:round "2"}))))
        (testing "mixed fractional precision still sorts chronologically"
          ;; 00:00:01Z stringifies with no fraction; lexicographically
          ;; "2026-01-01T00:00:01Z" > "2026-01-01T00:00:01.900Z" is false but
          ;; "...:01Z" vs "...:01.100Z": 'Z' (0x5A) > '.' (0x2E), so the
          ;; fraction-less earlier-written 01Z would sort AFTER 01.100Z only
          ;; chronologically-wrongly under string compare when it is earlier.
          (reset! clock (Instant/parse "2026-01-01T00:00:01.100Z"))
          (notes/note! rt target "later with fraction" {})
          (reset! clock (Instant/parse "2026-01-01T00:00:01Z"))
          (notes/note! rt target "earlier without fraction" {})
          (is (= ["earlier without fraction" "later with fraction"]
                 (mapv :note (notes/notes rt target {})))))))))

(deftest target-deletion-cascades-the-edge-leaving-no-dangling-read
  (with-runtime
    (fn [rt _config-dir]
      (let [target (target! rt)
            {note-id :id} (notes/note! rt target "outlives its target" {})]
        (is (= 1 (count (notes/notes rt target {}))))
        (graph/burn-by-id! rt target)
        (testing "the note strand survives but is unreachable through the read"
          (is (some? (weaver/show rt note-id)))
          (is (empty? (graph/incoming-edges rt [target] "notes")))
          (is (= [] (notes/notes rt target {}))))))))

(deftest writer-write!-merges-per-call-decoration-over-the-default
  (with-runtime
    (fn [rt _config-dir]
      (let [target (target! rt)
            w (notes/writer rt target {:decoration {"note/kind" "activity"
                                                    "kanban/card" "true"}
                                       :by "alice"})]
        (testing "the writer default decoration and author land on a bare write"
          (let [{note-id :id} (notes/write! w "default write" {})
                note (weaver/show rt note-id)]
            (is (= "activity" (get-in note [:attributes :note/kind])))
            (is (= "true" (get-in note [:attributes :kanban/card])))
            (is (= "alice" (get-in note [:attributes :note/by])))))
        (testing "per-call decoration merges per key over the default; :by/:round override"
          (let [{note-id :id} (notes/write! w "override write"
                                            {:decoration {"note/kind" "decision"}
                                             :by "bob" :round 4})
                note (weaver/show rt note-id)]
            ;; per-key merge: kanban/card survives from the default, note/kind is replaced
            (is (= "decision" (get-in note [:attributes :note/kind])))
            (is (= "true" (get-in note [:attributes :kanban/card])))
            (is (= "bob" (get-in note [:attributes :note/by])))
            (is (= 4 (get-in note [:attributes :note/round])))))))))

(deftest writer-construction-and-use-reject-malformed-shapes
  (with-runtime
    (fn [rt _config-dir]
      (testing "construction rejects a non-string/non-fn target naming :target"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"target"
                              (notes/writer rt :not-a-target {}))))
      (testing "construction rejects a non-map decoration naming :decoration"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"decoration"
                              (notes/writer rt "t" {:decoration ["not" "a" "map"]}))))
      (testing "construction rejects non-string decoration entries"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"decoration"
                              (notes/writer rt "t" {:decoration {"note/kind" 3}}))))
      (let [w (notes/writer rt (target! rt) {})]
        (testing "write! rejects a malformed per-call decoration naming :decoration"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"decoration"
                                (notes/write! w "x" {:decoration {:kw "v"}}))))))))

(deftest writer-thunk-resolution-fails-loudly-on-a-non-string-return
  (with-runtime
    (fn [rt _config-dir]
      ;; a thunk returning nil/keyword/number must fail at resolution naming the
      ;; bad return, in both write! and writer-ref, not later in note!/show.
      (doseq [bad [nil :kw 7]]
        (let [w (notes/writer rt (fn [] bad) {})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"resolve to a strand-id"
                                (notes/write! w "x" {})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"resolve to a strand-id"
                                (notes/writer-ref w))))))))

(deftest writer-write!-on-a-missing-thunk-target-fails-loudly
  (with-runtime
    (fn [rt _config-dir]
      ;; the thunk resolves to a well-formed but nonexistent id, so the primitive
      ;; not the resolver rejects it — proving write! composes over note!.
      (let [w (notes/writer rt (fn [] "no-such-strand") {})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                              (notes/write! w "x" {})))))))

(deftest writer-ref-resolves-a-thunk-exactly-once-and-freezes-the-id
  (with-runtime
    (fn [rt _config-dir]
      (let [target (target! rt)
            calls (atom 0)
            w (notes/writer rt (fn [] (swap! calls inc) target)
                            {:decoration {"note/kind" "summary"} :by "alice"})
            ref (notes/writer-ref w)]
        (is (= 1 @calls) "thunk resolves exactly once at ref time")
        (is (= {:target target
                :decoration {"note/kind" "summary"}
                :by "alice"}
               ref))))))

(deftest writer-ref->prompt-renders-only-the-write-fragment
  (with-runtime
    (fn [rt _config-dir]
      (let [target (target! rt)
            w (notes/writer rt target {:decoration {"note/kind" "decision"
                                                    "kanban/card" "true"}
                                       :by "alice"})
            fragment (notes/writer-ref->prompt (notes/writer-ref w))]
        (testing "the fragment is the write instruction with a text placeholder"
          (is (= (str "agent note " target
                      " \"<text>\" --by alice --attr kanban/card=true --attr note/kind=decision")
                 fragment)))
        (testing "no read/agent notes string leaks into the fragment"
          (is (not (str/includes? fragment "agent notes")))))
      (testing "a malformed ref fails loudly naming the offending field"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"target"
                              (notes/writer-ref->prompt {:decoration {} :by "x"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"decoration"
                              (notes/writer-ref->prompt {:target "t" :decoration [:bad]})))))))
