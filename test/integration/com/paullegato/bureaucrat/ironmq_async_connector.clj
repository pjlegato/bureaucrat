(ns integration.com.paullegato.bureaucrat.ironmq-async-connectors
  "Connects an IronMQ endpoint to core.async and tests the result"
  (:require [com.paullegato.bureaucrat.endpoints.ironmq :as iq]
            [com.paullegato.bureaucrat.endpoint :as queue]
            [onelog.core :as log]
            [clojure.core.async :as async :refer [<!! >!! close! chan]]
            [com.paullegato.bureaucrat.async-connector :refer [endpoint> endpoint<]])
  (:use [midje.sweet]
        [helpers.bureaucrat.test-helpers]))


(namespace-state-changes [(before :facts (create-ironmq-test-queue!))])


(fact "we can hook an IronMQ queue up to core.async"
      (let [endpoint @endpoint

            test-messages     ["Message one" "Message two" "Message three" "Message four"]
            test-messages-set (set test-messages)
            
            send-channel    (chan)
            receive-channel (chan)]

        ;; Ensure correct initial state:
        (queue/purge! endpoint)
        (queue/count-messages endpoint) => 0
        (try

          ;; Connect the endpoints:
          (log/info "Connecting channels...")
          (endpoint< endpoint send-channel)
          (endpoint> endpoint receive-channel)

          ;; Send some messages via core.async:
          (log/info "Sending messages...")
          (doseq [message test-messages]
            (>!! send-channel message))

          ;; Now receive some messages:
          (log/info "Receiving messages...")          
          (dotimes [_ (count test-messages)]
            (with-timeout 4000
              (let [msg (<!! receive-channel)]
                ;;(log/info "Got " msg)
                (contains? test-messages-set msg) => truthy)))
          

          (log/info "Test done.")
          (finally
            (queue/unregister-listener! endpoint)
            (close! send-channel)
            (close! receive-channel)))))
