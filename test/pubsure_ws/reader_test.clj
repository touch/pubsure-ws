(ns pubsure-ws.reader-test
  "Simple tests for the Websocket implementation."
  (:require [pubsure-ws.reader :refer :all]
            [pubsure.core :as api]
            [pubsure.memory :as memory]
            [clojure.test :refer :all]
            [gniazdo.core :as ws]
            [clojure.core.async :as async :refer (<!!)]
            [cheshire.core :as json]
            [org.httpkit.client :as http])
  (:import [java.net URI]))


(deftest websocket
  (let [dir (memory/mk-directory)
        serv (start-server dir :port 8091)
        recv (async/chan)
        ws (ws/connect "ws://localhost:8091/test-topic"
                       :on-receive (partial async/put! recv))]

    ;; Get welcome message.
    (is (= 0 (-> (async/alts!! [recv (async/timeout 500)]) first json/parse-string first)))

    ;; Test automatic subscribe and events.
    (api/add-source dir "test-topic" (URI. "ws://source-1"))
    (is (= {"event" "joined", "uri" "ws://source-1"}
           (-> (async/alts!! [recv (async/timeout 500)]) first json/parse-string (nth 2))))
    (api/remove-source dir "test-topic" (URI. "ws://source-1"))
    (is (= {"event" "left", "uri" "ws://source-1"}
           (-> (async/alts!! [recv (async/timeout 500)]) first json/parse-string (nth 2))))

    ;; Test unsubscribe.
    (ws/send-msg ws (json/generate-string [6 "test-topic"]))
    (Thread/sleep 500) ; give the server the chance to unsub
    (api/add-source dir "test-topic" (URI. "ws://source-1"))
    (is (= nil (-> (async/alts!! [recv (async/timeout 500)]) first)))

    ;; Test subscribe.
    (ws/send-msg ws (json/generate-string [5 "test-topic"]))
    (Thread/sleep 500) ; give the server the chance to sub
    (api/remove-source dir "test-topic" (URI. "ws://source-1"))
    (is (= {"event" "left", "uri" "ws://source-1"}
           (-> (async/alts!! [recv (async/timeout 500)]) first json/parse-string (nth 2))))

    ;; Done
    (async/close! recv)
    (ws/close ws)
    (stop-server serv)))


(deftest http-get
  (let [dir (memory/mk-directory)
        serv (start-server dir :port 8091)]
    (api/add-source dir "test-topic" (URI. "ws://source-1"))
    (api/add-source dir "test-topic" (URI. "ws://source-2"))
    (is (every? #{"ws://source-1" "ws://source-2"}
                (-> (http/get "http://localhost:8091/test-topic") deref :body json/parse-string)))
    (stop-server serv)))
