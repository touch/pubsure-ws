(ns pubsure-ws.source
  "A Source implementation that uses Websockets for communication. The
  publish function takes whatever cheshire can encode as JSON."
  (:require [pubsure.core :as api :refer (Source)]
            [pubsure.utils :refer (conj-set)]
            [org.httpkit.server :as http]
            [clj-wamp.server :as wamp]
            [clojure.string :refer (split)]
            [cheshire.core :as json]
            [cheshire.generate :as generate]
            [ring.middleware.params :as params])
  (:import [java.net InetAddress URI]))


;;; Fix wamp/map-key-or-prefix. Supports catch-all. Pull request posted.

(defn- map-key-or-prefix
  [m k]
  (or (m k)
      (some (fn [[mk mv]]
              (and (string? mk)
                   (= \* (last mk))
                   (.startsWith k (subs mk 0 (dec (count mk))))
                   mv))
            m)))

(alter-var-root #'wamp/map-key-or-prefix (constantly map-key-or-prefix))


;;; WAMP application.

;;---TODO: Make sending cache a WAMP RPC call? This way, one can decide for each topic
;;         when and how much cache one wants to receive.
(defn- send-cache
  "Sends cache data for the given topic to the socket identified with
  `sess-id`. The cache data is availabe in the source state record,
  and the number of cache items is currently retrieved from the
  `cache` parameter in the original request."
  [{:keys [cache] :as source} request sess-id topic]
  (prn "SENDING CACHE" request sess-id topic)
  (when-let [nr (Long/parseLong (get-in request [:params "cache"]))]
    (doseq [message (reverse (take nr (get @cache topic)))]
      (wamp/emit-event! topic message sess-id)))
  ;;---TODO Determine whether this topic is actually published by this
  ;;        source, and unsubscribe when its not?
  )


(defn- make-app
  "Creates a ring app, handling the requests and data using WAMP and
  the given source state record."
  [source]
  (-> (fn [request]
        (http/with-channel request channel
          (if (http/websocket? channel)
            (let [sess-id (wamp/http-kit-handler
                           channel
                           {:on-auth {:allow-anon? true ;---TODO Support authentication?
                                      :timeout 0}
                            :on-subscribe {"*" true
                                           :on-after (partial send-cache source request)}})
                  topic (subs (:uri request) 1)]
              (when (seq topic)
                (wamp/topic-subscribe topic sess-id)
                (send-cache source request sess-id topic)))
            (http/send! channel {:status 400 :body "Server only supports websockets"}))))
      params/wrap-params))


;;; Source implementation.

;; topics = (ref #{"topic"})
;; cache = (atom {"topic" (msg-3 msg-2 msg-1)})
;; open = (atom boolean)
;; config = {:cache-size long}
(defrecord WebsocketSource [dirwriter stop-fn open topics cache uri config]
  Source
  (publish [this topic message]
    (when @open
      (when (dosync
              (when-not (get @topics topic)
                (alter topics conj topic)))
        (when @open (api/add-source dirwriter topic uri)))
      (wamp/send-event! topic message)
      (swap! cache update-in [topic]
             (fn [c] (take (config :cache-size) (conj c message))))))

  (done [this topic]
    (when @open
      (when (dosync (when (get @topics topic)
                      (alter topics disj topic)))
        (when @open (api/remove-source dirwriter topic uri))
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

  :cache-size - The number of last published messages kept for each
  topic. Default is 0.

  :hostname - The hostname to use in the ws:// URI as registered in
  the directory service. Default is system hostname.

  Returns the source state record, used for `stop-source`."
  [directory-writer & {:keys [port hostname cache-size]
                       :or {port 8090
                            cache-size 0
                            hostname (. (InetAddress/getLocalHost) getHostName)}}]
  (let [uri (URI. (str "ws://" hostname ":" port))
        stop-fn (atom nil)
        source (WebsocketSource. directory-writer stop-fn (atom true) (ref #{}) (atom {}) uri
                                 {:cache-size cache-size})]
    (reset! stop-fn (http/run-server (make-app source) {:port port}))
    source))


(defn stop-source
  "Given the return value of `start-source`, this stops the Websocket
  server and removes every topic for this source from the directory
  service."
  [{:keys [stop-fn open uri dirwriter topics] :as source}]
  (when @open
    (reset! open false)
    (@stop-fn :timeout 100)
    (dosync (doseq [topic @topics]
              (api/remove-source dirwriter topic uri)))))
