(ns ^{:author "wikibooks" :doc "Clojure Programming"} wikibooks.sample
  (:require [clojure.set :as set]))

;; Clojure Programming/Examples/Lazy Fibonacci
(defn fib-seq [] ((fn rfib [a b] (cons a (lazy-seq (rfib b (+ a b))))) 0 1))

;; Recursive Fibonacci with any start point and the amount of numbers that you want
;; note that your 'start' parameter must be a vector with at least two numbers (the two which are your starting points)
(defn fib [start range]
  "Creates a vector of fibonnaci numbers"
  (if (<= range 0)
    start
    (recur
      (let [subvector (subvec start (- (count start) 2))
            x         (nth subvector 0)
            y         (nth subvector 1)
            z         (+ x y)]
        (conj start z))
      (- range 1))))
