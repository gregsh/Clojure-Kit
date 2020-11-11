;; imports
(do
  (refer 'clojure.set)
  (union)
  (intersection #{1} #{1 2})
  )
(do
  (refer 'clojure.set :as <warning>s3</warning> :exclude '[intersection])
  (union)
  (<warning>intersection</warning> #{1} #{1 2})
  (<warning>s3</warning>/union)
  )
(do
  (refer 'clojure.set :rename {union union_renamed})
  (union_renamed)
  (<warning>union</warning>)
  )

(do
  (require 'clojure.set :as <warning>s1</warning>)
  (<warning>union</warning>)
  (<warning>s1</warning>/union)
  (require '[clojure.set :as s1])
  (<warning>union</warning>)
  (s1/union)
  )
(do
  (require '[clojure [set :as s1] [data :as s2]])
  (<warning>union</warning>)
  (s1/union)
  )
(do
  (require '(clojure zip [set :as s2]))
  (<warning>union</warning>)
  (<warning>s1</warning>/union)
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
  (<warning>trim-newline</warning>)
  (when-let [nsname 'foo.core']
    (require (symbol nsname)))
  )
(do
  (ns nsns (:require [clojure.core]))
  (require '[clojure.missing]))

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
  (<warning>intersection</warning> #{1} #{1 2})
  )

(do
  (<warning>no-forward-def</warning>)
  (defn no-forward-def [] (no-forward-def))
  (no-forward-def)
  )
(do
  (defmacro some-macro)
  (some-macro allow-forward-decl-in-spec)
  (defn allow-forward-decl-in-spec)
  )
(do
  (alias 'clojure.set-alias clojure.set)
  (defn no-resolve-to-alias [] [<warning>clojure.set-alias</warning> clojure.set-alias/union])
  )

@<warning>not-to-resolve</warning>
#:some-ns {:some-key <warning>not-to-resolve</warning>}
(#'clojure.uuid/default-uuid-reader)
(clojure.uuid/<warning>default-uuid-reader</warning>)

(def #_comment named-zero 0)
{#_0 #_1 :a #_'(xxx)  'a :b #_:comm 'b #_2 #_3}
# #_comment dbg 10

::<warning>missing_alias</warning>/kwd

' ^meta #_ comment quoted_sym

(do
  (deftype Type [x y])
  (.equals (->Type 1 2) (<warning>map->Type</warning> {:x 1 :y 2}))
  (.-x (->Type 1 2))

  (defrecord Record [x y])
  (.equals (user/->Record 1 2) (map->Record {:x 1 :y 2}))
  (. (map->Record {:x 1 :y 2}) -x)
  )

(defn keys-destr [{:keys [clojure.core/abc missing_ns/edf ijk]
                   :or {abc 1, edf 2, ijk 3, <warning>missing_key</warning> 4}}]
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
  (alias buz 'clojure.set)
  (alias bar.buz 'clojure.core)
  (buz/union))