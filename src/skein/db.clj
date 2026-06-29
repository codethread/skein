(ns skein.db
  "SQLite persistence for strands, edges, relation metadata, and graph queries."
  (:import [java.security SecureRandom])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [skein.query :as query]
            [skein.specs :as specs]))

(def ^:private default-db-file "skein.sqlite")

(def ^:private id-alphabet "abcdefghijklmnopqrstuvwxyz0123456789")
(def ^:private id-length 5)
(def ^:private max-id-attempts 32)
(def ^:private secure-random (SecureRandom.))

(defn- generate-id
  "Return a random short strand id candidate."
  []
  (apply str
         (repeatedly id-length
                     #(nth id-alphabet (.nextInt secure-random (count id-alphabet))))))

(defn datasource
  "Create a SQLite datasource for db-file, creating parent directories first.

  With no argument, uses the internal default database filename."
  ([] (datasource default-db-file))
  ([db-file]
   (io/make-parents db-file)
   (jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:" db-file)})))

(defn execute!
  "Execute SQL params against ds and return unqualified lower-case map rows."
  [ds sql-params]
  (jdbc/execute! ds sql-params {:builder-fn rs/as-unqualified-lower-maps}))

(defn execute-one!
  "Execute SQL params against ds and return one unqualified lower-case map row."
  [ds sql-params]
  (jdbc/execute-one! ds sql-params {:builder-fn rs/as-unqualified-lower-maps}))

(defn- require-valid! [spec value message]
  (when-not (s/valid? spec value)
    (throw (ex-info message {:value value :explain (s/explain-str spec value)})))
  value)

(defn ->json
  "Encode an attribute map as JSON object text.

  Nil encodes as an empty JSON object. Invalid attribute values throw."
  [m]
  (require-valid! ::specs/attributes m "Attributes must be nil or a map that encodes to a JSON object")
  (json/write-str (or m {})))

(defn <-json
  "Decode JSON object text into a keyword-keyed Clojure map.

  Nil decodes as an empty map."
  [s]
  (json/read-str (or s "{}") :key-fn keyword))

(def ^:private schema-sql
  [["PRAGMA foreign_keys = ON"]
   ["CREATE TABLE IF NOT EXISTS strands (
       id TEXT PRIMARY KEY,
       title TEXT NOT NULL,
       state TEXT NOT NULL DEFAULT 'active',
       attributes TEXT NOT NULL DEFAULT '{}',
       created_at TEXT NOT NULL DEFAULT (datetime('now')),
       updated_at TEXT NOT NULL DEFAULT (datetime('now')),
       CHECK (state IN ('active', 'closed', 'replaced')),
       CHECK (json_valid(attributes))
     )"]
   ["CREATE TABLE IF NOT EXISTS strand_edges (
       from_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
       to_strand_id TEXT NOT NULL REFERENCES strands(id) ON DELETE CASCADE,
       edge_type TEXT NOT NULL,
       attributes TEXT NOT NULL DEFAULT '{}',
       PRIMARY KEY (from_strand_id, to_strand_id, edge_type),
       CHECK (json_valid(attributes))
     )"]
   ["CREATE INDEX IF NOT EXISTS idx_strand_edges_to ON strand_edges(to_strand_id, edge_type)"]
   ["CREATE TABLE IF NOT EXISTS acyclic_relations (
       relation TEXT PRIMARY KEY
     )"]])

(def ^:private required-strand-columns #{"id" "title" "state" "attributes" "created_at" "updated_at"})
(def ^:private required-edge-columns #{"from_strand_id" "to_strand_id" "edge_type" "attributes"})
(def ^:private shipped-acyclic-relations #{"depends-on" "parent-of" "supersedes"})

(defn- missing-columns [ds table required]
  (seq (remove (set (map :name (execute! ds [(str "PRAGMA table_info(" table ")")]))) required)))

(defn- ensure-current-schema! [ds]
  (when-let [missing (missing-columns ds "strands" required-strand-columns)]
    (throw (ex-info "Existing strands table is not compatible with the current schema; use a new database or migrate it explicitly."
                    {:missing-columns (vec missing)})))
  (let [edge-schema (:sql (execute-one! ds ["SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'strand_edges'"]))]
    (when (or (missing-columns ds "strand_edges" required-edge-columns)
              (str/includes? edge-schema "CHECK (edge_type IN"))
      (throw (ex-info "Existing strand_edges table is not compatible with the current schema; use a new database or migrate it explicitly." {})))))

(declare bootstrap-acyclic-relation!)

(defn init!
  "Initialize ds with the current schema and shipped acyclic relations.

  Existing incompatible schemas throw instead of being migrated implicitly."
  [ds]
  (doseq [stmt schema-sql]
    (execute! ds stmt))
  (ensure-current-schema! ds)
  (doseq [relation shipped-acyclic-relations]
    (bootstrap-acyclic-relation! ds relation))
  ds)


(declare get-strand update-strand! add-edge! strands-by-ids require-updated-strand require-existing-strand-ids!)

(def ^:private batch-strand-keys #{:title :state :attributes :ref :edges})
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
    (throw (ex-info "Batch edge :type must be a valid relation name"
                    {:edge edge})))
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

(def ^:private strand-columns "id, title, state, attributes, created_at, updated_at")

(def ^:private generic-states #{"active" "closed"})

(defn- require-generic-state! [value field]
  (when-not (contains? generic-states value)
    (throw (ex-info "Lifecycle state must be active or closed" {:field field :value value :allowed generic-states})))
  value)

(defn- insert-strand! [ds id title state attributes]
  (execute-one! ds
                [(str "INSERT INTO strands (id, title, state, attributes)
                       VALUES (?, ?, ?, json(?))
                       RETURNING " strand-columns)
                 id title state (->json attributes)]))


(defn- unique-strand-id-error? [^Exception e]
  (str/includes? (.getMessage e) "UNIQUE constraint failed: strands.id"))

(def ^:private strand-input-keys #{:title :state :attributes})
(def ^:private strand-patch-keys #{:title :state :attributes})
(def ^:private removed-lifecycle-fields #{:status :final_at :active :inactive_at})

(defn- reject-removed-lifecycle-fields! [m context]
  (when-let [fields (seq (filter removed-lifecycle-fields (keys m)))]
    (throw (ex-info "Removed lifecycle fields are not core strand fields"
                    {:context context :fields (vec fields)}))))

(defn- reject-unknown-strand-keys! [m allowed context]
  (let [unknown (seq (remove allowed (keys m)))]
    (when unknown
      (throw (ex-info "Unknown core strand fields" {:context context :fields (vec unknown)})))))

(defn add-strand!
  "Create a strand row and return it.

  Generates a unique id, defaults missing state to active, validates core strand fields,
  and throws if an id cannot be allocated after bounded retries."
  [ds {:keys [title state attributes] :as strand}]
  (reject-removed-lifecycle-fields! strand :create)
  (reject-unknown-strand-keys! strand strand-input-keys :create)
  (let [strand (merge {:state "active"} strand)]
    (require-valid! ::specs/strand-input strand "Invalid strand")
    (loop [attempt 1]
      (when (> attempt max-id-attempts)
        (throw (ex-info "Unable to generate unique strand id" {:attempts max-id-attempts})))
      (let [id (generate-id)
            result (try
                     [:created (insert-strand! ds id title (:state strand) attributes)]
                     (catch org.sqlite.SQLiteException e
                       (if (unique-strand-id-error? e)
                         [:retry nil]
                         (throw e))))]
        (case (first result)
          :created (second result)
          :retry (recur (inc attempt)))))))

(defn- add-strand-with-edges!
  [ds strand edges]
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

(defn add-strand-batch!
  "Create multiple strands and their batch-local edges in one transaction.

  Symbolic :ref values may be used by edge targets anywhere in the batch."
  [ds strands]
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
          (let [{:keys [title state attributes]} strand
                created-strand (add-strand! tx (cond-> {:title title :attributes attributes}
                                                 (contains? strand :state) (assoc :state state)))
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

(defn- path-exists? [ds type from to]
  (boolean
   (execute-one! ds
                 ["WITH RECURSIVE reachable(id) AS (
                     SELECT to_strand_id
                     FROM strand_edges
                     WHERE from_strand_id = ?
                       AND edge_type = ?
                   UNION
                     SELECT e.to_strand_id
                     FROM reachable r
                     JOIN strand_edges e ON e.from_strand_id = r.id
                     WHERE e.edge_type = ?
                   )
                   SELECT 1 AS found
                   FROM reachable
                   WHERE id = ?
                   LIMIT 1"
                  from type type to])))

(defn acyclic-relation?
  "Return true when relation is declared acyclic in ds."
  [ds relation]
  (boolean (execute-one! ds ["SELECT 1 AS found FROM acyclic_relations WHERE relation = ?" relation])))

(defn list-acyclic-relations
  "Return all declared acyclic relation names in sorted order."
  [ds]
  (mapv :relation (execute! ds ["SELECT relation FROM acyclic_relations ORDER BY relation"])))

(defn- require-valid-relation-name! [relation]
  (when-not (s/valid? ::specs/edge-type relation)
    (throw (ex-info "Relation must be a valid relation name" {:relation relation})))
  relation)

(defn- require-existing-relation-acyclic! [ds relation]
  (doseq [{:keys [from_strand_id to_strand_id]} (execute! ds ["SELECT from_strand_id, to_strand_id FROM strand_edges WHERE edge_type = ?" relation])]
    (when (path-exists? ds relation to_strand_id from_strand_id)
      (throw (ex-info "Existing relation contains a cycle" {:relation relation :from from_strand_id :to to_strand_id})))))

(defn- insert-acyclic-relation! [ds relation]
  (execute-one! ds ["INSERT INTO acyclic_relations (relation) VALUES (?) RETURNING relation" relation])
  {:relation relation :acyclic true})

(defn- bootstrap-acyclic-relation! [ds relation]
  (if (acyclic-relation? ds relation)
    {:relation relation :acyclic true}
    (do
      (require-existing-relation-acyclic! ds relation)
      (insert-acyclic-relation! ds relation))))

(defn declare-acyclic-relation!
  "Declare relation acyclic before edges of that relation exist.

  Existing declarations are idempotent. Invalid relation names or late declarations throw."
  [ds relation]
  (require-valid-relation-name! relation)
  (if (acyclic-relation? ds relation)
    {:relation relation :acyclic true}
    (do
      (when (execute-one! ds ["SELECT 1 AS found FROM strand_edges WHERE edge_type = ? LIMIT 1" relation])
        (throw (ex-info "Cannot declare relation acyclic after edges of that relation exist" {:relation relation})))
      (insert-acyclic-relation! ds relation))))

(defn- require-acyclic-edge! [ds from to type]
  (when (= from to)
    (throw (ex-info "Strand edges must not point to the same strand" {:from from :to to :type type})))
  (when (and (acyclic-relation? ds type)
             (path-exists? ds type to from))
    (throw (ex-info "Strand edge would create a cycle" {:from from :to to :type type}))))

(defn add-edge!
  "Upsert an edge row and return it.

  Validates endpoints, attributes, and acyclicity for declared acyclic relations."
  [ds {:keys [from to type attributes] :as edge}]
  (require-valid! ::specs/edge-input edge "Invalid edge")
  (require-existing-strand-ids! ds [from to] :edge)
  (require-acyclic-edge! ds from to type)
  (execute-one! ds
                ["INSERT INTO strand_edges (from_strand_id, to_strand_id, edge_type, attributes)
                  VALUES (?, ?, ?, json(?))
                  ON CONFLICT(from_strand_id, to_strand_id, edge_type) DO UPDATE SET attributes = excluded.attributes
                  RETURNING from_strand_id, to_strand_id, edge_type, attributes"
                 from to type (->json attributes)]))

(defn- edge-row [ds from to type]
  (execute-one! ds
                ["SELECT from_strand_id, to_strand_id, edge_type, attributes
                  FROM strand_edges
                  WHERE from_strand_id = ?
                    AND to_strand_id = ?
                    AND edge_type = ?"
                 from to type]))

(defn- delete-edge! [ds from to type]
  (let [row (edge-row ds from to type)]
    (when row
      (execute! ds
                ["DELETE FROM strand_edges
                  WHERE from_strand_id = ?
                    AND to_strand_id = ?
                    AND edge_type = ?"
                 from to type]))
    row))

(defn- set-strand-state-internal! [ds strand-id state]
  (require-updated-strand
    strand-id
    (execute-one! ds
                  [(str "UPDATE strands
                         SET state = ?,
                             updated_at = datetime('now')
                         WHERE id = ?
                         RETURNING " strand-columns)
                   state strand-id])))

(defn ^:no-doc supersede-strand-in-transaction!
  [tx old-id replacement-id]
  (when (= old-id replacement-id)
    (throw (ex-info "A strand cannot supersede itself" {:old-id old-id :replacement-id replacement-id})))
  (let [old-before (or (get-strand tx old-id)
                       (throw (ex-info "Old strand not found" {:old-id old-id})))
        replacement (or (get-strand tx replacement-id)
                        (throw (ex-info "Replacement strand not found" {:replacement-id replacement-id})))]
    (when (= "replaced" (:state old-before))
      (throw (ex-info "Old strand is already replaced" {:old-id old-id :state (:state old-before)})))
    (when-not (= "active" (:state replacement))
      (throw (ex-info "Replacement strand must be active" {:replacement-id replacement-id :state (:state replacement)})))
    (let [supersedes-edge (add-edge! tx {:from replacement-id
                                         :to old-id
                                         :type "supersedes"
                                         :attributes {}})
          old-after (set-strand-state-internal! tx old-id "replaced")
          incoming-depends (execute! tx ["SELECT from_strand_id, to_strand_id, edge_type, attributes
                                          FROM strand_edges
                                          WHERE to_strand_id = ?
                                            AND edge_type = 'depends-on'
                                          ORDER BY from_strand_id"
                                       old-id])
          rewired (mapv (fn [edge]
                          (let [dependent (:from_strand_id edge)
                                existing-edge (edge-row tx dependent replacement-id "depends-on")
                                new-edge (or existing-edge
                                             (add-edge! tx {:from dependent
                                                            :to replacement-id
                                                            :type "depends-on"
                                                            :attributes (some-> (:attributes edge) <-json)}))
                                deleted-edge (delete-edge! tx dependent old-id "depends-on")]
                            {:from dependent
                             :old-to old-id
                             :new-to replacement-id
                             :type "depends-on"
                             :deleted-edge deleted-edge
                             :edge new-edge}))
                        incoming-depends)]
      {:old {:before old-before :after old-after}
       :replacement-id replacement-id
       :supersedes-edge supersedes-edge
       :rewired-dependencies rewired})))

(defn supersede-strand!
  "Mark old-id as replaced by replacement-id and rewire incoming dependencies.

  Creates a supersedes edge from replacement to old and returns before/after details."
  [ds old-id replacement-id]
  (jdbc/with-transaction [tx ds]
    (supersede-strand-in-transaction! tx old-id replacement-id)))

(defn get-strand
  "Return the strand row for strand-id, or nil when it does not exist."
  [ds strand-id]
  (execute-one! ds
                [(str "SELECT " strand-columns " FROM strands WHERE id = ?")
                 strand-id]))

(defn- require-updated-strand
  "Return row or throw a Strand not found error for strand-id."
  [strand-id row]
  (or row
      (throw (ex-info "Strand not found" {:strand-id strand-id}))))

(defn update-strand!
  "Patch a strand row and return the updated row.

  Attribute patches are merged with SQLite json_patch. Unknown or removed core fields throw."
  [ds strand-id {:keys [title state attributes] :as patch}]
  (reject-removed-lifecycle-fields! patch :update)
  (reject-unknown-strand-keys! patch strand-patch-keys :update)
  (when (and title (str/blank? title))
    (throw (ex-info "Strand title must be non-blank" {:title title})))
  (when (contains? patch :state)
    (require-generic-state! state :state))
  (require-updated-strand strand-id (get-strand ds strand-id))
  (require-updated-strand
    strand-id
    (execute-one! ds
                  [(str "UPDATE strands
                         SET title = COALESCE(?, title),
                             state = COALESCE(?, state),
                             attributes = CASE WHEN ? IS NULL THEN attributes ELSE json_patch(attributes, json(?)) END,
                             updated_at = datetime('now')
                         WHERE id = ?
                         RETURNING " strand-columns)
                   title (when (contains? patch :state) state)
                   (when attributes (->json attributes)) (when attributes (->json attributes))
                   strand-id])))


(defn query-strands
  "Return strand rows matching query-def and optional query params."
  ([ds query-def]
   (query-strands ds query-def {}))
  ([ds query-def params]
   (let [{:keys [sql params]} (query/compile-query query-def params)]
     (mapv identity
           (execute! ds (into [(str "SELECT " strand-columns " FROM strands t WHERE " sql " ORDER BY t.id")]
                              params))))))

(defn query-strand-ids
  "Return sorted strand ids matching query-def and optional query params."
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

(defn- delete-strands! [ds ids]
  (doseq [id ids]
    (execute! ds ["DELETE FROM strand_edges WHERE from_strand_id = ? OR to_strand_id = ?" id id])
    (execute! ds ["DELETE FROM strands WHERE id = ?" id])))

(defn burn-by-ids!
  "Delete existing strands by id and return burn metadata."
  [ds ids]
  (let [ids (require-existing-strand-ids! ds ids :burn-by-ids)]
    (jdbc/with-transaction [tx ds]
      (delete-strands! tx ids)
      {:burned ids :count (count ids)})))

(defn burn-by-id!
  "Delete one existing strand by id and return burn metadata."
  [ds id]
  (burn-by-ids! ds [id]))

(def ^:private batch-mutation-top-level-keys #{:refs :strands :edges :burn})
(def ^:private batch-mutation-strand-keys #{:ref :title :state :attributes})
(def ^:private batch-mutation-edge-keys #{:op :from :to :type :attributes})

(defn- require-batch-ref! [ref context]
  (when-not (and (keyword? ref)
                 (nil? (namespace ref))
                 (not (str/blank? (name ref))))
    (throw (ex-info "Batch refs must be unqualified non-blank keywords"
                    {:context context :ref ref})))
  ref)

(defn- require-vector-section! [value section]
  (when-not (vector? value)
    (throw (ex-info "Batch section must be a vector" {:section section :value value})))
  value)

(defn- require-map-section! [value section]
  (when-not (map? value)
    (throw (ex-info "Batch section must be a map" {:section section :value value})))
  value)

(defn- duplicate-keys-by-value [m]
  (->> m
       (group-by val)
       (keep (fn [[id entries]]
               (when (< 1 (count entries))
                 {:id id :refs (mapv key entries)})))
       vec))

(defn- duplicate-item [xs]
  (->> xs frequencies (some (fn [[x n]] (when (< 1 n) x)))))

(defn- section [payload k default require-fn]
  (require-fn (if (contains? payload k) (get payload k) default) k))

(defn- valid-title? [title]
  (and (string? title) (not (str/blank? title))))

(defn ^:no-doc normalize-batch-payload! [payload]
  (when-not (map? payload)
    (throw (ex-info "Batch payload must be a map" {:value payload})))
  (require-no-unknown-keys! payload batch-mutation-top-level-keys :batch)
  (let [refs (section payload :refs {} require-map-section!)
        strands (section payload :strands [] require-vector-section!)
        edges (section payload :edges [] require-vector-section!)
        burn (section payload :burn [] require-vector-section!)]
    (doseq [[ref id] refs]
      (require-batch-ref! ref :refs)
      (when-not (string? id)
        (throw (ex-info "Batch ref targets must be durable strand id strings" {:ref ref :id id}))))
    (when-let [dupes (seq (duplicate-keys-by-value refs))]
      (throw (ex-info "Multiple batch refs cannot alias the same existing strand id" {:duplicates dupes})))
    (doseq [[idx strand] (map-indexed vector strands)]
      (when-not (map? strand)
        (throw (ex-info "Batch strand entry must be a map" {:index idx :strand strand})))
      (reject-removed-lifecycle-fields! strand :batch-strand)
      (reject-unknown-strand-keys! strand batch-mutation-strand-keys :batch-strand)
      (when-not (contains? strand :ref)
        (throw (ex-info "Batch strand entry requires :ref" {:index idx :strand strand})))
      (require-batch-ref! (:ref strand) :strands)
      (when (contains? strand :title)
        (when-not (valid-title? (:title strand))
          (throw (ex-info "Batch strand :title must be a non-blank string" {:index idx :strand strand}))))
      (when (contains? strand :state)
        (require-generic-state! (:state strand) :state))
      (when (contains? strand :attributes)
        (require-json-object-encodable! (:attributes strand) :strand)))
    (when-let [duplicate-ref (duplicate-item (map :ref strands))]
      (throw (ex-info "Duplicate batch strand ref" {:ref duplicate-ref})))
    (doseq [[idx ref] (map-indexed vector burn)]
      (require-batch-ref! ref {:section :burn :index idx}))
    (when-let [duplicate-ref (duplicate-item burn)]
      (throw (ex-info "Duplicate batch burn ref" {:ref duplicate-ref})))
    (doseq [[idx edge] (map-indexed vector edges)]
      (when-not (map? edge)
        (throw (ex-info "Batch edge entry must be a map" {:index idx :edge edge})))
      (require-no-unknown-keys! edge batch-mutation-edge-keys :batch-edge)
      (when-not (= :upsert (:op edge))
        (throw (ex-info "Unsupported batch edge operation" {:index idx :op (:op edge)})))
      (doseq [k [:from :to]]
        (when-not (contains? edge k)
          (throw (ex-info "Batch edge endpoint is required" {:index idx :field k :edge edge})))
        (require-batch-ref! (get edge k) {:section :edges :index idx :field k}))
      (when-not (s/valid? ::specs/edge-type (:type edge))
        (throw (ex-info "Batch edge :type must be a valid relation name"
                        {:index idx :edge edge})))
      (require-json-object-encodable! (:attributes edge) :edge))
    {:refs refs :strands strands :edges edges :burn burn}))

(defn ^:no-doc apply-batch-in-transaction!
  [tx payload]
  (let [{:keys [refs strands edges burn]} (normalize-batch-payload! payload)]
    (require-existing-strand-ids! tx (vals refs) :batch-refs)
    (let [strand-refs (set (map :ref strands))
          burn-refs (set burn)
          bound-refs (set (keys refs))
          known-refs (into bound-refs strand-refs)]
      (doseq [strand strands]
        (when-not (contains? refs (:ref strand))
          (when-not (valid-title? (:title strand))
            (throw (ex-info "Batch strand create requires a non-blank :title"
                            {:ref (:ref strand) :strand strand})))))
      (doseq [ref burn]
        (when-not (contains? refs ref)
          (throw (ex-info "Batch burn refs must name existing bound refs" {:ref ref}))))
      (when-let [ref (first (filter strand-refs burn-refs))]
        (throw (ex-info "Batch ref cannot be both mutated and burned" {:ref ref})))
      (doseq [{:keys [from to]} edges]
        (doseq [ref [from to]]
          (when-not (contains? known-refs ref)
            (throw (ex-info "Batch edge references unknown ref" {:ref ref})))
          (when (contains? burn-refs ref)
            (throw (ex-info "Batch edge cannot reference a burned ref" {:ref ref})))))
      (let [{final-refs :refs created-rows :rows}
            (reduce (fn [acc strand]
                      (if (contains? refs (:ref strand))
                        acc
                        (let [created-row (add-strand! tx (select-keys strand [:title :state :attributes]))]
                          (-> acc
                              (update :refs assoc (:ref strand) (:id created-row))
                              (update :rows conj created-row)))))
                    {:refs refs :rows []}
                    strands)
            updated (->> strands
                         (keep (fn [strand]
                                 (when-let [id (get refs (:ref strand))]
                                   {:ref (:ref strand)
                                    :id id
                                    :before (get-strand tx id)
                                    :after (update-strand! tx id (dissoc strand :ref))})))
                         vec)
            edge-outcomes (mapv (fn [edge]
                                  (let [from-id (get final-refs (:from edge))
                                        to-id (get final-refs (:to edge))]
                                    {:op :upsert
                                     :from (:from edge)
                                     :to (:to edge)
                                     :type (:type edge)
                                     :edge (add-edge! tx {:from from-id
                                                          :to to-id
                                                          :type (:type edge)
                                                          :attributes (:attributes edge)})}))
                                edges)
            burn-ids (mapv final-refs burn)
            burned-rows (strands-by-ids tx burn-ids)]
        (delete-strands! tx burn-ids)
        {:refs final-refs
         :created created-rows
         :updated updated
         :burned (mapv (fn [ref id row] {:ref ref :id id :before row}) burn burn-ids burned-rows)
         :edges edge-outcomes}))))

(defn apply-batch!
  "Apply a mixed batch mutation transaction.

  Payload refs bind existing and newly-created strands, edges are upserted, and burns
  delete strands after mutation validation succeeds."
  [ds payload]
  (jdbc/with-transaction [tx ds]
    (apply-batch-in-transaction! tx payload)))

(defn strands-by-ids
  "Return existing strand rows in first-seen id order.

  Duplicate ids are collapsed by first occurrence. Missing ids throw."
  [ds ids]
  (let [ids (require-existing-strand-ids! ds ids :strands-by-ids)]
    (if (empty? ids)
      []
      (let [rows-by-id (into {}
                             (map (juxt :id identity))
                             (execute! ds (into [(str "SELECT " strand-columns " FROM strands WHERE id IN (" (placeholders ids) ")")]
                                                ids)))]
        (mapv rows-by-id ids)))))

(defn ancestor-root-ids
  "Return root ancestor ids for seed-ids along an acyclic relation.

  Optional opts support :type and a query :where filter with :params."
  ([ds seed-ids]
   (ancestor-root-ids ds seed-ids {}))
  ([ds seed-ids {:keys [where params type] :or {type "parent-of"}}]
   (require-valid-relation-name! type)
   (when-not (acyclic-relation? ds type)
     (throw (ex-info "Graph traversal requires a declared acyclic relation" {:relation type})))
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
                                           WHERE e.edge_type = ?
                                         )
                                         SELECT DISTINCT candidate_id AS id
                                         FROM paths
                                         WHERE candidate_id IS NOT NULL
                                           AND NOT EXISTS (
                                             SELECT 1
                                             FROM strand_edges e
                                             WHERE e.to_strand_id = paths.id
                                               AND e.edge_type = ?
                                           )
                                         ORDER BY id")]
                                    (concat where-params seed-ids where-params [type type])))))
         (mapv :id
               (execute! ds (into [(str "WITH RECURSIVE ancestors(id) AS (
                                         SELECT id FROM strands WHERE id IN (" (placeholders seed-ids) ")
                                       UNION
                                         SELECT e.from_strand_id
                                         FROM ancestors a
                                         JOIN strand_edges e ON e.to_strand_id = a.id
                                         WHERE e.edge_type = ?
                                       )
                                       SELECT a.id
                                       FROM ancestors a
                                       WHERE NOT EXISTS (
                                         SELECT 1
                                         FROM strand_edges e
                                         WHERE e.to_strand_id = a.id AND e.edge_type = ?
                                       )
                                       ORDER BY a.id")]
                                  (concat seed-ids [type type])))))))))

(defn subgraph
  "Return strands and relation-scoped edges reachable from root-ids.

  Traversal requires a declared acyclic relation, defaulting to parent-of."
  ([ds root-ids]
   (subgraph ds root-ids {}))
  ([ds root-ids {:keys [type] :or {type "parent-of"}}]
   (require-valid-relation-name! type)
   (when-not (acyclic-relation? ds type)
     (throw (ex-info "Graph traversal requires a declared acyclic relation" {:relation type})))
   (let [root-ids (require-existing-strand-ids! ds root-ids :subgraph)]
     (if (empty? root-ids)
       {:root-ids [] :strands [] :edges []}
       (let [cte (str "WITH RECURSIVE nodes(id) AS (
                        SELECT id FROM strands WHERE id IN (" (placeholders root-ids) ")
                      UNION
                        SELECT e.to_strand_id
                        FROM nodes n
                        JOIN strand_edges e ON e.from_strand_id = n.id
                        WHERE e.edge_type = ?
                      )")]
         (let [rows (mapv identity
                          (execute! ds (into [(str cte "
                                       SELECT " strand-columns "
                                       FROM strands
                                       WHERE id IN (SELECT id FROM nodes)
                                       ORDER BY id")]
                                             (concat root-ids [type]))))
               edges (execute! ds (into [(str cte "
                                       SELECT e.from_strand_id, e.to_strand_id,
                                              e.edge_type, e.attributes
                                       FROM strand_edges e
                                       WHERE e.edge_type = ?
                                         AND e.from_strand_id IN (SELECT id FROM nodes)
                                         AND e.to_strand_id IN (SELECT id FROM nodes)
                                       ORDER BY e.from_strand_id, e.to_strand_id, e.edge_type")]
                                         (concat root-ids [type type])))]
           {:root-ids root-ids
            :strands rows
            :edges edges}))))))

(defn all-strands
  "Return all strand rows, or rows matching query-def and optional params."
  ([ds]
   (mapv identity (execute! ds [(str "SELECT " strand-columns " FROM strands ORDER BY id")])))
  ([ds query-def]
   (query-strands ds query-def {}))
  ([ds query-def params]
   (query-strands ds query-def params)))

(defn ready-strands
  "Return active strands with no active depends-on blockers.

  Optional query-def and params further filter candidate strands."
  ([ds]
   (mapv identity
         (execute! ds
                   [(str "SELECT " strand-columns "
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
               ORDER BY t.id")])) )
  ([ds query-def]
   (ready-strands ds query-def {}))
  ([ds query-def params]
   (let [{query-sql :sql query-params :params} (query/compile-query query-def params)]
     (mapv identity
           (execute! ds
                     (into [(str "SELECT " strand-columns "
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
                   AND " query-sql "
                 ORDER BY t.id")]
                           query-params))))))

