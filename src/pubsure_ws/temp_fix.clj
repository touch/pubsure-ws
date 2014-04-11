(ns pubsure-ws.temp-fix
  (:require [clj-wamp.server :as wamp]
            [taoensso.timbre :refer (warn)]))


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

(warn "Monkey-patched clj-wamp.server/map-key-or-prefix.")
