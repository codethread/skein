(ns skein.peers-test
  "Tests for local weaver peer discovery."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [skein.api.peers.alpha :as peers]
            [skein.api.weaver.alpha :as api]
            [skein.core.db-test :as db-test]
            [skein.core.weaver.config :as weaver-config]
            [skein.core.weaver.metadata :as metadata]
            [skein.core.weaver.runtime :as runtime]))

(defn- temp-dir [prefix]
  (.toFile (java.nio.file.Files/createTempDirectory prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree! [file]
  (when (.exists file)
    (doseq [f (reverse (file-seq file))]
      (.delete f))))

(defn peer-test-op [{:op/keys [name argv]}]
  {:name name :argv argv :from :peer-test})

(defn- current-pid []
  (.pid (java.lang.ProcessHandle/current)))

(defn- metadata-map [state-dir workspace name pid]
  {:pid pid
   :transport :nrepl
   :protocol-version 1
   :endpoint {:host "127.0.0.1" :port 5555}
   :config-dir (.getPath workspace)
   :name name
   :state-dir (.getPath state-dir)
   :data-dir (.getPath (io/file workspace "data"))
   :canonical-db-path (.getPath (io/file workspace "data" "skein.sqlite"))
   :nonce (str "nonce-" name "-" (System/nanoTime))
   :socket-path (.getPath (metadata/socket-file {:state-dir (.getPath state-dir)}))
   :started-at "2026-07-02T00:00:00Z"})

(defn- write-peer! [state-root hash workspace name pid]
  (let [state-dir (io/file state-root "weavers" hash)]
    (.mkdirs state-dir)
    (spit (io/file state-dir "weaver.edn") (pr-str (metadata-map state-dir workspace name pid)))
    state-dir))

(defn- with-state-root [state-root f]
  (let [state-root-var (ns-resolve 'skein.api.peers.alpha 'state-root)
        original @state-root-var]
    (alter-var-root state-root-var (constantly (fn [] state-root)))
    (try
      (f)
      (finally
        (alter-var-root state-root-var (constantly original))))))

(deftest peers-empty-root-test
  (let [state-root (temp-dir "skein-peers-empty")]
    (with-state-root state-root
      #(is (= [] (peers/peers))))))

(deftest peers-list-and-resolve-running-test
  (let [state-root (temp-dir "skein-peers-running")
        workspace (temp-dir "skein-peer-workspace")
        state-dir (write-peer! state-root "a" workspace "alpha" (current-pid))
        socket-path (.getPath (metadata/socket-file {:state-dir (.getPath state-dir)}))]
    (with-state-root state-root
      #(let [rows (peers/peers)]
         (is (= 1 (count rows)))
         (is (= {:name "alpha"
                 :workspace (.getPath workspace)
                 :protocol-version 1
                 :socket-path socket-path
                 :state-dir (.getPath state-dir)
                 :running? true}
                (select-keys (first rows) [:name :workspace :protocol-version :socket-path :state-dir :running?])))
         (is (= "alpha" (:name (peers/peer "alpha"))))
         (is (= "alpha" (:name (peers/peer (.getPath workspace)))))))))

(deftest peers-bare-name-not-shadowed-by-local-directory-test
  ;; "src" exists as a directory relative to the test cwd; a bare token must
  ;; still resolve as a logical peer name, never as a workspace path.
  (let [state-root (temp-dir "skein-peers-shadow")
        workspace (temp-dir "skein-peer-shadow-workspace")]
    (write-peer! state-root "s" workspace "src" (current-pid))
    (with-state-root state-root
      #(is (= "src" (:name (peers/peer "src")))))))

(deftest peers-stale-listed-but-not-resolvable-test
  (let [state-root (temp-dir "skein-peers-stale")
        workspace (temp-dir "skein-peer-stale-workspace")]
    (write-peer! state-root "stale" workspace "stale" 999999999)
    (with-state-root state-root
      #(do
         (is (false? (:running? (first (peers/peers)))))
         (try
           (peers/peer "stale")
           (is false "expected stale peer resolution to throw")
           (catch clojure.lang.ExceptionInfo ex
             (is (= :peer/stale (:code (ex-data ex))))))))))

(deftest peers-duplicate-name-ambiguity-test
  (let [state-root (temp-dir "skein-peers-ambiguous")
        workspace-a (temp-dir "skein-peer-a")
        workspace-b (temp-dir "skein-peer-b")]
    (write-peer! state-root "a" workspace-a "shared" (current-pid))
    (write-peer! state-root "b" workspace-b "shared" (current-pid))
    (with-state-root state-root
      #(try
         (peers/peer "shared")
         (is false "expected ambiguous peer resolution to throw")
         (catch clojure.lang.ExceptionInfo ex
           (is (= :peer/ambiguous (:code (ex-data ex))))
           (is (= #{(.getPath workspace-a) (.getPath workspace-b)}
                  (set (map :workspace (:candidates (ex-data ex)))))))))))

(deftest peers-malformed-metadata-fails-test
  (let [state-root (temp-dir "skein-peers-malformed")
        state-dir (io/file state-root "weavers" "bad")]
    (.mkdirs state-dir)
    (spit (io/file state-dir "weaver.edn") (pr-str {:pid (current-pid) :name "bad"}))
    (with-state-root state-root
      #(try
         (peers/peers)
         (is false "expected malformed metadata to throw")
         (catch clojure.lang.ExceptionInfo ex
           (is (= :peer/malformed-metadata (:code (ex-data ex)))))))))

(defn- world-under [root hash name]
  (let [workspace (io/file root "workspaces" name)
        state-dir (io/file root "state" "skein" "weavers" hash)
        data-dir (io/file root "data" name)]
    (.mkdirs workspace)
    (.mkdirs state-dir)
    (.mkdirs data-dir)
    (weaver-config/world (.getCanonicalPath workspace)
                         (.getCanonicalPath state-dir)
                         (.getCanonicalPath data-dir))))

(defn- short-temp-root [prefix]
  (let [root (io/file "/tmp" (str prefix (System/nanoTime)))]
    (.mkdirs root)
    root))

(defn- with-two-runtimes [f]
  (let [root (short-temp-root "sg")
        state-root (io/file root "state" "skein")
        db-a (db-test/temp-db-file)
        db-b (db-test/temp-db-file)
        rt-a (runtime/start! db-a {:world (world-under root "a" "alpha") :name "alpha"})]
    ;; The runtime enforces one process-current runtime for REPL convenience;
    ;; peer socket tests need two independent local runtimes and do not rely on
    ;; current-runtime dispatch.
    (reset! runtime/current-runtime nil)
    (let [rt-b (runtime/start! db-b {:world (world-under root "b" "beta") :name "beta"})]
      (try
        (with-state-root state-root #(f rt-a rt-b))
        (finally
          (runtime/stop! rt-b)
          (runtime/stop! rt-a)
          (reset! runtime/current-runtime nil)
          (db-test/delete-sqlite-family! db-a)
          (db-test/delete-sqlite-family! db-b)
          (delete-tree! root))))))

(deftest call-peer-add-show-list-and-op-test
  (with-two-runtimes
    (fn [_rt-a rt-b]
      (api/register-op! rt-b 'echo "Echo peer test argv" 'skein.peers-test/peer-test-op)
      (let [beta (peers/peer "beta")
            added (peers/call! beta :add {"title" "Created through peer"
                                          "attributes" {"origin" "alpha"}})
            shown (peers/call! beta "show" {"id" (get added "id")})
            listed (peers/call! "beta" 'list {})
            op-result (peers/call! beta "op" {"name" "echo" "args" ["x" "y"]})]
        (is (= "Created through peer" (get added "title")))
        (is (= shown added))
        (is (= #{(get added "id")} (set (map #(get % "id") listed))))
        (is (= (get added "id") (:id (api/show rt-b (get added "id")))))
        (is (= {"name" "echo" "argv" ["x" "y"] "from" "peer-test"} op-result))))))

(deftest call-peer-rejects-invalid-op-before-connect-test
  (let [peer-row {:name "offline"
                  :workspace "/tmp/offline"
                  :weaver-id "missing"
                  :protocol-version 1
                  :socket-path "/tmp/skein-peer-missing.sock"
                  :state-dir "/tmp"}]
    (try
      (peers/call! peer-row "not-allowed" {})
      (is false "expected unknown operation to throw")
      (catch clojure.lang.ExceptionInfo ex
        (is (= :peer/operation-not-allowed (:code (ex-data ex))))))
    (try
      (peers/call! peer-row 42 {})
      (is false "expected invalid operation type to throw")
      (catch clojure.lang.ExceptionInfo ex
        (is (= 42 (:operation (ex-data ex))))))
    (try
      (peers/call! peer-row :peer/stop {})
      (is false "expected namespaced operation to throw")
      (catch clojure.lang.ExceptionInfo ex
        (is (= :peer/stop (:operation (ex-data ex))))))))

(deftest call-peer-domain-error-is-structured-test
  (with-two-runtimes
    (fn [_rt-a _rt-b]
      (try
        (peers/call! "beta" "op" {"name" "missing-op" "args" []})
        (is false "expected peer domain error")
        (catch clojure.lang.ExceptionInfo ex
          (is (= :peer/domain-error (:code (ex-data ex))))
          (is (= "beta" (get-in (ex-data ex) [:peer :name])))
          (is (= "op" (:operation (ex-data ex))))
          (is (= "domain" (get-in (ex-data ex) [:error "type"]))))))))

(deftest call-stopped-peer-fails-loudly-test
  (let [root (short-temp-root "sgstop")
        state-root (io/file root "state" "skein")
        db-b (db-test/temp-db-file)
        rt-b (runtime/start! db-b {:world (world-under root "b" "beta") :name "beta"})]
    (try
      (let [beta (with-state-root state-root #(peers/peer "beta"))]
        (runtime/stop! rt-b)
        (try
          (peers/call! beta "status" {})
          (is false "expected stopped peer transport failure")
          (catch clojure.lang.ExceptionInfo ex
            (is (= :peer/transport-failed (:code (ex-data ex))))
            (is (= "beta" (get-in (ex-data ex) [:peer :name]))))))
      (finally
        (runtime/stop! rt-b)
        (db-test/delete-sqlite-family! db-b)
        (delete-tree! root)))))
