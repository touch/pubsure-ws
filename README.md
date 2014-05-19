# pubsure-ws

A Websocket implementation for the `Source` protocol of [pubsure](#), and separately, a HTTP/Websocket server exposing a `DirectoryReader` implementation. Both use the [WAMP v1](#) specification for communication. You need to understand this spec, at least on a high level, to use these Pubsure implementations. One can use for example the [Autobahn](http://autobahn.ws/) libraries to "talk" WAMP.

## Integration

Add `[pubsure/pubsure-ws "0.1.0-SNAPSHOT"]` to your dependencies in Leiningen.

## The `Source` server

### Starting and stopping

1. Create a `DirectoryWriter` implementation (for example using [pubsure-zk](#))
2. Call `(def s (pubsure-ws.source/start-source directory-reader [options]))`. There are multiple options available, as specified below.
3. Call `(pubsure-ws.source/stop-source s)` to stop the source.

#### Start options

* `:port` - The port is where the server will bind to. Default is 8090.
* `:uri` - The websocket URI to be registered in the directory service. Default is `ws://<system-hostname>:<port>`.
* `:cache-size` - The number of last published messages to keep per topic. These messages can be retrieved using WAMP RPC (see below). Default is 0.
* `:clean-cache-on-done` - A boolean indicating whether to clean the cache of messages for a topic, when the `done` function is called for that topic. Default is false.
* `:summary-fn` - A function that updates a summary value for each topic on every published message. This summary can be retrieved using WAMP RPC (see below). The function takes two arguments; the current summary (which may be nil), and the published message. Default is no function.
* `:clean-summary-on-done` - A boolean indicating whether to clean the summary for a topic, when the `done` function is called for that topic. Default is false.
* `:wrap-fn` - A ring wrapper function, which wraps the standard request handling function. This can be used for instance for authentication. Default is no function.
* `:done-payload` - If specified, the given value will be published to a topic when the `done` function is called for that topic. By default this option is not used.
* `:auth-fn` - This function checks whether a connection may subscribe, retrieve a cache, or retrieve a summary for a particular topic. The function takes the original request, the topic, and a keyword for the requested function (`:subscribe`, `:cache` or `:summary`) as its parameters. The function should return true to allow, false to deny. Default is `(constantly true)`.

### Usage

Open a Websocket connection to `ws://<host>:<port>/[topic]`. The `[topic]` part is optional. When supplied, the connection is automatically subscribed to that topic.

In order to be informed of source updates, one needs to send a WAMP subscribe request to the server. A client can subscribe to multiple topics.

#### RPC call - `cache`

In order to receive the cached messages for a topic, one can call the `"cache"` RPC function. It expects three parameters. The first is the topic name. The second is the number of cached messages one desires to receive. This number needs to be within the bounds of the `:cache-size` start option. The third is a boolean indicating whether to publish the messages. When set to true, the messages will be send to the client as WAMP EVENT messages, as if they are published at that time. Make sure the client is subscribed to the topic at hand when doing this. If the boolean is set to false, the response of the RPC call will hold an array with the messages.

#### RPC call - `summary`

One can call the `"summary"` RPC function in order to receive the summary object. If the source is not started with the `:summary-fn` start option, then an error is returned. The RPC function takes the topic name as its sole argument.

## The `DirectoryReader` server

### Starting and stopping

1. Create a `DirectoryReader` implementation (for example using [pubsure-zk](#))
2. Call `(def s (pubsure-ws.reader/start-server directory-reader :port 8091 :subscribe-buffer 100))`, where the `:port` and `:subscribe-buffer` options are optional. The values shown above are the defaults. The port is where the server will bind to. The subscribe-buffer is the size of the [sliding buffer](#) of the core.async channel used for receiving source updates from the `DirectoryReader`.
3. Call `(pubsure-ws.reader/stop-server s)` to stop the server.

### Usage

#### Single batch request

Perform a HTTP GET request to `http://<host>:<port>/<topic>` to receive all currently known sources of the given topic. The response is a JSON array, holding strings with URIs.

For example:

```
> curl localhost:8091/foo
["ws://source-1", "ws://source-2"]
```

#### Continuously updated

Open a Websocket connection to `ws://<host>:<port>/[topic]`. The `[topic]` part is optional. When supplied, the connection is automatically subscribed to that topic.

In order to be informed of source updates, one needs to send a WAMP subscribe request to the server. A client can subscribe to multiple topics. The object in the WAMP EVENT messages, contain the source updates. These are in the form of:

```
{"event": <event>, "uri": <uri>}
```

The `<event>` value is either `"joined"` or `"left"`, and the `<uri>` value is a string holding the URI. Note that theoretically, some updates from the `DirectoryReader` might get lost on slow connections, in case these updates are happening in one big burst and this burst exceeds the `:subscribe-buffer` size (see above).

##### RPC call - `sources`

The WAMP connection supports one RPC call, called `"sources`". It returns the currently known sources for a topic. It expects two parameters. The first is the topic name, and the second is a boolean indicating whether to publish the sources. When set to true, the sources will be send to the client as WAMP EVENT messages, as above. Make sure the client is subscribed to the topic at hand. If the boolean is set to false, the response of the RPC call will hold an array with the source URIs.

## License

Copyright © 2014

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
