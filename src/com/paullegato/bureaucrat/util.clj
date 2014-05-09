(ns com.paullegato.bureaucrat.util
  "General utility functions."
    (:require [com.paullegato.bureaucrat.transport  :as transport]
              [com.paullegato.bureaucrat.endpoint   :as endpoint]

              [clojure.core.async :as async :refer [put!]]
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
