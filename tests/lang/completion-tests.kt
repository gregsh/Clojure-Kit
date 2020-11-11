package org.intellij.clojure.lang

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.Lookup.REPLACE_SELECT_CHAR
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * @author gregsh
 */
class ClojureCompletionTest : BasePlatformTestCase() {
  companion object {
    val NS_KEY = "namespace/keyword"
    val NS_ALIAS = "(alias 'namespace 'some-ns)"
    val STR_ALIAS = "(alias 'str 'clojure.string)"
  }

  override fun setUp() {
    super.setUp()
    Registry.get("ide.completion.variant.limit").setValue(10000)
  }

  fun testCoreNs1() = doTest("<caret>", "clojure.core", "clojure.core")
  fun testCoreNs2() = doTest(":<caret>", "clojure.core", ":clojure.core")
  fun testCoreDefn1() = doTest("(<caret>)", "defn", "(defn)")

  fun testRefsInBindings1() = doNegTest("(let [abc 10 <caret>])", "abc")
  fun testRefsInBindings2() = doNegTest("(let [abc 10 <caret>])", "clojure.core")
  fun testRefsInBindings3() = doTest("(let [abc 10 edf <caret>])", "abc")
  fun testRefsInBindings4() = doTest("(let [{:keys [<caret>]} m])", "clojure.core")

  fun testNothingInName() = doNegTest("(def strip) (def stri<caret>)", "clo-def", "clojure.string", ":strict")

  fun testKeyword1() = ":keyword".let { doPosTest("$it :<caret>", it) }
  fun testKeyword2() = ":keyword".let { doTest("$it :key<caret>", it, "$it $it") }
  fun testKeyword3() = ":keyword".let { doNegTest("$it <caret>", it) }
  fun testKeyword4() = ":keyword".let { doNegTest("$it ${it}11 ${it}22 $it<caret>", it) }
  fun testKeyword5() = ":namespace/keyword".let { doNegTest("$it ${it}11 ${it}22 $it<caret>", it) }
  fun testKeywordMeta1() = ":keyword".let { doPosTest("^meta $it ^meta :<caret>", it) }
  fun testKeywordMeta2() = ":keyword".let { doTest("^meta $it ^meta :key<caret>", it, "^meta $it ^meta $it") }
  fun testKeywordUser1() = "::keyword".let { doTest("$it :<caret>", it, "$it $it") }
  fun testKeywordUser2() = "::keyword".let { doTest("$it ::key<caret>", it, "$it $it") }
  fun testKeywordUser3() = "::keyword".let { doNegTest("$it <caret>", it) }
  fun testKeywordNs1() = ":$NS_KEY".let { doPosTest("$it :<caret>", it) }
  fun testKeywordNs2() = ":$NS_KEY".let { doTest("$it :key<caret>", it, "$it $it") }
  fun testKeywordNs3() = ":$NS_KEY".let { doNegTest("$it <caret>", it) }
  fun testKeywordNs4() = ":$NS_KEY".let { doTest("$it :namespace/<caret>", it, "$it $it") }
  fun testKeywordNs5() = ":$NS_KEY".let { doTest("$it :namespace/key<caret>", it, "$it $it") }
  fun testKeywordNs6() = ":$NS_KEY".let { doTest("$it :name<caret>", it, "$it $it") }
  fun testKeywordNs7() = ":$NS_KEY".let { doTest("$it :nk<caret>", it, "$it $it") }
  fun testKeywordNs8() = ":$NS_KEY".let { doNegTest("$it nk<caret>", it) }
  fun testKeywordNsUser1() = "::$NS_KEY".let { doTest("$NS_ALIAS $it :<caret>", it, "$NS_ALIAS $it $it") }
  fun testKeywordNsUser2() = "::$NS_KEY".let { doTest("$NS_ALIAS $it :key<caret>", it, "$NS_ALIAS $it $it") }
  fun testKeywordNsUser3() = "::$NS_KEY".let { doNegTest("$NS_ALIAS $it <caret>", it) }
  fun testKeywordNsUser4() = "::$NS_KEY".let { doTest("$NS_ALIAS $it ::namespace/<caret>", it, "$NS_ALIAS $it $it") }
  fun testKeywordNsUser5() = "::$NS_KEY".let { doTest("$NS_ALIAS $it ::namespace/key<caret>", it, "$NS_ALIAS $it $it") }
  fun testKeywordNsUser6() = "::$NS_KEY".let { doTest("$NS_ALIAS $it ::name<caret>", it, "$NS_ALIAS $it $it") }
  fun testKeywordNsUser8() = "::$NS_KEY".let { doTest("$NS_ALIAS $it ::nk<caret>", it, "$NS_ALIAS $it $it") }
  fun testKeywordNsUser4a() = "::$NS_KEY".let { doTest("$NS_ALIAS $it :some-ns/<caret>", it, "$NS_ALIAS $it ::namespace/keyword") }
  fun testKeywordNsUser5a() = "::$NS_KEY".let { doTest("$NS_ALIAS $it :some-ns/key<caret>", it, "$NS_ALIAS $it ::namespace/keyword") }
  fun testKeywordNsUser6a() = "::$NS_KEY".let { doTest("$NS_ALIAS $it :some<caret>", it, "$NS_ALIAS $it $it") }
  fun testKeywordNsUser8a() = "::$NS_KEY".let { doTest("$NS_ALIAS $it :sk<caret>", it, "$NS_ALIAS $it $it") }
  fun testKeywordNsUser9() = "::$NS_KEY".let { doNegTest("$NS_ALIAS $it nk<caret>", it) }

  fun testNsAlias1() = "namespace".let { doPosTest("$NS_ALIAS (<caret>", it) }
  fun testNsAlias2() = "namespace".let { doPosTest("$NS_ALIAS (<caret>/", it) }
  fun testNsAlias3() = "namespace".let { doPosTest("$NS_ALIAS (<caret>/xxx", it) }
  fun testNsAlias4() = "namespace".let { doPosTest("$NS_ALIAS (nam<caret>", it) }
  fun testNsAlias5() = "namespace".let { doPosTest("$NS_ALIAS (nam<caret>/", it) }
  fun testNsAlias6() = "namespace".let { doPosTest("$NS_ALIAS (nam<caret>/xxx", it) }

  fun testFqn1() = doTest("(clojure.string/<caret>)", "blank?", "(clojure.string/blank?)")
  fun testFqn2() = "clojure.string/blank?".let { doTest("(bla<caret>)", it, "($it)", 2) }
  fun testFqn3() = "clojure.string/blank?".let { doTest("(clostribla<caret>)", it, "($it)", 2) }
  fun testFqn4() = doTest("(clojure.str<caret>ing/blank?)", "clojure.string", "(clojure.string/blank?)")
  fun testFqn2a() = "str/blank?".let { doTest("$STR_ALIAS (bla<caret>)", it, "$STR_ALIAS ($it)", 2) }
  fun testFqn3a() = "str/blank?".let { doTest("$STR_ALIAS (clostribla<caret>)", it, "$STR_ALIAS ($it)", 2) }
  fun testFqn4a() = doTest("$STR_ALIAS (s<caret>tr/blank?)", "str", "$STR_ALIAS (str/blank?)")

  fun testInsideImport1() = doTest("(require '[<caret> :refer [blank?]])", "clojure.string")
  fun testInsideImport2() = doNegTest("(require '[<caret> :refer [blank?]])", "def")
  fun testInsideImport3() = doTest("(require '[clojure.string :refer [<caret>]])", "blank?")
  fun testInsideImport4() = doNegTest("(require '[clojure.string :refer [<caret>]])", "def")

  fun testJava1() = doPosTest("(defn a [^java.util.SortedSet a] (.<caret> a))", "comparator", "size", "contains", "add")


  private fun doTest(text: String, select: String,
                     expected: String = text.replace("<caret>", select),
                     count: Int = 1) = myFixture.run {
    configureByText("a.clj", text)
    complete(CompletionType.BASIC, count)?.let { variants ->
      val chosen = variants.find { it.lookupString == select }
      assertNotNull("'$select' variant not suggested", chosen)
      lookup.currentItem = chosen
      finishLookup(REPLACE_SELECT_CHAR)
    }
    checkResult(expected)
  }

  private fun doNegTest(text: String, vararg select: String) = myFixture.run {
    configureByText("a.clj", text)
    complete(CompletionType.BASIC, 1)
    select.forEach { it -> assertFalse("'$it' unexpected", lookupElementStrings?.contains(it) ?: false) }
    checkResult(text)
  }

  private fun doPosTest(text: String, vararg select: String) = myFixture.run {
    configureByText("a.clj", text)
    complete(CompletionType.BASIC, 1)
    select.forEach { it -> assertTrue("'$it' expected", lookupElementStrings?.contains(it) ?: false) }
    checkResult(text)
  }
}