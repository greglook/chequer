(ns chequer.crypto
  "Random tokens, hashing, HMAC and base64url encoding, over jolt-crypto's OpenSSL shims."
  (:require
    [jolt.crypto :as crypto])
  (:import
    (java.security
      MessageDigest)
    (java.util
      Base64)
    (javax.crypto
      Mac)
    (javax.crypto.spec
      SecretKeySpec)))


(defn- utf8-bytes
  [s]
  (.getBytes ^String s "UTF-8"))


(defn base64url
  "Encode bytes, or a string's UTF-8 bytes, as unpadded base64url."
  [x]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (if (string? x) (utf8-bytes x) x)))


(defn unbase64url
  "Decode base64url text, padded or not, to bytes. Returns nil if s isn't valid base64url."
  [s]
  (when (and (string? s) (re-matches #"[A-Za-z0-9_-]*=*" s))
    (try
      (.decode (Base64/getUrlDecoder) ^String s)
      (catch Exception _ nil))))


(defn unhex
  "Decode hex text to bytes. Returns nil if s isn't an even number of hex digits."
  [s]
  (when (and (string? s) (re-matches #"(?:[0-9a-fA-F]{2})*" s))
    (byte-array (map #(unchecked-byte (Integer/parseInt (apply str %) 16)) (partition 2 s)))))


(defn random-token
  "n (default 32) random bytes from the OS CSPRNG, as unpadded base64url."
  ([] (random-token 32))
  ([n] (base64url (crypto/random-bytes n))))


(defn sha256
  "The SHA-256 digest of the string s's UTF-8 bytes."
  [s]
  (.digest (MessageDigest/getInstance "SHA-256") (utf8-bytes s)))


(defn hmac-sha256
  "The HMAC-SHA256 of the string s's UTF-8 bytes under the key k (bytes)."
  [k s]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. k "HmacSHA256"))
    (.doFinal mac (utf8-bytes s))))


(defn constant-time=
  "True if strings a and b are equal, taking time that depends only on their lengths."
  [a b]
  (and (string? a)
       (string? b)
       (= (count a) (count b))
       (zero? (reduce bit-or 0 (map #(bit-xor (int %1) (int %2)) a b)))))
