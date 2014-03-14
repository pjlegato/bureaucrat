(ns bureaucrat.endpoints.ironmq

  "Implementation of the IEndpoint protocol for IronMQ.

   Be aware that these functions throw exceptions if the network is down, 
   since they cannot communicate with Iron in that case. A future version 
   may wrap them in retry logic (or even better, a bridge from a local MQ.)

   Besides the queue name, you must specify an IronMQ project ID, OAuth2 token,
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

  The `:cloud` value must be one of the constants defined in the
 `[http://iron-io.github.io/iron_mq_java/io/iron/ironmq/Cloud.html](io.iron.ironmq.Cloud)`
  class. 


  Internal Documentation for Developers
  -----------

  * The name field specifies the queue's string name.

  * Handler-cache is an atom used to store a handler function that will be 
    invoked in a background thread when messages are available on the queue.
     It should accept one argument, which is the message being processed.  

    Note that the handler function are
    stored per-instance, not globally, and that the underlying Ironmq
    endpoint is global (i.e. the same underlying queue in Ironmq can
    potentially be shared by many IronmqEndpoint instances.) If you
    lose your IronmqEndpoint object without stopping it or removing
    its listeners, its listeners will still remain attached to the
    underlying Ironmq queue!

  TODO: Configurable DLQ per queue rather than a systemwide one?
  TODO: State machine modelling backend state?"

  (:use bureaucrat.endpoint)
  (:require [immutant.util]

            [com.stuartsierra.component :as component]
            [clojure.math.numeric-tower :as math]

            [cheshire.core      :as json]
            [onelog.core        :as log]
            [org.httpkit.client :as http]

            [org.tobereplaced (mapply :refer [mapply])])
  (:import [io.iron.ironmq Client Queue Cloud EmptyQueueException]))


;; How long to sleep between poll cycles, in ms
(def poll-sleep-time 500)

(defn- milli-time
  "Returns System/nanoTime converted to milliseconds."
  []
  (long (/ (System/nanoTime) 1000000)))


(defn try-to-get-message
  "If a message is available on the given queue, deletes it from the
  queue and returns it. Otherwise, returns nil. Does not block."
  [queue]
  (when-let [message (try (.get queue)
                          (catch EmptyQueueException e 
                            nil))]
    (.deleteMessage queue message)
    (.getBody message)))


(defn get-client
  "Given a map structured as an IronMQEndpoint, returns the Java
  io.iron.ironmq.Client instance stored in it. If there is none,
  creates one and stores it in the map's :iron-cache atom.

  project-id, token, and cloud are used to initialize the Java Client
  object. It is recommended that they be nil in most cases, in which
  case the Client will attempt to use environment variables and the
  Iron.io config file to find values for them, as described at
  http://dev.iron.io/worker/reference/configuration/ .

  If there is no atom in :iron-cache, the record is uninitialized, so
  this function returns nil."
  ([iron-mq-endpoint] (get-java-client iron-mq-endpoint nil nil nil))
  ([iron-mq-endpoint ^String project-id ^String token ^Cloud cloud]
      (if-let [iron-cache (:iron-cache iron-mq-endpoint)]
        (if-let [client (get @iron-cache :client)]
          client
          (let [new-client (Client. project-id token cloud)]
            (swap! iron-cache assoc :client new-client)
            new-client))
        nil)))





(defn get-cloud-url
  "Given an io.iron.ironmq.Client instance, constructs a URL that can
  be used for direct REST requests. This is necessary in cases where
  the Java client does not implement a feature exposed by the REST API
  yet."
  [^Client client]
  (if-not client
    nil
    (let [options    (.getOptions client)
          hostname   (get options "host")
          port       (get options "port")
          scheme     (get options "scheme")
          project-id (get options "project_id")]
      (str scheme "://" hostname ":" port "/1/projects/" project-id))))


(defn ironmq-request
  "Given an io.iron.ironmq.Client instance, submits the given REST
  request to its endpoint.

  body is optional; it will be JSON encoded and submitted as the
  request body if given.

  Example:
       (im/ironmq-request (im/get-client foo)
                          :post
                          \"/queues/foo/messages\"
                          {\"messages\" [{\"body\" \"First test message\"} {\"body\" \"Second test message\"}]})
"
  [^Client client method ^String request & body]
  (if-not client
    nil
    (let [client-options (.getOptions client)
          token          (get client-options "token")
          base-url       (get-cloud-url client)
          http-options {:url (str base-url request)
                        :method method
                        :headers {"Content-Type"  "application/json"
                                  "Authorization" (str "OAuth "  token)}}
          http-options (if-not body
                         http-options
                         (assoc http-options :body (apply json/generate-string body)))]
      (loop [try 0]
        (let [{:keys [status headers body error] :as resp} @(http/request http-options nil)]
          (if (= status 200)
            (json/parse-string body)
            (if (and (= status 503)
                     (< try 5))
              (do
                (Thread/sleep (* (Math/pow 4 try) 100 (Math/random)))
                (recur (+ try 1)))
              (throw (Exception. (str "[ironmq] Error attempting to communicate with IronMQ: " resp)))
              ))))
)))


(defrecord IronMQEndpoint [^String name 
                           handler-cache
                           iron-cache  ;; atom of a map which holds references to the Java
                                       ;; objects used to communicate with Iron
                           options]
  IQueueEndpoint

  (lookup [component]
    (or (get @iron-cache :queue-object)
        (let [new-queue (.queue (get-client component) name)]
          (swap! iron-cache assoc :queue-object new-queue)
          new-queue)))

  (create-in-backend! [component options]
    ;; IronMQ has no "create" method as such, but you can create an
    ;; emtpy queue by updating its queue options.
    ;;
    ;; Note that this system expects a queue; that is, each message is
    ;; delivered to only one consumer. IronMQ queues can also be put
    ;; into a multicast / push mode, which is called a "topic" in
    ;; JMS. Since this codebase expects queue semantics, you will get
    ;; undefined results if your IronMQ queue is not in regular "pull"
    ;; / queue mode.
    (ironmq-request (get-client component)
                    :post
                    (str "/queues/" name)
                    (or options {}))
    (lookup component))


  (destroy-in-backend! [component]
    (.destroy (lookup component)))


  (send! [component message ttl]
    ;; The protocl specifies that ttls are in milliseconds, but
    ;; IronMQ requires timeouts in seconds, not milliseconds.
    ;; ttls will be rounded up to the next second.

    (if (or (nil? ttl)
            (< ttl 1))
      (send! component message)
      (let [ttl-in-seconds (math/ceil (/ ttl 1000))
            queue (lookup component)
            ;; TODO: Work around the lack of a Queue#push method in
            ;; the client that lets you specify only the timeout.
            timeout 60
            delay 0]
        (.push queue message timeout delay ttl-in-seconds))))

  (send! [component message]
    (let [queue (lookup component)]
      (.push queue message)))


  (receive! [component timeout]
    ;; blocks for timeout ms
    (let [wait-until (+ timeout (milli-time))
          queue (lookup component)]
      (loop []
        (if-let [message (try-to-get-message queue)]
          message
          (when (>= wait-until (milli-time))
            (Thread/sleep poll-sleep-time) ;; don't hammer the server
            (recur))))))


  (receive! [component] 
    ;; Blocks until a message is available
    (let [queue (lookup component)]
      (loop []
        (if-let [message (try-to-get-message queue)]
          message
          (do
            (Thread/sleep poll-sleep-time) ;; don't hammer the server
            (recur))))))


  (receive-batch! [component size]
    (let [queue           (lookup component)
          messages (.getMessages (.get queue size))]
      (doall (pmap (fn [message]
                     (.deleteMessage queue message)
                     (.getBody message))
                   messages))))



  (count-messages [component]
    (get (ironmq-request (get-client component)
                         :get
                         (str "/queues/" name))
         "size"))


  ;; (register-listener!  [component handler-fn concurrency]
  ;;   (unregister-listener! component)
  ;;   (reset! handler-cache
  ;;           @(mq/listen (mq/as-queue name)
  ;;                      handler-fn
  ;;                      :concurrency concurrency)))


  ;; (registered-listener [component]
  ;;   @handler-cache)


  ;; (unregister-listener! [component]
  ;;   (when-let [handler @handler-cache]
  ;;     @(mq/unlisten handler)
  ;;     (reset! handler-cache nil)))

)











  ;; (dead-letter-queue [component]
  ;;   (start-ironmq-endpoint! dlq-name))


  ;; (purge! [component]
  ;;   "Unconditionally deletes all pending messages from the queue."
  ;;   (some-> (lookup component)
  ;;           (.removeMessages "")))

  ;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; component/Lifecycle

  ;; (start [component]
  ;;   ;; Idempotent; does nothing if a queue already exists
  ;;   (create-in-backend! component nil)
  ;;   component)


  ;; (stop [component]
  ;;   ;; Idempotent
  ;;   (destroy-in-backend! component)
  ;;   component)


(defn ironmq-endpoint
  "Constructor for Ironmq queue endpoints. Returns an IQueueEndpoint
  that implements component/Lifecycle. You can call (start component)
  to start it, or use it in a component system."
  ([name] (ironmq-endpoint name nil))
  ([name options]
      (map->IronMQEndpoint {:name name
                            :options options
                            :handler-cache (atom nil)
                            :iron-cache (atom {})})))


(defn start-ironmq-endpoint!
  "Convenience method for those not using the Components library to
  start services; creates a wrapper around the Ironmq queue with the
  given name, starts the underlying endpoint, and returns it."
  ([name] (start-ironmq-endpoint! name nil))
  ([name options] (component/start (ironmq-endpoint name options))))
