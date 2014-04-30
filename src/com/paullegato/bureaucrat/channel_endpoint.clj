(ns com.paullegato.bureaucrat.channel-endpoint
  "Facilities to connect an IQueueEndpoint to core.async channels.

  The IChannelEndpoint protocol defines the capability to provide
  core.async endpoints for enqueueing and dequeueing messages on the
  underlying endpoint. It is initially intended to be built on top
  of an IQueueEndpoint, but other mechanisms are possible.

   Helper methods are provided to perform the connection.
   Given an IQueueEndpoint and an async channel, `endpoint>` takes
   messages from the endpoint and places them on the
   channel. `endpoint<` takes messages from the channel and places
   them on the endpoint."
  (:require [com.paullegato.bureaucrat.endpoint :as queue :refer [register-listener! unregister-listener! send!]]
            [clojure.core.async :as async :refer [<! >! >!! put! go go-loop chan]]
            [onelog.core :as log]))


(defprotocol IChannelEndpoint
  "The IChannelEndpoint protocol defines the capability to provide
  core.async endpoints for transmitting and receiving messages to the
  same underlying endpoint. It is initially intended to be built on top
  of an IQueueEndpoint, but other mechanisms are possible.

  Implementations should not actually preconnect any core.async channels
  until the corresponding method is actually called. For example, an
  IQueueEndpoint whose purpose is to transmit to remote systems would
  not work very well if all messages written to it were immediately
  dequeued onto a local async channel!"
  (enqueue-channel [component] "Returns a core.async channel whose contents will be placed onto the underlying endpoint's queue.")
  (dequeue-channel [component] "Returns a core.async channel where messages received on the underlying endpoint will be placed."))


;; To be used in implementations:
(defn endpoint<
  "Given a source IQueueEndpoint, creates a core.async channel and
  connects them so that messages received on the IQueueEndpoint are
  placed onto the core.async channel in a go block. Returns the new
  channel.

  If concurrency is given, uses that many threads to process messages
  coming in from the endpoint.

  Replaces any previous listener function attached to the endpoint.

  The listener will unregister itself if its channel is closed."
  ([endpoint] (endpoint< endpoint 4))
  ([endpoint concurrency]
     (let [channel (chan)]
       (log/debug "[bureaucrat][channel-connector/endpoint<::" (:name endpoint) "] Registering a channel endpoint.")
       (register-listener! endpoint
                           (fn [message]
                             (log/debug "[bureaucrat][channel-connector/endpoint>::" (:name endpoint) "] got message " message ", dispatching to channel")
                             (when-not (>!! channel message)
                               (log/error "[bureaucrat][channel-connector/endpoint>::" (:name endpoint) "]: unregistering listener function from endpoint because my core.async channel has been closed! Was processing message: " message)
                               (unregister-listener! endpoint)
                               (throw (Exception. (str "[bureaucrat][channel-connector/endpoint>:: " (:name endpoint) "]'s core.async channel was closed")))))
                           concurrency)
       channel)))


(defn endpoint>
  "Given a target IQueueEndpoint, creates a core.async channel and
  connects them so that messages received on the core.async channel
  are placed onto the target IQueueEndpoint in a background thread.
  Returns the new channel.

  Send-options will be supplied to Bureaucrat's send! function. This
  can be used to e.g. supply a ttl for the messages."
  ([endpoint & send-options]
       (log/debug "[bureaucrat][channel-connector/endpoint>] Registering a channel endpoint on " (:name endpoint))
     (let [channel (chan)]
       (go-loop []
         ;; Take returns nil when the channel is closed. In that case,
         ;; we exit the go loop rather than recurring.
         (if-let [message (<! channel)]
           (do  (log/debug "[bureaucrat][channel-connector/endpoint<::" (:name endpoint) "]  got message " message ", dispatching to endpoint " (:name endpoint) )
                (send! endpoint message send-options)
                (recur))
           (log/warn "[bureaucrat][channel-connector/endpoint<]: exiting the go-loop for endpoint " (:name endpoint) " because my source channel was closed.")))
       channel)))


