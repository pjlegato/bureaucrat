(ns unit.com.paullegato.bureaucrat.endpoints.ironmq-test
  "Tests for the IronMQ implementation of IQueueEndpoint."
  (:use [midje.sweet]
        [helpers.bureaucrat.test-helpers])
  (:require [onelog.core :as log]
            [com.paullegato.bureaucrat.transports.util.ironmq :as util]
            [com.paullegato.bureaucrat.endpoint         :as queue]
            [com.paullegato.bureaucrat.transport        :as transport]
            [com.paullegato.bureaucrat.endpoints.ironmq :as iq]))



(namespace-state-changes [(before :facts (create-ironmq-test-queue!))])



(fact "endpoints can be created"
      (util/queue-exists? test-queue-name) => truthy)


(fact "endpoints can send and receive string messages"
      (let [test-message (str "Send/receive test message -- " (rand 10000000))]
        (queue/send!    @endpoint test-message {:ttl 10000})
        (queue/receive! @endpoint 1000) => test-message))


(fact "messages are counted properly"
      (let [endpoint @endpoint
            test-message "foo"]
        (queue/count-messages endpoint) => 0
        (queue/send! endpoint test-message {:ttl 100000})
        (queue/count-messages endpoint) => 1
        (queue/send! endpoint test-message {:ttl 100000})
        (queue/send! endpoint test-message {:ttl 100000})
        (queue/send! endpoint test-message {:ttl 100000})
        (queue/count-messages endpoint) => 4))


(fact "messages are purged properly (may erroneously fail if IronMQ is heavily loaded when run)"
      (let [endpoint @endpoint
            test-message "foo"]
        (queue/send! endpoint test-message {:ttl 100000})
        (queue/send! endpoint test-message {:ttl 100000})
        (queue/send! endpoint test-message {:ttl 100000})
        (queue/send! endpoint test-message {:ttl 100000})
        (queue/count-messages endpoint) => 4

        (queue/purge! endpoint)

        ;; Await processing by IronMQ
        (spin-on #(= 0 (queue/count-messages endpoint)) 10 1000)

        (queue/count-messages endpoint) => 0))


(fact "the listener function is called when messages are sent"
      (let [endpoint @endpoint
            result (atom nil)
            test-message (str "Listener function test message -- " (rand 10000000))]

        (try
          (queue/registered-listener endpoint) => nil
          (queue/register-listener! endpoint
                                    (fn [msg]
                                      (log/info "[test] Test message handler invoked with " msg)
                                      (reset! result msg))
                                    1)
          (queue/send! endpoint test-message {:ttl 10000})
          (queue/count-messages endpoint) => 1
          (queue/registered-listener endpoint) => truthy

          ;; Without this, the checker below may run before the message is
          ;; delivered on heavily loaded boxes
          (spin-on #(= (queue/count-messages endpoint) 0) 5 2000)

          (queue/count-messages endpoint) => 0

          @result => test-message
          (finally (queue/unregister-listener! endpoint)))))


(fact "listener unregistration works"
      (let [endpoint @endpoint
            result (atom nil)
            test-message (str "Unregistration test message -- " (rand 10000000))
            second-test-message (str "Unregistration test message -- " (rand 10000000))]
        (try
          (queue/registered-listener endpoint) => nil
          (queue/register-listener! endpoint
                                    (fn [msg] (reset! result msg))
                                    1)
          (queue/registered-listener endpoint) => truthy

          (queue/send! endpoint test-message {:ttl 10000})

          ;; Without this, the checker below may run before the message is
          ;; delivered, on heavily loaded boxes
          (spin-on #(= (queue/count-messages endpoint) 0) 5 2000)

          @result => test-message
          
          (queue/unregister-listener! endpoint)
          (queue/registered-listener endpoint) => nil
          
          (queue/send! endpoint second-test-message {:ttl 10000})

          ;; Result should still be the first test-message
          @result => (contains test-message)
          (finally (queue/unregister-listener! endpoint)))))

;;
;; Redelivery is not implemented yet in IronMQ -- see comments in ironmq.clj.
;;
;; (fact "messages are placed back on the queue for redelivery if a handler throws an exception"
;;       (let [endpoint (component/start (iq/ironmq-endpoint test-queue-name))
;;             tries-left (atom 3)
;;             done (atom nil)
;;             test-message (str "Transaction test message -- " (rand 10000000))]
;;         (try
;;           (queue/register-listener! endpoint
;;                                     (fn [msg]
;;                                       (let [tries @tries-left]
;;                                         ;;(log/info+ "* Exception-generating handler running. Tries left: " tries)
;;                                         (if (> tries 0)
;;                                           (do
;;                                             (swap! tries-left dec)
;;                                             (throw (Exception. "Test exception from within the redelivery test; nothing to worry about..")))
;;                                           (do
;;                                             ;;(log/info+ "No tries left, accepting the message.")
;;                                             (reset! done true)))))
;;                                     1)
;;           (queue/send! endpoint test-message {:ttl 10000})

;;           (Thread/sleep 100)

;;           ;; Wait for the backend to cycle through the retries...
;;           (while (not @done)
;;             (log/info+ "Awaiting test completion...")
;;             (Thread/sleep 200))

;;           @tries-left => 0
;;           (finally (queue/unregister-listener! endpoint)))))


(fact "non-string messages get coerced into strings"
      (let [endpoint @endpoint]
        (queue/send!    endpoint {:abc 123}) => truthy
        (queue/receive! endpoint 1000) => "{:abc 123}"))


(fact "messages wind up in the dead letter queue if delivery fails"
      (let [endpoint @endpoint
            dlq      (transport/dead-letter-queue @transport)
            test-message (str "DLQ test message -- " (rand 10000000))]
        (try
          (queue/purge! dlq)
          (queue/register-listener! endpoint
                                    ;; This always throws an exception, so all delivered messages should
                                    ;; wind up on the DLQ.
                                    (fn [msg]
                                      (log/info "Got test message " msg)
                                      (throw (Exception. "Test exception from within the DLQ test; nothing to worry about..")))
                                    1)
          (queue/send! endpoint test-message {:ttl 10000})

          ;; Wait for the message to be put onto the DLQ by IronMQ...
          (spin-on #(< 0 (queue/count-messages dlq)) 10 1000)

          (queue/receive! dlq 10000) => test-message
          (finally (queue/unregister-listener! endpoint)))))
