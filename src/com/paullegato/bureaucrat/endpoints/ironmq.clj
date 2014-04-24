(ns com.paullegato.bureaucrat.endpoints.ironmq
  "Implementation of the IEndpoint protocol for IronMQ.

   * Be aware that these functions throw exceptions if the network is down, 
     since they cannot communicate with Iron in that case. A future version 
     may wrap them in retry logic (or even better, a bridge from a local MQ.)

   * Besides the queue name, you must specify an IronMQ project ID, OAuth2 token,
     and server hostname to use. This is best
     accomplished by creating the
     [http://dev.iron.io/worker/reference/configuration/](
     environment variables or the iron.json config file described at Iron's website).

     For development, you can pass a hash with any of the following
     keys. If any are unspecified, the IronMQ client library will
     attempt to fall back on the environment variables and config files
     linked above.

          ````
          {:project-id \"your-project\"
           :token      \"your-oauth2-token\"
           :cloud      io.iron.ironmq.Cloud/ironAWSUSEast
          }
          ````

   * The `:cloud` value must be one of the constants defined in the
     `[http://iron-io.github.io/iron_mq_java/io/iron/ironmq/Cloud.html](io.iron.ironmq.Cloud)`
     class. 

   * IronMQ does not have intrinsic dead letter queues.
     We simulate a limited DLQ here by creating a queue called \"DLQ\". If a message listener
      function throws an exception, the message that caused the exception is placed on the DLQ.

   * IronMQ's push mode requires you to expose an HTTP endpoint, with
     associated security annoyances. Future versions may support
     this. For now, we poll the queue every 5 seconds when a message
     handler function is registered.

  Internal Documentation for Developers
  -------------------------------------
   * Messages are EDN-encoded before being sent to IronMQ, and messages
     are EDN-decoded upon receipt from IronMQ
   * :iron-cache is an atom of a map used to store state for the
     IronMQ Java client library.
   * :iron-cache also stores the threadpool that polls IronMQ in the
     background and executes the listener function when messages
     become available.

   TODO: I don't like that the programmer must remember to unregister
         the listener when it is no longer needed, since forgetting about one
         will leak threads with no (easy) way to find a reference to
         them and shut them down. That needs a better solution.
"

  (:use com.paullegato.bureaucrat.endpoint
        com.paullegato.bureaucrat.transports.util.ironmq
        [slingshot.slingshot :only [try+ throw+]])
  (:require [clojure.math.numeric-tower :as math]

            [cheshire.core      :as json]
            [onelog.core        :as log]
            [org.httpkit.client :as http]
            [com.climate.claypoole :as cp]
            [clojure.tools.reader.edn :as edn]

            [com.paullegato.bureaucrat.transport :as transport]
            [org.tobereplaced (mapply :refer [mapply])])
  (:import [io.iron.ironmq Client Queue Cloud Message EmptyQueueException]))

(declare start-ironmq-endpoint!)

;; How long to sleep between poll cycles, in ms
(def poll-sleep-time 500)

(defn- milli-time
  "Returns System/nanoTime converted to milliseconds."
  []
  (long (/ (System/nanoTime) 1000000)))


(defn try-to-get-message
  "If a message is available on the given queue, deletes it from the
  queue and returns it. Otherwise, returns nil. Does not block."
  [^Queue queue]
  (when-let [^Message message (try (.get queue)
                                   (catch EmptyQueueException e 
                                     nil))]
    (.deleteMessage queue message)
    (.getBody message)))



(defrecord IronMQEndpoint [^String name

                           transport    ;; IMessageTransport associated with this endpoint
                           ^Queue queue ;; Underlying Java queue object associated with this endpoint
                           
                           iron-cache  ;; atom of a map which holds references to the Java
                                       ;; objects used to communicate with Iron, and the 
                                       ;; Claypoole thread pool that runs the async handlers


                           poller-batch-size ;; The listener poller will fetch messages in batches of this size.
                           ]
  IQueueEndpoint



  (send! [component message options]
    ;; The protocl specifies that ttls are in milliseconds, but
    ;; IronMQ requires timeouts in seconds, not milliseconds.
    ;; ttls will be rounded up to the next second.
    (if-not message
      (log/error "send! got a nil message; ignoring it.")
      (let [ttl (:ttl options)
            queue ^Queue (:queue component)

            ;; IronMQ can only send strings, so we force every message to be a string here.
            ;; This should ideally be handled by middleware before the message gets to IronMQ.
            message (if (string? message)
                      message
                      (do
                        (log/warn "[bureaucrat] IronMQ endpoint " name ": send! got a non-string message; coercing it to a string. You should arrange for all messages to be strings!")
                        (pr-str message)))]
        
        (if (or (nil? ttl)
                (< ttl 1))
          (.push queue message)
          (let [ttl-in-seconds (math/ceil (/ ttl 1000))

                ;; TODO: Work around the lack of a Queue#push method in
                ;; the client that lets you specify only the timeout.
                timeout 60
                delay 0]
            (.push queue message timeout delay ttl-in-seconds))))))

  (send! [component message]
    (send! component message nil))


  (receive! [component timeout]
    ;; blocks for timeout ms
    (let [wait-until (+ timeout (milli-time))
          queue (:queue component)]
      (loop []
        (if-let [message (try-to-get-message queue)]
          (str message)
          (when (>= wait-until (milli-time))
            (Thread/sleep poll-sleep-time) ;; don't hammer the server
            (recur))))))


  (receive! [component] 
    ;; Blocks until a message is available
    (let [queue (:queue component)]
      (loop []
        (if-let [message (try-to-get-message queue)]
          (str message)
          (do
            (Thread/sleep poll-sleep-time) ;; don't hammer the server
            (recur))))))


  (receive-batch! [component size]
    (let [^Queue queue (:queue component)
          messages (.getMessages (.get queue size))]
      (doall (pmap (fn [^Message message]
                     (.deleteMessage queue message)
                     (str message))
                   messages))))


  (count-messages [component]
    (get (ironmq-request (-> component
                             :transport
                             :client)
                         :get
                         (str "/queues/" name))
         "size"))


  ;; IronMQ's push functionality requires us to expose an HTTP
  ;; endpoint, with concominant security implications and firewall
  ;; issues. For now, we are going to run listeners in a polling loop.
  ;;
  ;; Note that if your handler function throws an error while
  ;; processing a message, the message will NOT be placed back onto
  ;; the queue. It'll be put onto the dead letter queue instead.
  ;;
  (register-listener!  [component handler-fn concurrency]
    (unregister-listener! component)
    (swap! iron-cache assoc :should-halt false)
    (let [pool (or (:pool iron-cache)
                   ;; Add an extra thread for the outermost management future
                   (let [pool  (cp/threadpool (+ 1 concurrency)
                                              :daemon true)]
                     (:pool (swap! iron-cache assoc :pool pool))))]

      (cp/future pool 
                 (let [messages (receive-batch! component (or poller-batch-size 20))
                       message-count (count messages)]

                   (if (> message-count 0)
                     (log/info "[bureaucrat][ironmq] Background message poller got " message-count " messages."))
                   
                   (doall (cp/pmap pool (fn [message]
                                          (log/debug "[bureaucrat][ironmq] Background poller got a message, processing it. Message is " message)
                                          (try
                                            (handler-fn message)
                                            (catch Throwable t
                                              ;; TODO: Place message back on queue for retry -- but
                                              ;; we also need a mechanism to count redelivery attempts for a specific
                                              ;; message, to avoid infinite loops.
                                              ;; For now, erroneous messages just go onto the DLQ directly.
                                              (log/error "[bureaucrat/ironmq] Async listener: error processing message " message " - "
                                                         (log/throwable t))
                                              ;; TODO: implement middleware on DLQ , add error
                                              (send! (transport/dead-letter-queue (:transport component)) 
                                                     message))))
                                   messages)))

                 ;; TODO configurable poll interval
                 (Thread/sleep 2000)

                 (if (:should-halt @iron-cache)
                   (log/info "[bureaucrat][ironmq] Background poller got halt notice, stopping.")
                   (recur)))))


  (registered-listener [component]
    (:pool @iron-cache))


  (unregister-listener! [component]
    (swap! iron-cache assoc :should-halt true)
    (when-let [pool (registered-listener component)]
      (cp/shutdown pool)
      (swap! iron-cache dissoc :pool)))

  (purge! [component]
    (.clear ^Queue (:queue component))))



(defn ironmq-endpoint
  "Constructor for IronMQ queue endpoints. Note that this does not
  create anything in the backend; it just wraps the Clojure access
  points to the backend.

  Use the IronMQ IMessageTransport instance to create new instances
  rather than calling this directly!" 
  [name transport]
  (map->IronMQEndpoint {:name name
                        :queue (.queue ^Client (:client transport) name)
                        :transport transport
                        :poller-batch-size 100
                        :iron-cache (atom {})}))


