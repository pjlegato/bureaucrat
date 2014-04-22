(ns com.paullegato.bureaucrat.middleware.json
  "Middleware to perform JSON transformations on core.async messages.

   JSON doesn't support keywords or symbols, so these get translated 
   into strings when JSONified.

   When decoding JSON, we have no way of knowing what strings are supposed
   to be keywords or symbols, so all input strings stay strings.

   As a special case, the parser will translate strings that are map keys
   into keywords for us."
  (:require [clojure.core.async :as async :refer [map> map<]]
            [cheshire.core :as json :refer [generate-string parse-string]]))


(defn json-encode<
  "Accepts arbitrary Clojure data on the given channel,
   JSON encodes it, and outputs the resulting strings to the returned channel."
  [source-channel]
  (map< generate-string source-channel))


(defn json-encode>
  "Returns a new channel that accepts arbitrary Clojure data, JSON encodes it, 
   and outputs it to the given target channel."
  [target-channel]
  (map> generate-string target-channel))


(defn json-decode<
  "Reads strings from the given source channel, JSON decodes them,
   and outputs them to the returned channel."
  [source-channel]
  (map< #(parse-string % true) source-channel))


(defn json-decode>
  "Returns a new channel that accepts strings, JSON decodes them,
   and outputs them to the given target channel."
  [target-channel]
  (map> #(parse-string % true) target-channel))

