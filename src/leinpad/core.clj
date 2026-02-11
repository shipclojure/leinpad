(ns leinpad.core
  "A launchpad-inspired REPL launcher for Leiningen projects.

   Reuses lambdaisland/launchpad utilities (Emacs integration, nREPL wait,
   free-port, CLI handling, process management) and adds Leiningen-specific
   command building, config reading, and post-startup nREPL evaluation.

   Usage:
     (require '[leinpad.core :as leinpad])
     (leinpad/main {:profiles [:dev :test] :nrepl-port 7888 :go true})"
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p :refer [process]]
   [babashka.wait :as wait]
   [bencode.core :as bencode]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [lambdaisland.launchpad.log :as log])
  (:import
   [java.net Socket]
   [java.io PushbackInputStream]))

;; ============================================================================
;; Default dependency versions
;; ============================================================================

(def default-nrepl-version "1.5.1")
(def default-cider-nrepl-version "0.58.0")
(def default-refactor-nrepl-version "3.11.0")
(def default-shadow-cljs-version "2.28.20")

;; ============================================================================
;; nREPL Client
;; ============================================================================

(defn- bytes->str
  "Convert byte array to string, or return as-is if already a string."
  [x]
  (if (bytes? x)
    (String. ^bytes x "UTF-8")
    x))

(defn nrepl-eval
  "Evaluate code in a running nREPL server. Returns the result string or throws on error."
  [host port code & {:keys [timeout] :or {timeout 60000}}]
  (log/debug "nREPL eval on" (str host ":" port) "-" code)
  (with-open [socket (Socket. ^String host ^int port)
              in (PushbackInputStream. (.getInputStream socket))
              out (.getOutputStream socket)]
    (.setSoTimeout socket timeout)
    (bencode/write-bencode out {"op" "eval" "code" code})
    (loop [result nil
           error nil]
      (let [response (bencode/read-bencode in)
            status (get response "status")
            value (bytes->str (get response "value"))
            err (bytes->str (get response "err"))
            ex (bytes->str (get response "ex"))]
        (cond
          ;; Actual exception — record as error
          ex
          (recur result ex)

          ;; stderr output — log it but don't treat as error
          err
          (do
            (log/debug "nREPL stderr:" err)
            (recur result error))

          value
          (recur value error)

          (and status (some #(= (bytes->str %) "done") status))
          (if error
            (throw (ex-info "nREPL eval error" {:error error :code code}))
            result)

          :else
          (recur result error))))))

;; ============================================================================
;; Emacs Integration
;; ============================================================================

(defn emacsclient-available? []
  (try
    (zero? (:exit @(process ["which" "emacsclient"] {:out :string :err :string})))
    (catch Exception _ false)))

(defn eval-emacs [elisp]
  (log/debug "Evaluating in Emacs:" elisp)
  @(process ["emacsclient" "-e" elisp] {:out :string :err :string}))

(defn emacs-require [package]
  (let [result (eval-emacs (str "(featurep '" package ")"))]
    (= "t\n" (:out result))))

(defn emacs-cider-version []
  (let [result (eval-emacs "(if (boundp 'cider-required-middleware-version) cider-required-middleware-version cider-version)")]
    (when (zero? (:exit result))
      (-> (:out result) str/trim (str/replace "\"" "")))))

(defn emacs-refactor-nrepl-version []
  (let [result (eval-emacs "(when (boundp 'cljr-injected-middleware-version) cljr-injected-middleware-version)")]
    (when (zero? (:exit result))
      (let [v (str/trim (:out result))]
        (when (and (not= v "nil") (seq v))
          (str/replace v "\"" ""))))))

(defn connect-emacs-clj! [host port project-dir]
  (log/info "Connecting Emacs to Clojure nREPL at" (str host ":" port) "...")
  (let [elisp (format "(cider-connect-clj '(:host \"%s\" :port %d :project-dir \"%s\"))"
                      host port project-dir)
        result (eval-emacs elisp)]
    (if (zero? (:exit result))
      (log/info "Emacs connected to Clojure nREPL")
      (log/warn "Failed to connect Emacs:" (:err result)))))

(defn connect-emacs-cljs-sibling! [host port project-dir build-id]
  (log/info "Connecting Emacs to ClojureScript REPL for build:" (name build-id))
  (let [init-sym (str "leinpad/" (name build-id))
        register-elisp (format
                        "(setf (alist-get '%s cider-cljs-repl-types) '(\"%s\"))"
                        init-sym
                        (pr-str `(shadow.cljs.devtools.api/nrepl-select ~build-id)))
        connect-elisp (format
                       "(cider-connect-sibling-cljs '(:cljs-repl-type %s :host \"%s\" :port %d :project-dir \"%s\"))"
                       init-sym host port project-dir)]
    (eval-emacs register-elisp)
    (Thread/sleep 500)
    (let [result (eval-emacs connect-elisp)]
      (if (zero? (:exit result))
        (log/info "Emacs connected to ClojureScript REPL for" (name build-id))
        (log/warn "Failed to connect CLJS REPL:" (:err result))))))

;; ============================================================================
;; Configuration
;; ============================================================================

(defn free-port
  "Find a free TCP port."
  []
  (with-open [sock (java.net.ServerSocket. 0)]
    (.getLocalPort sock)))

(def default-config
  {:nrepl-bind "127.0.0.1"
   :profiles [:dev]
   :emacs false
   :verbose false
   :go false
   :clean true
   :cider-nrepl false
   :refactor-nrepl false
   :shadow-cljs false
   :shadow-build-ids [:app]
   :shadow-connect-ids []
   :extra-deps []
   :extra-plugins []})

(defn- load-config-file [filename]
  (if (fs/exists? filename)
    (do (log/debug "Loading config from" filename)
        (edn/read-string (slurp filename)))
    {}))

(defn- merge-leinpad-configs
  "Merge two leinpad config maps (with :leinpad/ namespaced keys).
   :leinpad/options are deep-merged. Collection keys are combined with distinct."
  [a b]
  (let [merged-opts (merge (:leinpad/options a) (:leinpad/options b))
        merged-profiles (vec (distinct (into (or (:leinpad/profiles a) [])
                                             (or (:leinpad/profiles b) []))))
        merged-deps (vec (distinct (into (or (:leinpad/extra-deps a) [])
                                         (or (:leinpad/extra-deps b) []))))
        merged-plugins (vec (distinct (into (or (:leinpad/extra-plugins a) [])
                                            (or (:leinpad/extra-plugins b) []))))
        merged-main-opts (or (:leinpad/main-opts b) (:leinpad/main-opts a))]
    (cond-> {}
      (seq merged-opts) (assoc :leinpad/options merged-opts)
      (seq merged-profiles) (assoc :leinpad/profiles merged-profiles)
      (seq merged-deps) (assoc :leinpad/extra-deps merged-deps)
      (seq merged-plugins) (assoc :leinpad/extra-plugins merged-plugins)
      merged-main-opts (assoc :leinpad/main-opts merged-main-opts))))

(defn read-lein-config
  "Read leinpad.edn + leinpad.local.edn, merge with defaults and ctx.
   Both files use the same :leinpad/ namespaced format."
  [ctx]
  (let [project-root (:project-root ctx (System/getProperty "user.dir"))
        file-config (load-config-file (str (fs/path project-root "leinpad.edn")))
        local-config (load-config-file (str (fs/path project-root "leinpad.local.edn")))
        ;; Merge the two leinpad config files
        leinpad-config (merge-leinpad-configs file-config local-config)
        ;; Build the flat ctx: defaults < options from files < programmatic ctx
        merged (merge default-config
                      (:leinpad/options leinpad-config)
                      ctx)
        ;; Merge collection keys
        merged (cond-> merged
                 (:leinpad/profiles leinpad-config)
                 (update :profiles #(vec (distinct (into (or % []) (:leinpad/profiles leinpad-config)))))

                 (:leinpad/extra-deps leinpad-config)
                 (update :extra-deps #(vec (distinct (into (or % []) (:leinpad/extra-deps leinpad-config)))))

                 (:leinpad/extra-plugins leinpad-config)
                 (update :extra-plugins #(vec (distinct (into (or % []) (:leinpad/extra-plugins leinpad-config)))))

                 (:leinpad/main-opts leinpad-config)
                 (update :main-opts #(or % (:leinpad/main-opts leinpad-config))))]
    (cond-> merged
      (:emacs merged) (assoc :cider-nrepl true :refactor-nrepl true)
      (:vs-code merged) (assoc :cider-nrepl true))))

;; ============================================================================
;; CLI Argument Parsing
;; ============================================================================

(defn parse-cli-args
  "Parse command line arguments into an options map."
  [args]
  (loop [args (seq args)
         opts {}]
    (if-not args
      opts
      (let [[arg & rest-args] args]
        (case arg
          "--emacs" (recur rest-args (assoc opts :emacs true))
          "--no-emacs" (recur rest-args (assoc opts :emacs false))
          ("--verbose" "-v") (recur rest-args (assoc opts :verbose true))
          "--go" (recur rest-args (assoc opts :go true))
          "--no-go" (recur rest-args (assoc opts :go false))
          "--clean" (recur rest-args (assoc opts :clean true))
          "--no-clean" (recur rest-args (assoc opts :clean false))
          ("--port" "-p") (recur (next rest-args) (assoc opts :nrepl-port (parse-long (first rest-args))))
          ("--bind" "-b") (recur (next rest-args) (assoc opts :nrepl-bind (first rest-args)))
          "--profile" (recur (next rest-args) (update opts :profiles (fnil conj []) (keyword (first rest-args))))
          "--vs-code" (recur rest-args (assoc opts :vs-code true))
          "--no-vs-code" (recur rest-args (assoc opts :vs-code false))
          "--cider-nrepl" (recur rest-args (assoc opts :cider-nrepl true))
          "--no-cider-nrepl" (recur rest-args (assoc opts :cider-nrepl false))
          "--refactor-nrepl" (recur rest-args (assoc opts :refactor-nrepl true))
          "--no-refactor-nrepl" (recur rest-args (assoc opts :refactor-nrepl false))
          "--shadow-cljs" (recur rest-args (assoc opts :shadow-cljs true))
          "--no-shadow-cljs" (recur rest-args (assoc opts :shadow-cljs false))
          "--shadow-build" (recur (next rest-args) (update opts :shadow-build-ids (fnil conj []) (keyword (first rest-args))))
          "--shadow-connect" (recur (next rest-args) (update opts :shadow-connect-ids (fnil conj []) (keyword (first rest-args))))
          "--cider-connect" (recur rest-args (assoc opts :cider-connect true))
          "--no-cider-connect" (recur rest-args (assoc opts :cider-connect false))
          "--help" (do
                     (println "leinpad - A launchpad-inspired REPL launcher for Leiningen projects")
                     (println)
                     (println "Options:")
                     (println "  --emacs              Connect Emacs CIDER after REPL starts")
                     (println "  --no-emacs           Don't connect Emacs (default)")
                     (println "  -v, --verbose        Show debug output")
                     (println "  --go                 Call (user/go) after REPL starts")
                     (println "  --no-go              Don't call (user/go) (default)")
                     (println "  --clean              Run lein clean before starting (default)")
                     (println "  --no-clean           Skip lein clean")
                     (println "  -p, --port PORT      nREPL port (default: random)")
                     (println "  -b, --bind ADDR      nREPL bind address (default: 127.0.0.1)")
                     (println "  --profile PROFILE    Add lein profile (repeatable)")
                     (println)
                     (println "Middleware:")
                     (println "  --cider-nrepl        Include CIDER nREPL middleware")
                     (println "  --no-cider-nrepl     Exclude CIDER middleware (default)")
                     (println "  --refactor-nrepl     Include refactor-nrepl middleware")
                     (println "  --no-refactor-nrepl  Exclude refactor-nrepl (default)")
                     (println "  --vs-code            Alias for --cider-nrepl")
                     (println)
                     (println "Shadow-cljs:")
                     (println "  --shadow-cljs        Enable shadow-cljs integration")
                     (println "  --no-shadow-cljs     Disable shadow-cljs (default)")
                     (println "  --shadow-build ID    Shadow build to watch (repeatable)")
                     (println "  --shadow-connect ID  Shadow build to connect REPL (repeatable)")
                     (println)
                     (println "  --help               Show this help")
                     (System/exit 0))
          (do
            (log/debug "Unknown argument:" arg)
            (recur rest-args opts)))))))

;; ============================================================================
;; Steps
;; ============================================================================

(defn get-nrepl-port
  "Assign a random free port if :nrepl-port is not set."
  [ctx]
  (assoc ctx :nrepl-port (or (:nrepl-port ctx) (free-port))))

(defn inject-lein-middleware
  "Resolve dependency versions for nREPL middleware. When emacs is enabled,
   tries to match the CIDER/refactor-nrepl versions from the running Emacs."
  [ctx]
  (let [cider-v (if (:emacs ctx)
                  (or (emacs-cider-version) default-cider-nrepl-version)
                  default-cider-nrepl-version)
        refactor-v (if (:emacs ctx)
                     (or (emacs-refactor-nrepl-version) default-refactor-nrepl-version)
                     default-refactor-nrepl-version)]
    (assoc ctx
           :nrepl-version (or (:nrepl-version ctx) default-nrepl-version)
           :cider-nrepl-version cider-v
           :refactor-nrepl-version refactor-v
           :shadow-cljs-version (or (:shadow-cljs-version ctx) default-shadow-cljs-version))))

(defn maybe-lein-clean
  "Run `lein clean` if :clean is true."
  [ctx]
  (when (:clean ctx)
    (log/info "Cleaning AOT-compiled classes...")
    @(process ["lein" "clean"] {:out :inherit :err :inherit
                                :dir (:project-root ctx)})
    (log/info "Clean complete"))
  ctx)

(defn build-lein-cmd
  "Build the lein command vector with runtime dependency injection.
   Uses `lein update-in` to inject nREPL, CIDER, refactor-nrepl, and
   shadow-cljs dependencies/plugins at startup."
  [{:keys [nrepl-port nrepl-bind profiles cider-nrepl refactor-nrepl shadow-cljs
           nrepl-version cider-nrepl-version refactor-nrepl-version shadow-cljs-version
           extra-deps extra-plugins]
    :or {nrepl-version default-nrepl-version
         cider-nrepl-version default-cider-nrepl-version
         refactor-nrepl-version default-refactor-nrepl-version
         shadow-cljs-version default-shadow-cljs-version}}]
  (let [;; Auto-add :cljs profile when shadow-cljs is enabled
        profiles (cond-> profiles
                   (and shadow-cljs (not (some #{:cljs} profiles)))
                   (conj :cljs))
        profile-str (str/join "," (map name profiles))]
    (cond-> ["lein"]
      ;; with-profile must come before update-in
      (seq profile-str)
      (into ["with-profile" profile-str "do"])

      ;; Always inject nrepl
      true
      (into ["update-in" ":dependencies" "conj"
             (format "[nrepl/nrepl \"%s\"]" nrepl-version) "--"])

      ;; CIDER nREPL as plugin
      cider-nrepl
      (into ["update-in" ":plugins" "conj"
             (format "[cider/cider-nrepl \"%s\"]" cider-nrepl-version) "--"])

      ;; refactor-nrepl as both dep and plugin
      refactor-nrepl
      (into ["update-in" ":dependencies" "conj"
             (format "[refactor-nrepl/refactor-nrepl \"%s\"]" refactor-nrepl-version) "--"])
      refactor-nrepl
      (into ["update-in" ":plugins" "conj"
             (format "[refactor-nrepl/refactor-nrepl \"%s\"]" refactor-nrepl-version) "--"])

      ;; Shadow-cljs dependency
      shadow-cljs
      (into ["update-in" ":dependencies" "conj"
             (format "[thheller/shadow-cljs \"%s\"]" shadow-cljs-version) "--"])

      ;; Shadow-cljs nREPL middleware
      shadow-cljs
      (into ["update-in" ":repl-options:nrepl-middleware" "conj"
             "shadow.cljs.devtools.server.nrepl/middleware" "--"])

      ;; Extra deps from leinpad config
      (seq extra-deps)
      (as-> cmd
            (reduce (fn [c [lib ver]]
                      (into c ["update-in" ":dependencies" "conj"
                               (format "[%s \"%s\"]" lib ver) "--"]))
                    cmd extra-deps))

      ;; Extra plugins from leinpad config
      (seq extra-plugins)
      (as-> cmd
            (reduce (fn [c [lib ver]]
                      (into c ["update-in" ":plugins" "conj"
                               (format "[%s \"%s\"]" lib ver) "--"]))
                    cmd extra-plugins))

      ;; Start headless repl
      true
      (into ["repl" ":headless" ":host" nrepl-bind ":port" (str nrepl-port)]))))

(defn start-lein-process
  "Build the lein command and start the REPL process in the background."
  [ctx]
  (let [cmd (build-lein-cmd ctx)]
    (log/info "Starting lein REPL on" (str (:nrepl-bind ctx) ":" (:nrepl-port ctx)))
    (when (:cider-nrepl ctx) (log/info "  with cider-nrepl" (:cider-nrepl-version ctx)))
    (when (:refactor-nrepl ctx) (log/info "  with refactor-nrepl" (:refactor-nrepl-version ctx)))
    (when (:shadow-cljs ctx) (log/info "  with shadow-cljs" (:shadow-cljs-version ctx)))
    (log/debug "Command:" (str/join " " cmd))
    (let [proc (process cmd {:out :inherit
                             :err :inherit
                             :dir (:project-root ctx)})]
      (assoc ctx :repl-process proc))))

(defn wait-for-nrepl
  "Wait for the nREPL server to become reachable."
  [ctx]
  (let [{:keys [nrepl-port nrepl-bind]} ctx
        timeout 300000]
    (log/info "Waiting for nREPL on" (str nrepl-bind ":" nrepl-port) "...")
    (if (wait/wait-for-port nrepl-bind nrepl-port {:timeout timeout :pause 500})
      (do
        (log/info "nREPL is ready")
        ;; Give middleware time to initialize
        (Thread/sleep 2000)
        ctx)
      (do
        (log/error "nREPL failed to start within timeout")
        (System/exit 1)))))

(defn- read-shadow-cljs-edn
  "Read shadow-cljs.edn from the project root, if it exists."
  [project-root]
  (let [f (str (fs/path project-root "shadow-cljs.edn"))]
    (when (fs/exists? f)
      (edn/read-string (slurp f)))))

(defn- shadow-dev-http-ports
  "Extract dev HTTP ports from shadow-cljs.edn build configs.
   Returns a map of build-id -> port."
  [shadow-config build-ids]
  (when shadow-config
    (into {}
          (keep (fn [id]
                  (when-let [port (get-in shadow-config [:builds id :devtools :http-port])]
                    [id port])))
          build-ids)))

(defn maybe-start-shadow
  "Start shadow-cljs builds via nREPL eval after the REPL is running."
  [ctx]
  (when (:shadow-cljs ctx)
    (let [build-ids (or (seq (:shadow-build-ids ctx)) [:app])
          host (:nrepl-bind ctx)
          port (:nrepl-port ctx)
          clj-code (format "(do (require 'shadow.cljs.devtools.server) (require 'shadow.cljs.devtools.api) (shadow.cljs.devtools.server/start!) %s :shadow-started)"
                           (str/join " "
                                     (map #(format "(shadow.cljs.devtools.api/watch %s)" (keyword %))
                                          build-ids)))]
      (log/info "Starting shadow-cljs watch for builds:" (str/join " " (map name build-ids)))
      (try
        (let [result (nrepl-eval host port clj-code :timeout 120000)]
          (if (= ":shadow-started" result)
            (do
              (log/info "Shadow-cljs builds started successfully")
              (let [shadow-config (read-shadow-cljs-edn (:project-root ctx))
                    http-ports (shadow-dev-http-ports shadow-config build-ids)
                    dashboard-port (get-in shadow-config [:http :port] 9630)]
                (log/info (str "Shadow-cljs dashboard: http://localhost:" dashboard-port))
                (doseq [[id p] http-ports]
                  (log/info (str "  " (name id) " dev server: http://localhost:" p)))))
            (log/info "Shadow-cljs returned:" result)))
        (catch Exception e
          (log/warn "Failed to start shadow-cljs builds:" (ex-message e))))))
  ctx)

(defn maybe-go
  "Evaluate (user/go) via nREPL if :go is true."
  [ctx]
  (when (:go ctx)
    (let [host (:nrepl-bind ctx)
          port (:nrepl-port ctx)]
      (log/info "Evaluating (user/go)...")
      (try
        (let [result (nrepl-eval host port "(user/go)" :timeout 120000)]
          (log/info "(user/go) =>" result))
        (catch Exception e
          (log/warn "(user/go) failed:" (ex-message e))))))
  ctx)

(defn maybe-connect-emacs
  "Connect Emacs CIDER to the running nREPL. Connects both CLJ and CLJS sibling REPLs."
  [ctx]
  (when (or (:emacs ctx) (:cider-connect ctx))
    (if (emacsclient-available?)
      (if (emacs-require 'cider)
        (let [host (:nrepl-bind ctx)
              port (:nrepl-port ctx)
              project-dir (:project-root ctx (System/getProperty "user.dir"))]
          (connect-emacs-clj! host port project-dir)
          (when (:shadow-cljs ctx)
            (Thread/sleep 2000)
            (let [connect-ids (if (seq (:shadow-connect-ids ctx))
                                (:shadow-connect-ids ctx)
                                (:shadow-build-ids ctx))]
              (doseq [build-id connect-ids]
                (connect-emacs-cljs-sibling! host port project-dir build-id)
                (Thread/sleep 1000)))))
        (log/warn "CIDER not loaded in Emacs, skipping connection"))
      (log/warn "emacsclient not found, skipping Emacs connection")))
  ctx)

(defn print-summary
  "Print a colored startup summary."
  [ctx]
  (println)
  (println "========================================")
  (println "leinpad - Leiningen REPL Launcher")
  (println "========================================")
  (println "nREPL:" (str (:nrepl-bind ctx) ":" (:nrepl-port ctx)))
  (println "Profiles:" (str/join ", " (map name (:profiles ctx))))
  (when (or (:emacs ctx) (:cider-connect ctx))
    (println "Emacs: will connect CIDER"))
  (when (:cider-nrepl ctx)
    (println "CIDER nREPL: enabled"))
  (when (:refactor-nrepl ctx)
    (println "refactor-nrepl: enabled"))
  (when (:shadow-cljs ctx)
    (println "Shadow-cljs: builds" (str/join ", " (map name (:shadow-build-ids ctx)))))
  (when (:go ctx)
    (println "Go: will call (user/go)"))
  (when (:clean ctx)
    (println "Clean: will run lein clean"))
  (println "========================================")
  (println)
  ctx)

;; ============================================================================
;; Signal Handling
;; ============================================================================

(defn- setup-shutdown-hook! [ctx-atom]
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread.
    (fn []
      (println "\nShutting down...")
      (when-let [proc (:repl-process @ctx-atom)]
        (println "Stopping REPL...")
        (p/destroy-tree proc))))))

;; ============================================================================
;; Pipeline
;; ============================================================================

(def before-steps
  [read-lein-config
   get-nrepl-port
   inject-lein-middleware
   maybe-lein-clean
   print-summary])

(def after-steps
  [wait-for-nrepl
   maybe-start-shadow
   maybe-go
   maybe-connect-emacs])

(defn process-steps
  "Reduce over steps, threading context through each step function."
  [ctx steps]
  (reduce (fn [ctx step] (step ctx)) ctx steps))

(defn main
  "Main entry point. Call from your bin/leinpad script.

  Options:
    :profiles      [:dev :test]    - Lein profiles to activate
    :nrepl-port    7888            - nREPL port (default: random free port)
    :go            true            - Call (user/go) after startup
    :pre-steps     [my-step]       - Steps to run before start-lein-process
    :post-steps    [my-other-step] - Steps to run after start-lein-process
    :steps         [...]           - Full override of step pipeline
    :emacs         true            - Enable Emacs integration
    :clean         true            - Run lein clean before starting
    :cider-nrepl   true            - Include CIDER nREPL middleware
    :refactor-nrepl true           - Include refactor-nrepl middleware
    :shadow-cljs   true            - Enable shadow-cljs integration
    :shadow-build-ids [:app]       - Shadow builds to watch
    :shadow-connect-ids [:app]     - Shadow builds to connect REPL"
  ([] (main {}))
  ([opts]
   (let [cli-opts (parse-cli-args *command-line-args*)
         ctx (merge {:project-root (System/getProperty "user.dir")
                     :main-opts *command-line-args*}
                    opts
                    cli-opts)
         ctx-atom (atom ctx)
         _ (setup-shutdown-hook! ctx-atom)
         pre (:pre-steps ctx [])
         post (:post-steps ctx [])
         steps (or (:steps ctx)
                   (vec (concat before-steps
                                pre
                                [start-lein-process]
                                post
                                after-steps)))
         ctx (process-steps ctx steps)]
     (reset! ctx-atom ctx)
     (when-let [proc (:repl-process ctx)]
       (log/info "REPL running. Press Ctrl+C to stop.")
       @proc))))

;; Run when invoked directly
(when (= *file* (System/getProperty "babashka.file"))
  (main))
