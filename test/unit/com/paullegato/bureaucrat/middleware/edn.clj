(ns unit.com.paullegato.bureaucrat.middleware.edn
  "Tests the EDN transcoding middleware."
  (:require [onelog.core :as log]
            [clojure.core.async :as async :refer [<!! >!! chan]])
  (:use [midje.sweet]
        [com.paullegato.bureaucrat.middleware.edn]
        [helpers.bureaucrat.test-helpers]))


(fact "edn-encode< accepts Clojure on the given channel and outputs EDN strings on the returned channel"
      (let [clojure-in      (chan 1)
            edn-strings-out (edn-encode< clojure-in)]
        (>!! clojure-in {:abc 123 :foo '(a b c)}) => truthy
        (<!! edn-strings-out) => "{:abc 123, :foo (a b c)}"))


(fact "edn-encode> accepts Clojure on the returned channel and outputs EDN strings on the given channel"
      (let [edn-strings-out (chan 1)
            clojure-in      (edn-encode> edn-strings-out)]
        (>!! clojure-in {:abc 123 :foo '(a b c)}) => truthy
        (<!! edn-strings-out) => "{:abc 123, :foo (a b c)}"))


(fact "edn-decode< accepts EDN strings on the given channel and outputs Clojure on the returned channel"
      (let [edn-strings-in (chan 1)
            clojure-out    (edn-decode< edn-strings-in)]
        (>!! edn-strings-in "{:abc 123, :foo (a b c)}") => truthy
        (<!! clojure-out) =>  {:abc 123 :foo '(a b c)}))


(fact "edn-decode> accepts EDN strings on the returned channel and outputs Clojure on the given channel"
      (let [clojure-out    (chan 1)
            edn-strings-in (edn-decode> clojure-out)]
        (>!! edn-strings-in "{:abc 123, :foo (a b c)}") => truthy
        (<!! clojure-out) =>  {:abc 123 :foo '(a b c)}))
