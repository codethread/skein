(ns skein.chime-sync-test
  "Serialized approved-spool-workspace loading test for the chime spool.

  Split from skein.chime-test: sync! calls clojure.repl.deps/add-libs, which
  mutates JVM-global tools.deps state, so this namespace stays in the test
  runner's serial add-libs island while the rest of the chime suite runs in
  the parallel batch."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [skein.api.events.alpha :as events]
            [skein.core.db-test :as db-test]
            [skein.core.weaver.config :as daemon-config]
            [skein.core.weaver.runtime :as runtime]))

(defn- temp-config-dir []
  (doto (.toFile (java.nio.file.Files/createTempDirectory
                  (.toPath (io/file "/tmp"))
                  "skein-chime-sync-config"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
    (.mkdirs)))

(defn- test-world [config-dir]
  (daemon-config/world config-dir
                       (str config-dir "/state")
                       (str config-dir "/data")))

(deftest spool-loads-through-approved-spool-workspace-flow
  (let [db-file (db-test/temp-db-file)
        config-dir (temp-config-dir)
        repo-root (.getCanonicalPath (io/file "spools/chime"))]
    (try
      (spit (io/file config-dir "spools.edn")
            (pr-str {:spools {'skein.spools/chime {:local/root repo-root}}}))
      ;; Published on purpose: this exercises the real approved-spool workspace
      ;; flow, and the namespace is serialized in the runner's add-libs island.
      (let [rt (runtime/start! db-file {:world (test-world (.getCanonicalPath config-dir))})]
        (try
          (let [synced ((requiring-resolve 'skein.api.runtime.alpha/sync!) rt)
                used ((requiring-resolve 'skein.api.runtime.alpha/use!)
                      rt :chime {:ns 'skein.spools.chime
                                 :spools ['skein.spools/chime]
                                 :call 'skein.spools.chime/install!
                                 :required? true})]
            (is (contains? #{:loaded :already-available}
                           (get-in synced [:spools 'skein.spools/chime :status])))
            (is (= :loaded (:status used)))
            (is (some #(= :chime/engine (:key %)) (events/handlers rt))))
          (finally
            (runtime/stop! rt))))
      (finally
        (db-test/delete-sqlite-family! db-file)))))
