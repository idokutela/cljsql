(ns cljsql.test
  "Tests for cljsql"
  (:require [cljs.core.async :refer [go <!]]
            [cljsql.core :as db]
            [cljs.test :refer-macros [deftest async is testing]]
            ["websql" :as openDatabase]))

(def test-db
  (openDatabase ":memory:" "1.0" "Test database" 1))

(deftest simple-tx-functionality
  (testing "Transactions run queries."
    (async
     done
     (go
       (is
        (map?
           (<! (db/tx test-db
                      (db/<first "SELECT 'foo' WHERE 1=1")))))
       (done)))))


(deftest tx-readonly-runs
  (testing "The different transaction kinds run."
    (async
     done
     (go
       (is (map?
            (<! (db/tx test-db
                       :type :read-only
                       (db/<first "SELECT 'foo' WHERE 1=1")))))
       (is (= "Error thrown"
              (<! (db/tx test-db
                         :type :read-only
                         (try
                           (db/<ignore "CREATE TABLE IF NOT EXISTS garble (foo TEXT)")
                           (db/<ignore "DROP TABLE garble")
                           (catch :default e "Error thrown"))))))
       (done)))))


(deftest queries-run
  (testing "Tests all the different query types"
    (async
     done
     (go
       (is (nil?
            (<!
             (db/tx
              test-db
              (try
                (is (nil? (db/<ignore "DROP TABLE IF EXISTS qtest")))
                (is (nil?
                     (db/<ignore "CREATE TABLE qtest (key INTEGER PRIMARY KEY, age INTEGER)")))
                (doseq [[k v] (into [] (map #(vector % (* 2 %)) (range 3 10)))]
                  (is (= k
                         (db/<insert-id
                          "INSERT INTO qtest VALUES (?, ?)" [k v])))))))))
       (is (= {:error :thrown}
              (<!
               (db/tx
                test-db
                (db/<ignore "INSERT INTO qtest VALUES (199, 299)")
                (try (db/<ignore "INSERT INTO qtest VALUES (3, 4)")
                     (catch :default error (throw {:error :thrown})))))))
       (<! (db/tx
            test-db
            (is
             (= 0
                (count (db/<q "SELECT * FROM qtest WHERE key=199"))))
            (is
             (= 3 (db/<affected "UPDATE qtest SET age=age+5 WHERE key < 6")))
            (is
             (= {:key 3 :age 11}
                (db/<first "SELECT * FROM qtest ORDER BY key")))
            (is
             (= (range 3 10)
                (db/<q "SELECT * FROM qtest ORDER BY key" :xform (map :key))))
            (is (= (:rows (db/<full "SELECT * FROM qtest ORDER BY key"))
                   (db/<q "SELECT * FROM qtest ORDER BY key")))
            (is (= (:insert-id (db/<full "INSERT INTO qtest VALUES (100, 1000)"))
                   100))
            (is
             (= (:rows-affected (db/<full "UPDATE qtest SET age=age+5 WHERE key < 6"))
                3))))
       (<! (db/tx
             test-db
             (db/<ignore "DROP TABLE IF EXISTS qtest")))
      (done)))))
