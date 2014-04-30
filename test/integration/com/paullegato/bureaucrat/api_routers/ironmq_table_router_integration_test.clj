(ns integration.com.paullegato.bureaucrat.api-routers.ironmq-table-router-integration-test
  "Exercises the table API router in conjunction with a IronMQ endpoint"
  (:use [midje.sweet]
        [com.paullegato.bureaucrat.api-routers.table-api-router]
        [helpers.bureaucrat.test-helpers])
  (:require [com.paullegato.bureaucrat.endpoints.ironmq :as im]
            [com.paullegato.bureaucrat.api-router :as router]
            [com.paullegato.bureaucrat.endpoint   :as endpoint]
            [com.paullegato.bureaucrat.transport  :as transport]
            [com.paullegato.bureaucrat.middleware.normalizer :as normalizer :refer [normalize-egress> normalize-ingress<]]
            [com.paullegato.bureaucrat.data-endpoint    :as data]
            [clojure.core.async :as async :refer [>!! <!! alt!!]]
            [com.paullegato.bureaucrat.api-routers.api-router-helpers :as api-helpers]
            [onelog.core :as log]))


(def router  (table-api-router {:foo (fn [message]
                                       (log/info "allowed-test-handler got a message: " message)
                                       (reset! last-result message))}))
(def last-result (atom nil))

(fact  "API handlers forward unprocessable messages to the dead letter queue"
      (log/info "------------------------------ Unprocessable message forwarding test")
      (let [endpoint (create-ironmq-test-queue! {:encoding :json})
            

            send-channel      (normalize-egress> (data/send-channel endpoint))

            ;; Don't wait forever trying to receive; time out in 10 seconds in case of error
            receive-channel   (-> (data/receive-channel endpoint)
                                  (normalizer/normalize-ingress< endpoint))
            test-payload      (str "IM/router integration test message -- " (rand 10000000))]


        (try
          (reset! last-result nil)
          (api-helpers/apply-router! receive-channel router)

          (>!! send-channel {:call :foo
                             :payload test-payload})
          ;; Await delivery
          (spin-on #(= 0 (endpoint/count-messages endpoint)) 20 1000)

          ;; Await processing
          (spin-on #(not (nil? @last-result)) 20 1000)

          ;; Success!
          @last-result => test-payload

          (finally 
            (endpoint/unregister-listener! endpoint)))))


(fact "API handlers are called properly from IronMQ source queues"
      (log/info "------------------------------ API handler test")
      (let [endpoint    (create-ironmq-test-queue! {:encoding :json})

            dlq          (transport/dead-letter-queue (:transport endpoint))
            dlq-channel  (data/receive-channel dlq)


            send-channel        (normalize-egress> (data/send-channel endpoint))

            receive-channel (-> (data/receive-channel endpoint)
                                (normalizer/normalize-ingress< endpoint))

            test-payload        (str "IM/router integration test message -- " (rand 10000000))
            other-test-payload  (str "IM/router integration test message -- " (rand 10000000))]

        (try
          (endpoint/purge! dlq)
          (endpoint/purge! endpoint)

          (api-helpers/apply-router! receive-channel router)

          ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
          ;;
          ;; Try to send to a forbidden function:
          ;;
          ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
          (>!! send-channel {:call "forbidden-test-handler"
                             :payload test-payload})
          
          ;; Make sure invalid API call went to the DLQ:
          (Thread/sleep 500)
          (spin-on #(= 0 (endpoint/count-messages endpoint)) 20 1000)
          (Thread/sleep 500)
          (spin-on #(= 0 (endpoint/count-messages dlq)) 20 1000)

          (<!!-timeout dlq-channel 20000) => (contains {:payload test-payload})

          ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
          ;;
          ;; Try to send to a nonexistent function:
          ;;
          ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

          (>!! send-channel {:call "nonexistent-test-handler"
                             :payload other-test-payload})

          ;; Await delivery from remote DLQ:
          (Thread/sleep 500)
          (spin-on #(= 0 (endpoint/count-messages endpoint)) 20 1000)
          (Thread/sleep 500)
          (spin-on #(= 0 (endpoint/count-messages dlq)) 20 1000)

          (<!!-timeout dlq-channel 20000) => (contains {:payload other-test-payload})

          (finally
            ;; (async/close! dlq-channel)
            ;; (async/close! send-channel)
            ;; (async/close! receive-channel)
            (endpoint/unregister-listener! endpoint)
            (endpoint/unregister-listener! dlq)))))


