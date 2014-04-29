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
            [clojure.core.async :as async :refer [>!! <!!]]
            [com.paullegato.bureaucrat.api-routers.api-router-helpers :as api-helpers]
            [onelog.core :as log]))


(fact "API handlers are called properly from IronMQ source queues"
      (let [last-result (atom nil)
            router   (table-api-router {:foo (fn [message]
                                               (log/info "allowed-test-handler got a message: " message)
                                               (reset! last-result message))})
            endpoint (create-ironmq-test-queue! {:encoding :json})
            

            send-channel        (normalize-egress> (data/send-channel endpoint))

            ;; Don't wait forever trying to receive; time out in 10 seconds in case of error
            receive-channel     (-> (data/receive-channel endpoint)
                                    (normalizer/normalize-ingress< endpoint)
                                    (async/pipe (async/timeout 10000)))

            test-payload        (str "IM/router integration test message -- " (rand 10000000))]


        (try
          (api-helpers/apply-router! receive-channel router)

          (>!! send-channel {:call :foo
                             :payload test-payload})
          ;; Await delivery
          (spin-on #(= 0 (endpoint/count-messages endpoint)))

          ;; Success!
          @last-result => test-payload

          (finally (endpoint/unregister-listener! endpoint)))))


(fact "API handlers forward unprocessable messaged to the dead letter queue"
      (let [last-result (atom nil)
            endpoint    (create-ironmq-test-queue! {:encoding :json})
            router      (table-api-router {:foo (fn [message]
                                                  (log/info "allowed-test-handler got a message: " message)
                                                  (reset! last-result message))})
            dlq          (transport/dead-letter-queue (:transport endpoint))
            dlq-channel  (async/pipe (data/receive-channel dlq)
                                     (async/timeout 90000))
            send-channel        (normalize-egress> (data/send-channel endpoint))

            ;; Don't wait forever trying to receive; time out in case of error
            receive-channel     (-> (data/receive-channel endpoint)
                                    (normalizer/normalize-ingress< endpoint)
                                    (async/pipe (async/timeout 90000)))

            test-payload        (str "IM/router integration test message -- " (rand 10000000))]

        (endpoint/purge! dlq)
        (endpoint/purge! endpoint)

        (try
          (api-helpers/apply-router! receive-channel router)

          ;; ;; try to send to a forbidden function:
          (>!! send-channel {:call "forbidden-test-handler"
                             :payload test-payload})
          ;; ;; Await delivery
          (spin-on #(= 0 (endpoint/count-messages endpoint)))
          @last-result => nil
          
          ;; ;; Make sure invalid API call went to the DLQ:
          (spin-on #(< 0 (endpoint/count-messages endpoint)))
          (<!! dlq-channel) => (contains {:payload test-payload})

          ;; Try to send to a nonexistent function:
          (>!! send-channel {:call "nonexistent-test-handler"
                             :payload test-payload})
          ;; Await delivery
          (spin-on #(= 0 (endpoint/count-messages endpoint)))
          (spin-on #(= 0 (endpoint/count-messages dlq)))
          (<!! dlq-channel) => (contains {:payload test-payload})

          
          ;; ;; ;; Make sure invalid API call went to the DLQ:
          ;; (spin-on #(< 0 (endpoint/count-messages endpoint)))
          ;; (endpoint/receive! dlq 10000) => (contains {:payload second-test-message})
          (finally
            (endpoint/unregister-listener! endpoint)
            (endpoint/unregister-listener! dlq)))))


