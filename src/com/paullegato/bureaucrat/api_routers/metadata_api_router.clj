(ns com.paullegato.bureaucrat.api-routers.metadata-api-router

  "API router (instance of IAPIRouter) that uses function metadata to
   determine valid API handler functions.

   Any Clojure function with the :api metadata key is a valid API
   handler. When given an API call message, the handler will execute
   any such function.

   prefix will be prepended to API callers' requests, to restrict
   their scope. For example, if your API handlers are all in
   foo.bar.api-handlers (e.g. foo.bar.api-handlers/some-function,
   foo.bar.api-handlers/other-function, and so on), you can supply a
   prefix of \"foo.bar.api-handlers/\", and clients then supply
   \"some-function\" and \"other-function\" as their calls. (Don't
   forget the trailing slash!)
"
  (:require [com.paullegato.bureaucrat.endpoint :as endpoint]
            [onelog.core :as log])

  (:use [com.paullegato.bureaucrat.api-router]
        [com.paullegato.bureaucrat.api-routers.api-router-helpers]
        [slingshot.slingshot :only [try+ throw+]]))


(defrecord MetadataAPIRouter
  [prefix]

  IAPIRouter 
  (process-message! [component message] 
    (try-to-process-message component message))
  

  (handler-for-call [component call]
    (if-let [call (str prefix (if (keyword? call)
                                (-> (str call)
                                    (.substring 1))
                                call))]
      (let [fn   (resolve (symbol call))
            api-allowed? (:api (meta fn))]
        (if api-allowed?
          fn
          (do
            (log/warn "[bureaucrat][metadata-api-router] Got a request for an invalid API call '" call "', rejecting!")
            nil)))))

  (process-unhandled-message! [component message]
    (process-unhandled-message component message)))


(defn metadata-api-router
  "Returns a new MetadataAPIRouter. The given prefix will be prepended
  to all API call namespace lookups."
  [prefix]
  (log/debug "[bureaucrat] MetadataAPIRouter starting with prefix " prefix)
  (map->MetadataAPIRouter {:prefix (str prefix)}))
