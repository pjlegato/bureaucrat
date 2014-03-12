(ns bureaucrat.endpoint
  "The IEndpoint protocol defines a type with a set of operations for
  interacting with a generic MQ-like asynchronous communications
  endpoint.

  Concrete connectors are provided in the bureaucrat.endpoints
  package.

  TODO: Support topics in addition to queues?")

;;
;; This is structured after the tripartite format for protocols
;; suggested at
;; http://thinkrelevance.com/blog/2013/11/07/when-should-you-use-clojures-object-oriented-features
;;

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

  (send!    [_ message ttl]
    "Places the given message onto the endpoint. The message will be
    destroyed automatically if it is not delivered in ttl milliseconds.")

  (receive! [_ timeout] 
    "Blocks the current thread until a message is available on the
    endpoint for at most timeout milliseconds. Returns the message,
    or nil if timed out.")

  (register-listener!  [component tag handler-fn concurrency]
    "Registers a listener that invokes handler-fn, a function of 1
     argument, in a background thread when a message becomes available
     on the endpoint. tag uniquely identifies the given handler-fn, so
     that it may be unregistered later. Any existing listener with
     that tag will be replaced.

     Concurrency is the number of threads to use for the listener
     functions.")

  (registered-listeners [_]
    "Returns a collection of currently registered listener tags for
    the endpoint." )

  (unregister-listener! [_ tag]
    "Unregisters the listener named by the given tag from the endpoint.")

  (count-messages [_]
    "Returns the number of messages currently queued in the endpoint.")

  (purge! [component]
    "Unconditionally deletes all pending messages from the queue."))


