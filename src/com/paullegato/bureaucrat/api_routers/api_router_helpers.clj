(ns com.paullegato.bureaucrat.api-routers.api-router-helpers
  "Utility functions shared by multiple API router implementations."
  (:require [com.paullegato.bureaucrat.api-router :as api-router]
            [com.paullegato.bureaucrat.transport  :as transport]
            [com.paullegato.bureaucrat.endpoint   :as endpoint]
            [com.paullegato.bureaucrat.util       :as util :refer [send-to-dlq!]]
            [clojure.core.async :as async :refer [<! go-loop]]
            [com.paullegato.bureaucrat.api-router :refer [handler-for-call]]
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
  (log/info "[bureaucrat][api-router] Got message: " (pr-str message))

  (when-not (valid-api-message? message)
    (log/error "[bureaucrat][api-router] Rejecting invalid message: " (pr-str message))
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
                   (log/throwable t) "\nOriginal message was:\n" (pr-str message))

        ;; Forward a copy of the message to the dead letter queue, if possible:
        (send-to-dlq! message)))))


(defn try-to-process-message 
  "Shared implementation for IAPIRouter's `process-message!` call. Tries
  to look up the appropriate handler using `handler-for-call`. If found,
  runs the handler."
  [component message]
  (log/debug "[bureaucrat][api-router-helpers] Trying to process raw message: " (pr-str message))
  (if-let [handler (handler-for-call component (keyword (:call message)))]
    (do (log/info "[bureaucrat][api-router-helpers] Looking up handler for call: " (:call message)
                  ". Message is: " (pr-str message))
        (try-handler handler message))
    (api-router/process-unhandled-message! component message)))


(defn apply-router!
  "Given a core.async channel that produces API messages, passes them
  to the given IAPIRouter as they arrive.
  TODO: Concurrency"
  [source-channel router]
  (log/debug "[bureaucrat] Adding API router " router " to " source-channel "...")
  (go-loop [next-message (<! source-channel)]
    (log/debug "[bureaucrat][api-router-helpers] Got raw message: " (pr-str next-message))
    (if-not next-message
      (log/warn "[bureaucrat][api-router-helpers] Source channel closed; exiting API router loop for router " router)
      (do
        (api-router/process-message! router next-message)
        (recur (<! source-channel))))))


(defn process-unhandled-message
  [component message]
  (log/debug "[bureaucrat][api-router-helpers] No handler found; sending message to default handler fn. Raw message is: " (pr-str message))
  (if-let [f (:unhandled-message-fn component)]
    (f component message)
    (do
      (log/warn "[bureaucrat][api-router-helpers] Couldn't find a valid API handler for call '" (:call message)
                "'; discarding. Message was: " (pr-str message))
      (send-to-dlq! message))))
