(ns com.paullegato.bureaucrat.api-router
  "An API router is a component that maps incoming messages to Clojure
  functions, optionally sending the return value of the function as a
  reply message.

  The router's main documentation is below, in the IAPIRouter docstring.
")


(defprotocol IAPIRouter
  "An API router is a component that maps incoming messages to Clojure
  functions, optionally sending the return value of the function as a
  reply message.

  Protocol
  ========

  Each API router instance exposes a function that accepts one
  argument, an incoming message. 

  If the message conforms to the  \"Bureaucrat API format\" (defined
  below), the router attempts to look up a function that corresponds
  to the message's specified API call. If a corresponding function is
  found, it is invoked with the message's payload as its argument. 

  The exact mechanism by which functions are looked up is an implementation 
  detail. Two implementations are provided with Bureaucrat. One uses a map 
  as a lookup table; the other uses metadata tags attached to functions to 
  mark valid API handlers.

  If the message includes a `:reply-to` key, the function's return value is
  dispatched back to the caller on the same underlying IMessageTransport where
  the original message arrived.

  The table-api-router
  must be given a map at instantiation that translates API calls to
  functions. 

  The metadata-api-router simply looks for a Clojure function named by
  the caller. If one is found, it checks the function's metadata for
  the `:api` key. If this is truthy, the function is used as an API
  handler. This has the advantage of being DRY and of allowing for
  dynamic extension. The disadvantage is the lack of a central
  repository of all valid API functions anywhere in the code, making
  it easier to forget about an obsolete API call somewhere that may
  introduce security or functionality issues.

  Bureaucrat API Format
  =======================

  All API call messages must be maps. Some keys have special meaning:

  * `:call` - the name of the API call to invoke. Required.
  * `:reply-to` - optional; if given, the return value of the API
    function will be sent to the named endpoint on the same transport.
  * `:correlation-id` - optional; an arbitrary string that the caller
    can use to correlate replies with their source messages. If
    present, the router will set the same `:correlation-id` on
    replies.
  * `:payload` - optional; arbitrary data to pass to the API handler
    function."

  (process-message! [component message]
    "Invokes the API call requested in the given message, if allowed.
    If an appropriate handler function cannot be found or if the
    handler throws an exception, logs this fact and places the message
    onto its transport's dead letter queue.")


  (handler-for-call [component call]
    "Returns the handler function that would be used for the given API
    call.")

  (process-unhandled-message! [component message]
    "Called by default if handler-for-call returns nil for a given call.")  )


(defprotocol IAdjustableAPIRouter
  "Protocol for adding and removing API handler methods. Not all API
  routers will be adjustable at runtime, or capable of enumerating 
  their set of valid handlers."

  (add-handler! [component api-call function]
    "Defines function as the handler for api-call, replacing any
    previous handler for api-call.")

  (remove-handler! [component api-call]
    "Removes the API handler for api-call."))


(defprotocol IListableAPIRouter
  "For routers that can enumerate their routes."
  (list-handlers! [component]
    "Returns the set of all valid API handlers as a map. Keys are
    calls, values are functions."))
