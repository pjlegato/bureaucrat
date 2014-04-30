(ns integration.com.paullegato.bureaucrat.api-routers.ironmq-metadata-router-integration-test
  "Exercises the metadata API router in conjunction with a IronMQ endpoint"
  (:require [com.paullegato.bureaucrat.endpoints.ironmq :as im]
            [com.paullegato.bureaucrat.api-router :as router]
            [com.paullegato.bureaucrat.endpoint   :as endpoint]
            [com.paullegato.bureaucrat.transport  :as transport]
            [com.paullegato.bureaucrat.middleware.normalizer :as normalizer :refer [normalize-egress> normalize-ingress<]]
            [com.paullegato.bureaucrat.data-endpoint    :as data]
            [clojure.core.async :as async :refer [>!! <!! alt!!]]
            [com.paullegato.bureaucrat.api-routers.api-router-helpers :as api-helpers]
            [onelog.core :as log])
  (:use [midje.sweet]
        [com.paullegato.bureaucrat.api-routers.metadata-api-router]
        [helpers.bureaucrat.test-helpers]))

(def last-result (atom nil))

(namespace-state-changes [(before :facts (do (reset! last-result nil)))])

(defn ^:api allowed-test-handler
  [message]
  (reset! last-result message))

;; no :api metadata:
(defn forbidden-test-handler
  [message]
  (reset! last-result message))

(def router  (metadata-api-router "integration.com.paullegato.bureaucrat.api-routers.ironmq-metadata-router-integration-test/"))
;; (fact "API handlers are called properly from Ironmq source queues"
;;       (let [router
;;             endpoint @endpoint
;;             dlq      (transport/dead-letter-queue (endpoint/transport endpoint))
;;             test-message (str "IQ/router integration test message -- " (rand 10000000))
;;             second-test-message (str "IQ/router integration second test message -- " (rand 10000000))]

;;         (endpoint/purge! dlq)

;;         (try
;;           (endpoint/register-listener! endpoint
;;                                     (fn [message]
;;                                       (router/process-message! router message))
;;                                     1)

;;           (endpoint/send! endpoint {:call "allowed-test-handler"
;;                                  :payload test-message})
;;           ;; Await delivery
;;           (spin-on #(= 0 (endpoint/count-messages endpoint)))
;;           (Thread/sleep 300)

;;           ;; Success!
;;           @last-result => test-message

;;           ;; Try to send to a forbidden function:
;;           (endpoint/send! endpoint {:call "forbidden-test-handler"
;;                                  :payload second-test-message})
;;           ;; Await delivery
;;           (spin-on #(= 0 (endpoint/count-messages endpoint)))
;;           (Thread/sleep 300)

;;           @last-result => test-message
          
;;           ;; Make sure invalid API call went to the DLQ:
;;           (endpoint/receive! dlq 10000) => (contains {:payload second-test-message})

;;           ;; Try to send to a nonexistent function:
;;           (endpoint/send! endpoint {:call "nonexistent-test-handler"
;;                                  :payload second-test-message})
;;           ;; Await delivery
;;           (spin-on #(= 0 (endpoint/count-messages endpoint)))
;;           (Thread/sleep 300)

;;           @last-result => test-message
          
;;           ;; Make sure invalid API call went to the DLQ:
;;           (endpoint/receive! dlq 10000) => (contains {:payload second-test-message})
;;           (finally (endpoint/unregister-listener! endpoint)))))

(fact "API handlers are called properly from IronMQ source queues"
      (log/info "------------------------------ API handler test")
      (let [endpoint (create-ironmq-test-queue! {:encoding :json})
            
            send-channel      (normalize-egress> (data/send-channel endpoint))

            ;; Don't wait forever trying to receive; time out in 10 seconds in case of error
            receive-channel   (-> (data/receive-channel endpoint)
                                  (normalizer/normalize-ingress< endpoint))
            test-payload      (str "IM/router integration test message -- " (rand 10000000))]


        (try
          (api-helpers/apply-router! receive-channel router)

          (>!! send-channel {:call :allowed-test-handler
                             :payload test-payload})
          ;; Await delivery
          (spin-on #(= 0 (endpoint/count-messages endpoint)) 20 1000)

          ;; Await processing
          (spin-on #(not (nil? @last-result)) 20 1000)

          ;; Success!
          @last-result => test-payload

          (finally 
            (endpoint/unregister-listener! endpoint)))))


(fact  "API handlers forward unprocessable messages to the dead letter queue"
      (log/info "------------------------------ Unprocessable message forwarding test")
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
