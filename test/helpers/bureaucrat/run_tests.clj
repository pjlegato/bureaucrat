(ns helpers.bureaucrat.run-tests
  "Utility to make it easy to run all tests."
  (:require [midje.config]
            [midje.util.ecosystem]
            [midje.repl]))


;; ;; Set Midje paths to values appropriate for Immutant container, if we are in Immutant
;; (if (immutant.util/in-immutant?)
;;   (midje.util.ecosystem/set-leiningen-paths! {:test-paths [(immutant.util/app-relative "test")],
;;                                               :source-paths [(immutant.util/app-relative "src")]}))



(defn run-tests!
  []
  (time (midje.repl/load-facts :all)))
