(ns com.paullegato.bureaucrat.data-endpoint
  "Facilities to ensure that an IChannelEndpoint can send and receive
   any Clojure data structure via the IDataEndpoint protocol."
  (:require [com.paullegato.bureaucrat.channel-endpoint :as channel-endpoint]
            [clojure.core.async :as async :refer [<! >! put! go go-loop chan]]
            [onelog.core :as log]))

(defprotocol IDataEndpoint
  "Facilities to ensure that an IChannelEndpoint can send and receive
   any Clojure data structure via the IDataEndpoint protocol.

   If the IQueueEndpoint underlying an IChannelEndpoint can transmit
   Clojure data structures natively, then implementation of IDataEndpoint
   is a no-op -- its send-channel and receive-channel can simply pass
   through the results of IChannelEndpoint's enqueue-channel and dequeue-channel.

   IQueueEndpoints that cannot transmit Clojure data structures directly
  have to take the IChannelEndpoint channels and decorate them with
  appropriate middleware to translate the Clojure data structures into
  something that the underlying IQueueEndpoint can understand. For
  example, IronMQ can only transmit strings, so Clojure data structures
  must be encoded somehow, such as with EDN or JSON middleware.
"
  (send-channel [component] 
    "Returns a core.async channel that renders messages placed on it
    appropriate for the underlying message transport and enqueues
    them.")

  (receive-channel [component] 
    "Returns a core.async channel that dequeues messages from the
    underlying transport, translates them into Clojure data structures
    if necessary, and places them on the channel."))




