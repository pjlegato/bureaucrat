(ns com.paullegato.bureaucrat.middleware.encryption
  "Middleware to perform AES encryption and decryption on core.async messages
   using a pre-shared key."
  (:require [clojure.core.async :as async :refer [map> map<]]
            [lock-key.core :refer [decrypt encrypt]]))


(defn encrypt<
  "Accepts byte arrays or Strings on the given channel, encrypts them with the given key, then
   outputs the resulting byte arrays to the returned channel."
  [key source-channel]
  (map< #(encrypt % key) source-channel))


(defn encrypt>
  "Returns a new channel that accepts arbitrary Clojure data, Base64 encodes it, 
   and outputs it to the given target channel."
  [key target-channel]
  (map> #(encrypt % key) target-channel))


(defn decrypt<
  "Reads strings from the given source channel, Base64 decodes them,
   and outputs them to the returned channel."
  [key source-channel]
  (map< #(decrypt % key) source-channel))


(defn decrypt>
  "Returns a new channel that accepts strings, Base64 decodes them,
   and outputs them to the given target channel."
  [key target-channel]
  (map> #(decrypt % key) target-channel))

