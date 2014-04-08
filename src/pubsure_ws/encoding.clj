(ns pubsure-ws.encoding
  "This namespace can setup default JSON encodings for common data
  types, by adding encoders to cheshire, the library used by clj-wamp
  (and thus pubsure-ws.source)."
  (:require [cheshire.generate :as gen]
            [clojure.data.codec.base64 :as b64])
  (:import [java.io InputStream]
           [java.nio ByteBuffer]))


(defn support-binary
  "Add JSON encoding support for byte-arrays, InputStreams and
  ByteBuffers. They will encoded as UTF-8 Base64 strings."
  []
  (generate/add-encoder (Class/forName "[B")
                        (fn [ba jg] (gen/encode-str (String. (b64/encode ba) "UTF-8") jg)))
  (generate/add-encoder InputStream
                        (fn [is jg] (gen/encode-str (String. (b64/encode is) "UTF-8") jg)))
  (generate/add-encoder ByteBuffer
                        (fn [^ByteBuffer bb jg]
                          (let [sl (.slice bb)
                                ba (byte-array (.remaining bb))]
                            (.get sl ba)
                            (gen/encode-str (String. (b64/encode ba) "UTF-8") jg)))))
