(ns integration.com.paullegato.bureaucrat.api-routers.ironmq-metadata-router-integration-test
  "Exercises the metadata API router in conjunction with a IronMQ endpoint"
  (:require [com.paullegato.bureaucrat.endpoints.ironmq :as iq]
            [com.paullegato.bureaucrat.api-router :as router]
            [com.paullegato.bureaucrat.endpoint   :as endpoint]
            [com.paullegato.bureaucrat.transport  :as transport]
            [com.paullegato.bureaucrat.util       :as util :refer [send-to-dlq!]]
            [onelog.core :as log])
  (:use [midje.sweet]
        [com.paullegato.bureaucrat.api-routers.metadata-api-router]
        [helpers.bureaucrat.test-helpers]))

(def last-result (atom nil))

(namespace-state-changes [(before :facts (do (create-ironmq-test-queue!)
                                             (reset! last-result nil)))])

(defn ^:api allowed-test-handler
  [message]
  (reset! last-result message))

;; no :api metadata:
(defn forbidden-test-handler
  [message]
  (reset! last-result message))


(fact "API handlers are called properly from Ironmq source queues"
      (let [router   (metadata-api-router "integration.com.paullegato.bureaucrat.api-routers.ironmq-metadata-router-integration-test/")
            endpoint @endpoint
            dlq      (transport/dead-letter-queue (endpoint/transport endpoint))
            test-message (str "IQ/router integration test message -- " (rand 10000000))
            second-test-message (str "IQ/router integration second test message -- " (rand 10000000))]

        (endpoint/purge! dlq)

        (try
          (endpoint/register-listener! endpoint
                                    (fn [message]
                                      (router/process-message! router message))
                                    1)

          (endpoint/send! endpoint {:call "allowed-test-handler"
                                 :payload test-message})
          ;; Await delivery
          (spin-on #(= 0 (endpoint/count-messages endpoint)))
          (Thread/sleep 300)

          ;; Success!
          @last-result => test-message

          ;; Try to send to a forbidden function:
          (endpoint/send! endpoint {:call "forbidden-test-handler"
                                 :payload second-test-message})
          ;; Await delivery
          (spin-on #(= 0 (endpoint/count-messages endpoint)))
          (Thread/sleep 300)

          @last-result => test-message
          
          ;; Make sure invalid API call went to the DLQ:
          (endpoint/receive! dlq 10000) => (contains {:payload second-test-message})

          ;; Try to send to a nonexistent function:
          (endpoint/send! endpoint {:call "nonexistent-test-handler"
                                 :payload second-test-message})
          ;; Await delivery
          (spin-on #(= 0 (endpoint/count-messages endpoint)))
          (Thread/sleep 300)

          @last-result => test-message
          
          ;; Make sure invalid API call went to the DLQ:
          (endpoint/receive! dlq 10000) => (contains {:payload second-test-message})
          (finally (endpoint/unregister-listener! endpoint)))))

