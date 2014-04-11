(ns pubsure-ws.source
  "A Source implementation that uses Websockets for communication. The
  publish function takes whatever cheshire can encode as JSON."
  (:require [pubsure.core :as api :refer (Source)]
            [pubsure.utils :refer (conj-set)]
            [org.httpkit.server :as http]
            [clj-wamp.server :as wamp]
            [clojure.string :refer (split)]
            [cheshire.core :as json]
            [pubsure-ws.temp-fix]
            [taoensso.timbre :as timbre])
  (:import [java.net InetAddress URI]
           [java.util UUID]))
(timbre/refer-timbre)

;;; WAMP application.

(defn- send-cache
  "Sends cache data for the given topic to the caller. This RPC call
  function takes three parameters: the topic, the size and whether to
  receive the cache items as a response or as published messsages. The
  cache data is availabe in the source state record."
  [{:keys [cache] :as source} topic size publish?]
  (debug "Requested to send " size "items from cache for" topic "to" wamp/*call-sess-id*)
  (let [messages (reverse (take size (get @cache topic)))
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
  [{:keys [summary-fn summaries] :as source} topic]
  (debug "Requested to send summary of" topic "to" wamp/*call-sess-id*)
  (if summary-fn
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
  [source]
  (debug "Creating source app using state" source)
  (fn [request]
    (http/with-channel request channel
      (if (http/websocket? channel)
        (let [sess-id (wamp/http-kit-handler
                       channel
                       {:on-auth {:allow-anon? true ;---TODO Support authentication?
                                  :timeout 0}
                        :on-subscribe {"*" true
                                       :on-after #(debug "Subscribing" %1 "to" %2)}
                        :on-unsubscribe #(debug "Unsubscribing" %1 "to" %2)
                        :on-close #(debug "Connection with" %1 "closed having status" %2)
                        :on-call {"cache" (partial send-cache source)
                                  "summary" (partial send-summary source)}})
              topic (subs (:uri request) 1)]
          (when (seq topic)
            (debug "Got topic in request path - subscribing to" topic)
            (wamp/topic-subscribe topic sess-id)))
        (http/send! channel {:status 400 :body "Server only supports websockets"})))))


;;; Source implementation.

;; topics = (ref #{"topic"})
;; cache = (atom {"topic" (msg-3 msg-2 msg-1)})
;; open = (atom boolean)
;; config = {:cache-size long, :clean-cache-on-done boolean, :clean-summary-on-done boolean}
;; summaries (atom {"topic", summary})
(defrecord WebsocketSource [dirwriter stop-fn open topics cache uri config summary-fn summaries]
  Source
  (publish [this topic message]
    (let [uuid (UUID/randomUUID)]
      (debug "Requested to publishing message" uuid "on" topic)
      (trace "Message" uuid "-" message)
      (when @open
        (when (dosync
                (when-not (get @topics topic)
                  (alter topics conj topic)))
          (when @open
            (api/add-source dirwriter topic uri)
            (debug "Registered" uri "for topic" topic "in directory service.")))
        (wamp/send-event! topic message)
        (let [new-cache (swap! cache update-in [topic]
                               (fn [c] (take (config :cache-size) (conj c message))))]
          (debug "Message " uuid "published and cached.")
          (trace "Cache for" topic "after message" uuid "is:" (get new-cache topic)))
        (when summary-fn
          (let [new-summaries (swap! summaries update-in [topic] summary-fn message)]
            (debug "Summary for" topic "updated with message" uuid)
            (trace "Summary for" topic "after message" uuid "is:" (get new-summaries topic)))))))

  (done [this topic]
    (debug "Initiated 'done' for" topic)
    (when @open
      (when (dosync (when (get @topics topic)
                      (alter topics disj topic)))
        (when @open
          (api/remove-source dirwriter topic uri)
          (debug "Unregistered" uri "for topic" topic "in directory service."))
        (when (:clean-cache-on-done config)
          (swap! cache dissoc topic)
          (debug "Cleaned cache for" topic))
        (when (and summary-fn (:clean-summary-on-done config))
          (swap! summaries dissoc topic)
          (debug "Cleaned summary for" topic))
        ;;---TODO: Send a "done" somehow to the subscribers, and unsubscribe them?
        ))))


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

  :hostname - The hostname to use in the ws:// URI as registered in
  the directory service. Default is system hostname.

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

  :wrap-fn - A ring request wrapper function, wrapping around the
  default handler. This can for instance be used for authentication.

  Returns the source state record, used for `stop-source`."
  [directory-writer & {:keys [port hostname cache-size summary-fn wrap-fn]
                       :or {port 8090
                            cache-size 0
                            hostname (. (InetAddress/getLocalHost) getHostName)}
                       :as config}]
  (info "Starting websocket source with directory writer" directory-writer "and config" config)
  (let [uri (URI. (str "ws://" hostname ":" port))
        stop-fn (atom nil)
        config (assoc config :cache-size cache-size)
        source (WebsocketSource. directory-writer stop-fn (atom true) (ref #{}) (atom {}) uri
                                 config summary-fn (atom {}))
        app (make-app source)
        wrapped-app (if wrap-fn (wrap-fn app) app)]
    (reset! stop-fn (http/run-server wrapped-app {:port port}))
    source))


(defn stop-source
  "Given the return value of `start-source`, this stops the Websocket
  server and removes every topic for this source from the directory
  service."
  [{:keys [stop-fn open uri dirwriter topics] :as source}]
  (when @open
    (info "Stopping websocket source, using state" source)
    (reset! open false)
    (@stop-fn :timeout 100)
    (dosync (doseq [topic @topics]
              (debug "Unregistering" uri "for topic" topic "in directory service.")
              (api/remove-source dirwriter topic uri)))))
