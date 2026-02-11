(defproject com.example/myapp-cljs "0.1.0-SNAPSHOT"
  :description "Example Leiningen + shadow-cljs project using leinpad"
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/clojurescript "1.11.132"]]

  :source-paths ["src"]

  :profiles {:dev  {:source-paths ["dev"]
                    :dependencies [[integrant/repl "0.4.0"]]}
             :cljs {:dependencies [[reagent/reagent "1.2.0"]
                                   [re-frame/re-frame "1.4.3"]]}
             :test {:source-paths ["test"]}})
