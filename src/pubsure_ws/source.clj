(ns pubsure-ws.source
  "A Source implementation that uses Websockets for communication. The
  publish function takes whatever cheshire can encode as JSON."
  (:require [pubsure.core :as api :refer (Source)]
            [pubsure.utils :refer (conj-set)]
            [org.httpkit.server :as http]
            [clj-wamp.server :as wamp]
            [clojure.string :refer (split)]
            [cheshire.core :as json]
            [taoensso.timbre :as timbre]
            [clojure.core.cache :as cache])
  (:import [java.net InetAddress URI]
           [java.util UUID]))
(timbre/refer-timbre)


;;; WAMP application.

(defn- send-cache
  "Sends cache data for the given topic to the caller. This RPC call
  function takes three parameters: the topic, the size and whether to
  receive the cache items as a response or as published messsages. The
  cache data is availabe in the source state record."
  [{:keys [topics] :as source} topic size publish?]
  (debug "Requested to send " size "items from cache for" topic "to" wamp/*call-sess-id*)
  (let [messages (reverse (take size (:cache @(get @topics topic))))
        cnt (count messages)]
    (if publish?
      (do (debug "Sending" cnt "cached message for" topic "to" wamp/*call-sess-id* "as events.")
          (doseq [message messages]
            (wamp/emit-event! topic message wamp/*call-sess-id*))
          {:result (str "published " cnt " messages from cache")})
      (do (debug "Sending" cnt "cached message for" topic "to" wamp/*call-sess-id* "as reply.")
          {:result messages}))))


(defn- send-summary
  "Sends a summary of the published messages, built by the optional
  summary function given at source creation."
  [{:keys [config summaries] :as source} topic]
  (debug "Requested to send summary of" topic "to" wamp/*call-sess-id*)
  (if (:summary-fn config)
    (let [summary (get @summaries topic)]
      (debug "Sending summary of" topic "to" wamp/*call-sess-id*)
      (trace "Summary is" summary)
      {:result summary})
    (do (debug "Sending no summary error to" wamp/*call-sess-id*)
        {:error {:uri topic
                 :message "No summary"
                 :description (str "This source does not use summaries.")
                 :kill false}})))


(defn- make-app
  "Creates a ring app, handling the requests and data using WAMP and
  the given source state record."
  [{:keys [config] :as source}]
  (debug "Creating source app using state" source)
  (fn [request]
    (http/with-channel request channel
      (if (http/websocket? channel)
        (let [auth-fn (or (:auth-fn config) (constantly true))
              sess-id (wamp/http-kit-handler
                       channel
                       {:on-auth {:allow-anon? true ; Authentication is done through :auth-fn.
                                  :timeout 0} ; Disables RPC authentication.
                        :on-subscribe {"*" (fn [sess-id topic] (auth-fn request topic :subscribe))
                                       :on-after #(debug "Subscribing" %1 "to" %2)}
                        :on-unsubscribe #(debug "Unsubscribing" %1 "to" %2)
                        :on-close #(debug "Connection with" %1 "closed having status" %2)
                        :on-call {"cache" (fn [topic & rest]
                                            (if (auth-fn request topic :cache)
                                              (apply send-cache source topic rest)
                                              "Unauthorized"))
                                  "summary" (fn [topic & rest]
                                              (if (auth-fn request topic :summary)
                                                (apply send-summary source topic rest)
                                                "Unauthorized"))}})
              topic (subs (:uri request) 1)]
          (when (and (seq topic) (auth-fn request topic :subscribe))
            (debug "Got topic in request path - subscribing to" topic)
            (wamp/topic-subscribe topic sess-id)))
        (http/send! channel {:status 400 :body "Server only supports websockets"})))))


;;; Source implementation.

(defn- publish-action
  [{:keys [topic registered? cache summaries dirwriter] :as data} config message]
  (let [uuid (UUID/randomUUID)]
    (debug "Requested to publishing message" uuid "on" topic)
    (trace "Message" uuid "-" message)

    (when-not registered?
      (api/add-source dirwriter topic (:uri config))
      (debug "Registered" (:uri config) "for topic" topic "in directory service."))

    (wamp/send-event! topic message)

    (when-let [summary-fn (:summary-fn config)]
      (swap! summaries update-in [topic] (fn [current] (summary-fn current message))))

    (let [new-data (merge data
                          {:cache (take (:cache-size config) (conj cache message))
                           :registered? true})]
      (debug "Message " uuid "published and cached.")
      (trace "Data for" topic "after message" uuid "is:" new-data)
      new-data)))


(defn- done-action
  [{:keys [topic registered? cache summaries dirwriter] :as data} config]
  (debug "Initiated 'done' for" topic)

  (when-let [done-payload (:done-payload config)]
    (debug "Sending done payload to" topic)
    (wamp/send-event! topic done-payload))

  (when registered?
    (api/remove-source dirwriter topic (:uri config))
    (debug "Unregistered" (:uri config) "for topic" topic "in directory service."))

  (when (:clean-summary-on-done config)
    (swap! summaries dissoc topic))

  (let [new-data (merge data
                        {:cache (when-not (:clean-cache-on-done config) cache)
                         :registered? false})]
    (debug "Done for topic" topic "processed.")
    (trace "Data for" topic "after done is:" new-data)
    new-data))


;; topics = (ref {"topic" agent})
;; open = (atom boolean)
;; config = {:cache-size long, :clean-cache-on-done boolean, :clean-summary-on-done boolean,
;;           :uri URI, :summary-fn nil/fn}
(defrecord WebsocketSource [dirwriter stop-fn open topics config summaries]
  Source
  (publish [this topic message]
    (if @open
      (let [topic-agent (get (dosync
                               (if-not (get @topics topic)
                                 (alter topics assoc topic
                                        (agent {:topic topic, :registered? false
                                                :summaries summaries, :dirwriter dirwriter}))
                                 @topics))
                             topic)]
        (send topic-agent publish-action config message))
      (warn "Trying to 'publish' after the source is closing or closed.")))

  (done [this topic]
    (if-let [topic-agent (get @topics topic)]
      (send topic-agent done-action config)
      (warn "Trying to 'done' on topic that never published."))))


(defn start-source
  "Given a DirectoryWriter implementation, this starts a Source that
  opens a Websocket server, talking the WAMP spec. The URI to connect
  to this source will be in the form of \"ws://<hostname>\".
  Optionally, one can provide a path when connecting, which points to
  a topic one whishes to subscribe to immediatly, e.g.
  \"ws://<hostname>/<topic>\". Furthermore, one can supply a parameter
  called \"cache\" (either as a query parameter or header parameter),
  which indicates the number of cache items one whishes to receive
  when subscribing to a topic.

  The following options are supported for this function:

  :port - The port number where the server will bind to. Default is
  8090.

  :uri - The websocket URI to be registered in the directory service.
  Default is `ws://system-hostname:port`.

  :cache-size - The number of last published messages kept for each
  topic. This cache can be received through WAMP RPC. Default is 0.

  :clean-cache-on-done - Whether to clean the cache when `done` is
  called on the Source. Default is false.

  :summary-fn - An optional function which builds up a summary of the
  messages published for each topic, which can be received through
  WAMP RPC. The function takes the current summary (which may be nil)
  and the published message as its parameters.

  :clean-summary-on-done - Whether to clean the summary when `done` is
  called on the Source. Default is false.

  :summary-ttl - The number of milliseconds to keep the summary for a
  topic after is was last updated. Defaults to one hour. Set to zero
  or less to keep summaries indefinite (not recommended).

  :wrap-fn - A ring request wrapper function, wrapping around the
  default handler. This can for instance be used for authentication.

  :done-payload - If specified, the given value will be published to a
  topic when `done` is called for that topic. By default this option
  is not used.

  :auth-fn - This function checks whether a connection may subscribe,
  retrieve a cache, or retrieve a summary for a particular topic. The
  function takes the original request, the topic, and a keyword for
  the requested function (:subscribe, :cache or :summary) as its
  parameters. The function should return true to allow, false to deny.
  Default is `(constantly true)`.

  Returns the source state record, used for `stop-source`."
  [directory-writer & {:keys [port uri cache-size summary-fn summary-ttl wrap-fn]
                       :or {port 8090
                            cache-size 0
                            summary-ttl (* 1000 60 60)}
                       :as config}]
  (info "Starting websocket source with directory writer" directory-writer "and config" config)
  (let [uri (or uri (URI. (str "ws://" (. (InetAddress/getLocalHost) getHostName) ":" port)))
        stop-fn (atom nil)
        config (assoc config :cache-size cache-size :uri uri)
        summaries (when summary-fn
                    (if (< 0 summary-ttl)
                      (cache/ttl-cache-factory {} :ttl summary-ttl)
                      {}))
        source (WebsocketSource. directory-writer stop-fn (atom true) (ref {}) config
                                 (atom summaries))
        app (make-app source)
        wrapped-app (if wrap-fn (wrap-fn app) app)]
    (reset! stop-fn (http/run-server wrapped-app {:port port}))
    source))


(defn stop-source
  "Given the return value of `start-source`, this stops the Websocket
  server and removes every topic for this source from the directory
  service."
  [{:keys [stop-fn open dirwriter topics config] :as source}]
  (when @open
    (info "Stopping websocket source, using state" source)
    (reset! open false)
    (doseq [topic-agent (vals @topics)]
      (send topic-agent done-action config))
    (when (:done-payload config)
      (Thread/sleep 1000))
    (@stop-fn :timeout 100)))
