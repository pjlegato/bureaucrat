(ns unit.com.paullegato.bureaucrat.middleware.base64
  "Tests the base64 middleware."
  (:require [onelog.core :as log]
            [clojure.core.async :as async :refer [<!! >!! chan]])
  (:use [midje.sweet]
        [com.paullegato.bureaucrat.middleware.base64]
        [helpers.bureaucrat.test-helpers]))

(def test-bytes (byte-array [1 2 3 4 5 6 7 8 9 100 200 255 99 88 77 66]))
(def test-bytes-base64 "AQIDBAUGBwgJZMj/Y1hNQg==")

(def test-string "Hello, world!")
(def test-string-base64 "SGVsbG8sIHdvcmxkIQ==")


(fact "base64-encode< accepts Strings on the given channel and outputs
       a Base64 encoded string on the returned channel"
      (let [plain-in   (chan 1)
            base64-out (base64-encode< plain-in)]
        (>!! plain-in test-string) => truthy
        (<!! base64-out) => test-string-base64))


(fact "base64-encode< accepts byte arrays on the given channel and outputs
       a Base64 encoded string on the returned channel"
      (let [plain-in   (chan 1)
            base64-out (base64-encode< plain-in)]
        (>!! plain-in test-bytes) => truthy
        (<!! base64-out) => test-bytes-base64))


(fact "base64-encode> accepts Strings on the returned channel and outputs
       a Base64 encoded string on the given channel"
      (let [base64-out (chan 1)
            plain-in   (base64-encode> base64-out)]
        (>!! plain-in test-string) => truthy
        (<!! base64-out) => test-string-base64))


(fact "base64-encode> accepts byte arrays on the returned channel and outputs
       a Base64 encoded string on the given channel"
      (let [base64-out (chan 1)
            plain-in   (base64-encode> base64-out)]
        (>!! plain-in test-bytes) => truthy
        (<!! base64-out) => test-bytes-base64))



(fact "base64-decode< accepts base64 strings on the given channel and outputs
       a decoded string on the returned channel"
      (let [base64-in (chan 1)
            plain-out (base64-decode< base64-in)]
        (>!! base64-in test-string-base64) => truthy
        (String. (<!! plain-out)) => test-string))


(fact "base64-decode> accepts base64 strings on the returned channel and outputs
       a decoded string on the given channel"
      (let [plain-out (chan 1)
            base64-in (base64-decode> plain-out)]
        (>!! base64-in test-string-base64) => truthy
        (String. (<!! plain-out)) => test-string))




(fact "base64-decode< accepts base64 strings on the given channel and outputs
       a decoded byte array on the returned channel"
      (let [base64-in (chan 1)
            plain-out (base64-decode< base64-in)]
        (>!! base64-in test-bytes-base64) => truthy
        (vec (<!! plain-out)) => (vec test-bytes)))


(fact "base64-decode> accepts base64 strings on the returned channel and outputs
       a decoded byte array on the given channel"
      (let [plain-out (chan 1)
            base64-in (base64-decode> plain-out)]
        (>!! base64-in test-bytes-base64) => truthy
        (vec (<!! plain-out)) => (vec test-bytes)))


(fact "we can decode our own coding"
      (let [input (chan 1)
            pipeline (-> input
                         base64-encode<
                         base64-decode<)]
        
        (>!! input test-bytes) => truthy
        (vec (<!! pipeline)) => (vec test-bytes)))
