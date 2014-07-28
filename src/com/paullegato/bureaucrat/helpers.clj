(ns com.paullegato.bureaucrat.helpers
  "User-facing convenience code to assist with Bureaucrat."
(:require [com.paullegato.bureaucrat.data-endpoint :refer [receive-channel]]
          [com.paullegato.bureaucrat.endpoint :refer [unregister-listener!]]))

(defmacro with-receive-channel 
  "Opens a core.async data-receive channel on the given Bureaucrat endpoint for
  the duration of the given forms. Ensures that the queue's listener is
  unregistered after forms with a try...finally.

  The first argument is a let-style vector specifying the
  queue-to-channel binding. The first element is the symbol to bind the
  receive channel to; the second argument is the Bureaucrat endpoint to
  derive the binding from.

  Example:

  (with-receive-channel [ch (get-a-bureaucrat-queue)]
     (println \"Got from queue:\" (<!! ch)))

  Here, `(get-a-bureaucrat-queue)` is some function that returns a
  Bureaucrat endpoint. The symbol ch will be bound to this endpoint via
  a core.async channel. In the code block, we take a value from the `ch`
  channel and print it. After our block is done, `with-receive-channel`
  macro unbinds the channel from the endpoint automatically.

  Note that any listener function already attached to the given endpoint
  will be overwritten by using it in this macro."
  [[channel queue] & forms]
  ;; TODO: Having to manually unregister a listener on the queue
  ;; is annoying. It'd be nice to have the listener unregistered
  ;; automatically when there are no more listeners on the receive channel.
  ;;
  ;; If we don't unregister, then the underlying receive poller
  ;; function leaks and hogs all the messages, making them
  ;; inaccessible to further code.

  ;; If queue is a function, we don't want to run it twice, so
  ;; we save the result in this let under queue#:
  `(let [queue# (do ~queue)]
     (try
       (let [~channel (receive-channel queue#)]
         ~@forms)
       (finally  (unregister-listener! queue#)))))
