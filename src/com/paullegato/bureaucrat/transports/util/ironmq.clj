(ns com.paullegato.bureaucrat.transports.util.ironmq
  "Utility functions for interacting with IronMQ directly."
  (:use com.paullegato.bureaucrat.transport
        [slingshot.slingshot :only [try+ throw+]])
  (:require [com.paullegato.bureaucrat.endpoints.ironmq :as endpoint]
            [org.httpkit.client :as http]
            [onelog.core        :as log]
            [cheshire.core      :as json])
  (:import [io.iron.ironmq Client Cloud]))


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
  request to its endpoint. Attempts to create a new Client with default
  settings if none is provided.

  body is optional; it will be submitted as the request body if given.

  The two argument version uses the default Client initialized from 
  the environment, for convenience.

  Examples:

       (ironmq-request (get-client foo)
                          :post
                          \"/queues/foo/messages\"
                          {\"messages\" [{\"body\" \"First test message\"} {\"body\" \"Second test message\"}]})

       (ironmq-request :get \"/queues/foo\")
"
  ([method request] (ironmq-request (Client. nil nil nil) method request))
  ([^Client client method ^String request & body]
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
                    (Thread/sleep (* (Math/pow 4 try) 100))
                    (recur (+ try 1)))
                  (let [trimmed-resp (select-keys resp [:body :status])]
                    
                    (log/warn (str "[ironmq][status=" (:status resp)  "] Error attempting to communicate with IronMQ. Request was "
                                  method " " request
                                  " Reply was " trimmed-resp))
                    (throw+ {:message "Error attempting to communicate with IronMQ"
                             :request-method method
                             :request-path request
                             :status (:status resp)
                             :body (:body resp)
                             }))))))))))


(defn queue-exists?
  "Returns a queue status map if a queue with the given name exists in
  IronMQ, else false if the queue does not exist."
  [name]
  (try+
   (ironmq-request :get (str "/queues/" name))
   (catch [:status 404] _
       false)))
