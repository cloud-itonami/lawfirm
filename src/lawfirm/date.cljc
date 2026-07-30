(ns lawfirm.date
  "Portable civil-date arithmetic over ISO `\"YYYY-MM-DD\"` strings.

  Deliberately hand-rolled integer math rather than `java.time`: every other
  namespace in this repo is `.cljc` that must run on the runtimes CLAUDE.md
  ranks above the JVM (kotoba wasm > clojurewasm > ClojureScript > nbb), and a
  `#?(:clj java.time :cljs js/Date)` split in the middle of 期限 math is
  exactly where a JVM-only assumption would hide. `js/Date` is also wrong for
  this job — it is a timestamp with a timezone, and a 出訴期限 is a civil date
  with neither.

  The conversion is Howard Hinnant's `days_from_civil` / `civil_from_days`
  (public domain, `howardhinnant.github.io/date_algorithms.html`), proleptic
  Gregorian, exact in integer arithmetic for any year this practice will see.

  ISO date strings also sort lexicographically, so ordering and range
  comparisons elsewhere in the repo use plain `compare` — only *differences*
  and *offsets* need this namespace."
  (:require [clojure.string :as str]))

(defn valid?
  "True for a syntactically well-formed ISO calendar date string."
  [s]
  (boolean (and (string? s) (re-matches #"\d{4}-\d{2}-\d{2}" s))))

(defn- parse-int [s]
  #?(:clj (Long/parseLong s)
     :cljs (js/parseInt s 10)))

(defn parts
  "`\"2026-07-30\"` -> `[2026 7 30]`, or nil when malformed."
  [s]
  (when (valid? s)
    (mapv parse-int (str/split s #"-"))))

(defn- pad2 [n] (if (< n 10) (str "0" n) (str n)))

(defn- days-from-civil
  "Days since 1970-01-01 (negative before). Hinnant's algorithm."
  [y m d]
  (let [y (if (<= m 2) (dec y) y)
        era (quot (if (>= y 0) y (- y 399)) 400)
        yoe (- y (* era 400))                                  ; [0, 399]
        doy (+ (quot (+ (* 153 (+ m (if (> m 2) -3 9))) 2) 5) (dec d))
        doe (+ (* yoe 365) (quot yoe 4) (- (quot yoe 100)) doy)]
    (+ (* era 146097) doe -719468)))

(defn- civil-from-days
  "Inverse of `days-from-civil` -> `[y m d]`."
  [z]
  (let [z (+ z 719468)
        era (quot (if (>= z 0) z (- z 146096)) 146097)
        doe (- z (* era 146097))                               ; [0, 146096]
        yoe (quot (- doe (quot doe 1460) (- (quot doe 36524)) (quot doe 146096)) 365)
        y (+ yoe (* era 400))
        doy (- doe (+ (* 365 yoe) (quot yoe 4) (- (quot yoe 100))))
        mp (quot (+ (* 5 doy) 2) 153)
        d (inc (- doy (quot (+ (* 153 mp) 2) 5)))
        m (+ mp (if (< mp 10) 3 -9))]
    [(if (<= m 2) (inc y) y) m d]))

(defn epoch-day
  "Days since the epoch for an ISO date string, or nil when malformed."
  [s]
  (when-let [[y m d] (parts s)]
    (days-from-civil y m d)))

(defn from-epoch-day
  "Days since the epoch -> ISO date string."
  [n]
  (let [[y m d] (civil-from-days n)]
    (str y "-" (pad2 m) "-" (pad2 d))))

(defn plus-days
  "ISO date `n` days later (negative `n` goes back), or nil when malformed."
  [s n]
  (when-let [e (epoch-day s)]
    (from-epoch-day (+ e n))))

(defn- last-day-of-month [y m]
  (let [lengths [31 28 31 30 31 30 31 31 30 31 30 31]
        leap? (and (zero? (mod y 4)) (or (pos? (mod y 100)) (zero? (mod y 400))))]
    (if (and (= m 2) leap?) 29 (nth lengths (dec m)))))

(defn plus-months
  "ISO date `n` calendar months later, clamped to the end of the target month.

  Clamping is the 民法143条2項但書 rule, not a convenience: a period of
  「1か月」 running from 1月31日 ends on 2月28日 (or 29日), because the
  target month has no corresponding day. Naive day arithmetic would put it in
  March and lose the deadline."
  [s n]
  (when-let [[y m d] (parts s)]
    (let [total (+ (* y 12) (dec m) n)
          y' (quot total 12)
          m' (inc (mod total 12))
          [y' m'] (if (neg? (mod total 12)) [(dec y') (inc (+ 12 (mod total 12)))] [y' m'])
          d' (min d (last-day-of-month y' m'))]
      (str y' "-" (pad2 m') "-" (pad2 d')))))

(defn days-between
  "`to` minus `from` in whole days — negative when `to` precedes `from`.
  nil when either date is malformed."
  [from to]
  (let [a (epoch-day from) b (epoch-day to)]
    (when (and a b) (- b a))))

(defn before?
  "True when `a` strictly precedes `b`. Both must be well-formed."
  [a b]
  (boolean (and (valid? a) (valid? b) (neg? (compare a b)))))

(defn on-or-before?
  [a b]
  (boolean (and (valid? a) (valid? b) (not (pos? (compare a b))))))
