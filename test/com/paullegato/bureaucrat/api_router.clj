(ns com.paullegato.bureaucrat.api-router

  "An API router is a software component that accepts messages from an
  endpoint, routes them to corresponding Clojure functions, and
  optionally sends the return value of the function back to the caller
  as a reply message.


  Every API router exposes a function which is called with specially
  formatted messages as its sole argument. The router validates the
  structure of the message, which must include a named API call within
  it. The router then determines whether it can find a Clojure
  function that is designated as the handler function for that API
  call. If so, the router invokes that function, passing any payload
  from the message as its argument. If the function returns a value
  and the caller expects a reply, the router dispatches a reply message")
