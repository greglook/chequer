(ns chequer.brotli
  "Streaming brotli compression over libbrotlienc, bound through jolt.ffi.

  An encoder compresses one stream spread over many chunks. Each chunk is flushed, so
  everything written so far is decodable as soon as it arrives, and the encoder keeps its
  window between chunks, so a chunk repeating an earlier one costs a few octets.

  Without libbrotlienc, `available?` is false and `encoder` returns nil, which callers
  read as \"send it uncompressed\"."
  (:require
    [clojure.core.async :as async]
    [clojure.string :as str]
    [jolt.ffi :as ffi]))


(def ^:private param-mode 0)
(def ^:private param-quality 1)
(def ^:private param-lgwin 2)
(def ^:private mode-text 1)

(def ^:private op-flush 1)
(def ^:private op-finish 2)


(defn- bind
  "The encoder functions. Binding is lazy, so the version is called to check that the
  library is there."
  []
  ((ffi/foreign-fn "BrotliEncoderVersion" [] :uint32))
  {:create (ffi/foreign-fn "BrotliEncoderCreateInstance" [:pointer :pointer :pointer] :pointer)
   :set-parameter (ffi/foreign-fn "BrotliEncoderSetParameter" [:pointer :int :uint32] :int)
   :compress-stream (ffi/foreign-fn "BrotliEncoderCompressStream"
                                    [:pointer :int :pointer :pointer :pointer :pointer :pointer] :int)
   :has-more-output (ffi/foreign-fn "BrotliEncoderHasMoreOutput" [:pointer] :int)
   :is-finished (ffi/foreign-fn "BrotliEncoderIsFinished" [:pointer] :int)
   :take-output (ffi/foreign-fn "BrotliEncoderTakeOutput" [:pointer :pointer] :pointer)
   :destroy (ffi/foreign-fn "BrotliEncoderDestroyInstance" [:pointer] :void)})


(def ^:private native
  "The bound encoder functions, or nil when libbrotlienc can't be found. The library is
  declared as an optional native in deps.edn; loading it here covers a run without it."
  (delay
    (or (try (bind) (catch Throwable _ nil))
        (some (fn [path]
                (try (ffi/load-library path) (bind) (catch Throwable _ nil)))
              ["libbrotlienc.so.1" "libbrotlienc.so" "libbrotlienc.1.dylib" "libbrotlienc.dylib"]))))


(defn available?
  "True when libbrotlienc could be bound."
  []
  (some? @native))


(defn acceptable?
  "True when an Accept-Encoding header value allows br: an entry naming it without q=0,
  or failing that, a * without q=0."
  [accept-encoding]
  (let [entries (for [entry (str/split (str accept-encoding) #",")
                      :let [[coding & params] (map str/trim (str/split entry #";"))]
                      :when (seq coding)]
                  [(str/lower-case coding)
                   (not-any? #(re-matches #"(?i)q\s*=\s*0(\.0{0,3})?" %) params)])
        verdict (fn [coding] (some (fn [[c ok]] (when (= c coding) [ok])) entries))]
    (first (or (verdict "br") (verdict "*") [false]))))


;; Scratch slots for CompressStream's in/out arguments, by byte offset.
(def ^:private o-avail-in 0)
(def ^:private o-next-in 8)
(def ^:private o-avail-out 16)
(def ^:private o-next-out 24)
(def ^:private o-take-size 32)
(def ^:private scratch-size 40)


(defn encoder
  "A streaming brotli encoder, or nil when libbrotlienc is unavailable. Feed it with
  `compress-chunk!`, end the stream with `finish!`, and release it with `close!` (finish
  releases it too). Not safe for concurrent use. opts:

    :quality  0..11 (default 5)
    :lgwin    10..24 (default 18), the log2 of the window. The encoder holds a ring
              buffer of this size for as long as the stream is open, so it adds up
              across many open streams."
  ([] (encoder {}))
  ([{:keys [quality lgwin] :or {quality 5 lgwin 18}}]
   (when-let [{:keys [create set-parameter destroy] :as l} @native]
     (let [state (create ffi/null ffi/null ffi/null)]
       (when (ffi/null? state)
         (throw (ex-info "brotli: encoder creation failed" {})))
       (when-not (and (pos? (set-parameter state param-mode mode-text))
                      (pos? (set-parameter state param-quality quality))
                      (pos? (set-parameter state param-lgwin lgwin)))
         (destroy state)
         (throw (ex-info "brotli: bad encoder parameters" {:quality quality :lgwin lgwin})))
       {:lib l
        :state state
        :scratch (ffi/alloc scratch-size)
        ;; the input buffer grows to the largest chunk seen and is reused
        :in (atom {:buf (ffi/alloc 4096) :cap 4096})
        :open (atom true)}))))


(defn close!
  "Release the encoder's native state without finishing the stream. Idempotent."
  [{:keys [lib state scratch in open]}]
  (when (compare-and-set! open true false)
    ((:destroy lib) state)
    (ffi/free scratch)
    (ffi/free (:buf @in)))
  nil)


(defn- stage-input!
  "Copy src into the encoder's input buffer, growing it when src is larger."
  [{:keys [in]} ^bytes src]
  (let [n (alength src)
        {:keys [buf cap]} @in]
    (if (<= n cap)
      (do (when (pos? n) (ffi/write-array buf src)) buf)
      (let [cap' (max n (* 2 cap))
            buf' (ffi/alloc cap')]
        (ffi/free buf)
        (reset! in {:buf buf' :cap cap'})
        (ffi/write-array buf' src)
        buf'))))


(defn- pump!
  "Run src through the encoder with operation op until op is done, and return the output
  as a byte array. A flush is done when the input is consumed and no output is pending; a
  finish when the encoder says so."
  [{{:keys [compress-stream has-more-output is-finished take-output]} :lib
    :keys [state scratch open] :as e}
   ^bytes src op]
  (when-not @open
    (throw (ex-info "brotli: encoder is closed" {})))
  (ffi/write scratch :size_t (alength src) o-avail-in)
  (ffi/write scratch :pointer (stage-input! e src) o-next-in)
  ;; no output buffer of our own: the encoder's is drained with TakeOutput
  (ffi/write scratch :size_t 0 o-avail-out)
  (ffi/write scratch :pointer ffi/null o-next-out)
  (let [slot #(ffi/slice scratch %)
        chunks (loop [acc []]
                 (when (zero? (compress-stream state op (slot o-avail-in) (slot o-next-in)
                                               (slot o-avail-out) (slot o-next-out) ffi/null))
                   (throw (ex-info "brotli: compression failed" {})))
                 (ffi/write scratch :size_t 0 o-take-size)
                 (let [out (take-output state (slot o-take-size))
                       n (ffi/read scratch :size_t o-take-size)
                       acc (cond-> acc (pos? n) (conj (ffi/read-array out n)))]
                   (if (if (= op op-finish)
                         (pos? (is-finished state))
                         (and (zero? (ffi/read scratch :size_t o-avail-in))
                              (zero? (has-more-output state))))
                     acc
                     (recur acc))))]
    (if (= 1 (count chunks))
      (first chunks)
      (byte-array (mapcat seq chunks)))))


(defn compress-chunk!
  "Compress src (a byte array) as the next part of the stream, flushed so the receiver can
  decode everything up to here. Returns the compressed octets."
  ^bytes [e ^bytes src]
  (pump! e src op-flush))


(defn finish!
  "End the stream: returns its final octets and releases the encoder."
  ^bytes [e]
  (try
    (pump! e (byte-array 0) op-finish)
    (finally
      (close! e))))


(defn compress-channel
  "A channel carrying the chunks (strings or byte arrays) of src as one brotli stream, or
  nil when no encoder could be made. Each chunk is flushed so it can be decoded on arrival,
  and src closing finishes the stream. Closing the returned channel closes src at its next
  chunk, so src's producer sees its put fail as it would have without us."
  ([src] (compress-channel src {}))
  ([src opts]
   (when-let [e (try (encoder opts) (catch Throwable _ nil))]
     (let [out (async/chan)]
       (async/go
         (try
           (loop []
             (if-some [v (async/<! src)]
               (if (async/>! out (compress-chunk! e (if (string? v) (.getBytes ^String v "UTF-8") v)))
                 (recur)
                 (async/close! src))
               (do (async/>! out (finish! e))
                   (async/close! out))))
           (catch Throwable _
             (async/close! out)
             (async/close! src))
           (finally
             (close! e))))
       out))))
