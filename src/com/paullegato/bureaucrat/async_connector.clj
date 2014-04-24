(ns com.paullegato.bureaucrat.async-connector
  "Facilities to connect an IQueueEndpoint to core.async channels.

   Given an IQueueEndpoint and an async channel, `endpoint>` takes
   messages from the endpoint and places them on the
   channel. `endpoint<` takes messages from the channel and places
   them on the endpoint."
  (:require [com.paullegato.bureaucrat.endpoint :as queue :refer [register-listener! unregister-listener! send!]]
            [clojure.core.async :as async :refer [<! >! put! go go-loop]]
            [onelog.core :as log]))


(defn endpoint>
  "Given a source IQueueEndpoint and a target core.async channel, connects them so
  that messages received on the IQueueEndpoint are placed onto the core.async
  channel in a go block.

  Replaces any previous listener function attached to the endpoint.

  The listener will unregister itself if its channel is closed."
  ([endpoint channel] (endpoint> endpoint channel 4))
  ([endpoint channel concurrency]
     (register-listener! endpoint
                         (fn [message]
                           (log/debug "[bureaucrat] async-connector/endpoint> got message " message ", dispatching to channel")
                           (when-not (put! channel message)
                             (log/error "[bureaucrat] async-connector/endpoint>: unregistering my listener function because my core.async channel has been closed! Was processing message: " message)
                             (unregister-listener! endpoint)
                             (throw (Exception. "[bureaucrat] async-connector/endpoint>'s core.async channel was closed"))))
                         concurrency)))


(defn endpoint<
  "Given a target IQueueEndpoint and a source core.async channel,
  connects them so that messages received on the core.async channel
  are placed onto the target IQueueEndpoint in a background thread.

  Send-options will be supplied to Bureaucrat's send! function. This
  can be used to e.g. supply a ttl for the messages."
  ([endpoint channel & send-options]
     (go-loop []
       ;; Take returns nil when the channel is closed. In that case,
       ;; we exit the go loop rather than recurring.
       (if-let [message (<! channel)]
         (do  (log/debug "[bureaucrat] async-connector/endpoint<  got message " message ", dispatching to endpoint")
              (future (send! endpoint message send-options))
             (recur))
         (log/warn "[bureaucrat] async-connector/endpoint<: exiting the go-loop because my source channel was closed.")))))


