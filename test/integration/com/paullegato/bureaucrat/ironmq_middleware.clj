(ns integration.com.paullegato.bureaucrat.ironmq-middleware
  "Tests the use of middleware on the IronMQ backend"
  (:require [com.paullegato.bureaucrat.endpoints.ironmq :as iq]
            [com.paullegato.bureaucrat.endpoint :as queue]

            [onelog.core :as log]
            [clojure.core.async :as async :refer [<!! >!! close! chan]]
            [com.paullegato.bureaucrat.channel-endpoint :refer [endpoint> endpoint<]])
  (:use [midje.sweet]
        [com.paullegato.bureaucrat.middleware.edn]
        [helpers.bureaucrat.test-helpers]))


(namespace-state-changes [(before :facts (create-ironmq-test-queue!))])


(fact "we can transcode EDN messages on IronMQ"
      (let [endpoint @endpoint

            test-messages     [{:abc 123 :def 567}
                               ["foo" 234 :bar '(asdf baz 948)]
                               #inst "1985-04-12T23:20:50.52Z"
                               #uuid "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"]
            test-messages-set (set test-messages)
            
            send-endpoint-channel  (endpoint> endpoint)
            send-channel           (edn-encode> send-endpoint-channel)


            receive-endpoint-channel (endpoint< endpoint)
            receive-channel          (edn-decode< receive-endpoint-channel)]

        ;; Ensure correct initial state:
        (queue/purge! endpoint)
        (queue/count-messages endpoint) => 0
        (try
          ;; Send some messages via core.async:
          (log/info "Sending messages...")
          (doseq [message test-messages]
            (>!! send-channel message))

          ;; Now receive some messages:
          (log/info "Receiving messages...")          
          (dotimes [_ (count test-messages)]
            (with-timeout 4000
              (let [msg (<!! receive-channel)]
                (log/info "Got " msg " of type " (class msg))
                (contains? test-messages-set msg) => truthy)))
          

          (log/info "Test done.")
          (finally
            (queue/unregister-listener! endpoint)
            (close! send-channel)
            (close! receive-channel)))))
