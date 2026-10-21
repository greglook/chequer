(ns chequer.db
  "SQLite access: a locked writer connection, a reader pool, transactions and migrations."
  (:require
    [chequer.migrations :as migrations]
    [clojure.java.io :as io]
    [db.jdbc]
    [jdbc.core :as jdbc]))


(def ^:private reader-count
  "Number of pooled read connections."
  4)


(def ^:dynamic *topics*
  "Atom of topics touched by the transaction running on this thread, or nil outside one."
  nil)


(defn- connect
  "Open a connection to the SQLite file at path with WAL, foreign keys and a busy timeout."
  [path]
  (let [conn (jdbc/connection (str "sqlite:" path))]
    (jdbc/execute! conn "PRAGMA journal_mode=WAL")
    (jdbc/execute! conn "PRAGMA foreign_keys=ON")
    (jdbc/execute! conn "PRAGMA busy_timeout=5000")
    conn))


(defn open
  "Open the database at path: one writer connection guarded by :lock plus a pool of readers."
  [path]
  (let [writer (connect path)]
    {:path path
     :writer writer
     :lock (Object.)
     ;; TODO: change this to a LinkedBlockingQueue for better fiber behavior
     ;; TODO: make these read-only connections?
     :readers (atom (vec (repeatedly reader-count #(connect path))))}))


(defn close
  "Close the writer and every pooled reader connection of db."
  [{:keys [writer readers]}]
  (doseq [conn (cons writer @readers)]
    (.close conn)))


(defn run-tx
  "Run (f tx) in a transaction on the writer while holding the write lock.
  Returns {:result r :topics #{...}} once the transaction has committed."
  [{:keys [writer lock]} f]
  (locking lock
    (binding [*topics* (atom #{})]
      (let [result (jdbc/atomic-apply writer f)]
        {:result result :topics @*topics*}))))


(defmacro with-tx
  "Evaluate body in a transaction, binding the transactional connection to tx.
  Rolls back if body throws. Returns {:result r :topics #{...}}."
  [[tx db] & body]
  `(run-tx ~db (fn [~tx] ~@body)))


(defn touch!
  "Record that topic (e.g. [:plan 7]) changed in the current transaction."
  [topic]
  (swap! *topics* conj topic))


(defn insert!
  "Insert row (a map) into table (a keyword) and return the new id."
  [tx table row]
  (:id (first (jdbc/insert! tx table row {:returning true}))))


(defn- checkout
  "Atomically take a connection from the readers atom, or nil if the pool is empty."
  [readers]
  (loop []
    (let [pool @readers]
      (when-let [conn (peek pool)]
        (if (compare-and-set! readers pool (pop pool))
          conn
          (recur))))))


(defn- with-reader
  "Call f with a pooled reader, returning it afterwards; falls back to the locked writer if the pool is empty."
  [{:keys [writer lock readers]} f]
  (if-let [conn (checkout readers)]
    (try
      (f conn)
      (finally (swap! readers conj conn)))
    (locking lock (f writer))))


(defn q
  "Run the read sqlvec and return all rows."
  [db sqlvec]
  (with-reader db #(jdbc/fetch % sqlvec)))


(defn q1
  "Run the read sqlvec and return the first row, or nil."
  [db sqlvec]
  (with-reader db #(jdbc/fetch-one % sqlvec)))


(defn- user-version
  "The schema version stored in PRAGMA user_version."
  [conn]
  (:user_version (jdbc/fetch-one conn "PRAGMA user_version")))


(defn- empty-database?
  "True when the database has no user tables."
  [conn]
  (nil? (jdbc/fetch-one conn "SELECT 1 FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' LIMIT 1")))


(defn migrate!
  "Apply pending migrations (default: all of chequer.migrations/migrations) in one
  transaction. A non-empty database is first copied to <path>.pre-v<N>.bak with
  VACUUM INTO, where N is the version being migrated to; an existing backup is
  never overwritten."
  ([db]
   (migrate! db migrations/migrations))
  ([{:keys [path writer lock]} all-migrations]
   (locking lock
     (let [current (user-version writer)
           pending (filter #(> (:version %) current) all-migrations)]
       (when (seq pending)
         (let [target (:version (last pending))]
           (when-not (empty-database? writer)
             (let [backup (str path ".pre-v" target ".bak")]
               (when (.exists (io/file backup))
                 (throw (ex-info (str "Backup already exists, refusing to overwrite: " backup)
                                 {:backup backup})))
               (jdbc/execute! writer ["VACUUM INTO ?" backup])))
           (jdbc/atomic writer
             (doseq [{:keys [sql]} pending
                     statement sql]
               (jdbc/execute! writer statement))
             (jdbc/execute! writer (str "PRAGMA user_version = " target)))))))))
