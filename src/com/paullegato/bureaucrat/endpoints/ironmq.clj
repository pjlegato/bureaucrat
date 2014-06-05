(ns com.paullegato.bureaucrat.endpoints.ironmq
  "Implementation of the IQueueEndpoint protocol for IronMQ.

   * IronMQ only supports strings as messages. If your message is not
     a string, it will be coerced into a string with `pr-str`. It is
     recommended that you use middleware to ensure that all messages
     are stringified as you like before they reach this endpoint.

   * Be aware that these functions throw exceptions if the network is
     down, since they cannot communicate with Iron in that case. A
     future version may wrap them in retry logic (or even better, a
     bridge from a local MQ.)

   * If a handler function throws an exception, the message is moved
     to the dead letter queue without a retry. Future versions may
     implement retries.

   * IronMQ does not have intrinsic dead letter queues.  We simulate a
     limited DLQ here by creating a queue called \"DLQ\". If a message
     listener function throws an exception, the message that caused
     the exception is placed on the DLQ.

   * IronMQ's push mode requires you to expose an HTTP endpoint, with
     associated security annoyances. Future versions may support
     this. For now, we poll the queue every 5 seconds when a message
     handler function is registered. Webhook push may be implemented
     in the future.

  Internal Documentation for Developers
  -------------------------------------


  * :iron-cache stores the threadpool that
     polls IronMQ in the background and executes the listener function
     when messages become available.

   TODO: I don't like that the programmer must remember to unregister
         the listener when it is no longer needed, since forgetting
         about one will leak threads with no (easy) way to find a
         reference to them and shut them down. That needs a better
         solution.

   TODO: use webhook rather than polling.
"

  (:use com.paullegato.bureaucrat.endpoint
        com.paullegato.bureaucrat.transports.util.ironmq
        [slingshot.slingshot :only [try+ throw+]])
  (:require [clojure.math.numeric-tower :as math]

            [com.paullegato.bureaucrat.middleware.json :as json :refer [json-encode> json-decode<]]
            [com.paullegato.bureaucrat.middleware.edn  :as edn  :refer [edn-encode>  edn-decode<]]
            [onelog.core        :as log]
            [org.httpkit.client :as http]
            [com.climate.claypoole :as cp]
            [environ.core :refer [env]]

            [clojure.core.async :as async :refer [map> map< <! >! >!! put! go go-loop chan]]
            [com.paullegato.bureaucrat.util :as util :refer [milli-time pmax]]

            [com.paullegato.bureaucrat.transport :as transport]
            [com.paullegato.bureaucrat.channel-endpoint :as channel-endpoint :refer [IChannelEndpoint endpoint< endpoint>]]
            [com.paullegato.bureaucrat.data-endpoint :as data-endpoint :refer [IDataEndpoint]]
            [org.tobereplaced (mapply :refer [mapply])])
  (:import [io.iron.ironmq Client Queue Cloud Message EmptyQueueException]))


;; How long to sleep between poll cycles, in ms
(def poll-sleep-time (or (env :bureaucrat-ironmq-poll-sleep-time)
                         10000))


(defn try-to-get-message
  "If a message is available on the given queue, deletes it from the
  queue and returns it. Otherwise, returns nil. Does not block."
  [^Queue queue]
  (when-let [^Message message (try (.get queue)
                                   (catch EmptyQueueException e nil))]
    (.deleteMessage queue message)
    (.getBody message)))


(defn try-handler
  "Tries to run handler-fn on message. If it throws an exception, logs
  the exception and sends message to the DLQ on transport"
  [handler-fn message transport]
  (log/debug "[bureaucrat/ironmq] Background poller processing message: " message)
  (try
    (handler-fn message)
    (log/trace "[bureaucrat/ironmq] Background poller finished processing message: " message)
    (catch Throwable t
      (log/error "[bureaucrat/ironmq] Background poller: error processing message " message
                 " - " (log/throwable t))
      (util/send-to-dlq! transport message))))


(defn log-prefix
  [component]
  (str "[bureaucrat][ironmq::"
       (or (:name component)
           "<unbound>")
       "-"(.hashCode component)
       "] "))


(def iqueueendpoint-implementations
  {
   :transport (fn [component] (:transport component))

   :purge! (fn [component] 
             (log/debug "[bureaucrat/ironmq] Purging queue: " (:name component))
             (.clear (:queue component)))

   :unregister-listener!   (fn [component]
                             (log/info (log-prefix component) "Unregistering old listener on " (:name component) "...")                             
                             (swap! (:iron-cache component) assoc :should-halt true))


   :registered-listener   (fn ([component]
                                 (:handler-fn @(:iron-cache component))))

   :register-listener!   (fn [component handler-fn concurrency]
                           ;; Clear any old listener that may be around:
                           (unregister-listener! component)

                           (log/info (log-prefix component) "Registering new listener on " (:name component) "...")

                           (swap! (:iron-cache component) assoc :should-halt false)
                           (swap! (:iron-cache component) assoc :handler-fn handler-fn)

                           ;; IronMQ requires us to poll or expose a webhook endpoint. 
                           ;; For now, we poll in a go loop, and write the messages we 
                           ;; get onto a channel for async processing by the handler 
                           ;; function later.
                           ;;
                           ;; The poller will write newly recevied messages to this buffer
                           ;; channel, for later processing by a worker:
                           (let [buffer-channel (chan 1000)]

                             ;; Main poll loop thread:
                             (async/thread
                               (try
                                 (loop []
                                   (let [messages      (receive-batch! component (or (:poller-batch-size component) 30))
                                         message-count (count messages)]
                                     (when (> message-count 0)
                                       (log/debug (log-prefix component) " Background message poller got "  message-count " messages.")
                                       (doseq [m messages]
                                         (>!! buffer-channel m))))

                                   (Thread/sleep poll-sleep-time)

                                   (if (:should-halt @(:iron-cache component))
                                     (do
                                       (log/info (log-prefix component) "Background poller got halt notice, stopping.")
                                       (swap! (:iron-cache component) dissoc :handler-fn)
                                       (async/close! buffer-channel))
                                     (recur)))
                                 (catch Throwable t
                                   (log/error+ (log-prefix component) "Got an error in IronMQ poller thread! " (log/throwable t)))))

                             ;; Processors:
                             (pmax concurrency
                                   (fn [message]
                                     (go (try-handler handler-fn message (:transport component))))
                                   buffer-channel
                                   (chan))))


   :count-messages   (fn [component]
                       (get
                        (ironmq-request
                         (-> component :transport :client)
                         :get
                         (str "/queues/" (:name component)))
                        "size"))

   :receive-batch!   (fn [component size]
                       (log/debug (log-prefix component)  "Asking backend for a message batch of size " size)
                       (let
                           [queue (:queue component)
                            messages (.getMessages (.get queue size))]
                         (log/trace (log-prefix component) "receive-batch! got " (count messages)  " messages.")

                         ;; 1) Delete from queue, acknowledging it;
                         ;; 2) Convert the IronMQ client object into a string
                         (doall
                          (cp/pmap 4 (fn [message]
                                       (.deleteMessage queue message)
                                       (str message))
                                   messages))))


   :receive!   (fn
                 ([component timeout]
                    (log/debug (log-prefix component) "Queue " (:name component) " receiving a message with timeout " timeout)

                    ;; blocks for timeout ms
                    (let [wait-until (+ timeout (milli-time))
                          queue (:queue component)]
                      (loop []
                        (if-let [message (try-to-get-message queue)]
                          (str message)
                          (when (>= wait-until (milli-time))
                            (Thread/sleep poll-sleep-time) ;; don't hammer the server
                            (recur))))))

                 ([component]
                    (log/debug (log-prefix component) "Queue " (:name component) " receiving a message (no timeout)")
                    (let
                        [queue (:queue component)]
                      (loop
                          []
                        (if-let
                            [message (try-to-get-message queue)]
                          (str message)
                          (do (Thread/sleep poll-sleep-time) (recur)))))))


   :send! (fn ([component message]
                 (send! component message nil))
            ([component message options]
               (log/debug (log-prefix component) "Queue " (:name component) " is being sent message: " message)

               ;; The protocl specifies that ttls are in milliseconds, but
               ;; IronMQ requires timeouts in seconds, not milliseconds.
               ;; ttls will be rounded up to the next second.
               (if-not message
                 (log/error  (log-prefix component)  "send! got a nil message; ignoring it.") 
                 (let [ttl (:ttl options)
                       queue ^Queue (:queue component)

                       ;; IronMQ can only send strings, so we force every message to be a string here.
                       ;; This should ideally be handled by middleware before the message gets to IronMQ.
                       message (if (string? message)
                                 message
                                 (do
                                   (log/warn (log-prefix component)
                                             "send! got a non-string message; coercing it to a string. "
                                             "You should arrange for all messages to be strings with middleware instead.")
                                   (pr-str message)))]
                   (log/debug  (log-prefix component) "Sending message: " message)
                   (if (or (nil? ttl)
                           (< ttl 1))
                     (.push queue message)
                     (let [ttl-in-seconds (math/ceil (/ ttl 1000))

                           ;; TODO: Work around the lack of a Queue#push method in
                           ;; the client that lets you specify only the timeout.
                           timeout 60
                           delay 0]
                       (.push queue message timeout delay ttl-in-seconds))))))
            )})


(def ichannelendpoint-implementations
    {:dequeue-channel (fn ([component] (endpoint< component)))
     :enqueue-channel (fn ([component] (endpoint> component)))})


;; Implementation of all common methods, providing up to IChannelEndpoint, but not IDataEndpoint or higher:
(defrecord IronMQEndpoint [^String name

                           transport ;; IMessageTransport associated with this endpoint
                           ^Queue queue ;; Underlying Java queue object associated with this endpoint
                           
                           iron-cache ;; atom of a map which holds references to the Java
                           ;; objects used to communicate with Iron, and the 
                           ;; Claypoole thread pool that runs the async handlers


                           poller-batch-size ;; The listener poller will fetch messages in batches of this size.
                           ])

(extend IronMQEndpoint 
  IQueueEndpoint iqueueendpoint-implementations
  IChannelEndpoint ichannelendpoint-implementations)


;; Like the plain IronMQEndpoint, but implements IDataEndpoint to do JSON transcoding.
(defrecord IronMQ-JSON-Endpoint [^String name

                                 transport ;; IMessageTransport associated with this endpoint
                                 ^Queue queue ;; Underlying Java queue object associated with this endpoint
                                 
                                 iron-cache ;; atom of a map which holds references to the Java
                                 ;; objects used to communicate with Iron, and the 
                                 ;; Claypoole thread pool that runs the async handlers


                                 poller-batch-size ;; The listener poller will fetch messages in batches of this size.
                                 ])


(extend IronMQ-JSON-Endpoint
  IQueueEndpoint iqueueendpoint-implementations
  IChannelEndpoint ichannelendpoint-implementations
  IDataEndpoint {:send-channel    #(json-encode> (channel-endpoint/enqueue-channel %))
                 :receive-channel #(json-decode< (channel-endpoint/dequeue-channel %))})


;; Like the plain IronMQEndpoint, but implements IDataEndpoint to do JSON transcoding.
(defrecord IronMQ-EDN-Endpoint [^String name

                                 transport ;; IMessageTransport associated with this endpoint
                                 ^Queue queue ;; Underlying Java queue object associated with this endpoint
                                 
                                 iron-cache ;; atom of a map which holds references to the Java
                                 ;; objects used to communicate with Iron, and the 
                                 ;; Claypoole thread pool that runs the async handlers


                                 poller-batch-size ;; The listener poller will fetch messages in batches of this size.
                                 ])


(extend IronMQ-EDN-Endpoint
  IQueueEndpoint iqueueendpoint-implementations
  IChannelEndpoint ichannelendpoint-implementations
  IDataEndpoint {:send-channel    #(edn-encode> (channel-endpoint/enqueue-channel %))
                 :receive-channel #(edn-decode< (channel-endpoint/dequeue-channel %))})


(defn ironmq-endpoint
  "Constructor for IronMQ queue endpoints. Note that this does not
  create anything in the backend; it just wraps the Clojure access
  points to the backend.

  Use the IronMQ IMessageTransport instance to create new instances
  rather than calling this directly!" 
  [name transport]
  (log/debug "[bureaucrat/ironmq] Making new IronMQ endpoint called " name " with no specified encoding..")
  (map->IronMQEndpoint {:name name
                        :queue (.queue ^Client (:client transport) name)
                        :transport transport
                        :poller-batch-size 100
                        :iron-cache (atom {})}))


(defn ironmq-json-endpoint
  "Constructor for IronMQ JSON-transcoded queue endpoints. Note that
  this does not create anything in the backend; it just wraps the
  Clojure access points to the backend.

  Use the IronMQ IMessageTransport instance to create new instances
  rather than calling this directly!" 
  [name transport]
  (log/debug "[bureaucrat/ironmq] Making new IronMQ endpoint called " name " with JSON encoding..")
  (map->IronMQ-JSON-Endpoint {:name name
                              :queue (.queue ^Client (:client transport) name)
                              :transport transport
                              :poller-batch-size 100
                              :iron-cache (atom {})}))


(defn ironmq-edn-endpoint
  "Constructor for IronMQ EDN-transcoded queue endpoints. Note that
  this does not create anything in the backend; it just wraps the
  Clojure access points to the backend.

  Use the IronMQ IMessageTransport instance to create new instances
  rather than calling this directly!" 
  [name transport]
  (log/debug "[bureaucrat/ironmq] Making new IronMQ endpoint called " name " with JSON encoding..")
  (map->IronMQ-EDN-Endpoint {:name name
                             :queue (.queue ^Client (:client transport) name)
                             :transport transport
                             :poller-batch-size 100
                             :iron-cache (atom {})}))

