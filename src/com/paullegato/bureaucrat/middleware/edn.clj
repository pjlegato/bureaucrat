(ns com.paullegato.bureaucrat.middleware.edn
  "Middleware to perform EDN transformations on core.async messages."
  (:require [clojure.core.async :as async :refer [map> map<]]
            [onelog.core :as log]
            [clojure.tools.reader.edn :as edn]))


(defn edn-encode<
  "Accepts arbitrary Clojure data on the given channel,
   EDN encodes it, and outputs the resulting strings to the returned channel."
  [source-channel]
  (map< pr-str source-channel))


(defn edn-encode>
  "Returns a new channel that accepts arbitrary Clojure data, EDN encodes it, 
   and outputs it to the given target channel."
  [target-channel]
  (map> pr-str target-channel))


(defn try-to-read-string
  [string]
  (try
    (edn/read-string string)
    (catch Throwable t
      (log/error "[bureaucrat][edn] Couldn't decode unreadable string: " string "\n" (log/throwable t)))))

(defn edn-decode<
  "Reads strings from the given source channel, EDN decodes them,
   and outputs them to the returned channel."
  [source-channel]
  (map< try-to-read-string source-channel))


(defn edn-decode>
  "Returns a new channel that accepts strings, EDN decodes them,
   and outputs them to the given target channel."
  [target-channel]
  (map> try-to-read-string target-channel))

