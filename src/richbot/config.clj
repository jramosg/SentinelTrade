(ns richbot.config
  "Loads .env from the project root into the process environment map."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- parse-env-file [path]
  (when (.exists (io/file path))
    (->> (slurp path)
         str/split-lines
         (remove #(or (str/blank? %) (str/starts-with? % "#")))
         (map #(str/split % #"=" 2))
         (filter #(= 2 (count %)))
         (into {} (map (fn [[k v]] [k v]))))))

(def env
  "Merged map of .env overrides on top of actual process environment."
  (merge (into {} (System/getenv))
         (parse-env-file ".env")))

(defn require-env! [k]
  (or (get env k)
      (throw (ex-info (str "Missing required env var: " k) {:key k}))))
