(ns richbot.indicators)

(defn sma
  "Simple moving average of period n over xs.
  Returns a seq aligned with xs; nil until n values are available."
  [n xs]
  (concat (repeat (dec n) nil)
          (map #(/ (reduce + %) (double n)) (partition n 1 xs))))

(defn rolling-max
  "Max of the trailing n values, aligned with xs; nil until n values."
  [n xs]
  (concat (repeat (dec n) nil)
          (map #(reduce max %) (partition n 1 xs))))

(defn rolling-min
  "Min of the trailing n values, aligned with xs; nil until n values."
  [n xs]
  (concat (repeat (dec n) nil)
          (map #(reduce min %) (partition n 1 xs))))

(defn atr
  "Simple average true range of period n, aligned with candles."
  [n candles]
  (let [trs (map (fn [prev-close {:keys [high low]}]
                   (let [hl (- high low)]
                     (if prev-close
                       (max hl
                            (Math/abs (- high prev-close))
                            (Math/abs (- low prev-close)))
                       hl)))
                 (cons nil (map :close candles))
                 candles)]
    (concat (repeat (dec n) nil)
            (map #(/ (reduce + %) (double n)) (partition n 1 trs)))))

(defn rsi
  "Wilder's RSI of period n over closes, aligned; nil for the first n."
  [n closes]
  (let [deltas (map - (rest closes) closes)
        gains (map #(max % 0.0) deltas)
        losses (map #(max (- %) 0.0) deltas)
        smooth (fn [xs]
                 (let [head (take n xs)]
                   (when (= n (count head))
                     (reductions
                      (fn [a x] (/ (+ (* a (dec n)) x) n))
                      (/ (reduce + head) (double n))
                      (drop n xs)))))
        rs->rsi (fn [g l]
                  (if (zero? l)
                    100.0
                    (- 100.0 (/ 100.0 (+ 1.0 (/ g l))))))]
    (concat (repeat n nil)
            (map rs->rsi (smooth gains) (smooth losses)))))
