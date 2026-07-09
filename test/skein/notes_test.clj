(ns skein.notes-test
  "Tests for the skein.api.notes.alpha cross-spool note primitive: the writer
  links notes by a `notes` edge (never `note/for`) and the reader walks that
  edge, ordered by the sub-second `note/at` stamp."
  (:require [clojure.test :refer [deftest is testing]]
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
