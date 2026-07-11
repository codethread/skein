(ns skein.userland-test
  "Tests for the userland-only terse ergonomics module.

  Covers runtime resolution precedence (scope > bind! > ambient), loud failure
  when no runtime is resolvable, wrapper delegation against a disposable
  unpublished runtime, cross-talk isolation between two `with-runtime` scopes,
  and the structural guard that no `skein.*` source requires the module."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [skein.api.current.alpha :as current]
            [skein.core.weaver.config :as weaver-config]
            [skein.core.weaver.runtime :as weaver-runtime]
            [skein.core.db-test :as db-test]
            [skein.userland.alpha :as u]))

(defn- delete-tree! [file]
  (doseq [f (reverse (file-seq file))]
    (.delete f)))

(defn- temp-world []
  ;; Short base dir: the weaver socket lives at <state-dir>/weaver.sock and must
  ;; stay under the ~104-byte unix-domain-socket path limit on macOS.
  (let [root (io/file "/tmp/skein-userland" (str (System/nanoTime)))
        workspace (io/file root "c")]
    (.mkdirs workspace)
    (weaver-config/world (.getCanonicalPath workspace)
                         (.getCanonicalPath (io/file root "s"))
                         (.getCanonicalPath (io/file root "d")))))

(defn- start-runtime []
  (let [db-file (db-test/temp-db-file)
        world (temp-world)]
    {:rt (weaver-runtime/start! db-file {:world world :publish? false})
     :db-file db-file
     :world world}))

(defn- stop-runtime [{:keys [rt db-file world]}]
  (weaver-runtime/stop! rt)
  (db-test/delete-sqlite-family! db-file)
  (delete-tree! (.getParentFile (io/file (:config-dir world)))))

(defmacro with-runtimes
  "Bind `bindings` to fresh disposable unpublished runtimes for `body`.

  Runtimes are NOT bound as the ambient `*runtime*`, so resolution behavior is
  exercised only through this module's `bind!`/`with-runtime`/ambient fallbacks."
  [bindings & body]
  (let [pairs (partition 2 bindings)
        starts (mapv (fn [[sym _]] [(gensym) sym]) pairs)]
    `(let [~@(mapcat (fn [[started _]] [started `(start-runtime)]) starts)
           ~@(mapcat (fn [[started sym]] [sym `(:rt ~started)]) starts)]
       (try
         (do ~@body)
         (finally
           ~@(map (fn [[started _]] `(stop-runtime ~started)) starts))))))

(use-fixtures :each (fn [f] (u/unbind!) (try (f) (finally (u/unbind!)))))

(deftest resolution-precedence-scope-beats-bind-beats-ambient
  (with-runtimes [rt-a nil rt-b nil]
    (testing "bind! sets the module-local default"
      (u/bind! rt-a)
      (is (identical? rt-a (u/runtime)))
      (is (identical? rt-a (u/bound))))
    (testing "with-runtime scope overrides the bind! default and restores after"
      (u/with-runtime rt-b
        (is (identical? rt-b (u/runtime))))
      (is (identical? rt-a (u/runtime))))
    (testing "ambient current/runtime is the fallback when nothing is bound"
      (u/unbind!)
      (current/with-runtime rt-b
        (is (identical? rt-b (u/runtime)))))))

(deftest loud-failure-when-no-runtime-resolvable
  (u/unbind!)
  (is (nil? @weaver-runtime/current-runtime)
      "test JVM must have no published runtime for this assertion")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"skein.userland.alpha"
                        (u/ready)))
  (testing "binding or scoping a nil runtime fails loudly instead of falling through"
    (is (thrown? clojure.lang.ExceptionInfo (u/bind! nil)))
    (is (thrown? clojure.lang.ExceptionInfo (u/with-runtime nil (u/ready))))))

(deftest wrapper-delegation-against-unpublished-runtime
  (with-runtimes [rt nil]
    (u/with-runtime rt
      (testing "strand! creates and strand reads back"
        (let [created (u/strand! "root" {:owner "agent"})
              id (:id created)]
          (is (= "root" (:title created)))
          (is (= "agent" (get-in (u/strand id) [:attributes :owner])))
          (testing "update! patches attributes"
            (u/update! id {:attributes {:owner "agent" :phase "done"}})
            (is (= "done" (get-in (u/strand id) [:attributes :phase]))))
          (testing "strands and named-query delegation"
            (u/defquery! 'owned '[:= [:attr :owner] "agent"])
            (is (seq (u/queries)))
            (is (= 1 (count (u/strands))))
            (is (= 1 (count (u/query 'owned)))))
          (testing "ready reflects dependency state"
            (let [dep (u/strand! "dep")]
              (u/update! id {:edges [{:type "depends-on" :to (:id dep)}]})
              (is (= #{(:id dep)} (set (map :id (u/ready)))))))
          (testing "apply! runs a batch mutation"
            (let [result (u/apply! {:strands [{:ref :new :title "batched"}]})]
              (is (contains? (:refs result) :new))))
          (testing "burn! deletes"
            (u/burn! id)
            (is (nil? (u/strand id)))))))))

(deftest scoped-runtimes-do-not-cross-talk
  (with-runtimes [rt-a nil rt-b nil]
    (u/with-runtime rt-a (u/strand! "only-a"))
    (u/with-runtime rt-b (u/strand! "only-b"))
    (testing "each scope sees only its own runtime's strands"
      (is (= ["only-a"] (map :title (u/with-runtime rt-a (u/strands)))))
      (is (= ["only-b"] (map :title (u/with-runtime rt-b (u/strands))))))
    (testing "a nested scope restores the outer scope on exit"
      (u/with-runtime rt-a
        (is (= ["only-b"] (map :title (u/with-runtime rt-b (u/strands)))))
        (is (= ["only-a"] (map :title (u/strands))))))))

;; Structural teeth for the userland-only invariant: no repo-owned skein.* source
;; (engine, blessed API, REPL, dev tooling, or shipped spool) may require this
;; module. The workspace's own .skein config is legitimately userland and is not
;; guarded; local/third-party shared spools are held to the rule by review + the
;; guidance doc.
(def ^:private guarded-roots
  ["src/skein" "dev/skein"
   "spools/delegation/src" "spools/kanban/src"
   "spools/chime/src" "spools/agent-run/src" "spools/src"])

(defn- clojure-sources [root]
  (->> (io/file root)
       file-seq
       (filter #(.isFile ^java.io.File %))
       (filter #(re-find #"\.cljc?$" (.getName ^java.io.File %)))
       (remove #(str/includes? (.getPath ^java.io.File %) "/skein/userland/"))))

(deftest no-skein-source-requires-the-userland-module
  (let [offenders (for [root guarded-roots
                        ^java.io.File file (clojure-sources root)
                        :when (str/includes? (slurp file) "skein.userland")]
                    (.getPath file))]
    (is (empty? offenders)
        (str "skein.* / shipped-spool sources must never require skein.userland.alpha: "
             (vec offenders)))))
