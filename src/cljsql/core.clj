(ns cljsql.core
  "Macros for cljsql.")

(defn- extract-tx-params
  "Extracts the parameter map and statement list from a tx statement."
  [statements]
  (loop [statements statements
         params {}]
    (if (keyword? (first statements))
      (do
        (recur (nthrest statements 2) (assoc params (first statements) (second statements))))
      {:statements statements
       :params params})))


(defmacro tx
  "Executes a block inside a transaction. Analogous to core.async's go macro.

  Usage:
    (tx db ...<opts> ...<forms>)

  where:
    <opts> are key-value pairs which may occur in any order:
      :type (:read-only | :change-version | :read-write) - default :read-write
      :from for :change-version - the initial version
      :to   for :change-version - the final version
    <forms> are the forms to execute inside the transaction.

  Unknown options are ignored. By default the transaction has an
  exclusive read-write lock on the database when operating. If the
  type is `:read-only`, a shared read lock is acquired instead. The
  `:change-version` type causes the version to change upon successful
  completion of the transaction.

  In all cases, uncaught exceptions cause the transaction to rollback,
  otherwise it is committed.

  The macro `<q` is analogous to `<!` in go blocks: it executes
  queries inside a transaction.

  Note: due to the nature of WebSQL, the keywords BEGIN, COMMIT and
  ROLLBACK are not allowed. Blocks that reach the end without
  unhandled exceptions are committed, blocks that do not are rolled
  back.

  Note:

  - unlike go, one *must not* use asynchronous statements inside
    tx. This is a websql restriction: by the specification, a
    transaction is not guaranteed to persist beyond the current
    event-loop tick.


  Example:
  (go
    (<!
     (tx db
         :from \"0.0\"
         :to \"0.1\"
         :type :change-version
         (<q \"CREATE TABLE IF NOT EXISTS foo (bar INTEGER UNIQUE)\")
         (doseq [x (range 10)]
           (<q \"INSERT INTO foo VALUES (?)\" [x])))

    (println
      (<! (tx db :read-only
            (<q \"SELECT * FROM foo\" ))

    ;; The next transaction rolls back.
    (<! (tx db
          (<q \"INSERT INTO foo VALUES (100)\")
          (throw (js/Error \"Throwing this rolls back!\")))))
  "
  [db & forms]
  (let [{:keys [statements params]} (extract-tx-params forms)
        tx-fn (case (:type params)
                :read-only 'cljsql.core/-open-readonly
                :change-version `(cljsql.core/-open-changeversion
                                  ~(:from params)
                                  ~(:to params))
                'cljsql.core/-open-transaction)
        err (gensym "err")]
    (if-some [statements (seq statements)]
      `(cljs.core.async/go
         (binding [*tx* (atom nil)]
           (try
             (cljs.core.async/<! (~tx-fn ~db))
             ~@forms
             (catch :default ~err
               (-rollback)
               ~err))))
      `(cljs.core.async/go))))

(defn- set-tx-in-opts
  "Adds an outside transducer that makes sure one only takes as many results as necessary."
  [opts]
  (let [outside-xform
        (case (:output opts)
          (:none :rows-affected :insert-id) `(take 0)
          :first `(take 1)
          `-identity)]
    (update
     opts :xform
     #(if (nil? %)
        outside-xform
        `(comp ~% ~outside-xform)))))


(defn- extract-query-options
  "Extracts the options map and the query params from a query. Normalises the options."
  [args]
  (let [params? ((complement keyword?) (first args))
        params  (if params? (first args) ())]
    (loop [args (if params? (rest args) args)
           opts {:keywordize-keys :true
                 :output :rows}]
      (if-some [[kw val & args] (seq args)]
        (recur args (assoc opts kw val))
        (do
          {:params params
           :options (set-tx-in-opts opts)})))))


(defmacro <q
  "Executes a query inside tx.

  Usage:
    (<q <sql> ?<params> ...<opts>)

  Where:
    <sql> : a string containing a single SQL statement
    <params> : an array of params to interpolate into the statement

    <opts> : named options for the execution:

      :xform           - a transducer to apply to the output rows

      :keywordize-keys - whether to keywordize the column names in
                         the output. Defaults to *true*.

      :output           - one of

         :none          drops all output
         :first         just the first (transformed) row
         :rows          the (transformed) rows
         :rows-affected the number of rows affected
         :insert-id     the id of the last row inserted in the query
                        or null
         :full          a map containing :insert-id, :rows-affected and
                        :rows.

         Default :rows.

    If the query fails, an exception is thrown. Catching and handling the
    exception causes the transaction to continue. Otherwise the transaction
    is rolled back.

    Examples:

      (<q \"SELECT * FROM t WHERE foo = ?2, bar = ?1\"
          [32, \"test\"]
          :xform (take 5))

      (<q \"INSERT INTO foo VALUES (1, ?)\" :output :insert-id)

      (<q \"DROP TABLE atable\")
  "
  [sql & args]
  (let [{:keys [params options]} (extract-query-options args)]
    `(-handle-result
      (cljs.core.async/<!
       (-executeSql ~sql ~params ~options)))))

(defmacro <first
  "Executes `sql` inside a `tx` block, returning the first row or `nil`.

  Alias for `(<q ... :output :first)`"
  [sql & params]
  `(<q ~sql ~@params :output :first))

(defmacro <affected
  "Executes `sql` inside a `tx` block, returning the number of rows affected.

  Alias for `(<q ... :output :rows-affected)`"
  [sql & params]
  `(<q ~sql ~@params :output :rows-affected))

(defmacro <insert-id
  "Executes `sql` inside a `tx` block, returning the id of the last row inserted or nil.

  Alias for `(<q ... :output :insert-id)`"
  [sql & params]
  `(<q ~sql ~@params :output :insert-id))

(defmacro <full
  "Executes `sql` inside a `tx` block, returning the full result map.

  Alias for `(<q ... :output :full)`"
  [sql & params]
  `(<q ~sql ~@params :output :full))

(defmacro <ignore
  "Executes `sql` inside a `tx` block, ignoring the results.

  Alias for `(<q ... :output :none)`"
  [sql & params]
  `(<q ~sql ~@params :output :none))
