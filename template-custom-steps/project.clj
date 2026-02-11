(defproject com.example/myapp "0.1.0-SNAPSHOT"
  :description "Example Leiningen project with Docker services"
  :dependencies [[org.clojure/clojure "1.12.0"]]

  :source-paths ["src"]

  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[integrant/repl "0.4.0"]]}
             :test {:source-paths ["test"]}})
