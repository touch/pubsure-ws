# pubsure-ws

A Websocket implementation for the `Source` protocol of [pubsure](#), and separately, a HTTP/Websocket server exposing a `DirectoryReader` implementation.

## Integration

Add `[pubsure/pubsure-ws "0.1.0-SNAPSHOT"]` to your dependencies in Leiningen.

## The `Source` server

### Starting and stopping



### Usage

How should client connect.

## The `DirectoryReader` server

### Starting and stopping

1. Create a `DirectoryReader` implementation (for example using [pubsure-zk](#))
2. Call `(def s (pubsure-ws.reader/start-server directory-reader :port 8091 :subscribe-buffer 100))`, where the `:port` and `:subscribe-buffer` options are optional. The values shown are the defaults. The port is where the server will bind to. The subscribe-buffer is the size of the [sliding buffer](#) of the core.async channel used for receiving source updates from the `DirectoryReader`.
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

Open a Websocket connection to `ws://<host>:<port>/[topic]`. The `[topic]` part is optional. When supplied, the connection is automatically subscribed to that topic. To subscribe after the connection is setup, send a JSON request in the form of:

```
{"action": "subscribe", 
 "data": {"topic": <topic>, "init": <init>}}
```

This will subscribe the connection to source updates concerning the given `<topic>`. The `<init>` parameter can be one of the following:

* `"all"` - receive all currently known sources for the topic on subscribe
* `"last"` - receive the last joined source for the topic on subscribe
* `"random"` - receive a random source for the topic on subscribe

A client can subscribe to multiple topics. Source updates sent back to the client will be in the form of:

```
{"event": <event>,
 "data": {"topic": <topic>, "uri": <uri>}}
```

The `<event>` value is either `"joined"` or `"left"`, and the `<uri>` value is a string holding the URI. Note that theoretically, some updates from the `DirectoryReader` might get lost on slow connections, in case these updates are happening in one big burst and this burst exceeds0the `:subscribe-buffer` size (see above). 

To unsubscribe, send a JSON request in the following form:

```
{"action": "unsubscribe", 
 "data": {"topic": <topic>}}
```

## License

Copyright © 2014

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
