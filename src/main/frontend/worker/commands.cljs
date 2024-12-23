(ns frontend.worker.commands
  "Invoke commands based on user settings"
  (:require [datascript.core :as d]
            [logseq.db.frontend.property.type :as db-property-type]
            [cljs-time.core :as t]
            [cljs-time.coerce :as tc]
            [logseq.db.frontend.property :as db-property]
            [logseq.outliner.pipeline :as outliner-pipeline]
            [frontend.worker.handler.page.db-based.page :as worker-db-page]
            [logseq.common.util.date-time :as date-time-util]))

;; TODO: allow users to add command or configure it through #Command (which parent should be #Code)
(def *commands
  (atom
   [[:repeated-task
     {:title "Repeated task"
      :entity-conditions [{:property :logseq.task/repeated?
                           :value true}]
      :tx-conditions [{:property :logseq.task/status
                       :value :logseq.task/status.done}]
      :actions [[:reschedule]
                [:set-property :logseq.task/status :logseq.task/status.todo]]}]]))

(defn sastify-condition?
  "Whether entity or updated datoms satisfy the `condition`"
  [db entity {:keys [property value]} datoms]
  (when-let [property-entity (d/entity db property)]
    (let [value-matches? (fn [datom-value]
                           (let [ref? (contains? db-property-type/all-ref-property-types (:type (:block/schema property-entity)))
                                 db-value (cond
                                            ;; entity-conditions
                                            (nil? datom-value)
                                            (get entity property)
                                            ;; tx-conditions
                                            ref?
                                            (d/entity db datom-value)
                                            :else
                                            datom-value)]
                             (cond
                               (qualified-keyword? value)
                               (and (map? db-value) (= value (:db/ident db-value)))

                               ref?
                               (or
                                (and (uuid? value) (= (:block/uuid db-value) value))
                                (= value (db-property/property-value-content db-value)))

                               :else
                               (= db-value value))))]
      (if (seq datoms)
        (some (fn [d] (and (value-matches? (:v d)) (:added d)))
              (filter (fn [d] (= property (:a d))) datoms))
        (value-matches? nil)))))

(defmulti handle-command (fn [action-id & _others] action-id))

(defmethod handle-command :reschedule [_ db entity]
  (let [property-ident (or (:db/ident (:logseq.task/reschedule-property entity))
                           :logseq.task/scheduled)
        frequency (db-property/property-value-content (:logseq.task/recur-frequency entity))
        unit (:logseq.task/recur-unit entity)]
    (when (and frequency unit)
      (let [interval (case (:db/ident unit)
                       :logseq.task/recur-unit.minute t/minutes
                       :logseq.task/recur-unit.hour t/hours
                       :logseq.task/recur-unit.day t/days
                       :logseq.task/recur-unit.week t/weeks
                       :logseq.task/recur-unit.month t/months
                       :logseq.task/recur-unit.year t/years)
            next-time (t/plus (t/now) (interval frequency))
            next-time-long (tc/to-long next-time)
            journal-day (outliner-pipeline/get-journal-day-from-long db next-time-long)
            create-journal-page (when-not journal-day
                                  (let [formatter (:logseq.property.journal/title-format (d/entity db :logseq.class/Journal))
                                        title (date-time-util/format next-time formatter)]
                                    (worker-db-page/create db title {:create-first-block? false})))]
        (concat
         [[:db/add (:db/id entity) property-ident next-time-long]]
         (:tx-data create-journal-page))))))

(defmethod handle-command :set-property [_ _db entity property value]
  [[:db/add (:db/id entity) property value]])

(defn execute-command
  "Build tx-data"
  [db entity [_command {:keys [actions]}]]
  (mapcat (fn [action]
            (apply handle-command (first action) db entity (rest action))) actions))

(defn run-commands
  [{:keys [tx-data db-after]}]
  (let [db db-after]
    (mapcat (fn [[e datoms]]
              (let [entity (d/entity db e)
                    commands (filter (fn [[_command {:keys [entity-conditions tx-conditions]}]]
                                       (and (every? #(sastify-condition? db entity % nil) entity-conditions)
                                            (every? #(sastify-condition? db entity % datoms) tx-conditions))) @*commands)]
                (mapcat
                 (fn [command]
                   (execute-command db entity command))
                 commands)))
            (group-by :e tx-data))))
