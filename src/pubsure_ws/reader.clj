(ns pubsure-ws.reader
  "A directory service reader supporting websockets and http requests."
  (:require [pubsure.core :as api]
            [org.httpkit.server :as http]
            [clojure.core.async :as async]
            [clojure.string :refer (upper-case lower-case split)]))


;; channels = (atom {http.channel {"topic" async.chan}})
(defrecord State [dirreader config channels stop-fn open])


(defn- unsubscribe
  [{:keys [dirreader channels] :as state} channel topic]
  (when-let [sourcesc (get-in @channels [channel topic])]
    (swap! channels update-in [channel] dissoc topic)
    (api/unwatch-sources dirreader topic sourcesc)
    (async/close! sourcesc)
    (http/send! channel (str "UNSUB " topic))))


(defn- subscribe
  [{:keys [dirreader config channels open] :as state} channel topic init]
  (when-not (get-in @channels [channel topic])
    (let [sourcesc (async/chan (async/sliding-buffer (get config :subscribe-buffer 10)))]
      (swap! channels assoc-in [channel topic] sourcesc)
      (async/go-loop []
        (if-let [{:keys [event topic uri]} (<! sourcesc)]
          (do (http/send! channel (str (upper-case (name event)) " " topic " " uri))
              (recur))
          (unsubscribe state channel topic)))
      (http/send! channel (str "SUB " topic))
      (api/watch-sources dirreader topic (keyword init) sourcesc))))


(defn- handle-data
  [{:keys [open] :as state} channel data]
  (when @open
    (let [[command & args] (split data #"\s+")]
      (case command
        "SUB" (let [[topic init] args
                    init (#{"last" "all" "random"} (lower-case init))]
                (if (and topic init)
                  (subscribe state channel topic init)
                  (http/send! channel "Illegal command" true)))
        "UNSUB" (let [[topic] args]
                  (if topic
                    (unsubscribe state channel topic)
                    (http/send! channel "Illegal command" true)))
        (http/send! channel "Illegal command" true)))))


(defn- handle-close
  [{:keys [channels] :as state} channel status]
  (doseq [[topic _] (get @channels channel)]
    (unsubscribe state channel topic)))


(defn- make-app
  [{:keys [dirreader open] :as state}]
  (fn [request]
    (if @open
      (http/with-channel request channel
        (if (http/websocket? channel)
          (do (prn request) ;---TODO Support subscribing via path/params.
              (http/on-receive channel (partial handle-data state channel))
              (http/on-close channel (partial handle-close state channel)))
          (let [topic (subs (:uri request) 1)]
            (if (seq topic)
              (http/send! channel (apply str (interpose \newline (api/sources dirreader topic))))
              (http/send! channel {:status 400 :body "Illegal request"})))))
      {:status 503 :body "Server closing"})))


(defn start-server
  [dirreader & {:keys [port subscribe-buffer] :or {port 8091} :as config}]
  (let [stop-fn (atom nil)
        state (State. dirreader config (atom {}) stop-fn (atom true))]
    (reset! stop-fn (http/run-server (make-app state) {:port port}))
    state))


(defn stop-server
  [{:keys [stop-fn open channels] :as state}]
  (reset! open false)
  (doseq [[channel topics] @channels]
    (doseq [[topic chan] topics]
      (unsubscribe state channel topic)
      (async/close! chan))
    (http/close channel))
  (@stop-fn :timeout 100))
