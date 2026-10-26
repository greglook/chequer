(ns chequer.db
  "SQLite access: a locked writer connection, a reader pool, transactions and migrations.

  Schema migrations are loaded from `resources/chequer/migrations/<yyyymmdd>-<nnn>-<slug>.sql`,
  where `nnn` is the schema version the database is at after the migration.
  Statements in a file end with a semicolon at the end of a line; -- line
  comments are dropped."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [db.jdbc]
    [jdbc.core :as jdbc]
    [jolt.fs :as fs]
    [tick.core :as t]))


;; ## DB Connection

(def ^:private reader-count
  "Number of pooled read connections."
  4)


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


(defn close!
  "Close the writer and every pooled reader connection of db."
  [{:keys [writer readers]}]
  (doseq [conn (cons writer @readers)]
    (.close conn)))


;; ## Schema Migration

(def ^:private migrations-dir
  "chequer/migrations")


(defmacro ^:private list-migration-files
  "The migration file names, listed at compile time: an embedded resource
  directory cannot be listed in a built binary, but its files can still be
  read."
  []
  (vec (sort (map fs/file-name (fs/list-dir (io/resource migrations-dir))))))


(def migration-files
  "Vector of migration filenames, collected at build time."
  (list-migration-files))


(defn- parse-migration-name
  "Parse a migration file's name to get its date, version, and slug."
  [filename]
  (let [[_ year month day version slug] (or (re-matches #"(\d{4})(\d\d)(\d\d)-(\d{3})-([a-z0-9]+(?:-[a-z0-9]+)*)\.sql" filename)
                                            (throw (ex-info (str "Malformed migration file name: " filename)
                                                            {:file filename})))]
    {:name slug
     :date (t/date (str year "-" month "-" day))
     :version (parse-long version)}))


(defn- parse-migration-text
  "Parse a migration file's text and return a vector of the SQL statements."
  [text]
  (->>
    text
    (str/split-lines)
    (remove #(str/starts-with? (str/trim %) "--"))
    (str/join "\n")
    (#(str/split % #";[ \t]*(?:\n|$)"))
    (map str/trim)
    (remove str/blank?)
    (vec)))


(def latest-version
  "The schema version the newest migration brings a database to."
  (:version (parse-migration-name (peek migration-files))))


(defn migration-versions
  "The schema version of each migration, in order."
  []
  (mapv (comp :version parse-migration-name) migration-files))


(defn- load-migration
  "Prepare a migration to be run by parsing its filename and attaching a delay
  which will return the SQL statements."
  [filename]
  (assoc (parse-migration-name filename)
         :file filename
         :statements (delay
                       (-> (str migrations-dir "/" filename)
                           (io/resource)
                           (slurp)
                           (parse-migration-text)))))


(defn- user-version
  "The schema version stored in PRAGMA user_version."
  [conn]
  (:user_version (jdbc/fetch-one conn "PRAGMA user_version")))


(defn- empty-database?
  "True when the database has no user tables."
  [conn]
  (nil? (jdbc/fetch-one conn "SELECT 1 FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' LIMIT 1")))


(defn schema-version
  "The schema version of the existing database file at path, read without migrating it."
  [path]
  (let [conn (jdbc/connection (str "sqlite:" path))]
    (try
      (user-version conn)
      (finally (.close conn)))))


(defn migration-backup-path
  "The file a non-empty database at path is copied to before migrating to version."
  [path version]
  (str path ".pre-v" version ".bak"))


(defn- vacuum-into!
  "Copy the database on conn to the file dest with VACUUM INTO; an existing file is never overwritten."
  [conn dest]
  (when (fs/exists? dest)
    (throw (ex-info (str "Backup already exists, refusing to overwrite: " dest)
                    {:backup dest})))
  (jdbc/execute! conn ["VACUUM INTO ?" dest]))


(defn backup!
  "Write a consistent copy of the database to the file dest. Throws if dest already exists."
  [{:keys [writer lock]} dest]
  (locking lock
    (vacuum-into! writer dest)))


(defn migrate!
  "Apply pending migrations (default: all migrations) in one transaction and
  return the vector of migrations applied. A non-empty database is first
  copied to <path>.pre-v<N>.bak with VACUUM INTO, where N is the version being
  migrated to; an existing backup is never overwritten."
  ([db]
   (let [migrations (mapv load-migration migration-files)]
     (migrate! db migrations)))
  ([{:keys [path writer lock]} migrations]
   (locking lock
     (let [current (user-version writer)
           pending (filterv #(< current (:version %)) migrations)]
       (when (seq pending)
         (let [target (:version (last pending))]
           (when-not (empty-database? writer)
             (vacuum-into! writer (migration-backup-path path target)))
           (jdbc/atomic writer
                        (doseq [{:keys [statements]} pending
                                statement @statements]
                          (jdbc/execute! writer statement))
                        (jdbc/execute! writer (str "PRAGMA user_version = " target)))))
       pending))))


;; ## Write Operations

(def ^:dynamic *topics*
  "Atom of topics touched by the transaction running on this thread, or nil outside one."
  nil)


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
  ;; Quoted, in case a column name is a reserved word.
  (:id (first (jdbc/insert! tx table row {:returning true :entities #(str \" % \")}))))


;; ## Read Operations

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
