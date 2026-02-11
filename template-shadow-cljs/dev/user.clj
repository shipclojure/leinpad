(ns user
  (:require [myapp.core :as core]))

(defn go []
  (println (core/greet "leinpad"))
  :started)
