(ns richbot.alert
  "Optional Telegram alerts for live trading events."
  (:require [clojure.string :as str]
            [richbot.config :as cfg])
  (:import [java.net HttpURLConnection URLEncoder]))

(defn- env [k]
  (not-empty (get cfg/env k)))

(defn enabled? []
  (boolean (and (env "TELEGRAM_BOT_TOKEN")
                (env "TELEGRAM_CHAT_ID"))))

(defn- encode [x]
  (URLEncoder/encode (str x) "UTF-8"))

(defn- post!
  [params]
  (when (enabled?)
    (try
      (let [token (env "TELEGRAM_BOT_TOKEN")
            chat-id (env "TELEGRAM_CHAT_ID")
            body (str "chat_id=" (encode chat-id)
                      "&"
                      (->> params
                           (map (fn [[k v]]
                                  (str (name k) "=" (encode v))))
                           (str/join "&")))
            url (java.net.URL.
                 (str "https://api.telegram.org/bot" token
                      "/sendMessage"))
            conn ^HttpURLConnection (.openConnection url)]
        (doto conn
          (.setRequestMethod "POST")
          (.setConnectTimeout 10000)
          (.setReadTimeout 10000)
          (.setDoOutput true)
          (.setRequestProperty "Content-Type"
                               "application/x-www-form-urlencoded"))
        (with-open [out (.getOutputStream conn)]
          (.write out (.getBytes body "UTF-8")))
        (when (>= (.getResponseCode conn) 400)
          (println "ALERT ERROR: Telegram HTTP" (.getResponseCode conn))))
      (catch Exception e
        (println "ALERT ERROR:" (str e))))))

(defn send!
  "Send a Telegram alert when TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID
  are configured. Alert failures are printed but never stop trading."
  [text]
  (post! {:text text}))

(defn send-html!
  "Send a Telegram alert using Telegram's safe HTML parse mode."
  [html]
  (post! {:text html :parse_mode "HTML"}))
