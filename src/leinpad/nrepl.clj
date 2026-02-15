(ns leinpad.nrepl
  "Minimal nREPL client for evaluating code against a running nREPL server.
   Supports timeouts and proper error detection via the \"ex\" field."
  (:require
   [bencode.core :as bencode]
   [leinpad.log :as log])
  (:import
   (java.io PushbackInputStream)
   (java.net Socket)))

(defn- bytes->str
  "Convert byte array to string, or return as-is if already a string."
  [x]
  (if (bytes? x)
    (String. ^bytes x "UTF-8")
    x))

(defn eval-expr
  "Evaluate code in a running nREPL server. Returns the result string or throws on error.

   Options:
     :timeout     — socket timeout in ms (default 60000)
     :stderr-fn   — callback invoked with each stderr chunk (default: log/debug).
                     Pass `log/info` or `println` to surface process output."
  [host port code & {:keys [timeout stderr-fn]
                     :or {timeout 60000
                          stderr-fn (fn [msg] (log/debug "nREPL stderr:" msg))}}]
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

          ;; stderr output — route through caller-supplied handler
          err
          (do
            (stderr-fn err)
            (recur result error))

          value
          (recur value error)

          (and status (some #(= (bytes->str %) "done") status))
          (if error
            (throw (ex-info "nREPL eval error" {:error error :code code}))
            result)

          :else
          (recur result error))))))
