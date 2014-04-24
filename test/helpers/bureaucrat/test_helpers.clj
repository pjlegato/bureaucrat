(ns helpers.bureaucrat.test-helpers
  "Utility functions for use in tests."
  (:require [immutant.messaging  :as mq]
            [com.paullegato.bureaucrat.transports.ironmq :as ironmq-transport]
            [com.paullegato.bureaucrat.transport :as transport]))

(def test-queue-name "test.queue")
(def transport (atom nil)) ;; To hold the current IMessageTransport
(def endpoint (atom nil))      ;; To hold the current IQueueEndpoint

(defn spin-on 
  "Spins at most n times waiting for fn to be true. Each spin is
  timeout ms long."
  ([fn] (spin-on fn 20 200))
  ([fn n timeout]
     (Thread/sleep timeout)
     (if (and (not (fn))
              (> n 0))
       (recur fn (- n 1) timeout))))


(defn create-ironmq-test-queue!
  "Ensure that the test queue is empty of any persistent messages and
   exists in the backend"
  []
  (let [a-transport (ironmq-transport/ironmq-transport)]
    (reset! transport a-transport)
    (reset! endpoint (transport/create-in-backend! a-transport test-queue-name nil))
    (queue/purge! @endpoint)))


(defmacro with-timeout [ms & body]
  `(let [f# (future ~@body)]
     (.get ^java.util.concurrent.Future f# ~ms java.util.concurrent.TimeUnit/MILLISECONDS)))
