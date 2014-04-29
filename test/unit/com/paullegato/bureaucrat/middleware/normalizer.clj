(ns unit.com.paullegato.bureaucrat.middleware.normalizer
  "Tests the message normalization middleware."
  (:require [onelog.core :as log]
            [clojure.core.async :as async :refer [<!! >!! chan]])
  (:use [midje.sweet]
        [com.paullegato.bureaucrat.middleware.normalizer]
        [helpers.bureaucrat.test-helpers]))


(fact "normalize-ingress< accepts strings on the given channel and outputs normalized messages on the returned channel"
      (let [raw-in         (chan 1)
            normalized-out (normalize-ingress< raw-in :test-endpoint)]
        (>!! raw-in "foo bar baz") => truthy
        (let [result (<!! normalized-out)]
          result => (contains {:payload "foo bar baz"})
          (keys (:bureaucrat result)) => (contains #{:ingress-endpoint :ingress-time})
          (-> result :bureaucrat :ingress-endpoint) => :test-endpoint)))


(fact "normalize-ingress> accepts strings on the returned channel and outputs normalized messages on the given channel"
      (let [normalized-out (async/pipe (async/timeout 10000)
                                       (chan 1))
            raw-in         (normalize-ingress> normalized-out :test-endpoint)]
        (>!! raw-in "foo bar baz") => truthy
        (let [result (<!! normalized-out)]
          result => (contains {:payload "foo bar baz"})
          (keys (:bureaucrat result)) => (contains #{:ingress-endpoint :ingress-time})
          (-> result :bureaucrat :ingress-endpoint) => :test-endpoint)))


(fact "normalize-egress< accepts internal-format messages on the given
channel and outputs interservice format messages on the returned channel"
      (let [internal-format-in         (chan 1)
            interservice-out  (async/pipe (normalize-egress< internal-format-in)
                                          (async/timeout 10000))]
        (>!! internal-format-in {:bureaucrat {:ingress-endpoint :test :ingress-time :test} :payload "asdf"}) => truthy
        (<!! interservice-out) => {:payload "asdf"}))


(fact "normalize-egress> accepts internal-format messages on the returned
channel and outputs interservice format messages on the given channel"
      (let [interservice-out   (chan 1)
            internal-format-in (normalize-egress> interservice-out)]
        (>!! internal-format-in {:bureaucrat {:ingress-endpoint :test :ingress-time :test} :payload "asdf"}) => truthy
        (<!! interservice-out) => {:payload "asdf"}))


