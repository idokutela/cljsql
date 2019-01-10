(ns cljsql.core
  "
  # `cljsql`: a minimal websql wrapper in clojurescript

  `cljsql` turns the callback hell of websql into nice
  sequential-looking code. A snippet is worth a thousand words:


      (println
         (tx db
            (try
              (<q \"INSERT INTO customers VALUES (?, ?)\"
                  [\"John Smith\", \"(123) 23-132\"])
              (catch SQLError e (println \"Insert failed!\")))

            (<q \"SELECT (name, age) FROM CUSTOMERS\"))))


  `cljsql` does not wrap any specific javascript library: you can use
  any that exposes an interface that complies with websql.

  For a discursive introduction, see the README.
  "
  (:require
   [cljs.core.async.impl.protocols :as impl]
   [cljs.core.async :as async :refer [promise-chan go put!]])
  (:require-macros [cljsql.core
                    :refer [tx <q <first <affected <insert-id
                            <full <ignore]]))

;;; Inside transactions, these dynamic variables are necessary


(def ^:dynamic ^:private
  *tx*
  "The current transaction")

;;; The ReadPort stuff is stolen (and very slightly modified) from my
;;; to-go library.  I don't want to depend on other libraries for this
;;; library to work.  Long term, I really should make a go-like state
;;; machine.

(defn -as-deref [x]
  "INTERNAL API: Wraps `x` in a deref."
  (reify cljs.core/IDeref
    (-deref [_] x)))



(defn -handle-result
  "INTERNAL API: processes the result of a node-function call."
  [[err & results]]
  (when (some? err) (throw err))
  (if (<= (count results) 1)
    (first results)
    results))


(defn- apply-with-cb
  "Applies f with `args` followed by `cb`"
  [f args cb]
  ((apply partial f args) cb))

(defn -ReadPort<-node
  "INTERNAL API: Returns a ReadPort that wraps node function call."
  [f & args]
  (let [val (atom nil)
        the-handler (atom nil)
        port (reify
               impl/ReadPort
               (take! [_ ^not-native handler]
                 (if (not ^boolean (impl/active? handler))
                   nil
                   (if-some [v @val]
                     (do
                       (-as-deref v))
                     (do
                       (assert (nil? @the-handler)
                               "One may only take once from a node fn!")
                       (reset! the-handler handler)
                       nil)))))
        cb (fn [& results]
             (reset! val (or results '()))
             (when-some [handler @the-handler]
               (reset! the-handler nil)
               (when (impl/active? handler)
                 ((impl/commit handler) @val))))]
    (apply-with-cb f args cb)
    port))


(defn- set-tx
  [cb]
  (fn [tx]
    (reset! *tx* tx)
    (cb nil "Ok")))


(defn -open-transaction
  "INTERNAL API.

   Starts a transaction.
   Node style callback."
  [db]
  (-ReadPort<-node
   (fn
     [cb]
     (.transaction db (set-tx cb)))))



(defn -open-readonly
  "INTERNAL API.

   Starts a transaction.
   ReadPort."
  [db]
  (-ReadPort<-node
   (fn
     [cb]
     (.readTransaction db (set-tx cb)))))



(defn -open-changeversion
  "INTERNAL API.

   Starts a changeVersion transaction with the given params."
  [from to]
  (fn [db]
    (-ReadPort<-node
     (fn [cb]
       (.changeVersion db from to (set-tx cb))))))


(defn- -get-rows
  "Gets the rows from the results, transforming them if necessary."
  [results {:keys [keywordize-keys xform]}]
  (let [rows (.-rows results)]
    (into
     []
     (comp
      (map #(.item rows %))
      (map #(js->clj % :keywordize-keys keywordize-keys))
      xform)
     (range (.-length rows)))))


(defn- -get-insertId
  "Gets the insertId from query results if present. Otherwise null."
  [results]
  (try (.-insertId results)
       (catch js/Error e nil)))


(defn- -get-rowsAffected
  "Gets the rows affected by a query."
  [results]
  (try (.-rowsAffected results)
       (catch js/Error e nil)))


(defn- -process-results
  "Processes a query's results."
  [results opts]
  (case (:output opts)
    :none nil
    :first (first (-get-rows results opts))
    :rows (-get-rows results opts)
    :rows-affected (-get-rowsAffected results)
    :insert-id (-get-insertId results)
    :full {:insert-id (-get-insertId results)
           :rows-affected (-get-rowsAffected results)
           :rows (-get-rows results opts)}))


(defn- query-ok
  "Called on a query completing normally."
  [cb opts]
  (fn [tx res]
    (reset! *tx* tx)
    (try
      (cb nil (-process-results res opts))
      (catch Object o
        (cb o)))
    nil))


(defn- query-err
  "Called on an error in a query."
  [cb]
  (fn [tx err]
    (reset! *tx* tx)
    (cb err)))


(defn -identity
  "INTERNAL: the identity transducer."
  [r]
  (fn
    ([]  (r))
    ([acc] (r acc))
    ([acc i] (r acc i))))

(defn -executeSql
  "INTERNAL API.

   Executes the given query.
   Node style callback."
  [sql params opts]
  (-ReadPort<-node
   (fn [cb]
     (let [params (if (nil? params) [] params)]
       (.executeSql @*tx* sql (clj->js params) (query-ok cb opts) (query-err cb))))))


(defn- -rollback
  "INTERNAL API

   Rolls back a transaction by deliberately executing a failing query."
  []
  ;; Rather annoyingly, websql gives no explicit rollback. The easiest
  ;; way to cause rollback is to execute an SQL query that causes an
  ;; error. This is easy enough: the query below is hopefully always
  ;; syntactically incorrect.
  (.executeSql @*tx* "THIS-WONT-WORK!" #js []))


(defn version
  "Gets the database version"
  [db]
  (.-version db))
