(ns keyfreq.core
  (:require
   [clojure.string :as string]
   [clojure.data.json :as json]
   [clojure.math :as math]
   [clojure.core.async :as a]
   [clojure.tools.cli :refer [parse-opts]]
   [clojure.java.io :as io])
  (:import [java.io DataInputStream FileWriter]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.file Path Paths Files]
           [java.lang Runtime Thread Integer])
  (:gen-class))

(defn strip-prefix [input prefix]
  (if (string/starts-with? input prefix)
    (subs input (count prefix))
    input))

(def key-codes
  (let [key-to-code (json/read (io/reader (io/resource "codes.json")))]
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

(defn char-seq [input-stream]
  (->> input-stream
       (partial read-event)
       repeatedly
       (filter #(= (:type %) EV_KEY))
       (filter #(not= (:value %) HOLD_VAL))
       (reductions with-mods init-key-state)
       (filter (fn [{:keys [out key-up]}]
                 (and (not= nil out)
                      (= key-up 1))))
       (map #(select-keys % [:out :when]))))

(defn handler [chan label ^Path prefix]
  (let [out
        (FileWriter.
         (.toString (.resolve prefix (str (name label) "-keys.out"))) true)]
    (a/thread
      (loop []
        (let [c (a/<!! chan)]
          (if (some? c)
            (do
              (doto out (.write c) (.write "\n") (.flush))
              (recur))
            (doto out .flush .close)))))))

(defn bigrams-within [interval]
  (fn [rf]
    (let [vstate (volatile! {:prev nil
                             :prev-when nil})]
      (fn
        ([] (rf))
        ([result] ; when stream has ended, nothing to do, no more bigrams.
         (rf result))
        ([result {:keys [out when]}]
         (if (not= out "BACKSPACE")
           (let [prev (:prev @vstate)
                 prev-when (:prev-when @vstate)]
             (vreset! vstate {:prev out, :prev-when when})
             (if (and prev prev-when
                      (> interval (- when prev-when)))
               (rf result
                   (format "%s,%s" prev out)) ; Within the time limit, emit
               result))
           result))))))

(def cli-opts-spec
  (letfn [(str->path [s]
            (Paths/get s (make-array String 0)))]
    [["-k" "--keyboard DEVICE" "Device to start tracking keys"]
     ["-t" "--bigrams-within TIME" "Time between keystrokes to count towards bigrams"
      :parse-fn #(Integer/parseInt %)
      :default 500000]
     ["-d" "--directory DIRECTORY" "File path used as prefix for the output files"
      :parse-fn str->path
      :default (str->path (System/getProperty "user.dir"))]]))

(defn -main [& args]
  (let [cli-opts (:options
                  (parse-opts args cli-opts-spec))
        src (a/chan 30)
        mux (a/mult src)
        unigram-tap (a/tap mux (a/chan 10 (map :out)))
        bigram-tap (a/tap mux (a/chan 10 (bigrams-within (:bigrams-within cli-opts))))
        in (DataInputStream. (io/input-stream "/dev/input/event26"))
        runtime (Runtime/getRuntime)]
    (.addShutdownHook runtime (Thread. (fn []
                                         (a/close! src))))
    (handler unigram-tap :unigram (:directory cli-opts))
    (handler bigram-tap :bigram (:directory cli-opts))
    (->> in
         char-seq
         (run! #(a/>!! src %)))))
