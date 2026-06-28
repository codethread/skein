(ns user
  (:require [skein.weaver.runtime :as runtime]
            [skein.repl :refer :all]))

(defonce ^:private demo-runtime (atom nil))
(defonce ^:private demo-world (atom nil))

(defn- checkout-root []
  (.getAbsolutePath (java.io.File. ".")))

(defn- new-demo-world! []
  (let [config-dir (.toFile (java.nio.file.Files/createTempDirectory "skein-demo-" (make-array java.nio.file.attribute.FileAttribute 0)))
        world {:config-dir (.getCanonicalPath config-dir)
               :state-dir (.getCanonicalPath (java.io.File. config-dir "state"))
               :data-dir (.getCanonicalPath (java.io.File. config-dir "data"))
               :db-path (.getCanonicalPath (java.io.File. config-dir "data/skein.sqlite"))}]
    (spit (java.io.File. config-dir "config.json")
          (format "{\"configFormat\":\"alpha\",\"source\":%s}\n" (pr-str (checkout-root))))
    world))

(defn start-demo-weaver!
  "Start a demo weaver in an explicit disposable config-dir world."
  []
  (when @demo-runtime
    (throw (ex-info "Demo weaver is already started from this REPL" {:world @demo-world})))
  (let [world (new-demo-world!)]
    (reset! demo-world world)
    (reset! demo-runtime (runtime/start! nil {:world world}))
    {:config-dir (:config-dir world)
     :status :weaver-started}))

(defn stop-demo-weaver!
  "Stop the demo weaver started by start-demo-weaver!."
  []
  (let [rt (or @demo-runtime
               (throw (ex-info "No demo weaver was started from this REPL" {:world @demo-world})))]
    (runtime/stop! rt)
    (let [world @demo-world]
      (reset! demo-runtime nil)
      (reset! demo-world nil)
      {:config-dir (:config-dir world)
       :status :weaver-stopped})))

(defn demo!
  "Connect to the demo weaver and initialize storage."
  []
  (let [world (or @demo-world
                  (throw (ex-info "Start the demo weaver first" {})))]
    (connect! (:config-dir world))
    (init!)
    {:config-dir (:config-dir world)
     :status :ready}))

(defn seed-demo!
  "Initialize the demo world and add a small dependency graph."
  []
  (demo!)
  (let [design (strand! "Sketch model" {:priority "high" :demo-id "design"} {:state "closed"})
        docs (strand! "Write docs" {:owner "agent" :demo-id "docs"})
        impl (strand! "Build feature" {:owner "agent" :demo-id "impl"})]
    (update! (:id docs) {:edges [{:type "depends-on" :to (:id design)}]})
    (update! (:id impl) {:edges [{:type "depends-on" :to (:id docs)}]})
    (strands)))

(comment
  (start-demo-weaver!)
  (demo!)
  (seed-demo!)
  (ready)
  (def docs-id (:id (first (filter #(= "docs" (get-in % [:attributes :demo-id])) (strands)))))
  (def replacement-docs-id (:id (strand! "Rewrite docs" {:owner "agent" :demo-id "replacement-docs"})))
  (supersede! docs-id replacement-docs-id)
  (query [:edge/out "supersedes" [:= [:attr :demo-id] "docs"]])
  (update! replacement-docs-id {:state "closed"})
  (ready)
  (stop-demo-weaver!))
