(ns unit.com.paullegato.bureaucrat.api-routers.table-api-router-test
  (:use [midje.sweet]
        [com.paullegato.bureaucrat.api-router]
        [com.paullegato.bureaucrat.api-routers.table-api-router]
        [helpers.bureaucrat.test-helpers])
  (:require [onelog.core :as log]))


(fact "handler functions can be added and removed from a TableAPIRouter"
      (let [router (table-api-router {})
            test-handler (fn [message] "Hello!")]
        (add-handler!     router :foo test-handler)
        (handler-for-call router :foo) => test-handler
        (remove-handler!  router :foo)
        (handler-for-call router :foo) => nil))


(fact "handler functions are called properly"
      (let [router (table-api-router {})
            result (atom nil)
            test-message (str "Router test message -- " (rand 10000000))
            second-test-message (str "Other router test message -- " (rand 10000000))
            test-handler (fn [message] (reset! result message))]

        (add-handler! router :foo test-handler)
        
        (process-message! router {:call    :foo
                                  :payload test-message})
        @result => test-message

        ;; This call doesn't exist, so the result shouldn't change
        (process-message! router {:call :bar
                                  :payload second-test-message})
        @result => test-message))


(fact "the router deals with exceptions thrown by the handler function without throwing"
      (let [router (table-api-router {})
            test-handler (fn [message] (throw (Exception. "Test exception from table-api-router-test -- nothing to worry about!")))]
        (add-handler! router :foo test-handler)
        (process-message! router {:call :foo
                                  :payload "asdf"}) => nil))


(fact "the router deals with incorrectly formatted input messages without throwing"
      (let [router (table-api-router {})
            test-handler (fn [message] :foo)]
        (add-handler! router :foo test-handler)
        (process-message! router :asdf) => nil
        (process-message! router nil) => nil
        (process-message! router 123) => nil
        (process-message! router {}) => nil
        (process-message! router {:payload "foo"}) => nil))
