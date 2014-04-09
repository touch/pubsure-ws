var reader = null;
var publishers = {};


function safeGet(obj, path) {
    if (obj) { return obj[path] } else { return obj }
}


function readerLog(str) {
    var current = $('#readerlog').val();
    $('#readerlog').val("[" + (new Date()).toTimeString() +"] " + str + "\n" + current);
}


function topicLog(str) {
    var current = $('#topiclog').val();
    $('#topiclog').val("[" + (new Date()).toTimeString() +"] " + str + "\n" + current);
}


function disconnect() {
    if (reader != null) reader.close();
    reader = null;
}


function connect() {
    disconnect();
    var host = $('#reader').val();
    reader = new ab.Session(host,
                            function() { readerLog("Connected to reader.") },
                            function(code) {
                                readerLog("Connection to reader closed, code: " + code);
                                reader = null;
                            },
                            {'skipSubprotocolCheck': true});
}


function publishEvent(topic, event) {
    topicLog("Published on '" + topic + "': " + JSON.stringify(event))
}


function readerEvent(topic, event) {
    if (event.event == "joined") {
        readerLog("A publisher for '" + topic + "' has joined at " + event.uri + ".");
        var connection = safeGet(publishers[event.uri], "_connection");
        if (connection == null || connection == undefined ||
            connection._websocket_connected == false) {
            readerLog("Connecting to publisher " + event.uri + " ...");
            connection = new ab.Session(event.uri,
                                        function() {
                                            readerLog("Connected to publisher: " + event.uri);
                                            publishers[event.uri] = {"_connection":  this};
                                            readerLog("Subscribing to topic '" + topic +"' at: "+ event.uri);
                                            this.subscribe(topic, publishEvent);
                                            publishers[event.uri][topic] = true;
                                            readerLog("Subscribed to topic '" + topic +"' at: "+ event.uri);
                                        },
                                        function(code) {
                                            readerLog("Connection to publisher " + event.uri +" closed, code: " + code);
                                        },
                                        {'skipSubprotocolCheck': true});
        } else {
            readerLog("Already an open connection to " + event.uri + " available.");
            readerLog("Subscribing to topic '" + topic +"' at: "+ event.uri);
            connection.subscribe(topic);
            publishers[event.uri][topic] = true;
            readerLog("Subscribed to topic '" + topic +"' at: "+ event.uri);
        }
    } else if (event.event == "left") {
        readerLog("The publisher for '" + topic + "' at " + event.uri + " has left.");
        var connection = safeGet(publishers[event.uri], "_connection");
        if (connection != null && connection != undefined &&  connection._websocket_connected == true) {
            connection.unsubscribe(topic);
            readerLog("Unsubscribed from topic '" + topic + "' at: "+ event.uri);
        } else {
            readerLog("No (connected) connection to unsubscribe from topic '" + topic +"' at: "+ event.uri);
        }
        if (publishers[event.uri]) {
            delete publishers[event.uri][topic];
            // disconnect when no subscriptions? How will this influence "concurrency"?
        }
    } else {
        readerLog("Got unknown event on '" + topic + "': " + JSON.stringify(event));
    }
}


function subscribe() {
    var topic = $('#topic').val().trim();
    if (topic == "") {
        readerLog("Subscribe error: no topic specified above.");
    } else if (reader == null) {
        readerLog("Subscribe error: not connected.");
    } else {
        reader.subscribe(topic, readerEvent);
        readerLog("Subscribed to '" + topic + "'. Getting current sources as events.");
        reader.call("sources", topic, true);
    }
}


function unsubscribe() {
    var topic = $('#topic').val().trim();
    if (topic == "") {
        readerLog("Unsubscribe error: no topic specified above.");
    } else if (reader == null) {
        readerLog("Unsubscribe error: not connected.");
    } else {
        reader.unsubscribe(topic);
        readerLog("Unsubscribed from '" + topic + "'.")
    }
}


function initialise() {
    $('#connect').click(connect);
    $('#disconnect').click(disconnect);
    $('#readerclear').click(function(){$('#readerlog').val("")});
    $('#topicclear').click(function(){$('#topiclog').val("")});
    $('#sub').click(subscribe);
    $('#unsub').click(unsubscribe);
}
