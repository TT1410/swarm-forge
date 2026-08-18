#!/usr/bin/env bb

(ns sprint-mockup
  "Serve the sprint mockup and drive it with squad-sprint-sim."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [squad-sprint-sim :as sim])
  (:import [java.net InetAddress ServerSocket]))

(def world (atom (sim/world)))

(defn html-path []
  (let [candidates [(fs/path (fs/cwd) "sprint-mockup.html")
                    (fs/path (fs/parent *file*) ".." ".." "sprint-mockup.html")]]
    (str (or (first (filter fs/exists? candidates))
             (first candidates)))))

(defn response [status content-type body]
  {:status status
   :headers {"Content-Type" content-type
             "Cache-Control" "no-store"}
   :body (str body)})

(defn send-response! [socket {:keys [status headers body]}]
  (let [bytes (.getBytes (str body) "UTF-8")
        out (.getOutputStream socket)
        status-text (case (int status) 200 "OK" 400 "Bad Request" 404 "Not Found" "OK")]
    (.write out (.getBytes
                 (str "HTTP/1.1 " status " " status-text "\r\n"
                      (str/join "" (map (fn [[k v]] (str k ": " v "\r\n")) headers))
                      "Content-Length: " (count bytes) "\r\n"
                      "Connection: close\r\n\r\n")
                 "UTF-8"))
    (.write out bytes)
    (.flush out)))

(defn read-headers [reader]
  (loop [headers {}]
    (let [line (.readLine reader)]
      (if (or (nil? line) (str/blank? line))
        headers
        (if-let [[_ k v] (re-matches #"([^:]+):\s*(.*)" line)]
          (recur (assoc headers (str/lower-case k) v))
          (recur headers))))))

(defn read-body [reader length]
  (if (pos? length)
    (let [buf (char-array length)
          n (.read reader buf 0 length)]
      (String. buf 0 (max 0 n)))
    ""))

(defn handle [method path body]
  (try
    (cond
      (and (= "GET" method) (= "/" path))
      (response 200 "text/html; charset=utf-8" (slurp (html-path)))

      (and (= "GET" method) (= "/api/state" path))
      (response 200 "application/json; charset=utf-8"
                (json/generate-string (sim/dashboard @world)))

      (and (= "POST" method) (= "/api/action" path))
      (let [cmd (json/parse-string body true)]
        (swap! world sim/apply-action cmd)
        (response 200 "application/json; charset=utf-8"
                  (json/generate-string (sim/dashboard @world))))

      (and (= "POST" method) (= "/api/reset" path))
      (do (reset! world (sim/world))
          (response 200 "application/json; charset=utf-8"
                    (json/generate-string (sim/dashboard @world))))

      :else (response 404 "text/plain; charset=utf-8" "Not found\n"))
    (catch Exception e
      (response 400 "application/json; charset=utf-8"
                (json/generate-string {:error (.getMessage e)})))))

(defn handle-client! [socket]
  (with-open [socket socket
              reader (java.io.BufferedReader.
                      (java.io.InputStreamReader. (.getInputStream socket) "UTF-8"))]
    (let [line (.readLine reader)
          [_ method target] (when line (re-matches #"([A-Z]+)\s+(\S+)\s+HTTP/.*" line))
          headers (read-headers reader)
          length (try (Long/parseLong (get headers "content-length" "0"))
                      (catch Exception _ 0))
          body (read-body reader length)
          path (when target (first (str/split target #"\?" 2)))]
      (send-response! socket (handle method path body)))))

(defn -main [& args]
  (let [port (if (seq args) (Long/parseLong (first args)) 4987)
        server (ServerSocket. port 50 (InetAddress/getByName "127.0.0.1"))
        url (str "http://127.0.0.1:" port "/")]
    (println "SPRINT_MOCKUP:" url)
    (try
      (let [open (cond
                   (= "Mac OS X" (System/getProperty "os.name")) ["open"]
                   :else ["xdg-open"])]
        (.start (ProcessBuilder. (conj (vec open) url))))
      (catch Exception _))
    (while true
      (handle-client! (.accept server)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
