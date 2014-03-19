(ns integration.com.paullegato.bureaucrat.api-routers.hornetq-table-router-integration-test
  "Exercises the table API router in conjunction with a HornetQ endpoint"
  (:use [midje.sweet]
        [com.paullegato.bureaucrat.api-routers.table-api-router]
        [com.paullegato.bureaucrat.test-helpers])
  (:require [com.paullegato.bureaucrat.endpoints.hornetq :as hq]
            [com.paullegato.bureaucrat.api-router :as router]
            [com.paullegato.bureaucrat.endpoint :as queue]
            [onelog.core :as log]))

(def test-queue-name "test.queue")

(namespace-state-changes [(before :facts (do (reset-queue! test-queue-name)
                                             (reset! last-result nil)))
                          (after  :facts (reset-queue! test-queue-name))])


(def last-result (atom nil))


(defn allowed-test-handler
  [message]
  (reset! last-result message))

;; no :api metadata:
(defn forbidden-test-handler
  [message]
  (reset! last-result message))

(def routes {:foo allowed-test-handler})


(fact "API handlers are called properly from HornetQ source queues"
      (let [router   (table-api-router routes)
            endpoint (hq/start-hornetq-endpoint! test-queue-name)
            dlq      (queue/dead-letter-queue endpoint)
            test-message (str "HQ/router integration test message -- " (rand 10000000))
            second-test-message (str "HQ/router integration test message -- " (rand 10000000))]

        (queue/purge! dlq)
        (queue/register-listener! endpoint
                                  (fn [message]
                                    (router/process-message! router message))
                                  1)

        (queue/send! endpoint {:call :foo
                               :payload test-message})
        ;; Await delivery
        (spin-on #(= 0 (queue/count-messages endpoint)))

        ;; Success!
        @last-result => test-message

        ;; Try to send to a forbidden function:
        (queue/send! endpoint {:call "forbidden-test-handler"
                               :payload second-test-message})
        ;; Await delivery
        (spin-on #(= 0 (queue/count-messages endpoint)))
        @last-result => test-message
        
        ;; Make sure invalid API call went to the DLQ:
        (queue/receive! dlq 10000) => (contains {:payload second-test-message})

        ;; Try to send to a nonexistent function:
        (queue/send! endpoint {:call "nonexistent-test-handler"
                               :payload second-test-message})
        ;; Await delivery
        (spin-on #(= 0 (queue/count-messages endpoint)))
        @last-result => test-message
        
        ;; Make sure invalid API call went to the DLQ:
        (queue/receive! dlq 10000) => (contains {:payload second-test-message})))
