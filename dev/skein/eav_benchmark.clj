(ns skein.eav-benchmark
  "Run the EAV attribute-storage benchmark gate.

  The harness builds paired SQLite fixtures from one deterministic synthetic
  dataset: a document-column baseline and the row-backed EAV schema. It measures
  the merge-blocking workloads from EAS-PLAN-001.P7 and exits non-zero when a
  target is missed."
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.io File]
           [java.sql Connection]
           [java.time Instant]
           [java.util Random]
           [org.sqlite SQLiteConfig SQLiteConfig$JournalMode SQLiteConfig$Pragma SQLiteDataSource]))

(def ^:private default-options
  {:n 250000
   :iterations 5
   :measure-timeout-secs 60
   :list-size 500
   :patch-size 200
   :payload-every 50
   :payload-bytes 65536
   :huge-payload-bytes 262144
   :seed 1337
   :out "target/eav-benchmark"})

(def ^:private usage
  (str/join
   "\n"
   ["Usage: clojure -M -m skein.eav-benchmark [options]"
    ""
    "Options:"
    "  --n N                 synthetic strand count (default 250000)"
    "  --iterations N        timed iterations per workload (default 5)"
    "  --measure-timeout N   query timeout per measured iteration, seconds (default 60)"
    "  --list-size N         rows for assembly read workload (default 500)"
    "  --patch-size N        payload-carrying rows patched for write amp (default 200)"
    "  --payload-every N     every Nth row carries a payload (default 50)"
    "  --payload-bytes N     payload bytes on payload rows (default 65536)"
    "  --huge-payload-bytes N payload bytes for the 256KiB write-amp bucket (default 262144)"
    "  --seed N              synthetic dataset seed (default 1337)"
    "  --out DIR             output directory (default target/eav-benchmark)"
    "  --help                print this help"]))

(defn- parse-long-option [opts k raw]
  (try
    (assoc opts k (Long/parseLong raw))
    (catch NumberFormatException e
      (throw (ex-info "Option must be an integer" {:option k :value raw} e)))))

(defn- parse-args [args]
  (loop [opts default-options
         remaining args]
    (if-let [[flag value & more] (seq remaining)]
      (case flag
        "--help" (assoc opts :help true)
        "--n" (recur (parse-long-option opts :n value) more)
        "--iterations" (recur (parse-long-option opts :iterations value) more)
        "--measure-timeout" (recur (parse-long-option opts :measure-timeout-secs value) more)
        "--list-size" (recur (parse-long-option opts :list-size value) more)
        "--patch-size" (recur (parse-long-option opts :patch-size value) more)
        "--payload-every" (recur (parse-long-option opts :payload-every value) more)
        "--payload-bytes" (recur (parse-long-option opts :payload-bytes value) more)
        "--huge-payload-bytes" (recur (parse-long-option opts :huge-payload-bytes value) more)
        "--seed" (recur (parse-long-option opts :seed value) more)
        "--out" (recur (assoc opts :out value) more)
        (throw (ex-info "Unknown benchmark option" {:option flag})))
      opts)))

(defn- require-positive! [opts k]
  (when-not (pos-int? (get opts k))
    (throw (ex-info "Benchmark option must be positive" {:option k :value (get opts k)}))))

(defn- validate-options! [opts]
  (doseq [k [:n :iterations :measure-timeout-secs :list-size :patch-size :payload-every :payload-bytes :huge-payload-bytes]]
    (require-positive! opts k))
  (when (< (:payload-bytes opts) 16384)
    (throw (ex-info "Payload bytes must be at least 16KiB for the structural write-amp gate"
                    (select-keys opts [:payload-bytes]))))
  (when (< (:huge-payload-bytes opts) 262144)
    (throw (ex-info "Huge payload bytes must be at least 256KiB for the structural write-amp gate"
                    (select-keys opts [:huge-payload-bytes]))))
  (when (< (:n opts) (:list-size opts))
    (throw (ex-info "List size must not exceed strand count" (select-keys opts [:n :list-size]))))
  (when (> (* 2 (:patch-size opts)) (quot (:n opts) (:payload-every opts)))
    (throw (ex-info "Patch size exceeds generated payload-carrying row count"
                    (select-keys opts [:n :patch-size :payload-every]))))
  opts)

(defn- sqlite-datasource [path journal-mode]
  (let [config (doto (SQLiteConfig.)
                 (.setBusyTimeout 5000)
                 (.enforceForeignKeys true)
                 (.setJournalMode journal-mode)
                 (.setPragma SQLiteConfig$Pragma/MMAP_SIZE (str (* 1024 1024 1024)))
                 (.setPragma SQLiteConfig$Pragma/CACHE_SIZE "-200000"))
        ds (SQLiteDataSource.)]
    (.setUrl ds (str "jdbc:sqlite:" path))
    (.setConfig ds config)
    ds))

(defn- execute! [connectable sql-params]
  (jdbc/execute! connectable sql-params {:builder-fn rs/as-unqualified-lower-maps}))

(defn- execute-one! [connectable sql-params]
  (jdbc/execute-one! connectable sql-params {:builder-fn rs/as-unqualified-lower-maps}))

(defn- delete-file! [path]
  (let [file (io/file path)]
    (when (.exists file)
      (io/delete-file file))))

(defn- clean-sqlite-files! [path]
  (doseq [suffix ["" "-journal" "-wal" "-shm"]]
    (delete-file! (str path suffix))))

(defn- setup-document-schema! [ds]
  (doseq [sql ["PRAGMA foreign_keys = ON"
               "CREATE TABLE strands (
                  id TEXT PRIMARY KEY,
                  title TEXT NOT NULL,
                  state TEXT NOT NULL DEFAULT 'active',
                  attributes TEXT NOT NULL DEFAULT '{}',
                  created_at TEXT NOT NULL DEFAULT (datetime('now')),
                  updated_at TEXT NOT NULL DEFAULT (datetime('now')),
                  CHECK (state IN ('active', 'closed', 'replaced')),
                  CHECK (json_valid(attributes))
                )"
               "CREATE TABLE strand_edges (
                  from_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
                  to_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
                  edge_type TEXT NOT NULL,
                  attributes TEXT NOT NULL DEFAULT '{}',
                  created_at TEXT NOT NULL DEFAULT (datetime('now')),
                  PRIMARY KEY (from_strand_id, to_strand_id, edge_type),
                  CHECK (json_valid(attributes))
                )"
               "CREATE INDEX idx_doc_edges_to_type ON strand_edges(to_strand_id, edge_type)"
               "CREATE INDEX idx_doc_edges_from_type ON strand_edges(from_strand_id, edge_type)"]]
    (execute! ds [sql])))

(defn- setup-eav-schema! [ds]
  (doseq [sql ["PRAGMA foreign_keys = ON"
               "CREATE TABLE strands (
                  id TEXT PRIMARY KEY,
                  title TEXT NOT NULL,
                  state TEXT NOT NULL DEFAULT 'active',
                  created_at TEXT NOT NULL DEFAULT (datetime('now')),
                  updated_at TEXT NOT NULL DEFAULT (datetime('now')),
                  CHECK (state IN ('active', 'closed', 'replaced'))
                )"
               "CREATE TABLE attributes (
                  strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
                  key TEXT NOT NULL,
                  value TEXT NOT NULL CHECK (json_valid(value)),
                  archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1)),
                  PRIMARY KEY (strand_id, key)
                )"
               "CREATE INDEX idx_attributes_key_value_hot ON attributes(key, value) WHERE archived = 0"
               "CREATE INDEX idx_attributes_strand_hot ON attributes(strand_id) WHERE archived = 0"
               "CREATE TABLE strand_edges (
                  from_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
                  to_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
                  edge_type TEXT NOT NULL,
                  attributes TEXT NOT NULL DEFAULT '{}',
                  created_at TEXT NOT NULL DEFAULT (datetime('now')),
                  PRIMARY KEY (from_strand_id, to_strand_id, edge_type),
                  CHECK (json_valid(attributes))
                )"
               "CREATE INDEX idx_eav_edges_to_type ON strand_edges(to_strand_id, edge_type)"
               "CREATE INDEX idx_eav_edges_from_type ON strand_edges(from_strand_id, edge_type)"]]
    (execute! ds [sql])))

(defn- payload [^Random rng payload-bytes]
  (let [alphabet "abcdefghijklmnopqrstuvwxyz0123456789"
        n (.length alphabet)
        sb (StringBuilder. payload-bytes)]
    (dotimes [_ payload-bytes]
      (.append sb (.charAt alphabet (.nextInt rng n))))
    (str sb)))

(defn- strand-id [i]
  (format "s%06d" i))

(defn- attributes-for [^Random rng i opts]
  (cond-> {:kind (case (mod i 6)
                   0 "task"
                   1 "note"
                   2 "feature"
                   3 "gate"
                   4 "run"
                   "review")
           :owner (str "agent-" (mod i 17))
           :priority (case (mod i 4)
                       0 "low"
                       1 "normal"
                       2 "high"
                       "urgent")
           :status (if (zero? (mod i 7)) "blocked" "open")
           :rank i}
    (zero? (mod i (:payload-every opts)))
    (assoc :body (payload rng (:payload-bytes opts)))))

(defn- insert-document-row! [tx id i state attributes]
  (execute! tx ["INSERT INTO strands (id, title, state, attributes)
                 VALUES (?, ?, ?, json(?))"
                id (str "Synthetic strand " i) state (json/write-str attributes)]))

(defn- insert-eav-row! [tx id i state attributes]
  (execute! tx ["INSERT INTO strands (id, title, state)
                 VALUES (?, ?, ?)"
                id (str "Synthetic strand " i) state])
  (doseq [[k v] (sort-by (comp name key) attributes)]
    (execute! tx ["INSERT INTO attributes (strand_id, key, value)
                   VALUES (?, ?, json(?))"
                  id (name k) (json/write-str v)])))

(defn- insert-edge! [tx from to]
  (execute! tx ["INSERT INTO strand_edges (from_strand_id, to_strand_id, edge_type, attributes)
                 VALUES (?, ?, 'depends-on', '{}')"
                from to]))

(defn- generate-fixtures! [doc-ds eav-ds opts]
  (let [doc-rng (Random. (:seed opts))
        eav-rng (Random. (:seed opts))]
    (jdbc/with-transaction [doc-tx doc-ds]
      (jdbc/with-transaction [eav-tx eav-ds]
        (dotimes [idx (:n opts)]
          (let [i (inc idx)
                id (strand-id i)
                state (if (< (mod i 10) 3) "active" "closed")
                doc-attrs (attributes-for doc-rng i opts)
                eav-attrs (attributes-for eav-rng i opts)]
            (insert-document-row! doc-tx id i state doc-attrs)
            (insert-eav-row! eav-tx id i state eav-attrs)
            (when (and (> i 1)
                       (zero? (mod i 4)))
              (insert-edge! doc-tx id (strand-id (dec i)))
              (insert-edge! eav-tx id (strand-id (dec i))))))))))

(defn- median [xs]
  (let [sorted (vec (sort xs))
        n (count sorted)]
    (if (odd? n)
      (double (nth sorted (quot n 2)))
      (/ (+ (nth sorted (dec (quot n 2)))
            (nth sorted (quot n 2)))
         2.0))))

(defn- timed-ms [f]
  (let [start (System/nanoTime)
        result (f)
        elapsed (/ (double (- (System/nanoTime) start)) 1000000.0)]
    {:ms elapsed :result result}))

(defn- measure-workload [f opts]
  (let [samples (vec (for [_ (range (:iterations opts))]
                       (:ms (timed-ms f))))]
    {:samples-ms samples
     :median-ms (median samples)
     :max-ms (apply max samples)}))

(defn- measure-query [ds sql-params opts]
  (measure-workload
   #(doall (jdbc/execute! ds
                          sql-params
                          {:builder-fn rs/as-unqualified-lower-maps
                           :timeout (:measure-timeout-secs opts)}))
   opts))

(def ^:private doc-filtered-sql
  ["SELECT id, title, state, attributes, created_at, updated_at
    FROM strands
    WHERE json_extract(attributes, '$.\"kind\"') = 'task'
      AND state = 'active'
    ORDER BY id"])

(def ^:private eav-filtered-sql
  ["SELECT t.id, t.title, t.state,
          COALESCE((
            SELECT json_group_object(key, json(value))
            FROM (
              SELECT key, value
              FROM attributes
              WHERE strand_id = t.id
                AND archived = 0
              ORDER BY key
            )
          ), '{}') AS attributes,
          t.created_at, t.updated_at
    FROM strands t
    WHERE t.state = 'active'
      AND t.id IN (
        SELECT a.strand_id
        FROM attributes AS a
        WHERE a.archived = 0
          AND a.key = 'kind'
          AND json_extract(a.value, '$') = 'task'
      )
    ORDER BY t.id"])

(def ^:private doc-ready-sql
  ["SELECT id, title, state, attributes, created_at, updated_at
    FROM strands t
    WHERE t.state = 'active'
      AND NOT EXISTS (
        SELECT 1
        FROM strand_edges e
        JOIN strands dep ON dep.id = e.to_strand_id
        WHERE e.from_strand_id = t.id
          AND e.edge_type = 'depends-on'
          AND dep.state = 'active'
      )
    ORDER BY t.id"])

(def ^:private eav-ready-base-sql
  ["SELECT t.id, t.title, t.state, t.created_at, t.updated_at
    FROM strands t
    WHERE t.state = 'active'
      AND NOT EXISTS (
        SELECT 1
        FROM strand_edges e
        JOIN strands dep ON dep.id = e.to_strand_id
        WHERE e.from_strand_id = t.id
          AND e.edge_type = 'depends-on'
          AND dep.state = 'active'
      )
    ORDER BY t.id"])

(def ^:private eav-ready-sql
  ["SELECT t.id, t.title, t.state,
          COALESCE((
            SELECT json_group_object(key, json(value))
            FROM (
              SELECT key, value
              FROM attributes
              WHERE strand_id = t.id
                AND archived = 0
              ORDER BY key
            )
          ), '{}') AS attributes,
          t.created_at, t.updated_at
    FROM strands t
    WHERE t.id IN (
      SELECT id FROM (
        SELECT t.id
        FROM strands t
        WHERE t.state = 'active'
          AND NOT EXISTS (
            SELECT 1
            FROM strand_edges e
            JOIN strands dep ON dep.id = e.to_strand_id
            WHERE e.from_strand_id = t.id
              AND e.edge_type = 'depends-on'
              AND dep.state = 'active'
          )
      )
    )
    ORDER BY t.id"])

(def ^:private attribute-assembly-batch-size 30000)
(def ^:private lean-attribute-byte-floor 1024)

(defn- eav-attribute-value-sql [lean?]
  (if lean?
    (str "CASE WHEN length(CAST(value AS BLOB)) > ? "
         "THEN json_object('skein/omitted', json('true'), 'bytes', length(CAST(value AS BLOB))) "
         "ELSE json(value) END")
    "json(value)"))

(defn- assemble-attribute-batches [ds ids opts lean?]
  (let [attrs-by-id
        (into {}
              (map (juxt :strand_id :attributes))
              (mapcat
               (fn [batch]
                 (jdbc/execute! ds
                                (into [(str "SELECT strand_id,
                                                    COALESCE(json_group_object(key, " (eav-attribute-value-sql lean?) "), '{}') AS attributes
                                             FROM (
                                               SELECT strand_id, key, value
                                               FROM attributes
                                               WHERE archived = 0
                                                 AND strand_id IN (" (str/join ", " (repeat (count batch) "?")) ")
                                             )
                                             GROUP BY strand_id")]
                                      (cond-> []
                                        lean? (conj lean-attribute-byte-floor)
                                        true (into batch)))
                                {:builder-fn rs/as-unqualified-lower-maps
                                 :timeout (:measure-timeout-secs opts)}))
               (partition-all attribute-assembly-batch-size ids)))]
    attrs-by-id))

(defn- eav-ready-batched [ds opts]
  (let [rows (jdbc/execute! ds eav-ready-base-sql {:builder-fn rs/as-unqualified-lower-maps
                                                   :timeout (:measure-timeout-secs opts)})
        ids (mapv :id rows)
        attrs-by-id (assemble-attribute-batches ds ids opts true)]
    (mapv #(assoc % :attributes (get attrs-by-id (:id %) "{}")) rows)))

(defn- list-sql [table-shape ids]
  (let [placeholders (str/join ", " (repeat (count ids) "?"))]
    (case table-shape
      :document (into [(str "SELECT id, title, state, attributes, created_at, updated_at
                             FROM strands
                             WHERE id IN (" placeholders ")
                             ORDER BY id")]
                      ids)
      :eav (into [(str "SELECT t.id, t.title, t.state,
                               COALESCE((
                                 SELECT json_group_object(key, json(value))
                                 FROM (
                                   SELECT key, value
                                   FROM attributes
                                   WHERE strand_id = t.id
                                     AND archived = 0
                                   ORDER BY key
                                 )
                               ), '{}') AS attributes,
                               t.created_at, t.updated_at
                        FROM strands t
                        WHERE t.id IN (" placeholders ")
                        ORDER BY t.id")]
                 ids))))

(defn- payload-target-ids [opts offset]
  (mapv #(strand-id (* (:payload-every opts) (+ offset (inc %)))) (range (:patch-size opts))))

(defn- payload-free-target-ids [opts]
  (mapv #(strand-id (+ 1 (* (:payload-every opts) %))) (range (:patch-size opts))))

(defn- file-size [path]
  (let [file (io/file path)]
    (if (.exists file)
      (.length file)
      0)))

(defn- with-rollback-journal [db-path f]
  (let [ds (sqlite-datasource db-path SQLiteConfig$JournalMode/DELETE)]
    (execute! ds ["PRAGMA journal_mode = DELETE"])
    (f ds)))

(defn- patch-write-samples [db-path sql-fn ids]
  (let [wal-path (str db-path "-wal")
        ds (sqlite-datasource db-path SQLiteConfig$JournalMode/WAL)]
    (execute! ds ["PRAGMA wal_autocheckpoint = 1000000"])
    (execute! ds ["PRAGMA wal_checkpoint(TRUNCATE)"])
    (with-open [conn (jdbc/get-connection ds)]
      (execute! conn ["PRAGMA wal_autocheckpoint = 0"])
      (mapv
       (fn [[idx id]]
         (let [before-size (file-size wal-path)]
           (.setAutoCommit conn false)
           ((sql-fn conn id idx))
           (.commit conn)
           (.setAutoCommit conn true)
           (max 0 (- (file-size wal-path) before-size))))
       (map-indexed vector ids)))))

(defn- document-patch-fn [^Connection conn id idx]
  (fn []
    (execute! conn ["UPDATE strands
                    SET attributes = json_patch(attributes, json(?)),
                        updated_at = datetime('now')
                    WHERE id = ?"
                    (json/write-str {:status (str "done-" idx) :_w4seq idx}) id])))

(defn- eav-patch-fn [^Connection conn id idx]
  (fn []
    (execute! conn ["INSERT INTO attributes (strand_id, key, value)
                    VALUES (?, 'status', json(?))
                    ON CONFLICT(strand_id, key) DO UPDATE
                    SET value = excluded.value,
                        archived = 0"
                    id (json/write-str (str "done-" idx))])
    (execute! conn ["INSERT INTO attributes (strand_id, key, value)
                    VALUES (?, '_w4seq', json(?))
                    ON CONFLICT(strand_id, key) DO UPDATE
                    SET value = excluded.value,
                        archived = 0"
                    id (json/write-str idx)])
    (execute! conn ["UPDATE strands SET updated_at = datetime('now') WHERE id = ?" id])))

(defn- set-document-payload! [tx id value]
  (execute! tx ["UPDATE strands
                 SET attributes = json_set(attributes, '$.body', json(?))
                 WHERE id = ?"
                (json/write-str value) id]))

(defn- set-eav-payload! [tx id value]
  (execute! tx ["INSERT INTO attributes (strand_id, key, value)
                 VALUES (?, 'body', json(?))
                 ON CONFLICT(strand_id, key) DO UPDATE
                 SET value = excluded.value,
                     archived = 0"
                id (json/write-str value)]))

(defn- seed-huge-payload-bucket! [doc-path eav-path ids opts]
  (let [value (payload (Random. (+ 31 (:seed opts))) (:huge-payload-bytes opts))]
    (with-rollback-journal
      doc-path
      (fn [ds]
        (jdbc/with-transaction [tx ds]
          (doseq [id ids]
            (set-document-payload! tx id value)))))
    (with-rollback-journal
      eav-path
      (fn [ds]
        (jdbc/with-transaction [tx ds]
          (doseq [id ids]
            (set-eav-payload! tx id value)))))))

(defn- write-amp-bucket [doc-path eav-path ids]
  (let [doc-samples (patch-write-samples doc-path document-patch-fn ids)
        eav-samples (patch-write-samples eav-path eav-patch-fn ids)
        doc-median (median doc-samples)
        eav-median (median eav-samples)]
    {:patched-rows (count ids)
     :document-patch-bytes {:median doc-median :samples doc-samples}
     :eav-patch-bytes {:median eav-median :samples eav-samples}
     :reduction (if (pos? eav-median) (/ (double doc-median) eav-median) ##Inf)}))

(defn- combined-write-amp-bucket [buckets]
  (let [doc-samples (mapcat #(get-in % [:document-patch-bytes :samples]) buckets)
        eav-samples (mapcat #(get-in % [:eav-patch-bytes :samples]) buckets)
        doc-median (median doc-samples)
        eav-median (median eav-samples)]
    {:patched-rows (count doc-samples)
     :document-patch-bytes {:median doc-median :samples (vec doc-samples)}
     :eav-patch-bytes {:median eav-median :samples (vec eav-samples)}
     :reduction (if (pos? eav-median) (/ (double doc-median) eav-median) ##Inf)}))

(defn- measure-write-amp [doc-path eav-path opts]
  (let [free-ids (payload-free-target-ids opts)
        large-ids (payload-target-ids opts 0)
        huge-ids (payload-target-ids opts (:patch-size opts))
        _ (seed-huge-payload-bucket! doc-path eav-path huge-ids opts)
        free (write-amp-bucket doc-path eav-path free-ids)
        large (write-amp-bucket doc-path eav-path large-ids)
        huge (write-amp-bucket doc-path eav-path huge-ids)
        ge-16kb (combined-write-amp-bucket [large huge])
        eav-independence (if (pos? (get-in free [:eav-patch-bytes :median]))
                           (/ (double (get-in huge [:eav-patch-bytes :median]))
                              (get-in free [:eav-patch-bytes :median]))
                           ##Inf)]
    {:accepted-small-row-cost "Payload-free rows may be slower than document rewrites because an EAV patch has a fixed page-granularity floor."
     :payload-free free
     :payload-64kb large
     :payload-ge-16kb ge-16kb
     :payload-256kb huge
     :eav-256kb-vs-free-patch-bytes eav-independence}))

(defn- row-count [ds sql-params]
  (count (execute! ds sql-params)))

(defn- run-benchmark [opts]
  (let [out-dir (io/file (:out opts))
        _ (.mkdirs out-dir)
        doc-path (.getPath (io/file out-dir "document.sqlite"))
        eav-path (.getPath (io/file out-dir "eav.sqlite"))
        _ (doseq [path [doc-path eav-path]] (clean-sqlite-files! path))
        doc-ds (sqlite-datasource doc-path SQLiteConfig$JournalMode/WAL)
        eav-ds (sqlite-datasource eav-path SQLiteConfig$JournalMode/WAL)
        list-ids (mapv strand-id (range 1 (inc (:list-size opts))))]
    (setup-document-schema! doc-ds)
    (setup-eav-schema! eav-ds)
    (let [generated (:ms (timed-ms #(generate-fixtures! doc-ds eav-ds opts)))
          _ (doseq [ds [doc-ds eav-ds]]
              (execute! ds ["ANALYZE"])
              (execute! ds ["PRAGMA optimize"]))
          filtered-document (measure-query doc-ds doc-filtered-sql opts)
          filtered-eav (measure-query eav-ds eav-filtered-sql opts)
          ready-document (measure-query doc-ds doc-ready-sql opts)
          ready-eav (measure-workload #(eav-ready-batched eav-ds opts) opts)
          list-document (measure-query doc-ds (list-sql :document list-ids) opts)
          list-eav (measure-query eav-ds (list-sql :eav list-ids) opts)
          write-amp (measure-write-amp doc-path eav-path opts)]
      {:started-at (str (Instant/now))
       :options opts
       :generation-ms generated
       :fixture-paths {:document doc-path :eav eav-path}
       :row-counts {:strands (:n opts)
                    :document-filtered (row-count doc-ds doc-filtered-sql)
                    :eav-filtered (row-count eav-ds eav-filtered-sql)
                    :document-ready (row-count doc-ds doc-ready-sql)
                    :eav-ready (row-count eav-ds eav-ready-sql)}
       :workloads {:write-amp write-amp
                   :filtered-scan {:document filtered-document :eav filtered-eav}
                   :ready {:document ready-document :eav ready-eav}
                   :list-assembly-500 {:document list-document :eav list-eav}}})))

(defn- pass? [result]
  (let [w (get-in result [:workloads :write-amp])
        filtered (get-in result [:workloads :filtered-scan])
        ready (get-in result [:workloads :ready])
        assembly (get-in result [:workloads :list-assembly-500])
        checks {:write-amp-payload-ge-16kb (>= (get-in w [:payload-ge-16kb :reduction]) 5.0)
                :write-amp-payload-independence (<= (:eav-256kb-vs-free-patch-bytes w) 1.5)
                :filtered-scan (<= (get-in filtered [:eav :median-ms])
                                   (get-in filtered [:document :median-ms]))
                :ready (<= (get-in ready [:eav :median-ms])
                           (* 1.5 (get-in ready [:document :median-ms])))
                :list-assembly-500 (<= (get-in assembly [:eav :median-ms])
                                       (* 2.0 (get-in assembly [:document :median-ms])))}]
    (assoc result
           :checks checks
           :passed? (every? true? (vals checks)))))

(defn- write-result! [out-dir result]
  (let [file (io/file out-dir "results.edn")]
    (spit file (with-out-str (pprint/pprint result)))
    (.getPath file)))

(defn -main [& args]
  (try
    (let [opts (parse-args args)]
      (if (:help opts)
        (println usage)
        (let [opts (validate-options! opts)
              result (pass? (run-benchmark opts))
              result-path (write-result! (:out opts) result)]
          (println "EAV benchmark result:" (if (:passed? result) "PASS" "FAIL"))
          (println "Result file:" result-path)
          (pprint/pprint (:checks result))
          (when-not (:passed? result)
            (System/exit 1)))))
    (catch Throwable t
      (binding [*out* *err*]
        (println (.getMessage t))
        (when-let [data (ex-data t)]
          (pprint/pprint data)))
      (System/exit 2))))
