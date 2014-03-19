(ns unit.com.paullegato.bureaucrat.api-routers.metadata-api-router-test
  (:use [midje.sweet]
        [com.paullegato.bureaucrat.api-router]
        [com.paullegato.bureaucrat.api-routers.metadata-api-router]
        [helpers.bureaucrat.test-helpers])
  (:require [onelog.core :as log]))

(def last-result (atom nil))

(namespace-state-changes [(before :facts (reset! last-result nil))])

(defn ^:api allowed-test-handler
  [message]
  (reset! last-result message))

;; no :api metadata:
(defn forbidden-test-handler
  [message]
  (reset! last-result message))


(fact "metadata-api-routers look up functions successfully"
      (let [router (metadata-api-router "unit.com.paullegato.bureaucrat.api-routers.metadata-api-router-test/")]

        (handler-for-call router "allowed-test-handler") => allowed-test-handler
        (handler-for-call router "forbidden-test-handler") => nil
        (handler-for-call router "nonexistent-test-handler") => nil))


(fact "handler functions are called properly"
      (let [router (metadata-api-router "unit.com.paullegato.bureaucrat.api-routers.metadata-api-router-test/")
            test-message (str "Router test message -- " (rand 10000000))
            second-test-message (str "Other router test message -- " (rand 10000000))]

        (process-message! router {:call "allowed-test-handler"
                                  :payload test-message})
        @last-result => test-message

        ;; This handler function is forbidden exist, so the result shouldn't change
        (process-message! router {:call "forbidden-test-handler"
                                  :payload second-test-message})
        @last-result => test-message))


