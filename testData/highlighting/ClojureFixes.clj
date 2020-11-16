;; imports
(do
  (refer 'clojure.set)
  (union)
  (intersection #{1} #{1 2})
  )
(do
  (refer 'clojure.set :as <warning descr="unable to resolve 's3'">s3</warning> :exclude '[intersection])
  (union)
  (<warning descr="unable to resolve 'intersection'">intersection</warning> #{1} #{1 2})
  (<warning descr="unable to resolve 's3'">s3</warning>/union)
  )
(do
  (refer 'clojure.set :rename {union union_renamed})
  (union_renamed)
  (<warning descr="unable to resolve 'union'">union</warning>)
  )

(do
  (require 'clojure.set :as <warning descr="unable to resolve 's1'">s1</warning>)
  (<warning descr="unable to resolve 'union'">union</warning>)
  (<warning descr="unable to resolve 's1'">s1</warning>/union)
  (require '[clojure.set :as s1])
  (<warning descr="unable to resolve 'union'">union</warning>)
  (s1/union)
  )
(do
  (require '[clojure [set :as s1] [data :as s2]])
  (<warning descr="unable to resolve 'union'">union</warning>)
  (s1/union)
  )
(do
  (require '(clojure zip [set :as s2]))
  (<warning descr="unable to resolve 'union'">union</warning>)
  (<warning descr="unable to resolve 's1'">s1</warning>/union)
  (s2/union)
  )
(do
  (require '(clojure zip [set :refer  :all]))
  (union)
  (intersection #{1} #{1 2})
  )
(do
  (require '[clojure.string :refer [blank?]])
  (blank?)
  (<warning descr="unable to resolve 'trim-newline'">trim-newline</warning>)
  (when-let [nsname 'foo.core']
    (require (symbol nsname)))
  )
(do
  (ns nsns (:require [clojure.core]))
  (require '[<warning descr="unable to resolve 'clojure.missing'">clojure.missing</warning>]))

(do
  (use '[clojure.set :as s1])
  (union)
  (s1/union)
  )
(do
  (use '(clojure zip [set :as s2]))
  (union)
  (s2/union)
  )
(do
  (use '(clojure zip [set :refer [union] :only [union]]))
  (union)
  (<warning descr="unable to resolve 'intersection'">intersection</warning> #{1} #{1 2})
  )

(do
  (<warning descr="unable to resolve 'no-forward-def'">no-forward-def</warning>)
  (defn no-forward-def [] (no-forward-def))
  (no-forward-def)
  )
(do
  (defmacro some-macro)
  (some-macro allow-forward-decl-in-spec)
  (defn allow-forward-decl-in-spec)
  )
(do
  (alias 'clojure.set-alias 'clojure.set)
  (defn no-resolve-to-alias [] [<warning descr="unable to resolve 'clojure.set-alias'">clojure.set-alias</warning> clojure.set-alias/union])
  )

@<warning descr="unable to resolve 'not-to-resolve'">not-to-resolve</warning>
#:some-ns {:some-key <warning descr="unable to resolve 'not-to-resolve'">not-to-resolve</warning>}
(#'clojure.uuid/default-uuid-reader)
(clojure.uuid/<warning descr="unable to resolve 'default-uuid-reader'">default-uuid-reader</warning>)

(def #_comment named-zero 0)
{#_0 #_1 :a #_'(xxx)  'a :b #_:comm 'b #_2 #_3}
# #_comment dbg 10

::<warning descr="unable to resolve 'missing_alias'">missing_alias</warning>/kwd

' ^meta #_ comment quoted_sym

(do
  (deftype Type [x y])
  (.equals (->Type 1 2) (<warning descr="unable to resolve 'map->Type'">map->Type</warning> {:x 1 :y 2}))
  (.-x (->Type 1 2))

  (defrecord Record [x y])
  (.equals (user/->Record 1 2) (map->Record {:x 1 :y 2}))
  (. (map->Record {:x 1 :y 2}) -x)
  )

(defn keys-destr [{:keys [clojure.core/abc missing_ns/edf ijk]
                   :or {abc 1, edf 2, ijk 3, <warning descr="unable to resolve 'missing_key'">missing_key</warning> 4}}]
  (print abc edf ijk))

(do
  (defprotocol Named (name [this]))
  (extend-protocol Named
    java.lang.Class
    (name [c] ((. c getName))))
  (extend Runnable
    Named
    {:name (fn [c] (.run c))})

  (import [java.io Writer])
  (proxy [Writer Runnable] []
         (close [] (do (.run this) (.flush this))))
  (reify
     Writer
     (close [] (do (.run this) (.flush this)))
     Runnable
     (run [])
   ))

(do
  (.. Integer (parseInt "12") floatValue)

  (.. (Object.) getClass isArray)

  (doto (Object.) (.getClass) (.getClass))
  (doto (Object.) .getClass .getClass)

  (-> (Object.) (.getClass) (.getName))
  (-> (Object.) .getClass .getName))

(do
  (alias <warning descr="unable to resolve 'bar'">bar</warning> <warning descr="unable to resolve 'clojure.set'">clojure.set</warning>)
  (alias 'buz 'clojure.set)
  (alias 'bar.buz 'clojure.core)
  (buz/union))