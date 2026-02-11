(ns myapp.app
  (:require [reagent.dom :as rdom]))

(defn root []
  [:div
   [:h1 "Hello from leinpad + shadow-cljs!"]])

(defn ^:dev/after-load render []
  (rdom/render [root] (.getElementById js/document "app")))

(defn init []
  (render))
