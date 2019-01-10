# `cljsql`: a minimal websql wrapper in clojurescript

`cljsql` turns the callback hell of websql into nice
sequential-looking code. A snippet is worth a thousand words:

```cljs
(go
  (println

    ; tx starts a transaction. It returns a channel with the final
    ; value in the block.
    (tx db
       (try

         ; <q executes a query asynchronously
         ; ... other code can run while it is executed
         (<q "INSERT INTO customers VALUES (?, ?)" ["John Smith", "(123)
            23-132"])

         ; If the insert fails, do something about it.
         (catch SQLError e (println "Insert failed!")))

       ; Executes after the (possibly failed) insert.
       (<q "SELECT (name, age) FROM CUSTOMERS"))))
```

`cljsql` does not wrap any specific javascript library: you can use
any that exposes an interface that complies with websql. Some
examples:

 - on browsers that support it (ie Chrome), just use the native support,
 - for node, use [`node-websql`](https://github.com/nolanlawson/node-websql),
 - for react native, your can use
   [`react-native-sqlite-storage`](https://github.com/andpor/react-native-sqlite-storage)
   or
   [`react-native-sqlite-2`](https://github.com/craftzdog/react-native-sqlite-2).
 - on Cordova/PhoneGap, there's
   [`cordova-sqlite-storage`](https://github.com/litehelpers/Cordova-sqlite-storage).

## API

**Be sure to require `cljs.core.async` if you use `cljsql`.**

`cljsql` macros rewrite queries using `core.async`. Please also read
the "Pitfalls" section if you plan to use `cljsql` in production.

For gory details, refer to the docs of the individual macros. Examples
follow further down.

### Opening and closing the database.

You're responsible for this. The various platforms have diverging API.

For example, in chrome, you might do this:

```cljs
(def db (js/openDatabase "test.db" "1.0" "Test database" (* 1024 1024)))
```

### Querying the database

Queries occur inside transactions using the `<q` macro. This has the
form

    <q <sql> <params>? <options>?

where

  - `<sql>` is a string containing a single SQL statement,
  - `<params>` is an optional collection containing values to
    interpolate into the statement,
  - `<options>` allow one to customise the output of the
    query. Currently supported options are:
    - `:output`: what to return from the query. Possible values are
      `:rows`, `:rows-affected`, `:insert-id`, `:none`, `:first` and
      `:full`. Defaults to `:rows`.
    - `:xform`: a transducer to transform the rows received before
      returning them.
    - `:keywordize-keys`: whether to turn the column names into
      keywords. Defaults to true.

`<q` blocks the transaction until the database returns, returning
control to the event loop. Once the database returns, it either
evaluates to its result, or, in the case of an error, throws it.

`cljsql` also defines the convenience macros `<first`, `<affected`,
`<insert-id`, `<full`, `<ignore`, which are sugar for `<q` with the
corresponding choice of `:output`.

### Executing transactions

Queries must occur in transactions. These are run using the `tx`
macro: `tx` and `<q` as being related in the same way `go`
and `<!` are related: neither makes sense without the other.

`tx` is similar to `go`: it contains a block to be executed. It has
the form:

    tx <db> <options>? <block-to-execute>

Just like `go`, it returns a channel containing the value that the
block evaluates to when all statements have completed executing.

`tx` opens a transaction on `<db>`, and proceeds to execute the block.
Whenever `tx` encounters a query, it runs the query (asynchronously)
agains `<db>`, replacing it by whatever value it receives after the
query is done. If the query results in an error, that error is
thrown. If there is an uncaught error at the end of the execution, the
transaction is is rolled back. Otherwise it is committed.

The `:type` option tells `tx` what sort of transaction to run.  By
default, `tx` opens an exclusive read-write lock on commencing to
execute. If you only intend to run non-mutating queries, you can tell
it to open a shared-read lock by setting `:type` to `:read-only`.

The final value of `:type`, `:change-version` allows one to change the
version of the database transactionally. If one specifies this value,
one must specify two more options: `:from` and `:to`. The transaction
then runs as follows:

 1. It checks whether the database version is the value specified in
    `:from`. If not, it aborts.
 2. It runs the statements passed to the transaction.
 3. On success, it commits and the database version to `:to`.
 4. Otherwise, it rolls back the changes.

**WARNING** : Due to the way `websql` is specified, all processing
between queries in a single *must* be synchronous. This doesn't
preclude one calling asynchronous functions, they simply cannot
continue in the same transaction.

## Examples

Here is an example of how one might do a data migration using
`tx`.

**WARNING**: `db.changeVersion` does not work on many platform
implementations of websql, and correspondingly for cljsql.

```cljs
(defn migrate-to-v2
  "Adds an SSID field to the customers database."
  [db]
  (tx :type :change-version
      :from "1.0"
      :to "2.0"

      (<ignore "ALTER TABLE customers ADD COLUMN SSID TEXT")))
```

Here, I fetch a bunch of integer values, and map the
[Collatz function](https://en.wikipedia.org/wiki/Collatz_conjecture)
over them:

```cljs
(defn collatz-over-entries
  "Returns a channel containing the entries in `column` of `table`
  mapped through the Collatz function, or an error."
  [table column]
  ;  I'm just reading from the table, so there's no need for a write lock:
  (tx :type :read-only
    (<q (str "SELECT " column " FROM " table)
        :keywordize-keys false
        :xform (comp
	        (map #(% column))
		(map #(if (even? x) (/ x 2) (inx (* x 3))))))))
```

Finally, here's a slightly more complicated transaction. Suppose I
have a post database, with two tables:

 - *posts*, having, in additional to the default `rowid` only the
   `content` text column,
 - *comments*, a table containing comments associated with individual
   posts. It has a text column `content`, and a foreign key `post-id`
   which points to the `rowid` of the post the comment comments on.

The following function adds a post along with a bunch of comments, and
returns a channel containing the id of the newly created post.

```cljs
(defn add-posts-and-comments
  [post comments]
  (tx
    (let [post-id (<insert-id "INSERT INTO posts VALUES (?)" [post])
          comment-insertion
            (str "INSERT INTO comments VALUES (?, " post-id ")")]
      (doseq [comment comments]
        (<ignore comment-insertion [comment])))
    post-id))
```

## Pitfalls and footguns

`cljsql` makes crucial use of `core.async` in the macro-rewrites:
*be sure to require it*. Worse, to ensure synchrony between database
calls, it needs to make use of internal implementation details. I
don't think these will change (unless the entire mechanism of how
channels work in clojurescript changes) but its best not to rely on
hope: *pin the verion of core async that you use!*

Websql has a bunch of footguns. Two that come to mind are:

 - although transactions are executed asynchronously, the code inside
   transactions must be *synchronous*. Don't try continue code inside
   `tx` blocks asynchronously.

 - `websql` itself does not have a means to close a database. On the
   web this means that if a transaction is in flight and someone
   closes a tab, all bets are off. It may be committed, it may not,
   and if posts on the internet are to be believed, it may be
   neither. Beware.

   In various implementations on other platforms, `close` may be
   provided. Use it.

 - Depending on where you are, you may run into problems with numbers
   and INTEGER columns. Javascript doesn't distinguish number types,
   and if you pass number parameters, the wrong type may be passed. I
   suggest making a function that makes your query, and interpolate
   into the actual query string.

Various platform implementations have their own issues and
incompatibilities. I can't do anything about those: be sure to read
their READMEs.

This library is almost entirely implemented in macros, with some
convenience functions behind it. This is usually not a problem, except
if you want to make your own macros depending on these. If you do,
make sure you include `cljsql` wherever you include your new macros,
or you may find clojurescript doesn't know where to find the original
macros and functions they rely on.

## Known issues

 - Changing version will not do anything if there is no block to
   execute.
 - Throwing non-objects inside of go-blocks causes uncatchable
   errors. This is a known issue of `cljs.core.async`.
 - `changeVersion` is not supported in many implementations.

## `websql` implementations I know about

 - **Browsers**: (from
   [caniuse](https://caniuse.com/#feat=sql-storage)): Chrome (desktop
   and Android), Opera, Safari (iOS and MacOS). **NOT** IE, Firefox,
   Edge. Given that websql is not on the standards track, it's
   unlikely that support will ever come to browsers that don't
   currently support it.
 - **Node**:
   [`node-websql`](https://github.com/nolanlawson/node-websql)
   implements a websql compliant interface to `sqlite3`.
 - **React Native**: there are several libraries that implement (mostly)
   websql complient interfaces to SQLite. Here are those I have come across:
   [`react-native-sqlite-storage`](https://github.com/andpor/react-native-sqlite-storage),
   [`react-native-sqlite-2`](https://github.com/craftzdog/react-native-sqlite-2).
   I have no definite recommendations which is best.
 - **Cordova/PhoneGap**: the library
   [`Cordova-sqlite-storage`](https://github.com/litehelpers/Cordova-sqlite-storage)
   will do the job for you.
