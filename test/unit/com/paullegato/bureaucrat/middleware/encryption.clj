(ns unit.com.paullegato.bureaucrat.middleware.encryption
  "Tests the encryption middleware."
  (:require [onelog.core :as log]
            [lock-key.core :refer [decrypt encrypt]]
            [clojure.core.async :as async :refer [<!! >!! chan]])
  (:use [midje.sweet]
        [com.paullegato.bureaucrat.middleware.encryption]
        [helpers.bureaucrat.test-helpers]))

(def plaintext "Hello, world!")
(def test-key "test key")

(fact "encrypt< accepts Strings on the given channel and outputs encrypted bytes on the returned channel"
      (let [string-in           (chan 1)
            encrypted-bytes-out (encrypt< test-key string-in)]
        (>!! string-in plaintext) => truthy
        (String. (decrypt (<!! encrypted-bytes-out) test-key)) => plaintext))


(fact "encrypt> accepts Strings on the returned channel and outputs encrypted bytes on the given channel"
      (let [encrypted-bytes-out (chan 1)
            string-in           (encrypt> test-key encrypted-bytes-out)]
        (>!! string-in plaintext) => truthy
        (String. (decrypt (<!! encrypted-bytes-out) test-key)) => plaintext))


(fact "decrypt< accepts encrypted bytes the on given channel and outputs decrypted bytes on the returned channel"
      (let [encrypted-bytes-in   (chan 1)
            decrypted-bytes-out  (decrypt< test-key encrypted-bytes-in)]
        (>!! encrypted-bytes-in (encrypt plaintext test-key)) => truthy
        (String. (<!! decrypted-bytes-out)) => plaintext))


(fact "decrypt> accepts encrypted bytes the on returned channel and outputs decrypted bytes to the given channel"
      (let [decrypted-bytes-out (chan 1)
            encrypted-bytes-in  (decrypt> test-key decrypted-bytes-out)]
        (>!! encrypted-bytes-in (encrypt plaintext test-key)) => truthy
        (String. (<!! decrypted-bytes-out)) => plaintext))


(fact "we can decode our own coding"
      (let [input (chan 1)
            pipeline (->> input
                         (encrypt< test-key)
                         (decrypt< test-key))]
        
        (>!! input plaintext) => truthy
        (String. (<!! pipeline)) => plaintext))
