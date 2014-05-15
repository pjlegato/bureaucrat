(ns com.paullegato.bureaucrat.transports.ironmq
  "IMessageTransport implementation on IronMQ (http://www.iron.io/)."
  (:use com.paullegato.bureaucrat.transport
        com.paullegato.bureaucrat.transports.util.ironmq
        [slingshot.slingshot :only [try+ throw+]])
  (:require [com.paullegato.bureaucrat.endpoints.ironmq :as endpoint]
            [org.httpkit.client :as http]
            [onelog.core        :as log]
            [cheshire.core      :as json])
  (:import [io.iron.ironmq Queue Client Cloud]))


;; What to call the dead letter queue
(def dlq-name "dead-letter-queue")

(defrecord IronMQTransport [^Client client] ;; The Java Client object underlying this IronMQ connection
  IMessageTransport

  (create-in-backend! [component name options]
    (log/info "[bureaucrat] Creating new IronMQ transport in backend: " name)
    ;; Options may have the :encoding key with a value of :json or
    ;; :edn. Messages going through the transport will be transncoded
    ;; in the given encoding. If not given, no encoding is used.

    ;; Remaining options are passed through to IronMQ.
    ;; See http://dev.iron.io/mq/reference/api/#update_a_message_queue
    ;; for allowed values.

    ;; IronMQ has no "create" method as such, but you can create an
    ;; emtpy queue by updating its queue options.
    ;;
    ;; Note that Bureaucrat expects to have a queue; that is, each message is
    ;; delivered to only one consumer. IronMQ queues can also be put
    ;; into a multicast / push mode, which is called a "topic" in
    ;; JMS. Since Bureaucrat expects queue semantics, you will get
    ;; undefined results if your IronMQ queue is not in regular "pull"
    ;; / queue mode.
    (let [encoding (:encoding options)
          options (dissoc options :encoding)]
      (if (ironmq-request (:client component)
                          :post
                          (str "/queues/" name)
                          (or options {}))
        (case encoding
          :json (endpoint/ironmq-json-endpoint name component)
          :edn  (endpoint/ironmq-edn-endpoint name component)
          (do (log/warn "[bureaucrat][ironmq-transport] You didn't specify either :edn or :json encoding when creating an endpoint -- this is probably not what you want! IronMQ can natively handle only strings.")
              (endpoint/ironmq-endpoint name component))))))


  (lookup [component queue-name]
    (if (queue-exists? name)
      (endpoint/ironmq-endpoint name nil)))


  (destroy-in-backend! [component queue-name]
    (log/info "[bureaucrat/ironmq] Destroying queue in backend: " queue-name)
    (if-let [endpoint (lookup component queue-name)]
      (.destroy ^Queue (:queue endpoint))))


  (force-destroy! [component name]
    (log/info "[bureaucrat/ironmq] Force-destroying queue in backend: " name)
    (destroy-in-backend! component name))


  (dead-letter-queue [component]
    (log/debug "[bureaucrat/ironmq] Creating DLQ in backend")
    (create-in-backend! component dlq-name {:encoding :edn})))


(defn ironmq-transport
  "Constructs a new IronMQTransport instance and connects it.

  If no credentials are given, as is recommended, the Java Client
  will attempt to use environment variables and the Iron.io config
  file to find values for them, as described at
  http://dev.iron.io/worker/reference/configuration/."
  ([] (ironmq-transport nil nil nil))
  ([^String project-id ^String token ^Cloud cloud]
     (log/debug "[bureaucrat] Connecting to IronMQ...")
     (map->IronMQTransport {:client (Client. project-id token cloud)})))
