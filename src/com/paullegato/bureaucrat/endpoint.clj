(ns com.paullegato.bureaucrat.endpoint
  "The IEndpoint protocol defines a type with a set of operations for
  interacting with a generic MQ-like asynchronous communications
  endpoint and providing normalized messages to the rest of the system.

  Concrete connectors are provided in the bureaucrat.endpoints
  package.

  TODO: Send messages in batches.
  TODO: Retrieve without auto-acknowledge
  TODO: Support topics in addition to queues?
  TODO: Conflates the transport mechanism (e.g. HornetQ or IronMQ) 
        with queue naming. It'd be better to have these seperate."
  (:require [clj-time.core :as time]))

;;
;; This is structured after the tripartite format for protocols
;; suggested at
;; http://thinkrelevance.com/blog/2013/11/07/when-should-you-use-clojures-object-oriented-features
;;


(defprotocol IMessageNormalizer
  "IMessageNormalizer provides a function 'ingress' that ensures that
  a given message is in a normalized structural format. Messages
  returned from this function will be Clojure maps with at least an
  `:x-ingress-endpoint key`. This key is a reference to the
  IQueueEndpoint from which we received the message.

  normalize-egress removes the :x-ingress-endpoint key in preparatio
  for sending an outbound message, because its value is not
  serializable.

  If the incoming message is already a Clojure map, the normalizer
  function should extend it by adding the `:x-ingress-endpoint` key.

  Normalizers may optionally add any other metadata they like,
  provided that the keys begin with :x-."
  
  (normalize-ingress [component endpoint message])
  (normalize-egress [component message]))


(deftype MessageNormalizer []
  IMessageNormalizer
  (normalize-ingress [component endpoint message]
    (let [new-message (if (map? message)
                        message
                        {:payload message})]
      (-> new-message
       (assoc :x-ingress-endpoint endpoint)
       (assoc :x-ingress-time (time/now)))))

  (normalize-egress [component message]
    (if (map? message)
      (-> message
          (dissoc :x-ingress-endpoint)
          (dissoc :x-ingress-time))
      message)))

;; As an implementation detail, and since this is unlikely to ever
;; change, we provide one standard global message normalizer for
;; everyone to use. Implementations are free to ignore it and define
;; their own if they like.
(def ^:dynamic *message-normalizer* (MessageNormalizer.))

(defn message-normalizer
  "Returns a standard MessageNormalizer."
  []
  *message-normalizer*)


(defprotocol IQueueEndpoint

  "Abstraction of an asynchronous communications endpoint.

   In all functions, \"endpoint\" may be either a string
   naming a particular endpoint, or an implementation-specific
   endpoint object.

   Note that some implementations (notably IronMQ) do not support
   millisecond resolution on ttls and timeouts. In such cases, they
   will round the time you give up to the nearest second. "

  (lookup   [component]
    "Returns an implementation-specific endpoint interface object if
    the endpoint has been created in the backend; else nil.")

  (create-in-backend!  [component options]
    "Creates the endpoint in the underlying implementation backend
    with implementation-specific options if it does not already
    exist. Returns the new endpoint.")

  (destroy-in-backend! [component]
    "Attempts to destroy the endpoint in the underlying
     implementation's backend. The backend might reject the destroy
     attempt if the queue has messages in it (or it might not,
     depending on the underlying implementation.)")


  (force-destroy! [component]
    "Unconditionally destroys the endpoint in the
     underlying implementation's backend, deleting any messages in the
     queue.")

  (send! 
    [component message ttl] 
    [component message]
    "Places the given message onto the endpoint. The message will be
    destroyed automatically if it is not delivered in ttl milliseconds.

    The version without a ttl stores the message in the queue
    indefinitely, or as long as the underlying implementation allows.
    Specifying a ttl of 0 is equivalent to not using a ttl.")


  (receive!
    [component timeout]
    [component]
    "Blocks the current thread until a message is available on the
    endpoint, for at most timeout milliseconds. Returns the normalized
    version of the raw message received from the endpoint, or nil if
    timed out.

    The version without a timeout blocks indefinitely, until a message
    is received.

    See also register-listener! for a non-blocking way to receive messages.")


  (receive-batch! [component size]
    "Attempts to Receives up to size messages from the backend in a batch.
     Fewer than size messages may be returned if size messages are not
     immediately available. The messages are acknowledged/deleted from
     the backend. Does not block.

     The maximum allowed size is implementation-dependent.")


  (register-listener!  [component handler-fn concurrency]
    "Registers a listener that invokes handler-fn, a function of 1
     argument, in a background thread when a message becomes available
     on the endpoint. Any existing listener will be replaced. Messages
     will be normalized with an IMessageNormalizer before the handler
     is called.

     Concurrency is the number of threads to use for the listener
     functions. If you require strict serial processing of
     messages (and your backend supports strict in-order delivery),
     set this to 1. If you don't need serial processing, you can get
     better performance by using more threads here.")


  (registered-listener [component]
    "Returns the currently registered listener function for the endpoint, if any." )

  (unregister-listener! [component]
    "Unregisters the current listener function from the endpoint.")

  (count-messages [component]
    "Returns the number of messages currently queued in the endpoint.")

  (purge! [component]
    "Unconditionally deletes all pending messages from the queue.")

  (dead-letter-queue [component]
    "Returns the component that is the dead letter queue associated
    with the given component's queue.

    Note that IronMQ does not support dead letter queues as of the
    time this was written, so don't rely on them if you're using
    IronMQ!"))





