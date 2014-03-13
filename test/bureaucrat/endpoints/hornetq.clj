(ns test.bureaucrat.endpoints.hornetq
  "Tests for the HornetQ implementation of IQueueEndpoint.

   These tests must be run within "
  (:use midje.sweet)  
  (:require [immutant.util]
            [com.stuartsierra.component   :as component]
            [bureaucrat.endpoints.hornetq :as hq]

            [bureaucrat.endpoint :as endpoint]
            [onelog.core         :as log]
            [immutant.messaging  :as mq]
            [immutant.messaging.hornetq]
))

(if-not (immutant.util/in-immutant?)
  (log/error+ "The  test.bureaucrat.endpoints.hornetq tests must be run within an Immutant container in order to test HornetQ integration!"))

(def test-queue-name "test.queue")

(defn- reset-test-queue!
  "Ensure that the test queue is empty of any persistent messages and
   does not exist in the backend, in case it leaks out of a failed
   test run."
  []
 (let [queue (mq/as-queue test-queue-name)] 
                                           (mq/start queue)
                                           (.removeMessages (immutant.messaging.hornetq/destination-controller queue) "")
                                           (mq/stop queue :force true)))

(namespace-state-changes [(before :facts (reset-test-queue!))
                          (after :facts (reset-test-queue!))])


(fact "endpoints can be created and destroyed with the constructor function"
      (let [endpoint (hq/hornetq-endpoint test-queue-name)]
        (record? endpoint) => true
        (immutant.messaging.hornetq/destination-controller test-queue-name) => nil
        (component/start endpoint) => truthy
        (immutant.messaging.hornetq/destination-controller test-queue-name) => truthy
        (component/stop endpoint) => truthy
        (immutant.messaging.hornetq/destination-controller test-queue-name) => nil))


(fact "endpoints can send and receive messages"
      (let [endpoint (component/start (hq/hornetq-endpoint test-queue-name))
            test-message (str "Send/receive test message -- " (rand 10000000))]
        (endpoint/send! endpoint test-message 10000)
        (endpoint/receive! endpoint 1000) => test-message))

(fact "messages are counted properly"
      (let [endpoint (component/start (hq/hornetq-endpoint test-queue-name))
            test-message "foo"]
        (endpoint/count-messages endpoint) => 0
        (endpoint/send! endpoint test-message 100000)
        (endpoint/count-messages endpoint) => 1
        (endpoint/send! endpoint test-message 100000)
        (endpoint/send! endpoint test-message 100000)
        (endpoint/send! endpoint test-message 100000)
        (endpoint/count-messages endpoint) => 4))


(fact "messages are purged properly"
      (let [endpoint (component/start (hq/hornetq-endpoint test-queue-name))
            test-message "foo"]
        (endpoint/send! endpoint test-message 100000)
        (endpoint/send! endpoint test-message 100000)
        (endpoint/send! endpoint test-message 100000)
        (endpoint/send! endpoint test-message 100000)
        (endpoint/count-messages endpoint) => 4

        (endpoint/purge! endpoint)
        (endpoint/count-messages endpoint) => 0))


(fact "the listener function is called when messages are sent"
      (let [endpoint (component/start (hq/hornetq-endpoint test-queue-name))
            result (atom nil)
            test-message (str "Listener function test message -- " (rand 10000000))]
        (endpoint/registered-listener endpoint) => nil
        (endpoint/register-listener! endpoint
                                     (fn [msg] (reset! result msg))
                                     1)
        (endpoint/send! endpoint test-message 10000)
        (endpoint/registered-listener endpoint) => truthy

        ;; Without this, the checker below may run before the message is
        ;; delivered on heavily loaded boxes
        (while (> 0 (endpoint/count-messages endpoint))
          (log/info+ "Awaiting message delivery...")
          (Thread/sleep 200))

        @result => test-message))


(fact "listener unregistration works"
      (let [endpoint (component/start (hq/hornetq-endpoint test-queue-name))
            result (atom nil)
            test-message (str "Unregistration test message -- " (rand 10000000))
            second-test-message (str "Unregistration test message -- " (rand 10000000))]
        (endpoint/registered-listener endpoint) => nil
        (endpoint/register-listener! endpoint
                                     (fn [msg] (reset! result msg))
                                     1)
        (endpoint/registered-listener endpoint) => truthy

        (endpoint/send! endpoint test-message 10000)

        ;; Without this, the checker below may run before the message is
        ;; delivered on heavily loaded boxes
        (while (> 0 (endpoint/count-messages endpoint))
          (log/info+ "Awaiting message delivery...")
          (Thread/sleep 200))

        @result => test-message
        
        (endpoint/unregister-listener! endpoint)
        (endpoint/registered-listener endpoint) => nil
        
        (endpoint/send! endpoint second-test-message 10000)

        ;; Result should still be the first test-message
        @result => test-message))

