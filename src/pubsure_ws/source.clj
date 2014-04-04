(ns pubsure-ws.source
  "A Source implementation that uses Websockets for communication. The
  publish function takes Strings, byte-arrays, InputStreams and
  ByteBuffers as message format. The published messages are wrapped
  with JSON (using the WAMP spec), so the latter three are encoded as
  Base64 strings."
  (:require [pubsure.core :as api :refer (Source)]
            [pubsure.utils :refer (conj-set)]
            [org.httpkit.server :as http]
            [clj-wamp.server :as wamp]
            [clojure.string :refer (split)]
            [cheshire.core :as json]
            [cheshire.generate :as generate]
            [ring.middleware.params :as params])
  (:import [java.net InetAddress URI]))


;;; Fix wamp/map-key-or-prefix. Quicker, and supports catch-all.

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


;;; Set up JSON encoding for binary data.

;;---FIXME
(generate/add-encoder (Class/forName "[B") nil)


;;; WAMP application.

(defn- send-cache
  [{:keys [cache] :as source} request sess-id topic]
  (prn "SENDING CACHE" request sess-id topic)
  (when-let [nr (Long/parseLong (get-in request [:params "cache"]))]
    (doseq [message (reverse (take nr (get @cache topic)))]
      (wamp/emit-event! topic message sess-id)))
  ;;---TODO Determine whether this topic is actually published by this
  ;;        source, and unsubscribe when its not?
  )


(defn- make-app
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
        (api/remove-source dirwriter topic uri)
        ;;---TODO: Send a "done" somehow to the subscribers, and unsubscribe them?
        ))))


(defn start-source
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
  [{:keys [stop-fn open uri dirwriter topics] :as source}]
  (when @open
    (reset! open false)
    (@stop-fn :timeout 100)
    (dosync (doseq [topic @topics]
              (api/remove-source dirwriter topic uri)))))
