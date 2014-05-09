(ns com.paullegato.bureaucrat.middleware.normalizer
  "Middleware to coerce messages to the standard \"Bureaucrat low-level
  format\", called ingress normalizers; and to a standard
  \"low-level inter-service format\" (egress normalizers).

  The standard Bureaucrat internal format is a map with at least the
  `:bureaucrat` key. The standard inter-service format is a map
  without a `:bureaucrat` key.

  Higher optional layers of Bureaucrat, such as the API router, define
  additional constraints on message format beyond these when used.

  Normalizers are meant to be used when messages enter the system from
  the outside.  Upon ingress:

  * Messages will be converted into Clojure maps if they aren't maps
    already.  Non-maps will be placed in the output map's `:payload`
    key.

  * A `:bureaucrat` key will be added for internal use by
    Bureaucrat. User code should not use this key, as it isn't part of
    the public API and its structure is not guaranteed to remain the
    same. (Let me know if you think you can do something interesting
    with it, though.)

    It contains a non-serializable reference to the endpoint where the
    message entered the system, so the API router can send replies on
    the same transport.  Its value is a map with `:ingress-endpoint`
    and `:ingress-time` keys.

  Egress normalizers are meant to be used when messages in Bureaucrat
  format (i.e. maps) leave Bureaucrat for transmission to the
  outside. Currently, they strip the `:bureaucrat` key.

  These normalizers do not deal with serialization, encryption, Base64
  encoding, and other meta-operations. They deal only with the
  formatting of the message as a map. This is because some underlying
  endpoints support Clojure data directly, and don't need any of that,
  while others do.
"
  (:require [clj-time.core :as time]
            [onelog.core :as log]
            [clojure.core.async :as async :refer [map> map<]]))


(defn- normalize-ingress
  "Ensures that the given message is a map. If it is not, makes it a
  map, and stores the original message under the :payload key.

  Adds the :bureaucrat key to the result."
  ([ingress-endpoint message]
     (normalize-ingress ingress-endpoint message (time/now)))
  ([ingress-endpoint message ingress-time]
     (log/trace "[bureaucrat][normalizer] Normalizing the ingress of message: " message)
     (let [message (if (map? message)
                     message
                     {:payload message})]
       (assoc message :bureaucrat {:ingress-endpoint ingress-endpoint
                                   :ingress-time ingress-time}))))


(defn- normalize-egress
  "Strips the :bureaucrat key from the given message if it is a map."
  [message]
  (log/debug "[bureaucrat][normalizer] Normalizing the egress of message: " message)
  (if (map? message)
    (dissoc message :bureaucrat)
    message))


(defn normalize-ingress<
  "Accepts arbitrary messages on the given channel, performs ingress
  normalization on them, and outputs the resulting strings to the
  returned channel."
  [source-channel ingress-endpoint]
  (map< (partial normalize-ingress ingress-endpoint) source-channel))


(defn normalize-ingress>
  "Accepts arbitrary messages on the returned channel, performs ingress
  normalization on them, and outputs the resulting strings to the
  given channel."
  [out-channel ingress-endpoint]
  (map> (partial normalize-ingress ingress-endpoint) out-channel))


(defn normalize-egress<
  "Accepts arbitrary messages on the given channel, performs egress
  normalization on them, and outputs the resulting strings to the
  returned channel."
  [source-channel]
  (map< normalize-egress source-channel))


(defn normalize-egress>
  "Accepts arbitrary messages on the returned channel, performs egress
  normalization on them, and outputs the resulting strings to the
  given channel."
  [out-channel]
  (map> normalize-egress out-channel))
