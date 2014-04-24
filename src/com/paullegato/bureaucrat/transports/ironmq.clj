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

(defrecord IronMQTransport [
                            ^Client client ;; The Java Client object underlying this IronMQ connection
                            ]
  IMessageTransport

  (create-in-backend! [component name options]
    ;; Options is passed through to IronMQ.
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
    (if (ironmq-request (:client component)
                        :post
                        (str "/queues/" name)
                        (or options {}))
      (endpoint/ironmq-endpoint name component)))

  (lookup [component queue-name]
    (if (queue-exists? name)
      (endpoint/ironmq-endpoint name nil)))

  (destroy-in-backend! [component queue-name]
    (if-let [endpoint (lookup component queue-name)]
      (.destroy ^Queue (:queue endpoint))))

  (force-destroy! [component name]
    (destroy-in-backend! component name))

  (dead-letter-queue [component]
    (create-in-backend! component dlq-name nil)))

;; (defn get-client
;;   "Given a map structured as an IronMQTransport, returns the Java
;;   io.iron.ironmq.Client instance stored in it. If there is none,
;;   creates one and stores it in the map's :iron-cache atom under 
;;   the :client key.

;;   project-id, token, and cloud are used to initialize the Java Client
;;   object. It is recommended that they be nil in most cases, in which
;;   case the Client will attempt to use environment variables and the
;;   Iron.io config file to find values for them, as described at
;;   http://dev.iron.io/worker/reference/configuration/ .

;;   If there is no atom in :iron-cache, the record is uninitialized, so
;;   this function returns nil."
;;   ([iron-mq-endpoint] (get-client iron-mq-endpoint nil nil nil))
;;   ([iron-mq-endpoint ^String project-id ^String token ^Cloud cloud]
;;       (if-let [iron-cache (:iron-cache iron-mq-endpoint)]
;;         (if-let [client (get @iron-cache :client)]
;;           client
;;           (let [new-client (Client. project-id token cloud)]
;;             (swap! iron-cache assoc :client new-client)
;;             new-client))
;;         nil)))


(defn ironmq-transport
  "Constructs a new IronMQTransport instance and connects it.

  If no credentials are given, as is recommended, the Java Client
  will attempt to use environment variables and the Iron.io config
  file to find values for them, as described at
  http://dev.iron.io/worker/reference/configuration/."
  ([] (ironmq-transport nil nil nil))
  ([^String project-id ^String token ^Cloud cloud]
     (map->IronMQTransport {:client (Client. project-id token cloud)})))
