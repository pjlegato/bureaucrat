(ns com.paullegato.bureaucrat.api-routers.table-api-router
  "API router (instance of IAPIRouter) that uses a predefined lookup
  table to map API calls to Clojure functions.

  * `table-atom` is an atom of a map. Keys are API call names
    (typically keywords), and values are functions."
  (:use [com.paullegato.bureaucrat.api-router]
        [com.paullegato.bureaucrat.endpoint]
        [slingshot.slingshot :only [try+ throw+]])
  (:require [ com.paullegato.bureaucrat.api-routers.api-router-helpers :as helpers]
            [onelog.core :as log]))


(defrecord TableAPIRouter
  [table-atom]

  IAPIRouter 

  (process-message! [component message]
    (helpers/try-handler component message))

  (handler-for-call [component call]
    (get @table-atom call))


  IAdjustableAPIRouter

  (add-handler! [component api-call function]
    (swap! table-atom assoc api-call function))

  (remove-handler! [component api-call]
    (swap! table-atom dissoc api-call)))


(defn table-api-router
  "Returns a new TableAPIRouter with the given initial routes."
  [initial-routes]
  (map->TableAPIRouter {:table-atom (atom initial-routes)}))
