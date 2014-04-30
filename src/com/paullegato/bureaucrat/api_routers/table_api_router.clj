(ns com.paullegato.bureaucrat.api-routers.table-api-router
  "API router (instance of IAPIRouter) that uses a predefined lookup
  table to map API calls to Clojure functions.

  * `table-atom` is an atom of a map. Keys are keyword API call names
    (typically keywords), and values are functions.

  The router will accept string API call names in incoming messages and
  automatically keywordize them, for JSON compatibility.
"
  (:use [com.paullegato.bureaucrat.api-router]
        [com.paullegato.bureaucrat.api-routers.api-router-helpers]
        [com.paullegato.bureaucrat.util       :as util :refer [send-to-dlq!]]
        [onelog.core :as log]
        [slingshot.slingshot :only [try+ throw+]])
  (:require [com.paullegato.bureaucrat.util :refer [send-to-dlq!]]
            [onelog.core :as log]))


(defrecord TableAPIRouter
  [table-atom]

  IAPIRouter 
  (process-message! [component message]
    (try-to-process-message component message))

  (handler-for-call [component call]
    (get @table-atom call))

  (process-unhandled-message! [component message]
    (if-let [f (:unhandled-message-fn component)]
      (f component message)
      (do
        (log/warn "[bureaucrat][table-api-router] Couldn't find a valid API handler for call '" (:call message) "'; discarding.\nMessage was: " message)
        (send-to-dlq! message))))

  IAdjustableAPIRouter
  (add-handler! [component api-call function]
    (swap! table-atom assoc api-call function))

  (remove-handler! [component api-call]
    (swap! table-atom dissoc api-call))

  IListableAPIRouter
  (list-handlers! [component]
    @table-atom))


(defn table-api-router
  "Returns a new TableAPIRouter with the given initial routes."
  [initial-routes]
    (log/debug "[bureaucrat] TableAPIRouter starting with initial-routes " initial-routes)
    (map->TableAPIRouter {:table-atom (atom initial-routes)}))
