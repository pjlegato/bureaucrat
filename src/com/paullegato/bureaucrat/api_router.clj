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

  Implementation
  =============

  Each API router instance exposes a function that accepts one
  argument, which is to be bound to an incoming message. If the
  message conforms to the specified API call message format (defined
  below), the router attempts to look up a function that corresponds
  to the message's specified API call. If a corresponding function is
  found, it is invoked with the message's payload as its argument. If
  the message requests a reply, the function's return value is
  dispatched back to the caller.

  The exact mechanism by which API calls named in messages are mapped
  to specific Clojure functions is an implementation detail. Two
  implementations are provided with Bureaucrat. 

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

  API Call Message Format
  =======================

  All API call messages must be maps. Map keys are automatically
  transformed into Clojure keywords by the router, so that non-Clojure
  systems can supply strings as keys.

  * `:call` - the name of the API call to invoke. Required.
  * `:reply-to` - optional; if given, the return value of the API
    function will be sent to the named queue.
  * `:correlation-id` - optional; an arbitrary string that the caller
    can use to correlate replies with their source messages. If
    present, the router will set the same `:correlation-id` on
    replies.
  * `:payload` - optional; arbitrary data to pass to the API handler
    function.
  
  Keys starting with `:x-` are reserved for internal use by
  Bureaucrat. 
 
  * :x-ingress-endpoint: a reference to the IQueueEndpoint that
    produced the message for us. This is currently used so that
    Bureaucrat can send erroneous messages to its dead letter queue. 
    The endpoint implementations are responsible for adding this.
"

  (process-message! [component message]
    "Processes the given message, which was received from the given
    endpoint. Message is a normalized message as returned by an
    IMessageNormalizer. Invokes the appropriate handler function for
    the :call defined in the message if possible. If there is no such
    handler function or the handler throws an exception, logs this
    fact and places the message onto its endpoint's dead letter
    queue.")


  (handler-for-call [component call]
    "Returns the handler function used for the given API call."))


(defprotocol IAdjustableAPIRouter
  "Protocol for adding and removing API handler methods. Not all API
  routers will be adjustable at runtime."

  (add-handler! [component api-call function]
    "Defines function as the handler for api-call, replacing any
    previous handler for api-call.")

  (remove-handler! [component api-call]
    "Removes the API handler for api-call."))
