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

    ;; Test automatic subscribe on connect.
    (is (= "success" (-> (async/alts!! [recv (async/timeout 500)]) first json/parse-string
                         (get "response"))))

    ;; Test events.
    (api/add-source dir "test-topic" (URI. "ws://source-1"))
    (is (= {"event" "joined", "data" {"topic" "test-topic", "uri" "ws://source-1"}}
           (-> (async/alts!! [recv (async/timeout 500)]) first json/parse-string)))
    (api/remove-source dir "test-topic" (URI. "ws://source-1"))
    (is (= {"event" "left", "data" {"topic" "test-topic", "uri" "ws://source-1"}}
           (-> (async/alts!! [recv (async/timeout 500)]) first json/parse-string)))

    ;; Test unsubscribe.
    (ws/send-msg ws (json/generate-string {:action :unsubscribe
                                           :data {:topic "test-topic"}}))
    (is (= "success" (-> (async/alts!! [recv (async/timeout 500)]) first json/parse-string
                         (get "response"))))

    ;; Test subscribe error.
    (ws/send-msg ws (json/generate-string {:action :subscribe
                                           :data {:topic "test-topic"}}))
    (is (= "error" (-> (async/alts!! [recv (async/timeout 500)]) first json/parse-string
                       (get "response"))))

    ;; Test subscribe.
    (api/add-source dir "test-topic" (URI. "ws://source-1"))
    (ws/send-msg ws (json/generate-string {:action :subscribe
                                           :data {:topic "test-topic" :init :all}}))
    (is (= "success" (-> (async/alts!! [recv (async/timeout 500)]) first json/parse-string
                         (get "response"))))
    (is (= {"event" "joined", "data" {"topic" "test-topic", "uri" "ws://source-1"}}
           (-> (async/alts!! [recv (async/timeout 500)]) first json/parse-string)))

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
