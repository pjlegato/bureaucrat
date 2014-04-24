(ns com.paullegato.bureaucrat.util
  "General utility functions."
    (:require [com.paullegato.bureaucrat.transport  :as transport]
              [com.paullegato.bureaucrat.endpoint   :as endpoint]
              [onelog.core :as log]))



(defn send-to-dlq!
  "Attempts to send the given message to the dead letter queue.
   TODO: This is more generally useful than just in the API router;
   move to general utils file."
  [message]
  (if-let [ingress-transport (-> message :bureaucrat :ingress-endpoint :transport)]
    (try
      (endpoint/send! (transport/dead-letter-queue ingress-transport)  message)
      (catch Throwable t
        (log/error "[bureaucrat][api-router] Exception trying to send a message to the dead letter queue! Exception was: "
                   (log/throwable t)
                   "\nMessage was: " message)))
    (log/error "[bureaucrat][api-router] Couldn't send message to the dead letter queue: "
               "Couldn't find an ingress-transport on the message! Message was: " message)))
