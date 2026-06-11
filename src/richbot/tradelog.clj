(ns richbot.tradelog
  "Append-only CSV log of executed orders (one line per order).
  Keep these files: every sale is a taxable event."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time Instant]))

(def ^:private header
  (str "timestamp,venue,symbol,side,executed-qty,avg-price,"
       "quote-qty,order-id,status"))

(def ^:private equity-header
  "timestamp,venue,symbol,price,cash,base-qty,equity,peak,drawdown,signal")

(defn append!
  "Append an executed order response to the CSV at path, creating
  the file with a header when missing."
  [path {:keys [venue symbol side resp]}]
  (let [qty (parse-double (:executedQty resp "0"))
        quote-qty (parse-double (:cummulativeQuoteQty resp "0"))
        avg (when (pos? qty) (/ quote-qty qty))
        line (str/join "," [(str (Instant/now)) (name venue) symbol
                            side qty (or avg "") quote-qty
                            (:orderId resp) (:status resp)])]
    (when-not (.exists (io/file path))
      (spit path (str header "\n")))
    (spit path (str line "\n") :append true)))

(defn append-equity!
  "Append one mark-to-market equity snapshot to a CSV file."
  [path {:keys [venue symbol price cash base-qty equity peak signal]}]
  (let [drawdown (if (and peak (pos? peak))
                   (- 1.0 (/ equity peak))
                   0.0)
        line (str/join "," [(str (Instant/now)) (name venue) symbol
                            price cash base-qty equity peak drawdown
                            (name (or signal :none))])]
    (when-not (.exists (io/file path))
      (spit path (str equity-header "\n")))
    (spit path (str line "\n") :append true)))
