(ns pubsure-ws.reader
  "A directory service reader supporting websockets and http requests."
  (:require [pubsure.core :as api]
            [org.httpkit.server :as http]
            [clojure.core.async :as async]
            [clojure.string :refer (upper-case lower-case split)]
            [cheshire.core :as json]
            [cheshire.generate :as json-enc])
  (:import [java.net URI]))


;;; Helper methods and initialisation.

;; Encode URIs as a String
(json-enc/add-encoder URI json-enc/encode-str)


(defn send-success
  "Send a success message to the client."
  [channel msg]
  (http/send! channel (json/generate-string {:response :success :message msg})))


(defn send-error
  "Send an error message to the client, and optionally close the connection afterwards."
  [channel msg close?]
  (http/send! channel (json/generate-string {:response :error :message msg}) close?))


;;; Exposing a DirectoryReader as a Websocket service.

;; channels = (atom {http.channel {"topic" async.chan}})
(defrecord State [dirreader config channels stop-fn open])


(defn- unsubscribe
  "Unsubscribe the given channel from the given topic."
  [{:keys [dirreader channels] :as state} channel topic]
  (when-let [sourcesc (get-in @channels [channel topic])]
    (swap! channels update-in [channel] dissoc topic)
    (api/unwatch-sources dirreader topic sourcesc)
    (async/close! sourcesc)
    (send-success channel {:unsubscribe topic})))


(defn- subscribe
  "Subscribe the given channel to the given topic, using the supplied
  init parameter. This starts a go-loop, reading from a sliding-buffer channel, "
  [{:keys [dirreader config channels open] :as state} channel topic init]
  (when-not (get-in @channels [channel topic])
    (let [sourcesc (async/chan (async/sliding-buffer (get config :subscribe-buffer 100)))]
      (swap! channels assoc-in [channel topic] sourcesc)
      (async/go-loop []
        (if-let [{:keys [event topic uri]} (<! sourcesc)]
          (do (http/send! channel (json/generate-string {:event event
                                                         :data {:topic topic :uri uri}}) )
              (recur))
          (unsubscribe state channel topic)))
      (send-success channel {:subscribe topic})
      (api/watch-sources dirreader topic (keyword init) sourcesc))))


(defn- handle-data
  "Handles data coming in from the client. In tries to parse the data
  as JSON and handle accordingly."
  [{:keys [open] :as state} channel data]
  (if @open
    (let [{:strs [action data] :as request} (json/parse-string data)
          {:strs [topic init]} data]
      (case action
        "subscribe"
        (if (and topic (#{"last" "all" "random"} init))
          (subscribe state channel topic init)
          (send-error channel (str "Missing topic and/or init parameter for: " data) false))

        "unsubscribe"
        (if topic
          (unsubscribe state channel topic)
          (send-error channel (str "Missing topic parameter for: " data) false))
        (send-error channel (str "Illegal command: " data) true)))
    (send-error channel (str "Server is closing") false)))


(defn- handle-close
  "Handles a closed connection. This will automatically unsubscribe
  the channel from all topics in the DirectoryReader."
  [{:keys [channels] :as state} channel status]
  (doseq [[topic _] (get @channels channel)]
    (unsubscribe state channel topic)))


(defn- make-app
  "Creates a ring app, using the State record to keep track of
  channels and subscriptions. The app accepts Websocket connections
  and HTTP requests."
  [{:keys [dirreader open] :as state}]
  (fn [request]
    (if @open
      (let [topic (subs (:uri request) 1)]
        (http/with-channel request channel
          (if (http/websocket? channel)
            (do (http/on-receive channel (partial handle-data state channel))
                (http/on-close channel (partial handle-close state channel))
                (when (seq topic) (subscribe state channel topic "last")))
            (let [topic (subs (:uri request) 1)]
              (if (seq topic)
                (http/send! channel (json/generate-string (api/sources dirreader topic)))
                (http/send! channel {:status 400 :body "Illegal request"}))))))
      {:status 503 :body "Server is closing"})))


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
  (let [stop-fn (atom nil)
        state (State. directory-reader config (atom {}) stop-fn (atom true))]
    (reset! stop-fn (http/run-server (make-app state) {:port port}))
    state))


(defn stop-server
  "Given the return value of `start-server`, this function will stop
  the server and unsubscribe every connection in the DirectoryReader."
  [{:keys [stop-fn open channels] :as state}]
  (reset! open false)
  (doseq [[channel topics] @channels]
    (doseq [[topic chan] topics]
      (unsubscribe state channel topic)
      (async/close! chan))
    (http/close channel))
  (@stop-fn :timeout 100))
