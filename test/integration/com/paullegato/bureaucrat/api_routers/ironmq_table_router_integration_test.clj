(ns integration.com.paullegato.bureaucrat.api-routers.ironmq-table-router-integration-test
  "Exercises the table API router in conjunction with a IronMQ endpoint"
  (:use [midje.sweet]
        [com.paullegato.bureaucrat.api-routers.table-api-router]
        [helpers.bureaucrat.test-helpers])
  (:require [com.paullegato.bureaucrat.endpoints.ironmq :as im]
            [com.paullegato.bureaucrat.api-router :as router]
            [com.paullegato.bureaucrat.endpoint   :as endpoint]
            [com.paullegato.bureaucrat.transport  :as transport]
            [com.paullegato.bureaucrat.async-connector :as async-connector]
            [onelog.core :as log]))


(def last-result (atom nil))

(namespace-state-changes [(before :facts (do (create-ironmq-test-queue!)
                                             (reset! last-result nil)))])


(defn allowed-test-handler
  [message]
  (log/info "allowed-test-handler got a message: " message)
  (reset! last-result message))


(def routes {:foo allowed-test-handler})


(fact "API handlers are called properly from IronMQ source queues"
      (let [router   (table-api-router routes)
            endpoint @endpoint
            channel  (chan)
            dlq      (transport/dead-letter-queue (endpoint/transport endpoint))

            test-message        (str "IM/router integration test message -- " (rand 10000000))
            second-test-message (str "IM/router integration test message -- " (rand 10000000))]

        (endpoint/purge! dlq)

        (try
          (endpoint> endpoint )
          (endpoint/register-listener! endpoint
                                       
                                    (fn [message]
                                      (router/process-message! router message))
                                    1)

          (endpoint/send! endpoint {:call :foo
                                    :payload test-message})
          ;; Await delivery
          (spin-on #(= 0 (endpoint/count-messages endpoint)))

          ;; Success!
          @last-result => test-message

          ;; ;; try to send to a forbidden function:
          ;; (endpoint/send! endpoint {:call "forbidden-test-handler"
          ;;                           :payload second-test-message})
          ;; ;; Await delivery
          ;; (spin-on #(= 0 (endpoint/count-messages endpoint)))
          ;; @last-result => test-message
          
          ;; ;; Make sure invalid API call went to the DLQ:
          ;; (spin-on #(< 0 (endpoint/count-messages endpoint)))
          ;; (endpoint/receive! dlq 10000) => (contains {:payload second-test-message})

          ;; ;; Try to send to a nonexistent function:
          ;; (endpoint/send! endpoint {:call "nonexistent-test-handler"
          ;;                           :payload second-test-message})
          ;; ;; Await delivery
          ;; (spin-on #(= 0 (endpoint/count-messages endpoint)))
          ;; @last-result => test-message
          
          ;; ;; ;; Make sure invalid API call went to the DLQ:
          ;; (spin-on #(< 0 (endpoint/count-messages endpoint)))
          ;; (endpoint/receive! dlq 10000) => (contains {:payload second-test-message})
          (finally (endpoint/unregister-listener! endpoint)))))
