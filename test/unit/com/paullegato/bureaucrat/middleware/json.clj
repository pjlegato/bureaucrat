(ns unit.com.paullegato.bureaucrat.middleware.json
  "Tests the JSON transcoding middleware."
  (:require [onelog.core :as log]
            [clojure.core.async :as async :refer [<!! >!! chan]])
  (:use [midje.sweet]
        [com.paullegato.bureaucrat.middleware.json]
        [helpers.bureaucrat.test-helpers]))

(def raw-map {:abc 123 :foo '(a b c) :bar "baz"})
(def encoded-string "{\"abc\":123,\"bar\":\"baz\",\"foo\":[\"a\",\"b\",\"c\"]}")

;; JSON doesn't support symbols, so we get strings back for them.
;; It also doesn't support keywords, but we set the parser to make
;; map keys into keywords automatically.
(def raw-map-with-string-symbols {:abc 123 :foo '("a" "b" "c") :bar "baz"})


(fact "json-encode< accepts Clojure on the given channel and outputs JSON strings on the returned channel"
      (let [clojure-in      (chan 1)
            json-strings-out (json-encode< clojure-in)]
        (>!! clojure-in raw-map) => truthy
        (<!! json-strings-out) => encoded-string))


(fact "json-encode> accepts Clojure on the returned channel and outputs JSON strings on the given channel"
      (let [json-strings-out (chan 1)
            clojure-in      (json-encode> json-strings-out)]
        (>!! clojure-in raw-map) => truthy
        (<!! json-strings-out) => encoded-string))


(fact "json-decode< accepts JSON strings on the given channel and outputs Clojure on the returned channel"
      (let [json-strings-in (chan 1)
            clojure-out    (json-decode< json-strings-in)]
        (>!! json-strings-in encoded-string) => truthy
        (<!! clojure-out) =>  raw-map-with-string-symbols))


(fact "json-decode> accepts JSON strings on the returned channel and outputs Clojure on the given channel"
      (let [clojure-out    (chan 1)
            json-strings-in (json-decode> clojure-out)]
        (>!! json-strings-in encoded-string) => truthy
        (<!! clojure-out) =>  raw-map-with-string-symbols))
