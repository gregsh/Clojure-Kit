;; good
(when something
  (something-else))

(with-out-str
  (println "Hello, ")
  (println "world!"))

;; bad - four spaces
(when something
    (something-else))

;; bad
(let []
     (when something
        (something-else)))

;; bad - one space
(with-out-str
 (println "Hello, ")
 (println "world!"))

;; good
(filter even?
        (range 1 10))

;; bad
(filter even?
  (range 1 10))

;; good
(filter
 even?
 (range 1 10))

(or
 ala
 bala
 portokala)

;; bad - two-space indent
(filter
  even?
  (range 1 10))

(or
  ala
  bala
  portokala)

;; good
(let [thing1 "some stuff"
      thing2 "other stuff"]
  {:thing1 thing1
   :thing2 thing2})

;; bad
(let [thing1 "some stuff"
  thing2 "other stuff"]
  {:thing1 thing1
  :thing2 thing2})

;; good
(defn foo
  [x]
  (bar x))

;; good
(defn foo [x]
  (bar x))

;; bad
(defn foo
  [x] (bar x))

;; good
(defmethod foo :bar [x] (baz x))

(defmethod foo :bar
  [x]
  (baz x))

;; bad
(defmethod foo
  :bar
  [x]
  (baz x))

(defmethod foo
  :bar [x]
  (baz x))

;; good
(defn foo [x]
  (bar x))

;; good for a small function body
(defn foo [x] (bar x))

;; good for multi-arity functions
(defn foo
  ([x] (bar x))
  ([x y]
   (if (predicate? x)
     (bar x)
     (baz x))))

;; bad
(defn foo
  [x] (if (predicate? x)
        (bar x)
        (baz x)))

;; bad
(defn foo [x] (if (predicate? x)
  (bar x)
  (baz x)))

;; good
(defn foo
  "I have two arities."
  ([x]
   (foo x 1))
  ([x y]
   (+ x y)))

;; bad - extra indentation
(defn foo
  "I have two arities."
  ([x]
    (foo x 1))
  ([x y]
    (+ x y)))

;; good
(foo (bar baz) quux)

;; bad
(foo(bar baz)quux)
(foo ( bar baz ) quux)

;; good; single line
(when something
  (something-else))

;; bad; distinct lines
(when something
  (something-else)
)

;; good
(def min-rows 10)
(def max-rows 20)
(def min-cols 15)
(def max-cols 30)

(defn foo ...)

;; bad
(def x ...)
(defn foo ...)


;;; reformat>
;;; reformat>

;; good
(when something
  (something-else))

(with-out-str
  (println "Hello, ")
  (println "world!"))

;; bad - four spaces
(when something
  (something-else))

;; bad
(let []
  (when something
    (something-else)))

;; bad - one space
(with-out-str
  (println "Hello, ")
  (println "world!"))

;; good
(filter even?
        (range 1 10))

;; bad
(filter even?
        (range 1 10))

;; good
(filter
 even?
 (range 1 10))

(or
 ala
 bala
 portokala)

;; bad - two-space indent
(filter
 even?
 (range 1 10))

(or
 ala
 bala
 portokala)

;; good
(let [thing1 "some stuff"
      thing2 "other stuff"]
  {:thing1 thing1
   :thing2 thing2})

;; bad
(let [thing1 "some stuff"
      thing2 "other stuff"]
  {:thing1 thing1
   :thing2 thing2})

;; good
(defn foo
  [x]
  (bar x))

;; good
(defn foo [x]
  (bar x))

;; bad
(defn foo
  [x]
  (bar x))

;; good
(defmethod foo :bar [x] (baz x))

(defmethod foo :bar
  [x]
  (baz x))

;; bad
(defmethod foo :bar
  [x]
  (baz x))

(defmethod foo :bar
  [x]
  (baz x))

;; good
(defn foo [x]
  (bar x))

;; good for a small function body
(defn foo [x] (bar x))

;; good for multi-arity functions
(defn foo
  ([x] (bar x))
  ([x y]
   (if (predicate? x)
     (bar x)
     (baz x))))

;; bad
(defn foo
  [x]
  (if (predicate? x)
    (bar x)
    (baz x)))

;; bad
(defn foo [x]
  (if (predicate? x)
    (bar x)
    (baz x)))

;; good
(defn foo
  "I have two arities."
  ([x]
   (foo x 1))
  ([x y]
   (+ x y)))

;; bad - extra indentation
(defn foo
  "I have two arities."
  ([x]
   (foo x 1))
  ([x y]
   (+ x y)))

;; good
(foo (bar baz) quux)

;; bad
(foo (bar baz) quux)
(foo (bar baz) quux)

;; good; single line
(when something
  (something-else))

;; bad; distinct lines
(when something
  (something-else))

;; good
(def min-rows 10)
(def max-rows 20)
(def min-cols 15)
(def max-cols 30)

(defn foo ...)

;; bad
(def x ...)

(defn foo ...)