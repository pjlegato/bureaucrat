(ns integration.com.paullegato.bureaucrat.api-routers.ironmq-metadata-router-integration-test
  "Exercises the metadata API router in conjunction with a IronMQ endpoint"
  (:require [com.paullegato.bureaucrat.endpoints.ironmq :as hq]
            [com.paullegato.bureaucrat.api-router :as router]
            [com.paullegato.bureaucrat.endpoint :as queue]
            [onelog.core :as log])
  (:use [midje.sweet]
        [com.paullegato.bureaucrat.api-routers.metadata-api-router]
        [com.paullegato.bureaucrat.test-helpers]))

(def test-queue-name "test.queue")
(def last-result (atom nil))

(namespace-state-changes [(before :facts (reset! last-result nil))])

(defn ^:api allowed-test-handler
  [message]
  (reset! last-result message))

;; no :api metadata:
(defn forbidden-test-handler
  [message]
  (reset! last-result message))


(fact "API handlers are called properly from Ironmq source queues"
      (let [router   (metadata-api-router "com.paullegato.bureaucrat.api-routers.ironmq-metadata-router-integration-test/")
            endpoint (hq/start-ironmq-endpoint! test-queue-name)
            dlq      (queue/dead-letter-queue endpoint)
            test-message (str "HQ/router integration test message -- " (rand 10000000))
            second-test-message (str "HQ/router integration second test message -- " (rand 10000000))]

        (queue/purge! dlq)

        (try
          (queue/register-listener! endpoint
                                    (fn [message]
                                      (router/process-message! router message))
                                    1)

          (queue/send! endpoint {:call "allowed-test-handler"
                                 :payload test-message})
          ;; Await delivery
          (spin-on #(= 0 (queue/count-messages endpoint)))
          (Thread/sleep 300)

          ;; Success!
          @last-result => test-message

          ;; Try to send to a forbidden function:
          (queue/send! endpoint {:call "forbidden-test-handler"
                                 :payload second-test-message})
          ;; Await delivery
          (spin-on #(= 0 (queue/count-messages endpoint)))
          (Thread/sleep 300)

          @last-result => test-message
          
          ;; Make sure invalid API call went to the DLQ:
          (queue/receive! dlq 10000) => (contains {:payload second-test-message})

          ;; Try to send to a nonexistent function:
          (queue/send! endpoint {:call "nonexistent-test-handler"
                                 :payload second-test-message})
          ;; Await delivery
          (spin-on #(= 0 (queue/count-messages endpoint)))
          (Thread/sleep 300)

          @last-result => test-message
          
          ;; Make sure invalid API call went to the DLQ:
          (queue/receive! dlq 10000) => (contains {:payload second-test-message})
          (finally (queue/unregister-listener! endpoint)))))

