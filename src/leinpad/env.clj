(ns leinpad.env
  "Environment variable loading from .env files"
  (:require
   [babashka.fs :as fs]
   [lambdaisland.dotenv :as dotenv]
   [leinpad.log :as log]))

(def env-file-paths
  "Dotenv files to load, in order of precedence (earlier files are overridden by later)."
  [".env" ".env.local"])

(defn parse-dotenv
  "Parse a .env file at the given path. Returns a map of environment variables, or
  an empty map if the file doesn't exist or can't be parsed."
  [path]
  (let [file (fs/path path)]
    (if (fs/exists? file)
      (try
        (let [content (slurp file)
              parsed (dotenv/parse-dotenv content)]
          (log/debug "Loaded" (count parsed) "env vars from" path)
          parsed)
        (catch Exception e
          (log/warn "Failed to parse" path ":" (ex-message e))
          {}))
      (do
        (log/debug "Dotenv file not found:" path)
        {}))))

(defn load-dotenv-files
  "Load and merge all .env files from the project root.
   Returns a map of environment variables. Later files override earlier ones."
  [project-root]
  (log/debug "Looking for .env files in" project-root)
  (reduce
    (fn [env-map filename]
      (let [path (str (fs/path project-root filename))
            parsed (parse-dotenv path)]
        (merge env-map parsed)))
    {}
    env-file-paths))
