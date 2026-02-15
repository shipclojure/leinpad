(ns build
  (:refer-clojure :exclude [test])
  (:require
   [clojure.tools.build.api :as b]
   [deps-deploy.deps-deploy :as dd]))

(def lib 'com.shipclojure/leinpad)
(def version "v0.1.0")
(def class-dir "target/classes")
(def url "https://github.com/shipclojure/leinpad")

(defn- pom-data []
  [[:description "A launchpad-inspired dev process launcher for Leiningen projects"]
   [:url url]
   [:licenses
    [:license
     [:name "MIT License"]
     [:url "https://opensource.org/licenses/MIT"]]]
   [:developers
    [:developer
     [:name "Ovi Stoica"]]]
   [:scm
    [:url url]
    [:connection "scm:git:git://github.com/shipclojure/leinpad.git"]
    [:developerConnection "scm:git:ssh://git@github.com/shipclojure/leinpad.git"]
    [:tag (str "v" version)]]])

(defn- jar-opts [opts]
  (assoc opts
         :lib lib :version version
         :jar-file (format "target/%s-%s.jar" lib version)
         :basis (b/create-basis {})
         :class-dir class-dir
         :target "target"
         :src-dirs ["src" "resources"]
         :pom-data (pom-data)))

(defn jar
  "Build the JAR."
  [opts]
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "\nCopying source...")
    (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
    (println "\nBuilding JAR...")
    (b/jar opts)
    (println "\nBuild Done ✅"))
  opts)

(defn install
  "Install the JAR locally."
  [opts]
  (let [opts (jar-opts opts)]
    (b/install opts))
  opts)

(defn deploy "Deploy the JAR to Clojars."
  [opts]
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (dd/deploy {:installer :remote :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)
