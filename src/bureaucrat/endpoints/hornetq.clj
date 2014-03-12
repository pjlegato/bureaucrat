(ns bureaucrat.endpoints.hornetq
  "Implementation of the IEndpoint protocol for Immutant's HornetQ
  message queue.


  Internal Documentation for Developers
  -----------

  * The name field specifies the queue's string name.

  * Handler-cache is an atom used to store the handlers listening to
    this endpoint, so we can remove them later. Note that these are
    stored per-instance, not globally, and that the underlying HornetQ
    endpoint is global (i.e. the same underlying queue in HornetQ can
    potentially be shared by many HornetQEndpoint instances.) If you
    lose your HornetQEndpoint object without stopping it or removing
    its listeners, its listeners will still remain attached to the
    underlying HornetQ queue!

  TODO: State machine modelling backend state?"

  (:use bureaucrat.endpoint)
  (:require [com.stuartsierra.component :as component]
            [immutant.messaging :as mq]
            [org.tobereplaced (mapply :refer [mapply])]
            [immutant.messaging.hornetq :as hornetq]))


(defrecord HornetQEndpoint [^String name handler-cache options]
  IQueueEndpoint

  (lookup [component]
    (hornetq/destination-controller (mq/as-queue name)))


  (create-in-backend! [component options]
    ;; Idempotent
    (or (lookup component)
        (if options
          (mapply mq/start (mq/as-queue name) options)
          (mq/start (mq/as-queue name)))
        (lookup component)))


  (destroy-in-backend! [component]
    ;; Idempotent
    (if (lookup component)
      (mq/stop (mq/as-queue name) :force true)))


  (send! [component message ttl]
    (mq/publish (mq/as-queue name)
                message
                :ttl ttl))


  (receive! [component timeout] 
    (mq/receive (mq/as-queue name)
                :timeout timeout))


  (register-listener!  [component tag handler-fn concurrency]
    (swap! handler-cache
           assoc
           tag
           @(mq/listen (mq/as-queue name)
                       handler-fn
                       :concurrency concurrency
                       :xa false)))


  (registered-listeners [component]
    @handler-cache)


  (unregister-listener! [_ tag]
    (when-let [handler (get @handler-cache tag)]
      @(mq/unlisten handler)
      (swap! handler-cache dissoc tag)))


  (count-messages [component]
    (some-> (lookup component)
            (.countMessages "")))


  (purge! [component]
    "Unconditionally deletes all pending messages from the queue."
    (some-> (lookup component)
            (.removeMessages "")))

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  component/Lifecycle

  (start [component]
    ;; Idempotent; does nothing if a queue already exists
    (create-in-backend! component nil)
    component)


  (stop [component]
    ;; Idempotent
    (destroy-in-backend! component)
    component))


(defn hornetq-endpoint
  "Constructor for HornetQ queue endpoints. Returns an IQueueEndpoint
  that implements component/Lifecycle. You can call (start component)
  to start it, or use it in a component system."
  ([name] (hornetq-endpoint name nil))
  ([name options]
      (map->HornetQEndpoint {:name name
                             :options options
                             :handler-cache (atom {})})))
