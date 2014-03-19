(ns com.paullegato.bureaucrat.api-routers.api-router-helpers
  "Utility functions shared by multiple API router implementations."
  (:use [com.paullegato.bureaucrat.endpoint]
        [slingshot.slingshot :only [try+ throw+]])
  (:require  [com.paullegato.bureaucrat.api-router :as api-router]
             [onelog.core :as log]))


(defn try-handler
  "Given an API router and a message, tries to look up the correct
  handler function and run it with the message payload. If there are
  any errors, logs them and attempts to put the message on the DLQ."
  [api-router message]
  (try+
   (if-let [call (:call message)]
     (if-let [handler-fn (api-router/handler-for-call api-router call)]
       (try
         (handler-fn (:payload message))
         (catch Throwable t
           (throw+ {:error-message (str "Got exception from handler while processing message -- "  (log/throwable t))})))
       (throw+ {:error-message (str "Message asked for API call '" call "', but no handler function is bound to that call!")}))
     (throw+ {:error-message (str "No API call specified in message " message "!")}))
   (catch map? {:keys [error-message]}
     ;; Log the error:
     (log/error "[bureaucrat][table-api-router] " error-message)

     ;; Forward a copy of the message to the dead letter queue, if possible:
     (if-let [ingress-endpoint (:x-ingress-endpoint message)]
       (send! (dead-letter-queue ingress-endpoint)
              (assoc message :x-error-processing error-message))
       (log/error "[bureaucrat][table-api-router] Couldn't find a dead letter queue to put the erroneous message on!")))))



