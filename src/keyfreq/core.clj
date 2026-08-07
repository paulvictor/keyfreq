(ns keyfreq.core
  (:require
   [clojure.string :as string]
   [clojure.data.json :as json]
   [clojure.math :as math]
   [clojure.java.io :as io])
  (:import [java.io DataInputStream]
           [java.nio ByteBuffer ByteOrder])
  (:gen-class))

(defn strip-prefix [input prefix]
  (if (string/starts-with? input prefix)
    (subs input (count prefix))
    input))

(def key-codes
  (let [key-to-code (json/read (io/reader "./codes.json"))]
    (into {}
          (map (fn [[k v]] [v k]))
          key-to-code)))

(def init-key-state {:ctrl 0, :meta 0, :shift 0, :alt 0, :out nil, :key-up 0})
(def EV_KEY 1)
(def HOLD_VAL 2)

(defn decode [buf]
  (let [bb (doto (ByteBuffer/wrap buf)
             (.order ByteOrder/LITTLE_ENDIAN))]
    {:sec   (.getLong bb 0)
     :usec  (.getLong bb 8)
     :type  (.getShort bb 16)
     :code  (key-codes (.getShort bb 18))
     :value (.getInt bb 20)}))

(defn read-event [^DataInputStream in]
  (let [buf (byte-array 24)]
    (.readFully in buf)
    (decode buf)))

(defn event-seq [^DataInputStream in]
  (repeatedly #(read-event in)))

(defn with-mods [cur-mods {:keys [code value sec usec]}]
  (let [key-pp #(let [ctrl-prefix (if (= 1 (:ctrl cur-mods)) "Ctrl-" "")
                     alt-prefix (if (= 1 (:alt cur-mods)) "Alt-" "")
                     shift-prefix (if (= 1 (:shift cur-mods)) "Shift-" "")
                     meta-prefix (if (= 1 (:meta cur-mods)) "Meta-" "")]
                  (str meta-prefix ctrl-prefix alt-prefix shift-prefix (strip-prefix code "KEY_")))
        usecs #(+ (* sec (math/pow 10 6)) usec)]
    (cond
      (or (= code "KEY_LEFTCTRL")
          (= code "KEY_RIGHTCTRL")) (assoc cur-mods :ctrl value :out nil :key-up value)
      (or (= code "KEY_LEFTALT")
          (= code "KEY_RIGHTALT")) (assoc cur-mods :alt value :out nil :key-up value)
      (or (= code "KEY_LEFTSHIFT")
          (= code "KEY_RIGHTSHIFT")) (assoc cur-mods :shift value :out nil :key-up value)
      (= code "KEY_LEFTMETA") (assoc cur-mods :meta value :out nil :key-up value)
      :else (assoc cur-mods
                   :out (key-pp)
                   :key-up value
                   :when (usecs)))))

(defn char-seq [input]
  (with-open [in (DataInputStream. (io/input-stream input))]
    (->> in
         (partial read-event)
         repeatedly
         (filter #(= (:type %) EV_KEY))
         (filter #(not= (:value %) HOLD_VAL))
         (reductions with-mods init-key-state)
         (filter (fn [{:keys [out key-up]}]
                   (and (not= nil out)
                        (= key-up 1))))
         (map #(select-keys % [:out :when]))
         (run! println))))

(defn -main [& args]
  (char-seq "/dev/input/event25" ; Make this configurable
            ))
