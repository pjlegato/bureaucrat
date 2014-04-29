(ns com.paullegato.bureaucrat.util
  "General utility functions."
    (:require [com.paullegato.bureaucrat.transport  :as transport]
              [com.paullegato.bureaucrat.middleware.normalizer :as normalizer :refer [normalize-egress>]]
              [com.paullegato.bureaucrat.data-endpoint :as data-endpoint]
              [clojure.core.async :as async :refer [put!]]
              [onelog.core :as log]))



(defn send-to-dlq!
  "Attempts to send the given message to the dead letter queue.
   TODO: This is more generally useful than just in the API router;
   move to general utils file."
  [message]
  (log/warn "[bureaucrat][dlq] Sending message to dead letter queue: " message)
  (if-let [send-channel (some-> message
                                :bureaucrat
                                :ingress-endpoint
                                :transport
                                transport/dead-letter-queue
                                data-endpoint/send-channel
                                normalize-egress>)]
    (try
      (put! send-channel message)
      (catch Throwable t
        (log/error "[bureaucrat][api-router] Exception trying to send a message to the dead letter queue! Exception was: "
                   (log/throwable t)
                   "\nMessage was: " message)))
    (log/error "[bureaucrat][api-router] Couldn't send message to the dead letter queue: "
               "Couldn't find an ingress-transport on the message! Message was: " message)))



