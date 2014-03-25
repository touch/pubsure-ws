(ns eventure-ws.server
  (:require [eventure.core :as api :refer (Server)]
            [org.httpkit.server :as http]
            [clojure.string :refer (split)])
  (:import [java.net InetAddress URI]))


;;; Helper methods.

(defn- conj-set
  "Ensures a conj results in a set."
  [coll val]
  (set (conj coll val)))


;;; Websocket handling.

(defn- unsubscribe
  [{:keys [channels topics config] :as server} channel topic]
  (when (dosync
          (when (get-in @channels [channel topic])
            (alter topics update-in [topic] disj channel)
            (alter channels update-in [channel] disj topic))))
  (http/send! channel (str "UNSUB " topic))
  (when (and (= 0 (count (get @channels channel))) (:close-on-nosubs? config))
    (http/close channel)))


(defn- subscribe
  [{:keys [channels topics cache] :as server} channel topic last]
  (when-not (get-in @channels [channel topic])
    (let [cmessages (get @cache topic)]
      (when (and (seq cmessages) (< 0 last))
        (http/send! channel (str "CACHE " topic))
        (doseq [msg (take last (reverse cmessages))] (http/send! channel (str topic ":" msg)))))
    (if (dosync
          (when (get @topics topic)
            (alter topics update-in [topic] conj channel)
            (alter channels update-in [channel] conj-set topic)))
      (http/send! channel (str "SUB " topic))
      (unsubscribe server channel topic))))


(defn- handle-data
  [server channel data]
  (let [[command & args] (split data #"\s+")]
    (case command
      "SUB" (let [[topic last] args
                  last (try (Long/parseLong last) (catch NumberFormatException ex))]
              (if (and topic last)
                (subscribe server channel topic last)
                (http/send! channel "Illegal command" true)))
      "UNSUB" (let [[topic] args]
                (if topic
                  (unsubscribe server channel topic)
                  (http/send! channel "Illegal command" true)))
      (http/send! channel "Illegal command" true))))


(defn- handle-close
  [{:keys [channels] :as server} channel status]
  (dosync (alter channels dissoc channel)))


;;; HTTP-kit app

(defn- make-app
  [server]
  (fn [request]
    (http/with-channel request channel
      (if (http/websocket? channel)
        (do (prn request) ;---TODO Support subscribing via path/params.
            (http/on-receive channel (partial handle-data server channel))
            (http/on-close channel (partial handle-close server channel)))
        (http/send! channel {:status 400 :body "Server only supports websockets"})))))


(defn- ensure-topic
  [{:keys [dirwriter topics uri] :as server} topic]
  (when-not (get @topics topic)
    (dosync (alter topics assoc topic #{}))
    (api/add-source dirwriter topic uri)))


;; cache = (atom {"topic" (msg msg msg)})
;; topics = (ref {"topic" #{chan chan chan}})
;; channels = (ref {chan #{"topic" "topic" "topic"}})
;; open = (atom boolean)
;; config = {:cache-size long, :close-on-nosubs? boolean}
(defrecord WebSocketServer [dirwriter uri stop-fn cache topics channels open config]
  Server
  (publish [this topic message]
    (when @open
      (ensure-topic this topic)
      (let [msg (str topic ":" message)]
        (doseq [channel (get @topics topic)]
          (when-not (http/send! channel msg)
            (dosync (alter topics update-in [topic] disj channel)))))
      (swap! cache update-in [topic]
             (fn [c] (take (config :cache-size) (conj c message))))))

  (done [this topic]
    (when-let [subs (get @topics topic)]
      (when @open
        (api/remove-source dirwriter topic uri)
        (dosync (alter topics dissoc topic))
        (doseq [channel subs]
          (unsubscribe this channel topic))))))


(defn start-server
  [dirwriter & {:keys [port cache-size hostname close-on-nosubs?]
                :or {port 8090
                     cache-size 0
                     hostname (. (InetAddress/getLocalHost) getHostName)
                     close-on-nosubs? true}}]
  (let [uri (URI. (str "ws://" hostname ":" port))
        stop-fn (atom nil)
        open (atom false)
        server (WebSocketServer. dirwriter uri stop-fn (atom {}) (ref {}) (ref {}) open
                                 {:cache-size cache-size :close-on-nosubs? close-on-nosubs?})]
    (reset! stop-fn (http/run-server (make-app server) {:port port}))
    (reset! open true)
    server))


(defn stop-server
  [{:keys [dirwriter uri stop-fn open topics] :as server}]
  (reset! open false)
  (doseq [[topic subs] @topics]
    (api/remove-source dirwriter topic uri) ;---TODO Add batch operation to DirectoryWriter?
    (dosync (alter topics dissoc topic))
    (doseq [channel subs]
      (unsubscribe server channel topic)))
  (@stop-fn :timeout 100))
