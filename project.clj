(defproject pubsure-ws "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [pubsure/pubsure-core "0.1.0-SNAPSHOT"]
                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]
                 [http-kit "2.1.18"]
                 [cheshire "5.3.1"]]
  :profiles {:test {:dependencies [[stylefruits/gniazdo "0.1.0"]]}})
