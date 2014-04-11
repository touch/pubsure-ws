(ns pubsure-ws.reader
  "A directory service reader supporting websockets and http requests."
  (:require [pubsure.core :as api]
            [org.httpkit.server :as http]
            [clojure.core.async :as async]
            [clojure.string :refer (upper-case lower-case split)]
            [cheshire.core :as json]
            [cheshire.generate :as json-enc]
            [clj-wamp.server :as wamp]
            [pubsure-ws.temp-fix]
            [taoensso.timbre :as timbre])
  (:import [java.net URI]))
(timbre/refer-timbre)


;;; Helper methods and initialisation.

;; Encode URIs as a String
(json-enc/add-encoder URI json-enc/encode-str)


;;; Exposing a DirectoryReader as a Websocket service.

;; channels = (atom {"sess-id" {"topic" async.chan}})
(defrecord State [dirreader config channels stop-fn open])


(defn- unsubscribe
  "Unsubscribe the given session from the given topic."
  [{:keys [dirreader channels] :as state} sess-id topic]
  (debug "Requested to unsubscribe" sess-id "from" topic "...")
  (if-let [sourcesc (get-in @channels [sess-id topic])]
    (do (swap! channels update-in [sess-id] dissoc topic)
        (api/unwatch-sources dirreader topic sourcesc)
        (async/close! sourcesc)
        (debug "Unsubscribed" sess-id "from" topic))
    (debug "Session" sess-id "is not subscribed (anymore) to" topic)))


(defn- subscribe
  "Subscribe the given session to the given topic. This starts a
  go-loop, reading from a sliding-buffer channel."
  [{:keys [dirreader config channels open] :as state} sess-id topic]
  (debug "Requested to subscribe" sess-id "to" topic "...")
  (if (and @open (not (get-in @channels [sess-id topic])))
    (let [sourcesc (async/chan (async/sliding-buffer (get config :subscribe-buffer 100)))]
      (swap! channels assoc-in [sess-id topic] sourcesc)
      (async/go-loop []
        (if-let [event (<! sourcesc)]
          (do (wamp/emit-event! topic (dissoc event :topic) sess-id)
              (recur))
          (unsubscribe state sess-id topic)))
      (api/watch-sources dirreader topic :none sourcesc)
      (debug "Subscribed" sess-id "to" topic))
    (debug "Cannot subscribe" sess-id "to" topic "- reader is closing or already subscribed.")))


(defn- handle-close
  "Handles a closed connection. This will automatically unsubscribe
  the channel from all topics in the DirectoryReader."
  [{:keys [channels] :as state} sess-id status]
  (debug "Handling close for" sess-id "given status" status)
  (doseq [[topic _] (get @channels sess-id)]
    (unsubscribe state sess-id topic)))


(defn- send-sources
  "Sends the currently known sources to the caller."
  [{:keys [dirreader open] :as state} topic publish?]
  (debug "Requested to send sources for" topic "to" wamp/*call-sess-id* "...")
  (if @open
    (let [sources (api/sources dirreader topic)]
      (if publish?
        (do (debug "Sending sources for" topic "to" wamp/*call-sess-id* "as events.")
            (doseq [source sources]
              (wamp/emit-event! topic {:event :joined, :uri source} wamp/*call-sess-id*))
            {:result (str "Published " (count sources) " sources as events.")})
        (do (debug "Sending sources for" topic "to" wamp/*call-sess-id* "as reply.")
            {:result sources})))
    (do (debug "Cannot send sources for" topic "to" wamp/*call-sess-id* "- reader is closing.")
        {:error {:uri topic
                 :message "Server closing"
                 :description (str "The server is closing.")
                 :kill false}})))


(defn- make-app*
  "Creates a ring app, using the State record to keep track of
  channels and subscriptions. The app accepts Websocket connections
  and HTTP requests."
  [{:keys [dirreader open] :as state}]
  (info "Creating ring app using state" state)
  (fn [request]
    (trace "Handling request" request)
    (if @open
      (let [topic (subs (:uri request) 1)]
        (http/with-channel request channel
          (if (http/websocket? channel)
            (let [sess-id (wamp/http-kit-handler
                           channel
                           {:on-auth {:allow-anon? true ;---TODO Support authentication?
                                      :timeout 0}
                            :on-subscribe {"*" true
                                           :on-after (partial subscribe state)}
                            :on-unsubscribe (partial unsubscribe state)
                            :on-close (partial handle-close state)
                            :on-call {"sources" (partial send-sources state)}})]
              (when (seq topic)
                (debug "Request" sess-id "has path to topic" topic "- subscribing to it now")
                (wamp/topic-subscribe topic sess-id)
                (subscribe state sess-id topic)))
            (if (seq topic)
              (http/send! channel (json/generate-string (api/sources dirreader topic)))
              (http/send! channel {:status 400 :body "Illegal request"})))))
      (do (debug "Sending 503 - server is closing")
          {:status 503 :body "Server is closing"}))))


(defn make-app
  "Creates a http-kit ring app, for use in an existing http-kit
  server. The following options are supported:

  :subscribe-buffer - The size of of the sliding buffer size used for
  buffering incoming source updates from the DirectoryReader. Default
  is 100.

  The returned value is a vector of two items. The first can be used
  for the `stop-app` function, and the second is the ring app
  function."
  [directory-reader & {:keys [subscribe-buffer] :as config}]
  (let [state (State. directory-reader config (atom {}) (atom nil) (atom true))
        app (make-app* state)]
    [state app]))


(defn start-server
  "Starts a Websocket-supporting server, handling subscriptions using
  the supplied DirectoryReader implementation. The following options
  are supported:

  :port - The port the server will bind to. Default is 8091.

  :subscribe-buffer - The size of of the sliding buffer size used for
  buffering incoming source updates from the DirectoryReader. Default
  is 100.

  The returned value can be used for the `stop-server` function."
  [directory-reader & {:keys [port subscribe-buffer] :or {port 8091} :as config}]
  (info "Starting reader server using directory reader" directory-reader "and config" config "...")
  (let [[state app] (apply make-app directory-reader (flatten config))]
    (reset! (:stop-fn state) (http/run-server app {:port port}))
    state))


(defn stop-app
  "Given the first item in the return value of `make-app` function,
  this function will unwatch the current open watches to the
  DirectoryReader. The app should not and cannot be used afterwards."
  [{:keys [open channels] :as state}]
  (info "Stopping the reader application ...")
  (reset! open false)
  (doseq [[sess-id topic-chans] @channels
          [topic _] topic-chans]
    (unsubscribe state sess-id topic))
  (info "Stopped the reader application."))


(defn stop-server
  "Given the return value of `start-server`, this function will stop
  the server and unsubscribe every watch in the DirectoryReader."
  [{:keys [stop-fn] :as state}]
  (info "Stopping the reader server ...")
  (stop-app state)
  (@stop-fn :timeout 100)
  (info "Stopped the reader server."))
