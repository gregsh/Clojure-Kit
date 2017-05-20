package org.intellij.clojure.lang

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.Lookup.REPLACE_SELECT_CHAR
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase

/**
 * @author gregsh
 */
class ClojureCompletionTest : LightPlatformCodeInsightFixtureTestCase() {
  companion object {
    val NS_KEY = "namespace/keyword"
    val NS_ALIAS = "(alias 'namespace some-ns)"
    val STR_ALIAS = "(alias 'str clojure.string)"
  }

  fun testCoreNs1() = doTest("<caret>", "clojure.core", "clojure.core")
  fun testCoreNs2() = doTest(":<caret>", "clojure.core", ":clojure.core")
  fun testCoreDefn1() = doTest("(<caret>)", "defn", "(defn)")

  fun testRefsInBindings1() = doNegTest("(let [abc 10 <caret>])", "abc")
  fun testRefsInBindings2() = doNegTest("(let [abc 10 <caret>])", "clojure.core")
  fun testRefsInBindings3() = doTest("(let [abc 10 edf <caret>])", "abc")
  fun testRefsInBindings4() = doTest("(let [{:keys [<caret>]} m])", "clojure.core")

  fun testNothingInName() = doNegTest("(def strip) (def stri<caret>)", "clo-def", "clojure.string", ":strict")

  fun testKeyword1() = ":keyword".let { doTest("$it :<caret>", it, "$it $it") }
  fun testKeyword2() = ":keyword".let { doTest("$it :key<caret>", it, "$it $it") }
  fun testKeyword3() = ":keyword".let { doNegTest("$it <caret>", it) }
  fun testKeywordMeta1() = ":keyword".let { doTest("^meta $it ^meta :<caret>", it, "^meta $it ^meta $it") }
  fun testKeywordMeta2() = ":keyword".let { doTest("^meta $it ^meta :key<caret>", it, "^meta $it ^meta $it") }
  fun testKeywordUser1() = "::keyword".let { doTest("$it :<caret>", it, "$it $it") }
  fun testKeywordUser2() = "::keyword".let { doTest("$it ::key<caret>", it, "$it $it") }
  fun testKeywordUser3() = "::keyword".let { doNegTest("$it <caret>", it) }
  fun testKeywordNs1() = ":$NS_KEY".let { doTest("$it :<caret>", it, "$it $it") }
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

  fun testFqn1() = doTest("(clojure.string/<caret>)", "blank?", "(clojure.string/blank?)")
  fun testFqn2() = "clojure.string/blank?".let { doTest("(bla<caret>)", it, "($it)", 2) }
  fun testFqn3() = "clojure.string/blank?".let { doTest("(clostribla<caret>)", it, "($it)", 2) }
  fun testFqn2a() = "str/blank?".let { doTest("$STR_ALIAS (bla<caret>)", it, "$STR_ALIAS ($it)", 2) }
  fun testFqn3a() = "str/blank?".let { doTest("$STR_ALIAS (clostribla<caret>)", it, "$STR_ALIAS ($it)", 2) }


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
    select.forEach { it -> assertFalse("'$it' unexpected", lookupElementStrings!!.contains(it)) }
    checkResult(text)
  }
}