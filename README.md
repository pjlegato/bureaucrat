# bureaucrat
## An MQ-based API Router for Clojure

Bureaucrat is a Clojure utility library for constructing message queue
based APIs. It provides an abstraction over MQ-based communications,
implementations for various backend MQs, and a small router that
dispatches incoming API messages to designated Clojure functions.

### Why the name "bureaucrat"?
The library merely shuttles messages around between endpoints, which
actually perform the useful work.

## Overview
The main abstraction is `bureaucrat.endpoint/IQueueEndpoint`. It
defines a minimal set of abstractions that all queue backends must
implement.

An implementation is provided in `bureaucrat.endpoints.hornetq` that
uses the [Immutant application container's](http://immutant.org/)
built-in message queue, [HornetQ](http://www.jboss.org/hornetq). (It
currently does not support standalone HornetQ, only the embedded
version supplied by Immutant.)

Next on the implementation list is [IronMQ](http://dev.iron.io/mq/).

## HornetQ Backend

To send and receive messages with HornetQ:

````
user> (require '[bureaucrat.endpoint :as endpoint])
nil
user> (require '[bureaucrat.endpoints.hornetq :as hornetq])
nil
user> (def queue (hornetq/start-hornetq-endpoint! "an-example-endpoint"))
#'user/queue
user> (endpoint/send! queue "Hello, world!")
#<HornetQTextMessage HornetQMessage[ID:f81d4fae-7dec-11d0-a765-00a0c91e6bf6]:PERSISTENT>
user> (endpoint/receive! queue)
"Hello, world!"
 ````
 
`receive!` will block forever if not given a timeout. This is mainly
useful for testing. Real systems tend to use the version with a
timeout, which returns `nil` if no message has become available for
receipt in timeout milliseconds, or better yet the asynchronous
listener.

For example, to use `receive!` with a timeout of one second:

````
user> (endpoint/receive! queue 1000)
nil
````
 
No message was sent during the 1 second window, so `receive!` returned
nil.

### Asynchronous Message Receipt

To receive messages asynchronously, we can register a handler function
on the queue. When a message becomes available, the handler will be
invoked in a background thread with the message as its sole argument.

````
user> (endpoint/register-listener! queue (fn [x] (println "Handler got a message:" x)) 1)
#<MessageProcessorGroup org.immutant.messaging.MessageProcessorGroup@12345678>
user> (endpoint/send! queue "Hello, world!")
Handler got a message: Hello, world!
#<HornetQTextMessage HornetQMessage[ID:f81d4fae-7dec-11d0-a765-00a0c91e6bf6]:PERSISTENT>
````

### XA Transactions
Your handler function demarcates an
[XA distributed transaction](http://immutant.org/documentation/current/messaging.html#sec-3-2-1). In
short, all XA operations in the transaction either fail or succeed
atomically. If your handler (or any other XA participant) throws an
exception, the entire XA transaction will be aborted, all XA
operations participating in the transaction will be rolled back, and
the message will be placed back on the queue for some other handler to
handle. If all participants succeed, all of the XA operations will be
applied.

Any HornetQ messages you generate in the handler function will be
participants in the XA transaction, as well as database access
performed through XA-compatible JDBC drivers.

### The Dead Letter Queue

We want message delivery to be retried in cases where the failure is
transitory and not related to the message as such. For example, if a
particular processor node's server hardware fails or if someone
accidentally kills a server process while it's attempting to process a
message, we want the message to be replayed somewhere else.

If, however, a particular message is unprocessable by our code due to
some bug, there is no point in retrying the message forever since
it'll just fail again. Obvious bugs in message processors are
usually caught prior to production deploys (you have tests, right?),
but there are sometimes subtle bugs that are only triggered by certain
messages. That is, the message processor will usually work fine, but
occasionally some specific messages trigger a bug in the handler code
that throws an exception. Such messages are called "poison messages."

HornetQ deals with poison message detection by retrying the delivery
some fixed number of times (10 by default). If message delivery still
fails after that, the message is diverted to a special "dead letter
queue" instead of its intended destination, to prevent endless cycles
of bug triggering. The system operators are expected to monitor the
dead letter queue and fix the bug.

Every HornetQ system defines a default systemwide dead letter queue
called "DLQ". (Specific queues can
[http://immutant.org/builds/LATEST/html-docs/messaging.html#sec-2-3](optionally
override that) and define their own dead letter queue if they want.)

You can retrieve the DLQ associated with a particular queue with
`bureaucrat.endpoint/dead-letter-queue`. It returns an ordinary queue
component which you can read like any other.

### Utility functions

You can count the messages currently in a queue, and unconditionally
purge all messages in the queue.

````
user> (endpoint/count-messages queue)
1
user> (endpoint/purge! queue)
1
user> (endpoint/count-messages queue)
0
````

## IronMQ Backend
TODO.

## API Router
TODO.

## Component / Lifecycle Architecture

Bureaucrat's backend message queue adapters are implemented as
components within Stuart Sierra's
["Component"](https://github.com/stuartsierra/component) software
component lifecycle management framework. The use of this framework in
your code is entirely optional; Bureaucrat will work fine without it.

Each Bureaucrat component provides a constructor that returns an uninitialized
component as well as a convenience method that returns an initialized
component. The uninitialized version is to be used with the Component
lifecycle management library; Component will start and stop it
automatically. The initialized version returned by the convenience
method can be used directly in cases where Component is not in use.

For example, `bureaucrat.endpoints.hornetq/hornetq-endpoint` returns a
record that has not yet been connected to the HornetQ backend. This
can be incorporated into Component orchestrations directly, like any
other. If you are not using Component in your program, you should
instead use the convenience method
`bureaucrat.endpoints.hornetq/start-hornetq-endpoint!`, which creates
the HornetQ adapter component and also starts it for you (that is,
connects it to the HornetQ backend.)


## License

Copyright © 2014 Paul Legato.

Distributed under the Eclipse Public License, the same as Clojure.
