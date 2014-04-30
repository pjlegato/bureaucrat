(ns helpers.bureaucrat.test-helpers
  "Utility functions for use in tests."
  (:require [immutant.messaging  :as mq]
            [onelog.core :as log]
            [midje.repl]
            [com.paullegato.bureaucrat.transports.ironmq :as ironmq-transport]

            [clj-logging-config.log4j :as log-cfg]
            [clojure.core.async :as async :refer [alts!!]]
            [com.paullegato.bureaucrat.transport :as transport]
            [com.paullegato.bureaucrat.endpoint  :as endpoint]))

(log/start! "/tmp/bureaucrat-tests.log")
(log-cfg/set-loggers! :root {:level :debug})

(def test-queue-name "test.queue")
(def transport (atom nil)) ;; To hold the current IMessageTransport
(def endpoint (atom nil))      ;; To hold the current IQueueEndpoint

(defn trim-filename
  [long-path]
  (last  (clojure.string/split long-path #"/")))

(defn spin-on*
  "Spins at most n times waiting for f to be true. Each spin is
  timeout ms long."
  [f tag n timeout]
  (log/debug "[test-helpers] Spinlocking on " tag)
  (loop [n n]
    (if (f)
      (log/debug "[test-helpers] Spinlock released for " tag)
      (if (> n 0)
        (do (Thread/sleep timeout)
            (recur (- n 1)))
        (throw (Exception. (str "[bureaucrat][test-helpers] Spinlock timeout awaiting " tag)))))))


(defmacro spin-on
  [f n timeout]
  `(spin-on* ~f
             ~(str (pr-str f) " at " (trim-filename *file*) ":" (:line (meta &form)))
             ~n
             ~timeout))


(defn create-ironmq-test-queue!
  "Ensure that the test queue is empty of any persistent messages and
  exists in the backend. Stores the endpoint in the 'endpoint' atom, and
  returns a copy of the new endpoint."
  ([] (create-ironmq-test-queue! nil))
  ([endpoint-options]
     (let [a-transport (ironmq-transport/ironmq-transport)]
       (reset! transport a-transport)
       (reset! endpoint (transport/create-in-backend! a-transport test-queue-name endpoint-options))
       (endpoint/purge! @endpoint)
       @endpoint)))


(defmacro with-timeout 
  "Runs the given code, aborting it after ms milliseconds if it has not
  finished executing yet."
  [ms & body]
  `(let [f# (future ~@body)]
     (.get ^java.util.concurrent.Future f# ~ms java.util.concurrent.TimeUnit/MILLISECONDS)))


(defn repeatedly-run-tests
  ([] (repeatedly-run-tests 10 *ns*))
  ([times] (repeatedly-run-tests times *ns*))
  ([times namespace]
     (dotimes [_ times]
       (log/info "\n\n\n-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=- New test run\n\n")
       (let [runtime  (time (midje.repl/load-facts namespace))]
         (println runtime)
         (log/info "Test result: " runtime)))))


(defn <!!-timeout
  "Like <!!, but times out after the given number of ms."
  [port ms]
  (let [timeout-port  (async/timeout ms)
        [val port] (alts!! [port timeout-port])]
    (if (= port timeout-port)
      (log/warn "[bureaucrat] <<!!-timeout timed out!"))
    val))
