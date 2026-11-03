(ns chequer.brotli-test
  (:require
    [chequer.brotli :as brotli]
    [clojure.core.async :as async]
    [clojure.test :refer [deftest is testing]]
    [jolt.ffi :as ffi]))


(def ^:private dec-lib
  (delay
    (ffi/load-library "libbrotlidec.so.1")
    {:create (ffi/foreign-fn "BrotliDecoderCreateInstance" [:pointer :pointer :pointer] :pointer)
     :decompress-stream (ffi/foreign-fn "BrotliDecoderDecompressStream"
                                        [:pointer :pointer :pointer :pointer :pointer :pointer] :int)
     :take-output (ffi/foreign-fn "BrotliDecoderTakeOutput" [:pointer :pointer] :pointer)
     :destroy (ffi/foreign-fn "BrotliDecoderDestroyInstance" [:pointer] :void)}))


(defn decoder
  "A streaming brotli decoder: a function of the next compressed chunk, returning the text
  it decodes to and whether the stream is finished, as [text finished?]."
  []
  (let [{:keys [create decompress-stream take-output]} @dec-lib
        state (create ffi/null ffi/null ffi/null)
        scratch (ffi/alloc 40)
        slot #(ffi/slice scratch %)]
    (fn [^bytes src]
      (let [in (ffi/alloc (max 1 (alength src)))]
        (ffi/write-array in src)
        (ffi/write scratch :size_t (alength src) 0)
        (ffi/write scratch :pointer in 8)
        (try
          (loop [out []]
            (ffi/write scratch :size_t 0 16)
            (ffi/write scratch :pointer ffi/null 24)
            (let [r (decompress-stream state (slot 0) (slot 8) (slot 16) (slot 24) ffi/null)
                  _ (ffi/write scratch :size_t 0 32)
                  p (take-output state (slot 32))
                  n (ffi/read scratch :size_t 32)
                  out (cond-> out (pos? n) (into (ffi/read-array p n)))]
              (case r
                3 (recur out)
                (1 2) [(String. (byte-array out) "UTF-8") (= 1 r)]
                (throw (ex-info "brotli: decode failed" {:result r})))))
          (finally
            (ffi/free in)))))))


(defn- take!!
  [ch]
  (first (async/alts!! [ch (async/timeout 2000)])))


(defn- put!!
  [ch v]
  (first (async/alts!! [[ch v] (async/timeout 2000)])))


(deftest acceptable-follows-accept-encoding
  (is (brotli/acceptable? "gzip, deflate, br, zstd"))
  (is (brotli/acceptable? "BR;q=0.5"))
  (is (brotli/acceptable? "*"))
  (is (not (brotli/acceptable? nil)))
  (is (not (brotli/acceptable? "gzip, deflate")))
  (is (not (brotli/acceptable? "br;q=0, gzip")))
  (is (not (brotli/acceptable? "*, br;q=0")) "a named coding beats the wildcard")
  (is (not (brotli/acceptable? "brotli")) "not a substring match"))


(deftest each-chunk-decodes-on-arrival
  (let [e (brotli/encoder)]
    (is (some? e) "libbrotlienc is available")
    (let [decode (decoder)
          frame "event: datastar-patch-elements\ndata: elements <ul><li>a task</li></ul>\n\n"
          first-chunk (brotli/compress-chunk! e (.getBytes frame "UTF-8"))
          second-chunk (brotli/compress-chunk! e (.getBytes frame "UTF-8"))]
      (is (= [frame false] (decode first-chunk)))
      (is (= [frame false] (decode second-chunk)))
      (is (< (alength second-chunk) (alength first-chunk)) "a repeat costs less than the original")
      (is (= ["" true] (decode (brotli/finish! e)))))))


(deftest compress-channel-streams-src
  (testing "src's chunks arrive compressed, and src closing finishes the stream"
    (let [src (async/chan)
          out (brotli/compress-channel src)
          decode (decoder)]
      (async/put! src "one\n\n")
      (is (= ["one\n\n" false] (decode (take!! out))))
      (async/put! src (.getBytes "two\n\n" "UTF-8"))
      (is (= ["two\n\n" false] (decode (take!! out))))
      (async/close! src)
      (is (= ["" true] (decode (take!! out))))
      (is (nil? (take!! out)) "and then out closes")))
  (testing "closing out closes src"
    (let [src (async/chan)
          out (brotli/compress-channel src)]
      (async/close! out)
      (put!! src "frame")
      (is (false? (put!! src "frame")) "src is closed once a chunk finds out closed"))))
