(ns todo.db
  (:require [clojure.data.json :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def default-db-file "todo.sqlite")

(defn datasource
  ([] (datasource default-db-file))
  ([db-file]
   (jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:" db-file)})))

(defn execute! [ds sql-params]
  (jdbc/execute! ds sql-params {:builder-fn rs/as-unqualified-lower-maps}))

(defn execute-one! [ds sql-params]
  (jdbc/execute-one! ds sql-params {:builder-fn rs/as-unqualified-lower-maps}))

(defn ->json [m]
  (when-not (or (nil? m) (map? m))
    (throw (ex-info "Attributes must be a map that encodes to a JSON object" {:attributes m})))
  (json/write-str (or m {})))

(defn <-json [s]
  (json/read-str (or s "{}") :key-fn keyword))

(def schema-sql
  [["PRAGMA foreign_keys = ON"]
   ["CREATE TABLE IF NOT EXISTS tasks (
       id TEXT PRIMARY KEY,
       title TEXT NOT NULL,
       attributes TEXT NOT NULL DEFAULT '{}',
       CHECK (json_valid(attributes))
     )"]
   ["CREATE TABLE IF NOT EXISTS task_edges (
       from_task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
       to_task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
       edge_type TEXT NOT NULL,
       attributes TEXT NOT NULL DEFAULT '{}',
       PRIMARY KEY (from_task_id, to_task_id, edge_type),
       CHECK (json_valid(attributes))
     )"]
   ["CREATE INDEX IF NOT EXISTS idx_task_edges_to ON task_edges(to_task_id, edge_type)"]
   ["CREATE INDEX IF NOT EXISTS idx_tasks_priority ON tasks(json_extract(attributes, '$.priority'))"]
   ["CREATE INDEX IF NOT EXISTS idx_tasks_due_date ON tasks(json_extract(attributes, '$.due-date'))"]])

(defn init! [ds]
  (doseq [stmt schema-sql]
    (execute! ds stmt))
  ds)

(defn reset-db! [ds]
  (execute! ds ["DROP TABLE IF EXISTS task_edges"])
  (execute! ds ["DROP TABLE IF EXISTS tasks"])
  (init! ds))

(defn add-task! [ds {:keys [id title attributes]}]
  (execute-one! ds
                ["INSERT INTO tasks (id, title, attributes) VALUES (?, ?, json(?))
                  ON CONFLICT(id) DO UPDATE SET title = excluded.title, attributes = excluded.attributes
                  RETURNING id, title, attributes"
                 id title (->json attributes)]))

(defn add-edge! [ds {:keys [from to type attributes]}]
  (execute-one! ds
                ["INSERT INTO task_edges (from_task_id, to_task_id, edge_type, attributes)
                  VALUES (?, ?, ?, json(?))
                  ON CONFLICT(from_task_id, to_task_id, edge_type) DO UPDATE SET attributes = excluded.attributes
                  RETURNING from_task_id, to_task_id, edge_type, attributes"
                 from to type (->json attributes)]))

(defn get-task [ds task-id]
  (execute-one! ds
                ["SELECT id, title, attributes FROM tasks WHERE id = ?"
                 task-id]))

(defn- require-updated-task [task-id row]
  (or row
      (throw (ex-info "Task not found" {:task-id task-id}))))

(defn update-task-attributes! [ds task-id attributes]
  (require-updated-task
   task-id
   (execute-one! ds
                 ["UPDATE tasks
                   SET attributes = json_patch(attributes, json(?))
                   WHERE id = ?
                   RETURNING id, title, attributes"
                  (->json attributes) task-id])))

(defn update-task-status! [ds task-id status]
  (require-updated-task
   task-id
   (execute-one! ds
                 ["UPDATE tasks
                   SET attributes = json_set(attributes, '$.status', ?)
                   WHERE id = ?
                   RETURNING id, title, attributes"
                  status task-id])))

(defn all-tasks [ds]
  (execute! ds ["SELECT id, title, attributes FROM tasks ORDER BY id"]))

(defn tasks-by-attribute [ds attr-key attr-value]
  (execute! ds
            ["SELECT t.id, t.title, t.attributes
              FROM tasks t
              WHERE EXISTS (
                SELECT 1
                FROM json_each(t.attributes) attr
                WHERE attr.key = ? AND attr.value = ?
              )
              ORDER BY t.id"
             (name attr-key) attr-value]))

(defn task-dependencies [ds task-id]
  (execute! ds
            ["SELECT dep.id, dep.title, dep.attributes, e.attributes AS edge_attributes
              FROM task_edges e
              JOIN tasks dep ON dep.id = e.to_task_id
              WHERE e.from_task_id = ? AND e.edge_type = 'depends-on'
              ORDER BY dep.id"
             task-id]))

(defn blocking-tasks [ds task-id]
  (execute! ds
            ["SELECT blocked.id, blocked.title, blocked.attributes, e.attributes AS edge_attributes
              FROM task_edges e
              JOIN tasks blocked ON blocked.id = e.from_task_id
              WHERE e.to_task_id = ? AND e.edge_type = 'depends-on'
              ORDER BY blocked.id"
             task-id]))

(defn blocked-tasks [ds]
  (execute! ds
            ["SELECT t.id, t.title, json_group_array(dep.id) AS blockers
              FROM tasks t
              JOIN task_edges e ON e.from_task_id = t.id AND e.edge_type = 'depends-on'
              JOIN tasks dep ON dep.id = e.to_task_id
              GROUP BY t.id, t.title
              ORDER BY t.id"]))

(defn ready-tasks [ds]
  (execute! ds
            ["SELECT t.id, t.title, t.attributes
              FROM tasks t
              WHERE json_extract(t.attributes, '$.status') IS NOT 'done'
                AND NOT EXISTS (
                  SELECT 1
                  FROM task_edges e
                  JOIN tasks dep ON dep.id = e.to_task_id
                  WHERE e.from_task_id = t.id
                    AND e.edge_type = 'depends-on'
                    AND json_extract(dep.attributes, '$.status') IS NOT 'done'
                )
              ORDER BY t.id"]))

(defn transitive-dependencies [ds task-id]
  (execute! ds
            ["WITH RECURSIVE deps(id, title, attributes) AS (
                SELECT dep.id, dep.title, dep.attributes
                FROM task_edges e
                JOIN tasks dep ON dep.id = e.to_task_id
                WHERE e.from_task_id = ? AND e.edge_type = 'depends-on'
              UNION
                SELECT dep.id, dep.title, dep.attributes
                FROM deps
                JOIN task_edges e ON e.from_task_id = deps.id AND e.edge_type = 'depends-on'
                JOIN tasks dep ON dep.id = e.to_task_id
              )
              SELECT id, title, attributes
              FROM deps
              WHERE id <> ?
              ORDER BY id"
             task-id task-id]))

(defn tasks-by-priority [ds priority]
  (execute! ds
            ["SELECT id, title, attributes
              FROM tasks
              WHERE json_extract(attributes, '$.priority') = ?
              ORDER BY json_extract(attributes, '$.due-date'), id"
             priority]))

(defn tasks-due-before [ds due-date]
  (execute! ds
            ["SELECT id, title, attributes
              FROM tasks
              WHERE json_extract(attributes, '$.due-date') IS NOT NULL
                AND json_extract(attributes, '$.due-date') <= ?
              ORDER BY json_extract(attributes, '$.due-date'), id"
             due-date]))

(defn related-tasks [ds task-id]
  (execute! ds
            ["SELECT e.edge_type, e.from_task_id, src.title AS from_title,
                     e.to_task_id, dst.title AS to_title, e.attributes
              FROM task_edges e
              JOIN tasks src ON src.id = e.from_task_id
              JOIN tasks dst ON dst.id = e.to_task_id
              WHERE e.from_task_id = ? OR e.to_task_id = ?
              ORDER BY e.edge_type, e.from_task_id, e.to_task_id"
             task-id task-id]))
