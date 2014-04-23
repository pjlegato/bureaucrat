(ns com.paullegato.bureaucrat.middleware.base64
  "Middleware to perform Base64 transformations on core.async messages."
  (:require [clojure.core.async :as async :refer [map> map<]]
            [clojure.data.codec.base64 :as b64]))


(defn to-base64
  "Like the b64 encoding function, but returns a UTF-8 string rather than a byte array.
  Input can be either a String or a byte array."
  [input]
  (let [input (if (string? input)
                (.getBytes input)
                input)]
    (String. (b64/encode input) "UTF-8")))


(defn base64-encode<
  "Accepts byte arrays or Strings on the given channel,
   Base64 encodes each message, and outputs the resulting string to the returned channel."
  [source-channel]
  (map< to-base64 source-channel))


(defn base64-encode>
  "Returns a new channel that accepts byte arrays or Strings, Base64 encodes them,
   and outputs the result to the given target channel."
  [target-channel]
  (map> to-base64 target-channel))


(defn base64-decode<
  "Reads Base64-encoded strings from the given source channel, decodes them,
   and outputs a byte array to the returned channel. You have to make
   this into a String yourself, if it is one!"
  [source-channel]
  (map< #(b64/decode (.getBytes ^String %)) source-channel))


(defn base64-decode>
  "Returns a new channel that accepts Base64-encoded strings, decodes them,
   and outputs a byte array to the given target channel.
   You have to turn that into a String yourself, if it is one!"
  [target-channel]
  (map> #(b64/decode (.getBytes ^String %)) target-channel))
