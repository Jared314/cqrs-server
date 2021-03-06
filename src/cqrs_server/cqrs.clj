(ns cqrs-server.cqrs
  (:require
   [cqrs-server.util :as util :refer [defdbfn]]
   [datomic.api :as d]
   [datomic-schema.schema :refer [schema fields]]
   [taoensso.timbre :refer [info]]
   [schema.core :as s]
   [schema.coerce :as coerce]
   [clj-uuid :as u]
   [clojure.data.fressian :as fressian]))

;; Not the prettiest, but will clean this up down the line.
(def datomic-uri (atom nil))

(defn command-db [{:keys [basis-t]}]
  (let [db (d/db (d/connect @datomic-uri))]
    (if basis-t (d/as-of db basis-t) basis-t)))

(defmulti aggregate-event (fn [{:keys [type] :as event}] type))

(defmethod aggregate-event :default [_] [])

(defn aggregate-event* [e]
  (let [r (aggregate-event e)]
    [{:tx (.array (fressian/write [[:idempotent-tx (java.util.UUID/fromString (str (:id e))) r]])) :e e}]))

(defdbfn idempotent-tx [db eid tx]
  (if-not (datomic.api/entity db [:event/uuid eid])
    (concat [[:db/add (datomic.api/tempid :db.part/tx) :event/uuid eid]] tx) []))

(defdbfn add [db entid attr value]
  (let [ent (datomic.api/entity db entid)]
    (if ent
      [{:db/id entid attr (+ (or (get ent attr) 0) value)}] [])))

(def db-schema
  [(:tx (meta idempotent-tx))
   (:tx (meta add))
   (schema
    event
    (fields
     [uuid :uuid :unique-identity]))])

(defmulti command-coerce (fn [{:keys [type] :as command}] type))

(defmethod command-coerce :default [_] [])

(defn command-coerce* [c]
  (command-coerce c)
  [c])


(defn install-command [[type schema]]
  `(let [coercer# (coerce/coercer (assoc ~schema s/Any s/Any) coerce/string-coercion-matcher)]
     (defmethod command-coerce ~type [c#]
       (coercer# (:data c#)))))

(defmacro install-commands [commands]
  (concat [(symbol "do")] (map install-command commands)))



(defmulti process-command (fn [{:keys [type] :as command}] type))

(defmethod process-command :default [_] [])

(defn process-command* [command]
  (process-command command))

(defn prepare-store [e]
  (assoc e :id (str (:id e)) :data (.array (fressian/write (:data e)))))

(defn error [msg]
  (throw (RuntimeException. msg)))

(defn command [basis-t type msg]
  {:basis-t basis-t
   :type type
   :id (str (java.util.UUID/randomUUID))
   :data msg})

(defn event [command segment n [type msg]]
  {:id (u/v5 u/+namespace-oid+ (str (:id command) ":" n "/" segment))
   :date (.getTime (java.util.Date.))
   :type type
   :basis-t (:basis-t command) 
   :data msg})

(defn events [command segment msgs]
  (map (partial event command segment) (range) msgs))

(def command-workflow
  [[:command/in-queue :command/coerce]
   [:command/coerce :command/process]
   [:command/process :event/out-queue]
   [:event/in-queue :event/prepare-store]
   [:event/prepare-store :event/store]
   [:event/in-queue :event/aggregator]
   [:event/aggregator :event/store-aggregate]])



(defn catalog [{:keys [command-queue in-event-queue out-event-queue event-store aggregate-store]}]
  [(assoc
    command-queue
    :onyx/name :command/in-queue
    :onyx/type :input)

   {:onyx/name :command/coerce
    :onyx/type :function
    :onyx/fn :cqrs-server.cqrs/command-coerce*
    :onyx/consumption :concurrent
    :onyx/batch-size 10}

   {:onyx/name :command/process
    :onyx/type :function
    :onyx/fn :cqrs-server.cqrs/process-command*
    :onyx/consumption :concurrent
    :onyx/batch-size 10}

   (assoc
    out-event-queue
    :onyx/name :event/out-queue
    :onyx/type :output)

   (assoc
    in-event-queue
    :onyx/name :event/in-queue
    :onyx/type :input)
   
   {:onyx/name :event/prepare-store
    :onyx/type :function
    :onyx/fn :cqrs-server.cqrs/prepare-store
    :onyx/consumption :concurrent
    :onyx/batch-size 10}
   
   (assoc
    event-store
    :onyx/name :event/store
    :onyx/type :output)
   
   {:onyx/name :event/aggregator
    :onyx/type :function
    :onyx/fn :cqrs-server.cqrs/aggregate-event*
    :onyx/consumption :concurrent
    :onyx/batch-size 1}

   (assoc
    aggregate-store
    :onyx/name :event/store-aggregate
    :onyx/type :output)])


