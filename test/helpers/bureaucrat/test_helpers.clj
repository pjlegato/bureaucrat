(ns helpers.bureaucrat.test-helpers
  "Utility functions for use in tests."
  (:require [immutant.messaging  :as mq]
            [com.paullegato.bureaucrat.endpoints.ironmq :as im]
            [com.paullegato.bureaucrat.endpoint :as queue]))

(def test-queue-name "test.queue")


(defn spin-on 
  "Spins at most n times waiting for fn to be true. Each spin is
  timeout ms long."
  ([fn] (spin-on fn 20 200))
  ([fn n timeout]
     (Thread/sleep timeout)
     (if (and (not (fn))
              (> n 0))
       (recur fn (- n 1) timeout))))


(defn reset-queue!
  "Ensure that the given queue is empty of any persistent messages and
   does not exist in the backend, in case it leaks out of a failed
   test run."
  [name]
 (let [queue (mq/as-queue name)] 
   (mq/start queue)
   (.removeMessages (immutant.messaging.hornetq/destination-controller queue) "")
   (mq/stop queue :force true)))


(defn reset-ironmq-test-queue!
  "Ensure that the test queue is empty of any persistent messages and
   does not exist in the backend, in case it leaks out of a failed
   test run."
  []
  (try
    (let [queue (im/start-ironmq-endpoint! test-queue-name)] 
      (queue/destroy-in-backend! queue))
    (catch io.iron.ironmq.HTTPException e
      (if-not (= (.getMessage e) "Queue not found")
        ;; re-throw if it is anything other than the queue not existing
        (throw e)))))


(defmacro with-timeout [ms & body]
  `(let [f# (future ~@body)]
     (.get f# ~ms java.util.concurrent.TimeUnit/MILLISECONDS)))
