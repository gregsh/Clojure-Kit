;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; destructuring
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(let [[a b c & d :as e] [1 2 3 4 5 6 7]]
  [a b c d e])

;->[1 2 3 (4 5 6 7) [1 2 3 4 5 6 7]]


(let [[[x1 y1][x2 y2]] [[1 2] [3 4]]]
  [x1 y1 x2 y2])

;->[1 2 3 4]

(let [[a b & c :as str] "asdjhhfdas"]
  [a b c str])

;->[\a \s (\d \j \h \h \f \d \a \s) "asdjhhfdas"]

(let [{a :a, b :b, c :c, :as m :or {a 2 b 3}}  {:a 5 :c 6}]
  [a b c m])

;->[5 3 6 {:c 6, :a 5}]

(let [m {:x/a 1, :y/b 2}
      {:keys [x/a y/b]} m]
  (+ a b))

;-> 3

(let [m {::x 42}
      {:keys [::x]} m]
   x )

;-> 42

(let [{j :j, k :k, i :i, [r s & t :as v] :ivec, :or {i 12 j 13}}
      {:j 15 :k 16 :ivec [22 23 24 25]}]
  [i j k r s t v])

;-> [12 15 16 22 23 (24 25) [22 23 24 25]]

; clojure 1.9
(let [m #:domain{:a 1, :b 2}
      {:domain/keys [a b]} m]
  [a b])

;-> [1 2]