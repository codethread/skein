(ns skein.db
  (:import [java.security SecureRandom])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [skein.query :as query]
            [skein.specs :as specs]))

(def default-db-file "skein.sqlite")

(def ^:private id-alphabet "abcdefghijklmnopqrstuvwxyz0123456789")
(def ^:private id-length 5)
(def ^:private max-id-attempts 32)
(def ^:private secure-random (SecureRandom.))

(defn generate-id []
  (apply str
         (repeatedly id-length
                     #(nth id-alphabet (.nextInt secure-random (count id-alphabet))))))

(defn datasource
  ([] (datasource default-db-file))
  ([db-file]
   (io/make-parents db-file)
   (jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:" db-file)})))

(defn execute! [ds sql-params]
  (jdbc/execute! ds sql-params {:builder-fn rs/as-unqualified-lower-maps}))

(defn execute-one! [ds sql-params]
  (jdbc/execute-one! ds sql-params {:builder-fn rs/as-unqualified-lower-maps}))

(defn- require-valid! [spec value message]
  (when-not (s/valid? spec value)
    (throw (ex-info message {:value value :explain (s/explain-str spec value)})))
  value)

(defn ->json [m]
  (require-valid! ::specs/attributes m "Attributes must be nil or a map that encodes to a JSON object")
  (json/write-str (or m {})))

(defn <-json [s]
  (json/read-str (or s "{}") :key-fn keyword))

(def schema-sql
  [["PRAGMA foreign_keys = ON"]
   ["CREATE TABLE IF NOT EXISTS strands (
       id TEXT PRIMARY KEY,
       title TEXT NOT NULL,
       active INTEGER NOT NULL DEFAULT 1,
       attributes TEXT NOT NULL DEFAULT '{}',
       created_at TEXT NOT NULL DEFAULT (datetime('now')),
       updated_at TEXT NOT NULL DEFAULT (datetime('now')),
       inactive_at TEXT,
       CHECK (active IN (0, 1)),
       CHECK ((active = 0 AND inactive_at IS NOT NULL)
              OR (active = 1 AND inactive_at IS NULL)),
       CHECK (json_valid(attributes))
     )"]
   ["CREATE TABLE IF NOT EXISTS strand_edges (
       from_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
       to_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
       edge_type TEXT NOT NULL,
       attributes TEXT NOT NULL DEFAULT '{}',
       PRIMARY KEY (from_strand_id, to_strand_id, edge_type),
       CHECK (edge_type IN ('depends-on', 'related-to', 'parent-of', 'supersedes')),
       CHECK (json_valid(attributes))
     )"]
   ["CREATE INDEX IF NOT EXISTS idx_strand_edges_to ON strand_edges(to_strand_id, edge_type)"]])

(def required-strand-columns #{"id" "title" "active" "attributes" "created_at" "updated_at" "inactive_at"})

(defn- ensure-current-schema! [ds]
  (let [columns (set (map :name (execute! ds ["PRAGMA table_info(strands)"])))
        missing (seq (remove columns required-strand-columns))]
    (when missing
      (throw (ex-info "Existing strands table is not compatible with the current schema; use a new database or migrate it explicitly."
                      {:missing-columns (vec missing)})))))

(defn init! [ds]
  (doseq [stmt schema-sql]
    (execute! ds stmt))
  (ensure-current-schema! ds)
  ds)


(declare get-strand add-edge!)

(def ^:private batch-strand-keys #{:title :active :attributes :ref :edges})
(def ^:private batch-edge-keys #{:type :to :attributes})

(defn- json-compatible? [value]
  (cond
    (nil? value) true
    (string? value) true
    (number? value) true
    (true? value) true
    (false? value) true
    (map? value) (and (every? #(or (keyword? %) (string? %)) (keys value))
                      (every? json-compatible? (vals value)))
    (vector? value) (every? json-compatible? value)
    (sequential? value) (every? json-compatible? value)
    :else false))

(defn- require-json-object-encodable! [attributes context]
  (when-not (or (nil? attributes)
                (and (map? attributes) (json-compatible? attributes)))
    (throw (ex-info "Attributes must be nil or an EDN map that encodes to a JSON object"
                    {:context context :attributes attributes})))
  attributes)

(defn- require-no-unknown-keys! [m allowed context]
  (let [unknown (seq (remove allowed (keys m)))]
    (when unknown
      (throw (ex-info "Unknown keys in batch input" {:context context :keys (vec unknown)})))))

(defn- validate-batch-edge! [edge]
  (when-not (map? edge)
    (throw (ex-info "Batch edge must be a map" {:edge edge})))
  (require-no-unknown-keys! edge batch-edge-keys :edge)
  (when-not (s/valid? ::specs/edge-type (:type edge))
    (throw (ex-info "Batch edge :type must be one of the allowed edge types"
                    {:edge edge :allowed specs/allowed-edge-types})))
  (when-not (or (symbol? (:to edge)) (string? (:to edge)))
    (throw (ex-info "Batch edge :to must be a symbol batch ref or string durable id" {:edge edge})))
  (require-json-object-encodable! (:attributes edge) :edge)
  edge)

(defn- validate-batch-strand! [strand]
  (when-not (map? strand)
    (throw (ex-info "Batch strand must be a map" {:strand strand})))
  (require-no-unknown-keys! strand batch-strand-keys :strand)
  (when-not (and (string? (:title strand)) (not (str/blank? (:title strand))))
    (throw (ex-info "Batch strand :title must be a non-blank string" {:strand strand})))
  (when (and (contains? strand :ref) (not (symbol? (:ref strand))))
    (throw (ex-info "Batch strand :ref must be a symbol" {:strand strand})))
  (require-json-object-encodable! (:attributes strand) :strand)
  (when-not (or (nil? (:edges strand)) (vector? (:edges strand)))
    (throw (ex-info "Batch strand :edges must be a vector" {:strand strand})))
  (doseq [edge (:edges strand)]
    (validate-batch-edge! edge))
  strand)

(defn- validate-batch! [strands]
  (when-not (vector? strands)
    (throw (ex-info "Batch input must be a vector of strand maps" {:value strands})))
  (when (empty? strands)
    (throw (ex-info "Batch input must contain at least one strand" {})))
  (doseq [strand strands]
    (validate-batch-strand! strand))
  (let [refs (keep :ref strands)
        duplicate-ref (->> refs frequencies (filter (fn [[_ n]] (> n 1))) ffirst)]
    (when duplicate-ref
      (throw (ex-info "Duplicate batch ref" {:ref duplicate-ref}))))
  strands)

(def strand-columns "id, title, active, attributes, created_at, updated_at, inactive_at")

(defn- require-boolean! [value field]
  (when-not (or (true? value) (false? value))
    (throw (ex-info "Lifecycle fields must be booleans" {:field field :value value})))
  value)

(defn- sqlite-bool [value]
  (if value 1 0))

(defn- row->booleans [row]
  (cond-> row
    (contains? row :active) (update :active #(case % 0 false 1 true %))))

(defn- insert-strand! [ds id title active attributes]
  (execute-one! ds
                [(str "INSERT INTO strands (id, title, active, attributes, inactive_at)
                       VALUES (?, ?, ?, json(?), CASE WHEN ? = 0 THEN datetime('now') ELSE NULL END)
                       RETURNING " strand-columns)
                 id title (sqlite-bool active) (->json attributes) (sqlite-bool active)]))


(defn- unique-strand-id-error? [^Exception e]
  (str/includes? (.getMessage e) "UNIQUE constraint failed: strands.id"))

(def ^:private strand-input-keys #{:title :active :attributes})
(def ^:private strand-patch-keys #{:title :active :attributes})
(def ^:private removed-lifecycle-fields #{:status :final_at})

(defn- reject-removed-lifecycle-fields! [m context]
  (when-let [fields (seq (filter removed-lifecycle-fields (keys m)))]
    (throw (ex-info "Removed lifecycle fields are not core strand fields"
                    {:context context :fields (vec fields)}))))

(defn- reject-unknown-strand-keys! [m allowed context]
  (let [unknown (seq (remove allowed (keys m)))]
    (when unknown
      (throw (ex-info "Unknown core strand fields" {:context context :fields (vec unknown)})))))

(defn add-strand! [ds {:keys [title active attributes] :as strand}]
  (reject-removed-lifecycle-fields! strand :create)
  (reject-unknown-strand-keys! strand strand-input-keys :create)
  (let [strand (merge {:active true} strand)]
    (require-valid! ::specs/strand-input strand "Invalid strand")
    (loop [attempt 1]
      (when (> attempt max-id-attempts)
        (throw (ex-info "Unable to generate unique strand id" {:attempts max-id-attempts})))
      (let [id (generate-id)
            result (try
                     [:created (row->booleans (insert-strand! ds id title (:active strand) attributes))]
                     (catch org.sqlite.SQLiteException e
                       (if (unique-strand-id-error? e)
                         [:retry nil]
                         (throw e))))]
        (case (first result)
          :created (second result)
          :retry (recur (inc attempt)))))))

(defn add-strand-with-edges! [ds strand edges]
  (jdbc/with-transaction [tx ds]
    (let [created-strand (add-strand! tx strand)]
      (doseq [{:keys [to type attributes]} edges]
        (when-not (get-strand tx to)
          (throw (ex-info "Link target strand not found" {:to to :type type})))
        (add-edge! tx {:from (:id created-strand)
                       :to to
                       :type type
                       :attributes attributes}))
      created-strand)))

(defn add-strand-batch! [ds strands]
  (validate-batch! strands)
  (jdbc/with-transaction [tx ds]
    (let [resolve-existing-ref (fn [refs value context]
                                 (cond
                                   (symbol? value) (or (get refs (str value))
                                                       (throw (ex-info "Batch ref not found; symbolic targets only resolve to batch refs, use a string for durable ids"
                                                                       {:context context :value value})))
                                   (string? value) (do
                                                     (when-not (get-strand tx value)
                                                       (throw (ex-info "Batch target strand not found" {:context context :value value})))
                                                     value)))]
      (loop [remaining strands
             created []
             refs {}]
        (if-let [strand (first remaining)]
          (let [{:keys [title active attributes]} strand
                created-strand (add-strand! tx (cond-> {:title title :attributes attributes}
                                                 (contains? strand :active) (assoc :active active)))
                refs (cond-> refs
                       (:ref strand) (assoc (str (:ref strand)) (:id created-strand)))]
            (recur (rest remaining) (conj created created-strand) refs))
          (do
            (doseq [[strand created-strand] (map vector strands created)
                    {:keys [to type attributes]} (:edges strand)]
              (let [resolved-to (resolve-existing-ref refs to :edge)]
                (add-edge! tx {:from (:id created-strand)
                               :to resolved-to
                               :type type
                               :attributes attributes})))
            {:created created
             :refs refs}))))))

(defn- path-exists? [ds from to]
  (boolean
   (execute-one! ds
                 ["WITH RECURSIVE reachable(id) AS (
                     SELECT to_strand_id
                     FROM strand_edges
                     WHERE from_strand_id = ?
                   UNION
                     SELECT e.to_strand_id
                     FROM reachable r
                     JOIN strand_edges e ON e.from_strand_id = r.id
                   )
                   SELECT 1 AS found
                   FROM reachable
                   WHERE id = ?
                   LIMIT 1"
                  from to])))

(defn- require-acyclic-edge! [ds from to type]
  (when (= from to)
    (throw (ex-info "Strand edges must not point to the same strand" {:from from :to to :type type})))
  (when (path-exists? ds to from)
    (throw (ex-info "Strand edge would create a cycle" {:from from :to to :type type}))))

(defn add-edge! [ds {:keys [from to type attributes] :as edge}]
  (require-valid! ::specs/edge-input edge "Invalid edge")
  (require-acyclic-edge! ds from to type)
  (execute-one! ds
                ["INSERT INTO strand_edges (from_strand_id, to_strand_id, edge_type, attributes)
                  VALUES (?, ?, ?, json(?))
                  ON CONFLICT(from_strand_id, to_strand_id, edge_type) DO UPDATE SET attributes = excluded.attributes
                  RETURNING from_strand_id, to_strand_id, edge_type, attributes"
                 from to type (->json attributes)]))

(defn get-strand [ds strand-id]
  (some-> (execute-one! ds
                        [(str "SELECT " strand-columns " FROM strands WHERE id = ?")
                         strand-id])
          row->booleans))

(defn- require-updated-strand [strand-id row]
  (or row
      (throw (ex-info "Strand not found" {:strand-id strand-id}))))

(defn update-strand! [ds strand-id {:keys [title active attributes] :as patch}]
  (reject-removed-lifecycle-fields! patch :update)
  (reject-unknown-strand-keys! patch strand-patch-keys :update)
  (when (and title (str/blank? title))
    (throw (ex-info "Strand title must be non-blank" {:title title})))
  (when (contains? patch :active)
    (require-boolean! active :active))
  (require-updated-strand strand-id (get-strand ds strand-id))
  (row->booleans
   (require-updated-strand
    strand-id
    (execute-one! ds
                  [(str "UPDATE strands
                         SET title = COALESCE(?, title),
                             active = COALESCE(?, active),
                             attributes = CASE WHEN ? IS NULL THEN attributes ELSE json_patch(attributes, json(?)) END,
                             updated_at = datetime('now'),
                             inactive_at = CASE
                               WHEN COALESCE(?, active) = 0 THEN COALESCE(inactive_at, datetime('now'))
                               ELSE NULL
                             END
                         WHERE id = ?
                         RETURNING " strand-columns)
                   title (when (contains? patch :active) (sqlite-bool active))
                   (when attributes (->json attributes)) (when attributes (->json attributes))
                   (when (contains? patch :active) (sqlite-bool active)) strand-id]))))


(defn query-strands
  ([ds query-def]
   (query-strands ds query-def {}))
  ([ds query-def params]
   (let [{:keys [sql params]} (query/compile-query query-def params)]
     (mapv row->booleans
           (execute! ds (into [(str "SELECT " strand-columns " FROM strands t WHERE " sql " ORDER BY t.id")]
                              params))))))

(defn query-strand-ids
  ([ds query-def]
   (query-strand-ids ds query-def {}))
  ([ds query-def params]
   (let [{:keys [sql params]} (query/compile-query query-def params)]
     (mapv :id (execute! ds (into [(str "SELECT t.id FROM strands t WHERE " sql " ORDER BY t.id")]
                                  params))))))

(defn- ordered-distinct [xs]
  (loop [remaining xs
         seen #{}
         result []]
    (if-let [items (seq remaining)]
      (let [x (first items)]
        (if (contains? seen x)
          (recur (rest items) seen result)
          (recur (rest items) (conj seen x) (conj result x))))
      result)))

(defn- placeholders [xs]
  (str/join ", " (repeat (count xs) "?")))

(defn- require-existing-strand-ids! [ds ids context]
  (let [ids (ordered-distinct ids)]
    (when (seq ids)
      (let [found (set (map :id (execute! ds (into [(str "SELECT id FROM strands WHERE id IN (" (placeholders ids) ")")]
                                                ids))))
            missing (vec (remove found ids))]
        (when (seq missing)
          (throw (ex-info "Strand ids not found" {:context context :missing missing})))))
    ids))

(defn burn-by-ids! [ds ids]
  (let [ids (require-existing-strand-ids! ds ids :burn-by-ids)]
    (jdbc/with-transaction [tx ds]
      (doseq [id ids]
        (execute! tx ["DELETE FROM strand_edges WHERE from_strand_id = ? OR to_strand_id = ?" id id])
        (execute! tx ["DELETE FROM strands WHERE id = ?" id]))
      {:burned ids :count (count ids)})))

(defn burn-by-id! [ds id]
  (burn-by-ids! ds [id]))

(defn strands-by-ids [ds ids]
  (let [ids (require-existing-strand-ids! ds ids :strands-by-ids)]
    (if (empty? ids)
      []
      (let [rows-by-id (into {}
                             (map (juxt :id identity))
                             (execute! ds (into [(str "SELECT " strand-columns " FROM strands WHERE id IN (" (placeholders ids) ")")]
                                                ids)))]
        (mapv (comp row->booleans rows-by-id) ids)))))

(defn ancestor-root-ids
  ([ds seed-ids]
   (ancestor-root-ids ds seed-ids {}))
  ([ds seed-ids {:keys [where params]}]
   (let [seed-ids (require-existing-strand-ids! ds seed-ids :ancestor-root-ids)]
     (if (empty? seed-ids)
       []
       (if where
         (let [{where-sql :sql where-params :params} (query/compile-query where (or params {}))]
           (mapv :id
                 (execute! ds (into [(str "WITH RECURSIVE paths(id, candidate_id) AS (
                                           SELECT t.id,
                                                  CASE WHEN " where-sql " THEN t.id ELSE NULL END
                                           FROM strands t
                                           WHERE t.id IN (" (placeholders seed-ids) ")
                                         UNION ALL
                                           SELECT t.id,
                                                  CASE WHEN " where-sql " THEN t.id ELSE paths.candidate_id END
                                           FROM paths
                                           JOIN strand_edges e ON e.to_strand_id = paths.id
                                           JOIN strands t ON t.id = e.from_strand_id
                                           WHERE e.edge_type = 'parent-of'
                                         )
                                         SELECT DISTINCT candidate_id AS id
                                         FROM paths
                                         WHERE candidate_id IS NOT NULL
                                           AND NOT EXISTS (
                                             SELECT 1
                                             FROM strand_edges e
                                             WHERE e.to_strand_id = paths.id
                                               AND e.edge_type = 'parent-of'
                                           )
                                         ORDER BY id")]
                                    (concat where-params seed-ids where-params)))))
         (mapv :id
               (execute! ds (into [(str "WITH RECURSIVE ancestors(id) AS (
                                         SELECT id FROM strands WHERE id IN (" (placeholders seed-ids) ")
                                       UNION
                                         SELECT e.from_strand_id
                                         FROM ancestors a
                                         JOIN strand_edges e ON e.to_strand_id = a.id
                                         WHERE e.edge_type = 'parent-of'
                                       )
                                       SELECT a.id
                                       FROM ancestors a
                                       WHERE NOT EXISTS (
                                         SELECT 1
                                         FROM strand_edges e
                                         WHERE e.to_strand_id = a.id AND e.edge_type = 'parent-of'
                                       )
                                       ORDER BY a.id")]
                                  seed-ids))))))))

(defn subgraph [ds root-ids]
  (let [root-ids (require-existing-strand-ids! ds root-ids :subgraph)]
    (if (empty? root-ids)
      {:root-ids [] :strands [] :edges []}
      (let [cte (str "WITH RECURSIVE nodes(id) AS (
                       SELECT id FROM strands WHERE id IN (" (placeholders root-ids) ")
                     UNION
                       SELECT e.to_strand_id
                       FROM nodes n
                       JOIN strand_edges e ON e.from_strand_id = n.id
                       WHERE e.edge_type = 'parent-of'
                     )")]
        (let [rows (mapv row->booleans
                         (execute! ds (into [(str cte "
                                      SELECT " strand-columns "
                                      FROM strands
                                      WHERE id IN (SELECT id FROM nodes)
                                      ORDER BY id")]
                                            root-ids)))
              edges (execute! ds (into [(str cte "
                                      SELECT e.from_strand_id, e.to_strand_id,
                                             e.edge_type, e.attributes
                                      FROM strand_edges e
                                      WHERE e.edge_type = 'parent-of'
                                        AND e.from_strand_id IN (SELECT id FROM nodes)
                                        AND e.to_strand_id IN (SELECT id FROM nodes)
                                      ORDER BY e.from_strand_id, e.to_strand_id, e.edge_type")]
                                        root-ids))]
          {:root-ids root-ids
           :strands rows
           :edges edges})))))

(defn all-strands
  ([ds]
   (mapv row->booleans (execute! ds [(str "SELECT " strand-columns " FROM strands ORDER BY id")])))
  ([ds query-def]
   (query-strands ds query-def {}))
  ([ds query-def params]
   (query-strands ds query-def params)))

(defn ready-strands
  ([ds]
   (mapv row->booleans
         (execute! ds
                   [(str "SELECT " strand-columns "
               FROM strands t
               WHERE t.active = 1
                 AND NOT EXISTS (
                   SELECT 1
                   FROM strand_edges e
                   JOIN strands dep ON dep.id = e.to_strand_id
                   WHERE e.from_strand_id = t.id
                     AND e.edge_type = 'depends-on'
                     AND dep.active = 1
                 )
               ORDER BY t.id")])) )
  ([ds query-def]
   (ready-strands ds query-def {}))
  ([ds query-def params]
   (let [{query-sql :sql query-params :params} (query/compile-query query-def params)]
     (mapv row->booleans
           (execute! ds
                     (into [(str "SELECT " strand-columns "
                 FROM strands t
                 WHERE t.active = 1
                   AND NOT EXISTS (
                     SELECT 1
                     FROM strand_edges e
                     JOIN strands dep ON dep.id = e.to_strand_id
                     WHERE e.from_strand_id = t.id
                       AND e.edge_type = 'depends-on'
                       AND dep.active = 1
                   )
                   AND " query-sql "
                 ORDER BY t.id")]
                           query-params))))))

