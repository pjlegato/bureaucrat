# bureaucrat
## An MQ-based API Router for Clojure

Bureaucrat is a Clojure utility library to ease the construction of
asynchronous message queue based APIs.

It provides an abstraction over MQ-based communications,
implementations for various backend MQs, a tiny router that
dispatches incoming API messages to designated Clojure functions,
optionally sending replies back to the caller, and a core.async based
middleware system for plumbing this all together in arbitrary ways.

Bureaucrat is alpha-stage software; API changes are still
possible. Pull requests are welcome!

### Why the name "bureaucrat"?
The library merely shuttles messages around between endpoints, which
actually perform the useful work.

### What underlying transport mechanisms are supported?
Bureaucrat currently supports IronMQ. 

Previous versions supported HornetQ. Updating this to modern
Bureaucrat style has been put on hold pending the release of Immutant
2.0.

Core.async and Amazon SQS are planned. Adding new endpoints is fairly
easy; pull requests with tests are welcome! :)

## Layers of Bureaucracy

Bureaucrat can be used on several different levels. Each level
provides additional services beyond those in the lower levels, while
imposing additional constraints on what sort of messages can be used
on that level.

Layers (other than the bottommost Transport Layer) are implemented as
core.async middleware; you use core.async channels to plumb them
together in arbitrarily complex fashion, according to your needs. Many
middleware components are provided, and it's easy to write more.


* Transport layer - send and receive strings or byte arrays.

An overview is provided in this README. For additional details,
consult the docstrings in the source files.




## Transport Layer

The lowest specification level is the transport layer, embodied in the
`IMessageTransport` and `IQueueEndpoint` protocols. This level
provides a unified abstraction over named queue-like asynchronous
endpoints. The endpoints must be able to send and receive non-nil data
to and from a named endpoint on the transport mechanism. No other
constraints on message format exist at this layer.

`IMessageTransport` defines a system for creating and destroying
uniquely named `IQueueEndpoints`. These map directly onto an
asynchronous message queueing system and its queues. For example, we
can build an `IMessageTransport` implementation encapsulating HornetQ,
IronMQ, core.async, ZeroMQ, or similar services; the `IQueueEndpoints`
are specific named queues or commmunications channels implemented by
those services.

`IQueueEndpoints` are created by an `IMessageTransport`
implementation, and represent specific named queues on that
transport. `IQueueEndpoints` are able to send and receive messages,
arbitrary data structures whose format is subject to the limitations
of the underlying transport mechanism. For example, an IronMQ-based
`IMessageTransport` can only transmit string messages, while a
core.async `IMessageTransport` can transmit any Clojure data structure
as a message.

nil and empty string messages are specifically disallowed (because
they confuse code that uses nil for other purposes); all messages must
be non-nil and non-zero length.

## Channel Layer

Above the Transport Layer is the Channel Layer, defined by the
`IChannelEndpoint` protocol. At this level, endpoints must be capable
of enqueueing and dequeueing messages via core.async channels.

The `channel-endpoint` namespace provides the `IChannelEndpoint`
protocol and functions that produce send and receive channels from any
`IQueueEndpoint`. Various serialization middleware is provided


## Channel Middleware

Various middleware adapters are provided to perform transformations on
core.async data flows, and it's easy to write more.

### Serialization Middleware

Middleware is provided to serialize binary data into EDN, JSON, and
Base64 formats, and to deserialize strings in those formats. These can
be attached to any `IChannelEndpoint`.

If you need to interoperate with another system that requires JSON
messages, for example, you can simply drop the JSON serialization
middleware into your outbound message pipeline, then write regular
Clojure data structures to the pipeline.

### Encryption Middleware

An AES-128 shared-secret / symmetric encryption middleware component
is provided to encrypt and decrypt messages.


## Data Layer

At the Data Layer, defined by the `IDataEndpoint` protocol, endpoints
must be capable of transmitting general Clojure data structures such
as maps and strings as messages, via core.async channels.

Transport Layer implementations that are incapable of transmitting
Clojure data structures as messages directly can use middleware to
extend their capabilities. For example, an IronMQ transport can only
transmit strings as messages, but the EDN or JSON middlewares can be
used to serialize Clojure data into strings and back for transmission
on IronMQ.

Still other Transport Layers may already be capable of transmitting
Clojure data structures, and will only require a thin wrapper to tie
them to core.async channels.

All maps at this layer must have keyword keys. JSON does not support
keywords, so keyword keys are typically serialized as strings when
they become JSON.

The requirement that all Channel Layer compliant endpoints expose
core.async connectors allows them to easily participate in complex
middleware graphs.


## Bureaucrat Layer

The Normalized Layer defines a standard message format for use by
higher-order Bureaucrat services called the "Bureaucrat low-level
format": all messages must be Clojure maps, and certain keys are
defined to store specific metadata as their values.

Middleware is provided to translate messages into this format and
decorate them with metadata. If the incoming message was not a map, a
new map is created, and the original message is added as the value of
the `:payload` key.

A `:bureaucrat` key is added to the message for internal use by
Bureaucrat components. User code should not rely on finding any
particular value in this key, as it is not part of the public API and
can change at any time. Currently, it contains a reference to the
IQueueEndpoint where the message entered Bureaucrat, so that services
such as the API router can send reply messages on the same transport.

An egress normalizer is also provided that strips the `:bureaucrat`
key out of any messages that pass through it. This message format may
be called the "low-level inter-service format". It is identical to the
"low-level Bureaucrat format" other than the absence of the
`:bureaucrat` key.

## Bureaucrat API Router Layer

The Bureaucrat API message format is defined as an extension of the
Bureaucrat low-level format defined above, with additional special
keys.

The `:call` key is required; its value specifies the API call that is
being made. The router looks up the appropriate handler function for a
given call and executes it. The optional but usually present
`:payload` key is passed to the handler function as its argument. If
the message requires a reply and the function returns a non-nil value,
the API router creates a new message on the same transport, addressed
to the endpoint named in the `:reply-to` field of the incoming message.


### API Router Details

The API router accepts messages from a core.async middleware pipeline
and routes them to designated functions for processing. 

Messages fed into the API router must be in "Bureaucrat API
format". This format is an extension of the Bureaucrat low-level
format. Besides being maps, messages must have at least the `:call`
key. Its value specifies what API call to make.

The Bureaucrat API format defines several additional optional keys:
  * `:reply-to` is a string endpoint name where replies to the call
     should be sent. The system will send them on the same
     IMessageTransport that the message came in on.
  * `:reply-call` is an optional `:call` to specify in the reply message.
  * `:payload` is arbitrary data to be used by the API call.
  
API handlers are ordinary Clojure functions. The router protocol
receives messages and 

Messages arrive on the endpoint from somewhere -- exactly where is
deliberately undefined, to promote loose coupling between
components. Bureaucrat dispatches the message payload to the
appropriate handler function as its argument. Your app performs
app-specific work in the handler function. If the incoming message
specified a `:reply-to` address and the handler function returns a
value, Bureaucrat will send that value to the queue named in
`:reply-to`.

Payloads can be any arbitrary EDN-serializable data.

If you later want to run the same API on a different transport, it's
as easy as swapping in a new IQueueEndpoint component. You can even
run the same API handlers simultaneously on multiple transports!

### `IQueueEndpoint` Protocol
The lowest level abstraction is `bureaucrat.endpoint/IQueueEndpoint`. It
defines a minimal set of messaging functionality that all queue backends must
implement, such as sending and receiving messages. At this level,
messages are treated as arbitrary blobs of EDN-serializable data and
no special structure is imposed on the messages.

Implementations are provided in `bureaucrat.endpoints.*` that
use the [Immutant application container's](http://immutant.org/)
built-in message queue, [HornetQ](http://www.jboss.org/hornetq) and
[IronMQ](http://dev.iron.io/mq/).

## `IAPIRouter` Protocol

The IAPIRouter protocol provides a generic mechanism for mapping
specially formatted messages to Clojure function calls and vice
versa.

To use IAPIRouter, your messages must be specially formatted rather
than generic EDN blobs. All routable messages must be maps. The only
required key is `:call`. Its value specifies the API call that the
message wishes to invoke.

The exact way this call name is mapped to a handler function is implementation
specific. Implementations are provided that allow you to supply a map
of keywords to functions to be used as a routing table, and to
annotate Clojure functions with the `:api` metadata to allow them to
be called by incoming messages automatically if the incoming message
knows the function's name.

If the calling message includes the optional key `:reply-to` and the
handler function returns a value, the API router will look up a queue
with the given name on the same transport where the message was
received (possibly the same queue), encode the returned value as the
`:payload` of a map, and send it to that queue.


## Example Endpoint: HornetQ Backend

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
When using HornetQ, your handler function demarcates an
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

Note that some backends such as IronMQ do not have a built-in DLQ, so
we simulate one in our wrapper.

## Utility functions

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

## Provided Endpoint Implementations

### IronMQ

You must specify an IronMQ project ID, OAuth2 token, and server
hostname to use. This can be done by creating an ~/.iron.json file, by
providing arguments to (ironmq-transport), or by setting the
environment variables expected by the IronMQ library before running
your Clojure process. The config file and env variables are described
in detail at [http://dev.iron.io/worker/reference/configuration/](IronIO's website).

I find it most convenient to use ~/.iron.json for development and
environment variables for production. These both avoid having
credentials in your source code, and env variables map nicely into 12 factor app harnesses.

The relevant env variables are:

  * `IRON_PROJECT_ID` - set to the project ID from your IronMQ account.
  * `IRON_TOKEN` - set to your IronMQ secret access token.


## Security
Security is handled at the transport layer, and not by Bureaucrat. Any
message that gets into an incoming message queue will be processed by
Bureaucrat, no questions asked. It is up to your application to ensure
that bad actors cannot access your queues, or to preprocess your
messages somehow to ensure that only authorized users can do things.

## Garbage collector performance note

The default JVM garbage collector is a "stop the world" type
collector. This means that when your JVM is close to running out of
memory, it will pause your program's execution to perform a
collection. The length of the pause depends on how much memory you've
used; it can range from seconds to minutes for a very large program.

This is generally highly undesirable with server software. It can
cause noticeable lags in execution that cascade to other systems which
timeout while waiting for an async reply. I recommend running
Bureaucrat with a different GC mode that doesn't stop the world for so long.

For example, you can add something like the following to your
`project.clj`. You may want to tweak the 512MB of memory as your app
requires.

````
  :jvm-opts ["-d64" "-Duser.timezone=GMT" "-server" "-Djava.awt.headless=true" "-Dfile.encoding=utf-8"
             "-Xmx512m" "-Xms512m" "-XX:+UseG1GC"]
```


## Component / Lifecycle Architecture

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


## Contributing

### Roadmap
* HTTP transport
* HornetQ
* Amazon SQS
* Provide additional canned enterprise messaging widgets for routing
  and transformation beyond the API router.
* ZeroMQ
* Nanomsg
* IronMQ push mode (by exposing a webhook)



## License

Copyright © 2014 Paul Legato.

Distributed under the Eclipse Public License, the same as Clojure.
