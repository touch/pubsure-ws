(defproject pubsure/pubsure-ws "0.1.0-SNAPSHOT"
  :description "WAMP over Websocket implementation for pubsure."
  :url "https://github.com/touch/pubsure-ws"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [pubsure/pubsure-core "0.1.0-SNAPSHOT"]
                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]
                 [http-kit "2.1.18"]
                 [clj-wamp "1.0.2"]
                 [cheshire "5.4.0"]
                 [org.clojure/data.codec "0.1.0"]
                 [com.taoensso/timbre "3.1.6"]
                 [org.clojure/core.cache "0.6.4"]]
  :profiles {:test {:dependencies [[stylefruits/gniazdo "0.4.0"]]}}
  :pom-plugins [[com.theoryinpractise/clojure-maven-plugin "1.3.15"
                 {:extensions "true"
                  :executions ([:execution
                                [:id "clojure-compile"]
                                [:phase "compile"]
                                [:configuration
                                 [:temporaryOutputDirectory "true"]
                                 [:sourceDirectories [:sourceDirectory "src"]]]
                                [:goals [:goal "compile"]]]
                                 [:execution
                                  [:id "clojure-test"]
                                  [:phase "test"]
                                  [:goals [:goal "test"]]])}]]
  :pom-addition [:properties [:project.build.sourceEncoding "UTF-8"]])
