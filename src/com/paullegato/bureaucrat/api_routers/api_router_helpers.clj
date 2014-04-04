(ns com.paullegato.bureaucrat.api-routers.api-router-helpers
  "Utility functions shared by multiple API router implementations."
  (:require [com.paullegato.bureaucrat.api-router :as api-router]
            [onelog.core :as log])
  (:use [com.paullegato.bureaucrat.endpoint]
        [slingshot.slingshot :only [try+ throw+]]))


(defn try-handler
  "Given an API router and a message, tries to look up the correct
  handler function and run it with the message payload. If there are
  any errors, logs them and attempts to put the message on the DLQ.

  TODO: This function is way too complex; break it up."
  [api-router message]
  (try+
   (if-let [call (:call message)]
     (if-let [handler-fn (api-router/handler-for-call api-router call)]
       (try

         (let [reply          (handler-fn (:payload message))
               reply-to       (:reply-to message)
               correlation-id (:correlation-id message)]
           (if reply
             (if-not reply-to
               ;; Caller doesn't care about the return value
               (log/info "[bureaucrat][api-router] Got a reply returned from '" call "', but no :reply-to is set; discarding it. The reply was: " reply)

               ;; Try to send a reply to the :reply-to address using the same transport:
               (if-let [reply-queue (lookup (:x-ingress-endpoint message) reply-to)]
                 (send! reply-queue reply {:correlation-id correlation-id})
                 (log/error "[bureaucrat][api-router] Got a reply returned from '" call "' and :reply-to is set to" 
                            reply-to ", but there is no queue by that name on the ingress endpoint's transport mechanism!"
                            " Discarding the reply. The reply was: " reply)))))

         (catch Throwable t
           (throw+ {:error-message (str "Got exception from handler while processing message -- "  (log/throwable t))})))
       (throw+ {:error-message (str "Message asked for API call '" call "', but no handler function is bound to that call!")}))
     (throw+ {:error-message (str "No API call specified in message " message "!")}))
   (catch map? {:keys [error-message]}
     ;; Log the error:
     (log/error "[bureaucrat][api-router] " error-message)

     ;; Forward a copy of the message to the dead letter queue, if possible:
     (if-let [ingress-endpoint (:x-ingress-endpoint message)]
       (send! (dead-letter-queue ingress-endpoint)
              (assoc message :x-error-processing error-message))
       (log/error "[bureaucrat][api-router] Couldn't find a dead letter queue to put the erroneous message on!")))))

