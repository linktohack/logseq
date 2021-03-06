(ns frontend.handler.block
  (:require [frontend.util :as util]
            [clojure.walk :as walk]
            [frontend.db :as db]
            [frontend.state :as state]
            [frontend.format.mldoc :as mldoc]
            [frontend.date :as date]
            [frontend.config :as config]
            [datascript.core :as d]))

(defn blocks->vec-tree [col]
  (let [col (map (fn [h] (cond->
                          h
                           (not (:block/dummy? h))
                           (dissoc h :block/meta))) col)
        parent? (fn [item children]
                  (and (seq children)
                       (every? #(< (:block/level item) (:block/level %)) children)))]
    (loop [col (reverse col)
           children (list)]
      (if (empty? col)
        children
        (let [[item & others] col
              cur-level (:block/level item)
              bottom-level (:block/level (first children))
              pre-block? (:block/pre-block? item)]
          (cond
            (empty? children)
            (recur others (list item))

            (<= bottom-level cur-level)
            (recur others (conj children item))

            pre-block?
            (recur others (cons item children))

            (> bottom-level cur-level)      ; parent
            (let [[children other-children] (split-with (fn [h]
                                                          (> (:block/level h) cur-level))
                                                        children)

                  children (cons
                            (assoc item :block/children children)
                            other-children)]
              (recur others children))))))))

;; recursively with children content for tree
(defn get-block-content-rec
  ([block]
   (get-block-content-rec block (fn [block] (:block/content block))))
  ([block transform-fn]
   (let [contents (atom [])
         _ (walk/prewalk
            (fn [form]
              (when (map? form)
                (when-let [content (:block/content form)]
                  (swap! contents conj (transform-fn form))))
              form)
            block)]
     (apply util/join-newline @contents))))

;; with children content
(defn get-block-full-content
  ([repo block-id]
   (get-block-full-content repo block-id (fn [block] (:block/content block))))
  ([repo block-id transform-fn]
   (let [blocks (db/get-block-and-children-no-cache repo block-id)]
     (->> blocks
          (map transform-fn)
          (apply util/join-newline)))))

(defn get-block-end-pos-rec
  [repo block]
  (let [children (:block/children block)]
    (if (seq children)
      (get-block-end-pos-rec repo (last children))
      (if-let [end-pos (get-in block [:block/meta :end-pos])]
        end-pos
        (when-let [block (db/entity repo [:block/uuid (:block/uuid block)])]
          (get-in block [:block/meta :end-pos]))))))

(defn get-block-ids
  [block]
  (let [ids (atom [])
        _ (walk/prewalk
           (fn [form]
             (when (map? form)
               (when-let [id (:block/uuid form)]
                 (swap! ids conj id)))
             form)
           block)]
    @ids))

(defn collapse-block!
  [block]
  (let [repo (:block/repo block)]
    (db/transact! repo
                  [{:block/uuid (:block/uuid block)
                    :block/collapsed? true}])))

(defn collapse-blocks!
  [block-ids]
  (let [repo (state/get-current-repo)]
    (db/transact! repo
                  (map
                   (fn [id]
                     {:block/uuid id
                      :block/collapsed? true})
                   block-ids))))

(defn expand-block!
  [block]
  (let [repo (:block/repo block)]
    (db/transact! repo
                  [{:block/uuid (:block/uuid block)
                    :block/collapsed? false}])))

(defn expand-blocks!
  [block-ids]
  (let [repo (state/get-current-repo)]
    (db/transact! repo
                  (map
                   (fn [id]
                     {:block/uuid id
                      :block/collapsed? false})
                   block-ids))))

(defn with-dummy-block
  ([blocks format]
   (with-dummy-block blocks format {} {}))
  ([blocks format default-option {:keys [journal? page-name]
                                  :or {journal? false}}]
   (let [format (or format (state/get-preferred-format) :markdown)
         blocks (if (and journal?
                      (seq blocks)
                      (when-let [title (second (first (:block/title (first blocks))))]
                        (date/valid-journal-title? title)))
                  (rest blocks)
                  blocks)
         blocks (vec blocks)]
     (cond
       (and (seq blocks)
         (or (and (> (count blocks) 1)
               (:block/pre-block? (first blocks)))
           (and (>= (count blocks) 1)
             (not (:block/pre-block? (first blocks))))))
       blocks

       :else
       (let [last-block (last blocks)
             end-pos (get-in last-block [:block/meta :end-pos] 0)
             dummy (merge last-block
                     (let [uuid (d/squuid)]
                       {:block/uuid uuid
                        :block/title ""
                        :block/content (config/default-empty-block format)
                        :block/format format
                        :block/level 2
                        :block/priority nil
                        :block/anchor (str uuid)
                        :block/meta {:start-pos end-pos
                                     :end-pos end-pos}
                        :block/body nil
                        :block/dummy? true
                        :block/marker nil
                        :block/pre-block? false})
                     default-option)]
         (conj blocks dummy))))))
