(ns com.paullegato.bureaucrat.util
  "General utility functions."
    (:require [com.paullegato.bureaucrat.transport  :as transport]
              [com.paullegato.bureaucrat.endpoint   :as endpoint]

              [clojure.core.async :as async :refer [put! go-loop alts! >!]]
              [onelog.core :as log]))



(defn send-to-dlq!
  "Attempts to send the given message to the dead letter queue. 

  The one argument version accepts a normalized message (i.e. one which
  contains a reference to the ingress transport) and uses the DLQ on
  that transport.

  The two argument version accepts any message (not necessarily
  normalized) and a transport, and uses the DLQ on the given transport.

  If an appropriate DLQ cannot be found, drops the message and logs an
  error."
  ([message]
     (send-to-dlq! (some-> message
                          :bureaucrat
                          :ingress-endpoint
                          :transport)
                   message))
  ([transport message]
     (log/warn "[bureaucrat][dlq] Sending message to dead letter queue: " message " on transport: " transport)
     (if-let [dlq (some-> transport
                          transport/dead-letter-queue)]
       (try
         (let [message (if (string? message)
                         message
                         (pr-str message))]
           (endpoint/send! dlq message))
         (catch Throwable t
           (log/error "[bureaucrat][api-router] Exception trying to send a message to the dead letter queue! Exception was: "
                      (log/throwable t)
                      "\nMessage was: " message)))
       (log/error "[bureaucrat][api-router] Couldn't send message to the dead letter queue: "
                  "Couldn't find an ingress-transport on the message! Message was: " message))))



(defn milli-time
  "Returns System/nanoTime converted to milliseconds."
  []
  (long (/ (System/nanoTime) 1000000)))


(defmacro profile
  "Logs profiling information about the execution of the given forms."
  [& forms]
  `(let [start-time# (milli-time)]
     (log/info+ (log/color [:bright :cyan] "[profile] Starting profile run..."))
     (do ~@forms)
     (log/info+ (log/color [:bright :cyan] "[profile] Execution took " (- (milli-time) start-time#) " ms"))))


(defn pmax
  " -- From http://stuartsierra.com/2013/12/08/parallel-processing-with-core-async

  Process messages from input in parallel with at most max concurrent
  operations.

  Invokes f on values taken from input channel. f must return a
  channel, whose first value (if not closed) will be put on the output
  channel.

  Returns a channel which will be closed when the input channel is
  closed and all operations have completed.

  Creates new operations lazily: if processing can keep up with input,
  the number of parallel operations may be less than max.

  Note: the order of outputs may not match the order of inputs."
  [max f input output]
  (go-loop [tasks #{input}]
    (when (seq tasks)
      (let [[value task] (alts! (vec tasks))]
        (if (= task input)
          (if (nil? value)
            (recur (disj tasks task))  ; input is closed
            (recur (conj (if (= max (count tasks))  ; max - 1 tasks running
                           (disj tasks input)  ; temporarily stop reading input
                           tasks)
                         (f value))))
          ;; one processing task finished: continue reading input
          (do (when-not (nil? value) (>! output value))
              (recur (-> tasks (disj task) (conj input)))))))))
