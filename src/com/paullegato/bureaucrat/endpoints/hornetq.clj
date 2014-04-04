(ns com.paullegato.bureaucrat.endpoints.hornetq
  "Implementation of the IEndpoint protocol for Immutant's HornetQ
  message queue.

  User Documentation
  ==================

  The name field specifies the queue's string name.

  Create-time options may be nil, or it may be a map of
  [http://immutant.org/documentation/current/apidoc/immutant.messaging.html#var-start](Immutant
  HornetQ options). The options you provide will be passed through directly to
  `immutant.messaging/start`.

  The metadata map given to `send!` supports any of the options available to `immutant.messaging/publish`, 
  in addition to those required by IQueueEndpoint. 


  Internal Documentation for Library Developers
  =============================================


  * Handler-cache is an atom used to store a handler function that will be 
    invoked in a background thread when messages are available on the queue.
     It should accept one argument, which is the message being processed.  

    Note that the handler function are
    stored per-instance, not globally, and that the underlying HornetQ
    endpoint is global (i.e. the same underlying queue in HornetQ can
    potentially be shared by many HornetQEndpoint instances.) If you
    lose your HornetQEndpoint object without stopping it or removing
    its listeners, its listeners will still remain attached to the
    underlying HornetQ queue!

  TODO: Implement receive-batch!
  TODO: Configurable DLQ per queue rather than a systemwide one?
  TODO: State machine modelling backend state?
  TODO: Cache / memoize (get-backend) calls"

  (:use com.paullegato.bureaucrat.endpoint)
  (:require [immutant.util]
            [com.stuartsierra.component :as component]
            [immutant.messaging :as mq]
            [onelog.core        :as log]
            [org.tobereplaced (mapply :refer [mapply])]
            [immutant.messaging.hornetq :as hornetq]))

(def dlq-name "DLQ")

(if-not (immutant.util/in-immutant?)
  (log/error+ "The code in com.paullegato.bureaucrat.endpoints.hornetq must be run within an Immutant container!"))

(declare start-hornetq-endpoint!)

(defrecord HornetQEndpoint [^String name
                            handler-cache
                            options]
  IQueueEndpoint

  (get-backend [component]
    (hornetq/destination-controller (mq/as-queue name)))


  (lookup [component queue-name]
    (start-hornetq-endpoint! queue-name))


  (create-in-backend! [component options]
    ;; Idempotent

    ;; Due to an apparent bug in JBoss AS7's default config files, the
    ;; default dead letter queue is not created when the container is
    ;; started, so we must create it ourselves.
    ;;
    ;; https://community.jboss.org/message/649386 is the only
    ;; reference I can find to this.
    (or (= name dlq-name) ;; don't try to create a seperate DLQ if this is the DLQ that we're trying to create!
        (get component :dlq)
        (assoc component :dlq (start-hornetq-endpoint! dlq-name)))

    (or (get-backend component)
        (if options
          (mapply mq/start (mq/as-queue name) options)
          (mq/start (mq/as-queue name)))
        (get-backend component)))


  (destroy-in-backend! [component]
    ;; Idempotent
    (if (get-backend component)
      (mq/stop (mq/as-queue name) :force true)))


  (send! [component message metadata]
    (mapply mq/publish
            (mq/as-queue name)
            (normalize-egress *message-normalizer* message)
            metadata))

  (send! [component message]
     (mq/publish (mq/as-queue name)
                 (normalize-egress *message-normalizer* message)))


  (receive! [component timeout] 
    (if-let [raw-message (mq/receive (mq/as-queue name)
                                     :timeout timeout)]
      (normalize-ingress *message-normalizer* component raw-message)))


  (receive! [component] 
    (if-let [raw-message     (mq/receive (mq/as-queue name))]
      (normalize-ingress *message-normalizer* component raw-message)))


  (register-listener!  [component handler-fn concurrency]
    (unregister-listener! component)
    (reset! handler-cache
            @(mq/listen (mq/as-queue name)
                        (fn [raw-message]
                          (let [message (normalize-ingress *message-normalizer* component raw-message)]
                            (try
                              (handler-fn message)
                              (catch Throwable t
                                (log/error "[bureaucrat][hornetq-endpoint] Error invoking handler function for message: " 
                                           raw-message "!"
                                           (log/throwable t))
                                (send! (dead-letter-queue component) (assoc message :x-handler-error t))
                                ;; HornetQ will attempt to redeliver if we throw an exception..
                                (throw t)))))

                       :concurrency concurrency)))


  (registered-listener [component]
    @handler-cache)


  (unregister-listener! [component]
    (when-let [handler @handler-cache]
      @(mq/unlisten handler)
      (reset! handler-cache nil)))


  (count-messages [component]
    (some-> (get-backend component)
            (.countMessages "")))

  (dead-letter-queue [component]
    ;; TODO memoize this
    (start-hornetq-endpoint! dlq-name))


  (purge! [component]
    (some-> (get-backend component)
            (.removeMessages "")))

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  component/Lifecycle

  (start [component]
    ;; Idempotent; does nothing if a queue already exists
    (create-in-backend! component nil)
    component)


  (stop [component]
    ;; No-op; we do not destroy the queue in the backend just because
    ;; the component is stoped, in case something else is using it.
    component))


(defn hornetq-endpoint
  "Constructor for HornetQ queue endpoints. Returns an IQueueEndpoint
  that implements component/Lifecycle. You can call (start component)
  to start it, or use it in a component system."
  ([name] (hornetq-endpoint name nil))
  ([name options]
      (map->HornetQEndpoint {:name name
                             :options options
                             :handler-cache (atom nil)})))


(defn start-hornetq-endpoint!
  "Convenience method for those not using the Components library to
  start services; creates a wrapper around the HornetQ queue with the
  given name, starts the underlying endpoint, and returns it."
  ([name] (start-hornetq-endpoint! name nil))
  ([name options] (component/start (hornetq-endpoint name options))))
