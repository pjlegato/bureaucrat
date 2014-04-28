(ns com.paullegato.bureaucrat.api-routers.api-router-helpers
  "Utility functions shared by multiple API router implementations."
  (:require [com.paullegato.bureaucrat.api-router :as api-router]
            [com.paullegato.bureaucrat.transport  :as transport]
            [com.paullegato.bureaucrat.endpoint   :as endpoint]
            [com.paullegato.bureaucrat.util       :as util :refer [send-to-dlq!]]
            [onelog.core :as log]))

(defn valid-api-message?
  "Inspects the given message and returns whether it is a valid
  Bureaucrat API format message."
  [message]
  (and (map? message)
       (:call message)))



(defn try-handler
  "Given a function and an API message, runs the function with the
  message payload as its argument.

  If the message defines a :reply-to address and the handler returns a
  truthy value, replies to the :reply-to address with the
  result. :correlation-id will be set if given in the incoming
  message.

  If there are any errors, logs them and attempts to put the message
  on the DLQ."
  [f message]

  (when-not (valid-api-message? message)
    (log/error "[bureaucrat][api-router] Rejecting invalid message: " message)
    (send-to-dlq! message))
  
  (let [ingress-transport (-> message :bureaucrat :ingress-endpoint :transport)]
    (try
      (let [reply          (f (:payload message))
            reply-to       (:reply-to message)
            reply-call     (:reply-call message)
            correlation-id (:correlation-id message)]
        (if (and reply reply-to)
          (let [reply-queue (transport/create-in-backend! ingress-transport reply-to nil)]
            (endpoint/send! reply-queue {:payload        reply
                                         :call           reply-call
                                         :correlation-id correlation-id}))))

      (catch Throwable t
        (log/error "[bureaucrat][api-router] Error running API handler: "
                   (log/throwable t) "\nOriginal message was:\n" message)

        ;; Forward a copy of the message to the dead letter queue, if possible:
        (send-to-dlq! message)))))

