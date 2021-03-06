(ns bluegenes.pages.mymine.subs
  (:require [re-frame.core :as re-frame :refer [reg-sub subscribe]]
            [reagent.core :as r]
            [cljs-time.format :as tf]
            [cljs-time.core :as t]
            [clojure.string :refer [split]]
            [clojure.walk :refer [postwalk]]
            [oops.core :refer [ocall]]
            [bluegenes.pages.mymine.listsubs]))

; Thanks!
; https://groups.google.com/forum/#!topic/clojure/VVVa3TS15pU
(def asc compare)
(def desc #(compare %2 %1))

(def desc-date (fn [x y]
                 (when (and x y)
                   (let [x-parsed (tf/parse x)
                         y-parsed (tf/parse y)]
                     (t/before? x-parsed y-parsed)))))

(def asc-date (fn [y x]
                (when (and x y)
                  (let [x-parsed (tf/parse x)
                        y-parsed (tf/parse y)]
                    (t/before? x-parsed y-parsed)))))

(def folders>files (fn [x y]
                     (cond
                       (= x y) 0
                       (and (= :folder x) (not= :folder y)) -1
                       (and (= :folder y) (not= :folder x)) 1)))

(def files>folders (fn [x y]
                     (cond
                       (= x y) 0
                       (and (= :folder x) (not= :folder y)) -1
                       (and (= :folder x) (= :folder y)) 1)))

(defn compare-by [& key-cmp-pairs]
  (fn [x y]
    (loop [[k cmp & more] key-cmp-pairs]
      (let [result (cmp (k x) (k y))]
        (if (and (zero? result) more)
          (recur more)
          result)))))

;;;;;;;;;; tree-seq components to convert the nested MyMine JSON document

(defn branch?
  "Branch? fn for a tree-seq. True if this branch is open and has children."
  [m] (and (:open m) (contains? m :children)))

(defn children
  "Children fn for a tree-seq. Append the child's key to the collection of keys (trail)
  so we know the nested location of the child"
  [sort-info m]
  (map
   (fn [[key {file-type :file-type :as child}]]
     (assoc child
            :trail (vec (conj (:trail m) :children key))
            :level (cond-> (count (remove keyword? (:trail m))) (not= :folder file-type) inc)))
   (sort
    (compare-by
     (comp :file-type second) folders>files

     (comp (:key sort-info) second) (case (:type sort-info)
                                      :alphanum (if (:asc? sort-info) asc desc)
                                      :date (if (:asc? sort-info) asc-date desc-date)))
    (:children m))))

(defn flatten-tree
  "Flatten the nested tree structure of MyMine data into a list (depth first)"
  [m sort-info]
  (tree-seq branch? (partial children sort-info) m))


;;;;;;;;;;;;;;;;;;;;;;;


(defn folder-children
  [[parent-k {:keys [file-type children index trail] :as c}]]
  (->> children
       ; Filter just the folders
       (filter (fn [[k v]] (= :folder (:file-type v))))
       ; Give the folder its index (indentation) and trail (location in mymine map)
       (map (fn [[k v]] [k (assoc v
                                  :trail (vec (conj trail :children k))
                                  :index (inc (or index 1)))]))))

(defn folder-branch?
  [[k m]]
  ; Only show open folders that have children
  (and (:open m) (= :folder (:file-type m))))

(defn flatten-folders
  [m]
  (tree-seq folder-branch? folder-children m))

;;;;;;;;;;;;;;;;;;;;;;;
(defn my-tree-children
  [[parent-k {:keys [file-type children index trail] :as c}]]
  (->> children
       ; Filter just the folders
       (filter (fn [[k v]] (= :folder (:file-type v))))
       ; Give the folder its index (indentation) and trail (location in mymine map)
       (map (fn [[k v]] [k (assoc v
                                  :trail (vec (conj trail :children k))
                                  :index (inc (or index 1)))]))))

(defn my-tree-branch?
  [[k m]]
  ; Only show open folders that have children
  (and (:open m) (= :folder (:file-type m))))

(defn flatten-my-tree
  [m]
  (tree-seq my-tree-branch? my-tree-children m))

(defn add-ids-to-folders
  [m]
  (let [f (fn [[k v]] (if (= :folder (:file-type v)) [k (assoc v :id k)] [k v]))]
    ;; only apply to maps
    (postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))

;;;;;;;;;; end of tree-seq components

(reg-sub
 ::sort-by
 (fn [db]
   (get-in db [:mymine :sort-by])))

(reg-sub
 ::my-tree
 (fn [db]
   (let [tree (get-in db [:mymine :tree])]
     (add-ids-to-folders tree))))

(reg-sub
 ::selected
 (fn [db]
   (get-in db [:mymine :selected])))

(reg-sub
 ::focus
 (fn [db]
   (get-in db [:mymine :focus])))

(reg-sub
 ::public-lists
 (fn [] [(subscribe [:lists/filtered-lists])])
 (fn [[lists]]
   (filter (fn [l] (= (:authorized l) false)) lists)))

(reg-sub
 ::my-items
 (fn [] (subscribe [:lists/filtered-lists]))
 (fn [lists]
   (->> lists
        (filter (comp true? :authorized))
        (map (fn [l]
               (assoc l
                      :file-type :list
                      :label (:title l)
                      :trail [:public (:title l)]))))))

(reg-sub
 ::files
 (fn [] [(subscribe [::my-tree])
         (subscribe [::focus])
         (subscribe [:lists/filtered-lists])
         (subscribe [::unfilled])
         (subscribe [::my-items])])

 (fn [[tree focus filtered-lists unfilled my-items]]
   (case focus
     [:public] (map (fn [l]
                      (assoc l :file-type :list
                             :read-only? true
                             :label (:title l)
                             :trail [:public (:title l)]))
                     ; TODO - rethink authorized flag
                     ; Assume that all unauthorized lists are public, but I bet this
                     ; isn't true if someone shares a list with you...
                    (filter (comp false? :authorized) filtered-lists))
     [:root] (let [files (:children (:root tree))]
               (->> files
                    (map (fn [[k v]] (assoc v :trail (vec (conj focus :children k)))))
                    (concat unfilled)
                    (sort-by :file-type folders>files)))
      ;[:unsorted] unfilled
     [:unsorted] my-items
     (let [files (:children (get-in tree focus))]
       (->> files
            (map (fn [[k v]] (assoc v :trail (vec (conj focus :children k)))))
            (sort-by :file-type folders>files))))))


; Returns a subtree of the files tree (i.e. files in the current visible directory)


(reg-sub
 ::visible-files
 (fn [] [(subscribe [::focus]) (subscribe [::my-tree]) (subscribe [:lists/filtered-lists])])
 (fn [[focus tree all-lists]]
    ; Get the children of the directory in focus
   (let [files (-> tree (get-in focus) :children)]
      ; Some "focuses" (directories) are special cases
     (case focus
        ; Show just the public items
       [:public] (->> all-lists
                       ; Get all public lists
                      (filter (comp false? :authorized))
                      (map (fn [l] (assoc l :file-type :list :trail (conj focus (:id l))))))

       [:unsorted] (->> all-lists
                         ; Get lists that belong to the user
                        (map (fn [l] (assoc l :file-type :list :trail [])))

                        (filter (comp true? :authorized)))
       (->> files
             ; Give each child a :trail attribute that represents its full path in the tree
            (map (fn [[k v]] (assoc v :trail (vec (conj focus :children k)))))
             ; Sort the children so that folders are first
            (sort-by :file-type folders>files))))))

(reg-sub
 ::selected-details
 (fn [] [(subscribe [::my-tree]) (subscribe [::selected])])
 (fn [[tree selected]]
   (let [selected (first selected)]
     selected)))

(reg-sub
 ::details-keys
 (fn [db] (get-in db [:mymine :details])))

(reg-sub
 ::details
 (fn [] [(subscribe [::details-keys]) (subscribe [:lists/filtered-lists])])
 (fn [[{:keys [id file-type]} lists]]
   (first (filter (comp (partial = id) :id) lists))))

(reg-sub
 ::breadcrumb
 (fn [] [(subscribe [::my-tree]) (subscribe [::focus])])
 (fn [[tree focus]]
   (when (seqable? focus)
     (reduce (fn [total next]
               (if (= next :children)
                 (conj total {:trail (conj (:trail (last total)) next)})
                 (-> total
                     (conj (-> tree
                               (get-in (conj (:trail (last total)) next))
                               (assoc :trail (vec (conj (:trail (last total)) next))))))))
             [] focus))))

; Fill the [:root :public] folder with public lists
(reg-sub
 ::with-public
 (fn [] [(subscribe [::my-tree])
         (subscribe [:lists/filtered-lists])])
 (fn [[my-tree filtered-lists]]
   (assoc-in my-tree [:root :children :public :children]
             (into {} (map
                       (fn [l]
                         {(:title l) (assoc l :file-type :list :read-only? true :label (:title l))})
                         ; TODO - rethink authorized flag
                         ; Assume that all unauthorized lists are public, but I bet this
                         ; isn't true if someone shares a list with you...
                       (filter (comp false? :authorized) filtered-lists))))))

(defn parse-dates [coll]
  (map
   (fn [item]
     (if-not (:dateCreated item)
       item
       (let [parsed (tf/parse (:dateCreated item))]
         (assoc item :date-created-obj parsed
                :friendly-date-created (str
                                        (tf/unparse
                                         (tf/formatter "MMM, dd")
                                         parsed)
                                        " "
                                        (tf/unparse
                                         (tf/formatter "YYYY")
                                         parsed))))))
   coll))

; MyMine data is a nested tree structure, however it's easier to display and sort when it's
; flattened into a list. Applying 'rest' removes the root folder
(reg-sub
 ::as-list
 (fn [] [(subscribe [::with-public]) (subscribe [::sort-by])])
 (fn [[with-public sort-info]]
   (-> with-public :root (assoc :fid :root :trail [:root]) (flatten-tree sort-info) rest parse-dates)))

(defn id-children
  [[parent-k {:keys [file-type children index trail] :as c}]]
  (->> children
       ; Filter just the folders
       ;(filter (fn [[k v]] (= :folder (:file-type v))))
       ; Give the folder its index (indentation) and trail (location in mymine map)
       (map (fn [[k v]] [k v]))))

(defn id-branch?
  [[k m]]
  ; Only show open folders that have children
  map?)

(defn flatten-ids
  [m]
  (tree-seq id-branch? id-children m))

(reg-sub
 ::file-ids
 (fn [] (subscribe [::my-tree]))
 (fn [tree]
   (->> tree
        (mapcat flatten-ids)
        (map (comp :id second))
        (remove nil?)
        set)))

(reg-sub
 ::unfilled
 (fn [] [(subscribe [::file-ids])
         (subscribe [:lists/filtered-lists])])
 (fn [[filed-ids lists]]
   (let [my-list-ids  (set (map :id (filter (comp true? :authorized) lists)))
         unfilled-ids (clojure.set/difference my-list-ids filed-ids)]
     (->> lists
          (filter (fn [l] (some? (some #{(:id l)} unfilled-ids))))
          (map (fn [l]
                 (assoc l
                        :file-type :list
                        :label (:title l)
                        :trail [:public (:title l)])))))))

(reg-sub
 ::context-menu-location
 (fn [db]
   (get-in db [:mymine :context-target])))

(reg-sub
 ::dragging-over-old
 (fn [db]
   (get-in db [:mymine :dragging-over])))

(reg-sub
 ::context-menu-target
 (fn [db]
   (get-in db [:mymine :context-menu-target])))

(reg-sub
 ::edit-target
 (fn [] [(subscribe [::with-public]) (subscribe [::context-menu-location])])
 (fn [[tree location]]
   (assoc (get-in tree location) :trail location)))

(reg-sub
 ::op-selected-items
 (fn [db]
   (get-in db [:mymine :list-operations :selected])))

(reg-sub
 ::checked-ids
 (fn [db] (get-in db [:mymine :checked])))

(reg-sub
 ::checked-details
 (fn [] [(subscribe [:lists/filtered-lists])
         (subscribe [::checked-ids])])
 (fn [[lists checked-ids]]
   (filter (fn [l] (some #{(:id l)} checked-ids)) lists)))

(reg-sub
 ::suggested-modal-state
 (fn [db]
   (get-in db [:mymine :suggested-state])))

(reg-sub
 ::one-list
 (fn [db [_ list-id]]
   (let [current-lists (get-in db [:assets :lists (get db :current-mine)])]
     (->> current-lists (filter #(= list-id (:id %))) first))))

(reg-sub
 ::menu-target
 (fn [db]
   (get-in db [:mymine :menu-target])))

(reg-sub
 ::modal
 (fn [db]
   (get-in db [:mymine :modal])))

(reg-sub
 ::menu-item
 :<- [::menu-target]
 :<- [::my-tree]
 (fn [[menu-target tree]]
   (-> tree (get-in menu-target) (assoc :trail menu-target))))

(reg-sub
 ::menu-details
 (fn [db]
   (get-in db [:mymine :menu-file-details])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(reg-sub
 ::entries
 (fn [db]
   (get-in db [:mymine :entries])))

(reg-sub
 ::tags
 :<- [::entries]
 (fn [entries]
   (filter (comp (partial = "tag") :im-obj-type) entries)))

(reg-sub
 ::not-tags
 :<- [::entries]
 (fn [entries]
   (filter (comp (partial not= "tag") :im-obj-type) entries)))

(reg-sub
 ::root-tags
 :<- [::tags]
 (fn [tags]
   (filter (comp nil? :parent-id) tags)))

(reg-sub
 ::sub-tags
 (fn [] (subscribe [::tags]))
 (fn [tags [_ parent-id]]
   (not-empty (filter (comp (partial = parent-id) :parent-id) tags))))

(reg-sub
 ::cursor
 (fn [db]
   (get-in db [:mymine :cursor])))

(defn isa-filter [root-id hierarchy entry]
  (if-let [entry-id (:entry-id entry)]
    (isa? hierarchy (keyword "tag" entry-id) (keyword "tag" root-id))
    false))

(reg-sub
 ::hierarchy
 (fn [db]
   (get-in db [:mymine :hierarchy])))

(reg-sub
 ::entries-map
 (fn [db]
   (into {} (map (juxt :entry-id identity) (get-in db [:mymine :entries])))))

(reg-sub
 ::untagged-items
 :<- [:lists/filtered-lists]
 :<- [::entries]
 (fn [[lists entries] [evt]]
   (let [tagged-list-ids (set (map :im-obj-id (filter (comp (partial = "list") :im-obj-type) entries)))]
     (->> lists
          (filter (comp (complement tagged-list-ids) :id))
          (map (fn [l] {:im-obj-type "list" :im-obj-id (:id l)}))))))

(reg-sub
 ::hierarchy-trail
 :<- [::hierarchy]
 :<- [::entries-map]
 (fn [[hierarchy entries-map] [_ pid]]
   (if pid
     (let [anc (ancestors hierarchy (keyword "tag" pid))]
       (map
        #(get-in entries-map [% :label])
        (reverse (map name anc))))
     nil)))

(reg-sub
 ::cursor-trail
 :<- [::hierarchy]
 :<- [::entries-map]
 :<- [::cursor]
 (fn [[hierarchy entries-map cursor]]
   (if cursor
     (let [anc (ancestors hierarchy (keyword "tag" (:entry-id cursor)))]
       (->> anc
            (map name)
            (map #(get entries-map %))
            (cons cursor)
            reverse))
     nil)))

(reg-sub
 ::cursor-items
 :<- [::cursor]
 :<- [::hierarchy]
 :<- [::not-tags]
 :<- [::untagged-items]
 :<- [::entries-map]
 (fn [[cursor hierarchy entries untagged entries-map]]
   (if (nil? cursor)
     (concat entries untagged)
     (->> entries
          (filter (partial isa-filter (:entry-id cursor) hierarchy))
          (map (fn [e]
                 (let [anc (ancestors hierarchy (keyword "tag" (:entry-id e)))]
                   (assoc e :hierarchy (map
                                        #(get-in entries-map [% :label])
                                        (reverse (map name anc)))))))))))

(reg-sub
 ::selected-items
 :<- [::checked-ids]
 :<- [::not-tags]
 :<- [::untagged-items]
 :<- [::cursor-items]
 (fn [[checked-ids entries untagged cursor-items]]
   (let [items (concat entries untagged)]
     (filter (comp checked-ids :im-obj-id) (concat entries untagged)))))

(reg-sub
 ::selected-items-not-in-view
 :<- [::selected-items]
 :<- [::cursor-items]
 (fn [[selected-items cursor-items]]
   (let [selected (into #{} selected-items)
         visible  (into #{} (map #(dissoc % :hierarchy) cursor-items))]
     (filter (fn [n] ((complement visible) n)) selected-items))))

(reg-sub
 ::show-selected-pane?
 :<- [::selected-items]
 :<- [::cursor]
 (fn [[selected-items cursor]]
   (let [selected-parent-ids (into #{} (map :parent-id selected-items))]
     (or
        ; Show if we're viewing a tag and selected items have at least 2 different tags. Add lots of CHEESE!!!!!!
      (and cursor (> (count selected-parent-ids) 1))
        ; Show if any of the tag ids of selected items are not the current view
      (and cursor (not-empty selected-items) (nil? (some #{(:entry-id cursor)} selected-parent-ids)))))))

(reg-sub
 ::cursor-items-at
 :<- [::hierarchy]
 :<- [::not-tags]
 :<- [::untagged-items]
 :<- [::entries-map]
 (fn [[hierarchy entries untagged entries-map] [_ entry-id]]
   (if (nil? entry-id)
     (concat entries untagged)
     (->> entries
          (filter (partial isa-filter (:entry-id entry-id) hierarchy))
          (map (fn [e]
                 (let [anc (ancestors hierarchy (keyword "tag" (:entry-id e)))]
                   (assoc e :hierarchy (map
                                        #(get-in entries-map [% :label])
                                        (reverse (map name anc)))))))))))

(reg-sub
 ::sub-not-tags
 :<- [::hierarchy]
 :<- [::not-tags]
 (fn [[hierarchy not-tags] [_ pid]]
   (let [d (descendants hierarchy (keyword "tag" pid))]
     (filter (fn [nt] (some? (some #{(keyword "tag" (:entry-id nt))} d))) not-tags))))

(reg-sub
 ::dragging
 (fn [db]
   (get-in db [:mymine :drag :dragging])))

(reg-sub
 ::dragging?
 (fn [db]
   (get-in db [:mymine :drag :dragging?])))

(reg-sub
 ::dragging-over
 (fn [db]
   (get-in db [:mymine :drag :dragging-over])))

(reg-sub
 ::dropping-on
 (fn [db]
   (get-in db [:mymine :drag :dropping-on])))
