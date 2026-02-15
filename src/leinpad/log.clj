(ns leinpad.log
  (:require
   [clojure.string :as str]))

;; ============================================================================
;; ANSI color helpers
;; ============================================================================

(def fg-codes
  {:black   30 :red    31 :green 32 :yellow 33
   :blue    34 :magenta 35 :cyan  36 :white  37})

(defn fg
  "Wrap text in an ANSI foreground color. `color` may be a keyword
   (:green, :magenta …) or a raw SGR code integer."
  [color & parts]
  (str "\u001b["
       (if (keyword? color) (get fg-codes color) color)
       "m"
       (str/join " " parts)
       "\u001b[0m"))

(defn bold [& parts]
  (str "\u001b[1m" (str/join " " parts) "\u001b[0m"))

;; ============================================================================
;; Logging
;; ============================================================================

(def verbose?
  "Atom controlling debug output. Initialised from CLI flags; can be
   toggled later via `set-verbose!` (e.g. after reading leinpad config)."
  (atom (boolean (some #{"-v" "--verbose"} *command-line-args*))))

(defn set-verbose!
  "Enable or disable verbose (debug) logging at runtime."
  [v]
  (reset! verbose? (boolean v)))

(defn debug [& args] (when @verbose? (apply println (fg :cyan "[DEBUG]") args)))
(defn info  [& args] (apply println (fg :green "[INFO]") args))
(defn warn  [& args] (apply println (fg :yellow "[WARN]") args))
(defn error [& args] (apply println (fg :red "[ERROR]") args))
