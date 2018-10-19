/*
 * Copyright 2016-present Greg Shrago
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.intellij.clojure

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.tree.IElementType
import org.intellij.clojure.psi.ClojureTypes
import javax.swing.Icon

/**
 * @author gregsh
 */
object ClojureConstants {

  @JvmStatic val CLJ_CORE_PATH = "/clojure/core.clj"
  @JvmStatic val CLJ_SPEC_PATH = "/clojure/spec/alpha.clj"
  @JvmStatic val CLJS_CORE_PATH = "/cljs/core.cljs"

  @JvmStatic val CLJS = "cljs"
  @JvmStatic val CLJ = "clj"
  @JvmStatic val CLJC = "cljc"

  @JvmStatic val NS_USER = "user"
  @JvmStatic val NS_SPEC = "clojure.spec"
  @JvmStatic val NS_SPEC_ALPHA = "clojure.spec.alpha"

  @JvmStatic val CLOJURE_CORE = "clojure.core"
  @JvmStatic val CLJS_CORE = "cljs.core"
  @JvmStatic val CORE_NAMESPACES = hashSetOf(CLOJURE_CORE, CLJS_CORE)

  @JvmStatic val SYMBOLIC_VALUES = hashSetOf("Inf", "-Inf", "NaN")

  @JvmStatic val SPECIAL_FORMS = "\\s+".toRegex().split("""
    def if do quote var
    recur throw
    try catch finally
    monitor-enter monitor-exit
    . new set!
     fn* let* loop* letfn* case* import* reify* deftype*
    in-ns load-file
    """.trim()).toSet()

  @JvmStatic val CONTROL_SYMBOLS = "\\s+".toRegex().split("""
    fn if do let loop
    try catch finally

    and or when when-not when-let when-first if-not if-let cond condp case when-some if-some
    for doseq dotimes while
    .. doto -> ->> as-> cond-> cond->> some-> some->>
    binding locking time with-in-str with-local-vars with-open with-out-str with-precision with-redefs with-redefs-fn
    lazy-cat lazy-seq delay
    assert comment doc
    """.trim()).toSet()

  @JvmStatic val DEF_ALIKE_SYMBOLS = "\\s+".toRegex().split("""
    def defn defn- defmacro defonce deftype defrecord defstruct defmulti defprotocol
    def-aset definline definterface
    define defcurried deftype* defrecord* create-ns
    """.trim()).toSet()

  @JvmStatic val FN_ALIKE_SYMBOLS = "\\s+".toRegex().split("""fn fn* rfn""").toSet()

  @JvmStatic val LET_ALIKE_SYMBOLS = "\\s+".toRegex().split("""
    let let* loop when-let when-some
    if-let if-some with-open when-first with-redefs
    for doseq dotimes
    with-local-vars
    """.trim()).toSet()

  @JvmStatic val NS_ALIKE_SYMBOLS = "\\s+".toRegex().split("""
    ns in-ns import require require-macros use refer refer-clojure alias
    """.trim()).toSet()

  @JvmStatic val TYPE_META_ALIASES = "\\s+".toRegex().split("""
    int ints long longs float floats double doubles void short shorts
     boolean booleans byte bytes char chars objects
    """.trim()).toSet()

  @JvmStatic val OO_ALIKE_SYMBOLS = "\\s+".toRegex().split("""
    defprotocol definterface deftype defrecord extend-protocol extend-type proxy reify
    """.trim()).toSet()

  @JvmStatic val LEIN_CONFIG = "project.clj"
  @JvmStatic val BOOT_CONFIG = "build.boot"
  @JvmStatic val DEPS_CONFIG = "deps.edn"

  @JvmStatic val LEIN_VM_OPTS = "clojure.kit.lein.vm.opts"

  // clojurescript-specific
  @JvmStatic val JS_OBJ = "#js"
  @JvmStatic val JS_NAMESPACES = hashSetOf("js", "Math", "goog")

  //core.cljs/special-symbol?
  @JvmStatic val CLJS_SPECIAL_FORMS = "\\s+".toRegex().split("""
    if def fn* do let* loop* letfn* throw try catch finally
    recur new set! ns deftype* defrecord* . js* quote var

    Infinity -Infinity
    """.trim()).toSet()

  @JvmStatic val CLJS_TYPES = "\\s+".toRegex().split("""
    default nil object boolean number string array function
    """.trim()).toSet()

  // java specific
  @JvmStatic val J_OBJECT = "java.lang.Object"
  @JvmStatic val J_CLASS = "java.lang.Class"
  @JvmStatic val J_READER = "java.io.Reader"
  @JvmStatic val J_WRITER = "java.io.Writer"
  @JvmStatic val C_VAR = "clojure.lang.Var"
  @JvmStatic val C_NAMESPACE = "clojure.lang.Namespace"

}

object ClojureIcons {
  @JvmStatic val CLOJURE_ICON = IconLoader.getIcon("/icons/clojure.svg")
  @JvmStatic val NAMESPACE = IconLoader.getIcon("/icons/namespace.svg")
  @JvmStatic val SYMBOL = IconLoader.getIcon("/icons/symbol.svg")
  @JvmStatic val DEFN = AllIcons.Nodes.Function
  @JvmStatic val MACRO = AllIcons.Nodes.AbstractMethod
  @JvmStatic val FIELD = AllIcons.Nodes.Field
  @JvmStatic val METHOD = AllIcons.Nodes.Method
}

fun getIconForType(type: String): Icon? = when {
  type == "keyword" -> null
  type == "ns" -> ClojureIcons.NAMESPACE
  type.startsWith("def") -> ClojureIcons.DEFN
  type == "defmacro" -> ClojureIcons.MACRO
  type == "method" -> ClojureIcons.METHOD
  ClojureConstants.NS_ALIKE_SYMBOLS.contains(type) -> ClojureIcons.NAMESPACE
  else -> ClojureIcons.SYMBOL
}

fun getTokenDescription(type: IElementType?) = when (type) {
  ClojureTypes.C_AT -> "Deref macro:<p>" +
      "Returns in-transaction-value of refs, agents, vars, atoms, delays, futures, promises.<p>" +
      "@form ⇒ (deref form)"
  ClojureTypes.C_BOOL -> "A boolean value: true or false."
  ClojureTypes.C_BRACE1,
  ClojureTypes.C_BRACE2 -> "Braces are for maps and sets:<p>" +
      "{:a 10 :b 5} ⇒ (hash-map :a 10, :b 5) ⇒ a map<p>" +
      "#{1 2 3} ⇒ (set '(1 2 3)) ⇒ a set<p>"
  ClojureTypes.C_BRACKET1,
  ClojureTypes.C_BRACKET2 -> "Brackets are for vectors:<p>" +
      "[1 2 3] ⇒ (vec '(1 2 3)) ⇒ a vector"
  ClojureTypes.C_CHAR -> "A character."
  ClojureTypes.C_COLON -> "Keywords<p>:name ⇒ (keyword 'name)"
  ClojureTypes.C_COLONCOLON -> "Keywords with a namespace:<p>::name ⇒ (keyword (str *ns*) \"name\")"
  ClojureTypes.C_COMMA -> "Commas are whitespaces."
  ClojureTypes.C_DOT -> "Java interop:<p>" +
      "(.method o) ⇒ o.method()<p>" +
      "(. o method args) ⇒ o.method(args)<p>" +
      "(. o (method args)) ⇒ o.method(args)<p>" +
      "(. o -field) ⇒ (.-prop o) ⇒ o.field<p>" +
      "(Object.) ⇒ (new Object) ⇒ new Object()"
  ClojureTypes.C_DOTDASH -> "Java interop:<p>" +
      "(.-prop o) ⇒ (. o -prop) ⇒ o.prop"
  ClojureTypes.C_HAT,
  ClojureTypes.C_SHARP_HAT -> "Metadata:<p>^(symbol|keyword|string|map) form<p>" +
      "^:key form ⇒ ^{:key true} form<p>" +
      "^String form ⇒ ^{:tag java.lang.String} form<p>" +
      "^\"tag\" form ⇒ ^{:tag \"tag\"} form<p>" +
      "Meta macro (#^) means the same as ^"
  ClojureTypes.C_HEXNUM -> "A hex number."
  ClojureTypes.C_NIL -> "1. a Java null<p>2. a false<p>3. an end-of-sequence marker"
  ClojureTypes.C_PAREN1,
  ClojureTypes.C_PAREN2 -> "Parentheses are for lists and anonymous functions:<p>" +
      "'(1 2 3) ⇒ (list 1 2 3) ⇒ a list<p>" +
      "#(+ % 1) ⇒ (fn [x] (+ x 1)"
  ClojureTypes.C_QUOTE -> "Quote sign:<p>'form ⇒ (quote form)"
  ClojureTypes.C_RATIO -> "Rational numbers:<p>" +
      "(/ 2 3) ⇒ 2/3"
  ClojureTypes.C_RDXNUM -> "Radix numbers<p>:" +
      "2r1011 ⇒ 11<p>" +
      "8r377 ⇒ 255<p>" +
      "16rCAFEBABE ⇒ 3405691582"
  ClojureTypes.C_SHARP -> "Dispatch macro:<p>" +
      "#{1 2 3} ⇒ (set '(1 2 3))<p>" +
      "#\"\\s*\\d+\" ⇒ (re-pattern \"\\\\s*\\\\d+\")<p>" +
      "#(+ % 1) ⇒ (fn [x] (+ x 1)<p>" +
      "#foo/bar [1 2 3] ⇒ tagged literal, e.g. #uuid \"uuid-str\", #inst \"date-str\", #js {}"
  ClojureTypes.C_SHARP_COMMENT -> "Comment macro:<p>Means ignore next form."
  ClojureTypes.C_SHARP_EQ -> "Evaluate macro:<p>#= (+ 1 2) ⇒ 3 ; at read time"
  ClojureTypes.C_SHARP_NS -> "Namespace map macro:<p>:ns {:key 1} ⇒ {:ns/key 1}"
  ClojureTypes.C_SHARP_QMARK -> "Conditional macro:<p>[1 #?(:clj [2 3])] ⇒ [1 [2 3]]"
  ClojureTypes.C_SHARP_QMARK_AT -> "Conditional splicing macro:<p>[1 #?@(:clj [2 3])] ⇒ [1 2 3]"
  ClojureTypes.C_SHARP_QUOTE -> "Var macro:<p>#'x ⇒ (var x)"
  ClojureTypes.C_SHARP_SYM -> "Symbolic value macro:<p>##Inf, ##-Inf, ##NaN"
  ClojureTypes.C_SYNTAX_QUOTE -> "Syntax quote:<p>" +
      "(let [a 10] `(str a \"=\" ~a)) ⇒ (clojure.core/str user/a \"=\" 10)"
  ClojureTypes.C_TILDE -> "Syntax unquote:<p>" +
      "(let [a 10] `(str a \"=\" ~a)) ⇒ (clojure.core/str user/a \"=\" 10)"
  ClojureTypes.C_TILDE_AT -> "Syntax unquote-splicing:<p>" +
      "(let [a [10 11]] `(str a \"=\" ~a)) ⇒ (clojure.core/str user/a \"=\" [10 11])"
  else -> null
}
