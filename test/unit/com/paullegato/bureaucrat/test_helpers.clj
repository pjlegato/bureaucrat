(ns com.paullegato.bureaucrat.test-helpers
  "Utility functions for use in tests."
  (:require [immutant.messaging  :as mq]))


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
