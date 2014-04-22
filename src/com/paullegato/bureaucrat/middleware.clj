(ns com.paullegato.bureaucrat.middleware
  "Middleware to perform transformations on messages"
  (:require [com.paullegato.bureaucrat.endpoint :as queue :refer [register-listener! unregister-listener! send!]]
            [clojure.core.async :as async :refer [map> map<]]
            [clojure.tools.reader.edn :as edn]
            [onelog.core :as log]))
)

