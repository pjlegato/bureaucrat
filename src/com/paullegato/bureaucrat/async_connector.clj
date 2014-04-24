(ns com.paullegato.bureaucrat.async-connector
  "Facilities to connect an IQueueEndpoint to core.async channels.

   Given an IQueueEndpoint and an async channel, `endpoint>` takes
   messages from the endpoint and places them on the
   channel. `endpoint<` takes messages from the channel and places
   them on the endpoint."
  (:require [com.paullegato.bureaucrat.endpoint :as queue :refer [register-listener! unregister-listener! send!]]
            [clojure.core.async :as async :refer [<! >! put! go go-loop chan]]
            [onelog.core :as log]))


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
       (register-listener! endpoint
                           (fn [message]
                             (log/debug "[bureaucrat] async-connector/endpoint> got message " message ", dispatching to channel")
                             (when-not (put! channel message)
                               (log/error "[bureaucrat] async-connector/endpoint>: unregistering my listener function because my core.async channel has been closed! Was processing message: " message)
                               (unregister-listener! endpoint)
                               (throw (Exception. "[bureaucrat] async-connector/endpoint>'s core.async channel was closed"))))
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
     (let [channel (chan)]
       (go-loop []
         ;; Take returns nil when the channel is closed. In that case,
         ;; we exit the go loop rather than recurring.
         (if-let [message (<! channel)]
           (do  (log/debug "[bureaucrat] async-connector/endpoint<  got message " message ", dispatching to endpoint")
                (future (send! endpoint message send-options))
                (recur))
           (log/warn "[bureaucrat] async-connector/endpoint<: exiting the go-loop because my source channel was closed.")))
       channel)))


