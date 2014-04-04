(ns unit.com.paullegato.bureaucrat.endpoints.ironmq-test
  "Tests for the IronMQ implementation of IQueueEndpoint."
  (:use [midje.sweet]
        [helpers.bureaucrat.test-helpers])
  (:require [com.stuartsierra.component   :as component]
            [com.paullegato.bureaucrat.endpoints.ironmq :as im]
            [com.paullegato.bureaucrat.endpoint :as queue]
            [onelog.core         :as log]))


(def test-queue-name "test.queue")

(defn- reset-test-queue!
  "Ensure that the test queue is empty of any persistent messages and
   does not exist in the backend, in case it leaks out of a failed
   test run."
  []
  (try
    (let [queue (im/start-ironmq-endpoint! test-queue-name)] 
      (queue/destroy-in-backend! queue))
    (catch io.iron.ironmq.HTTPException e
      (if-not (= (.getMessage e) "Queue not found")
        ;; re-throw if it is anything other than the queue not existing
        (throw e)))))


(namespace-state-changes [(before :facts (reset-test-queue!))
                          (after :facts (reset-test-queue!))])



(fact "endpoints can be created by starting the component"
      (let [endpoint (im/ironmq-endpoint test-queue-name)]
        (im/queue-exists? test-queue-name) => falsey
        (component/start endpoint)
        (im/queue-exists? test-queue-name) => truthy))


(fact "endpoints can send and receive messages"
      (let [endpoint (component/start (im/ironmq-endpoint test-queue-name))
            test-message (str "Send/receive test message -- " (rand 10000000))]
        (queue/send! endpoint {:payload test-message} {:ttl 10000})
        (queue/receive! endpoint 1000) => (contains {:payload test-message})))


(fact "messages are counted properly"
      (let [endpoint (component/start (im/ironmq-endpoint test-queue-name))
            test-message "foo"]
        (queue/count-messages endpoint) => 0
        (queue/send! endpoint test-message {:ttl 100000})
        (queue/count-messages endpoint) => 1
        (queue/send! endpoint test-message {:ttl 100000})
        (queue/send! endpoint test-message {:ttl 100000})
        (queue/send! endpoint test-message {:ttl 100000})
        (queue/count-messages endpoint) => 4))


(fact "messages are purged properly (may erroneously fail if IronMQ is heavily loaded when run)"
      (let [endpoint (component/start (im/ironmq-endpoint test-queue-name))
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
      (let [endpoint (component/start (im/ironmq-endpoint test-queue-name))
            result (atom nil)
            test-message (str "Listener function test message -- " (rand 10000000))]

        (try
          (queue/registered-listener endpoint) => nil
          (queue/register-listener! endpoint
                                    (fn [msg]
                                      (log/info "[test] Test message handler invoked with " msg)
                                      (reset! result msg))
                                    1)
          (queue/send! endpoint {:payload test-message} {:ttl 10000})
          (queue/count-messages endpoint) => 1
          (queue/registered-listener endpoint) => truthy

          ;; Without this, the checker below may run before the message is
          ;; delivered on heavily loaded boxes
          (spin-on #(= (queue/count-messages endpoint) 0) 5 2000)

          (queue/count-messages endpoint) => 0

          @result => (contains {:payload test-message})
          (finally (queue/unregister-listener! endpoint)))))


(fact "listener unregistration works"
      (let [endpoint (component/start (im/ironmq-endpoint test-queue-name))
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

          @result => (contains {:payload test-message})
          
          (queue/unregister-listener! endpoint)
          (queue/registered-listener endpoint) => nil
          
          (queue/send! endpoint second-test-message {:ttl 10000})

          ;; Result should still be the first test-message
          @result => (contains {:payload test-message})
          (finally (queue/unregister-listener! endpoint)))))

;;
;; Redelivery is not implemented yet in IronMQ -- see comments in ironmq.clj.
;;
;; (fact "messages are placed back on the queue for redelivery if a handler throws an exception"
;;       (let [endpoint (component/start (im/ironmq-endpoint test-queue-name))
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


(fact "messages wind up in the dead letter queue if delivery fails"
      (let [endpoint (component/start (im/ironmq-endpoint test-queue-name))
            dlq      (queue/dead-letter-queue endpoint)
            test-message (str "DLQ test message -- " (rand 10000000))]
        (try
          (queue/purge! dlq)
          (queue/register-listener! endpoint
                                    (fn [msg]
                                      (log/info "Got test message " msg)
                                      (throw (Exception. "Test exception from within the DLQ test; nothing to worry about..")))
                                    1)
          (queue/send! endpoint test-message {:ttl 10000})

          ;; Wait for the message to be put onto the DLQ by IronMQ...
          (spin-on #(< 0 (queue/count-messages dlq)) 10 2000)
          (log/warn "count is " (queue/count-messages dlq))

          (queue/receive! dlq 10000) => (contains {:payload test-message})
          (finally (queue/unregister-listener! endpoint)))))
