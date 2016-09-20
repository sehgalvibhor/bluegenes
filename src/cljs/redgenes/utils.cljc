(ns redgenes.utils
  ""
  (:require
    #?(:cljs [re-frame.core :as re-frame :refer [reg-event-db reg-event-fx reg-fx]])
    [clojure.string :as string]))

(defn register!
  ([v]
   (register! @v (meta v)))
  ([f {reframe-key :reframe-key reframe-kind :reframe-kind naym :name}]
   #?(:cljs
      (case reframe-kind
        :event (reg-event-db reframe-key f)
        :fx     (reg-fx reframe-key f)
        :cofx   (reg-event-fx reframe-key f))
      :clj
        (println "would register" naym reframe-key reframe-kind))))

(comment
  (register!
   (comp test-reset-query (fn [db _] (println "something first") db))
   {:reframe-key  :query-builder/reset-query
    :reframe-kind :effect
    }))

;(register! #'reset-query)