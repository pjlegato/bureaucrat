(ns com.paullegato.bureaucrat.transport
  "The IMessageTransport protocol abstracts a backend transport mechanism
   capable of asynchronously delivering messages to named queues.")

(defprotocol IMessageTransport

  "Abstraction of an asynchronous message transport system.

   Note that some implementations (notably IronMQ) do not support
   millisecond resolution on ttls and timeouts. In such cases, they
   will round the time you give up to the nearest second. "


  (lookup [component queue-name]
    "Returns an IQueueEndpoint attached to the transport with the given
     queue-name. The queue will *not* be created if it doesn't already exist.")

  (create-in-backend!  [component queue-name options]
    "Creates the named endpoint in the underlying implementation backend
    with implementation-specific options if it does not already
    exist. Returns an IQueueEndpoint instance.")

  (destroy-in-backend! [component queue-name]
    "Attempts to destroy the named endpoint in the underlying
     implementation's backend. The backend might reject the destroy
     attempt if the queue has messages in it (or it might not,
     depending on the underlying implementation.)")

  (force-destroy! [component queue-name]
    "Unconditionally destroys the named endpoint in the underlying
     implementation's backend, deleting any messages in the queue.")

  (dead-letter-queue [component]
    "Returns an IQueueEndpoint that is the dead letter queue associated
    with the given component's queue.

    Note that IronMQ does not intrinsically support dead letter queues
    as of the time this was written, so don't rely on them if you're
    using IronMQ! They can be simulated on our end, though."))
