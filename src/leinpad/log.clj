(ns leinpad.log)

(def verbose? (some #{"-v" "--verbose"} *command-line-args*))

(defn debug [& args] (when verbose? (apply println "[DEBUG]" args)))
(defn info [& args] (apply println "[INFO]" args))
(defn warn [& args] (apply println "[WARN]" args))
(defn error [& args] (apply println "[ERROR]" args))
