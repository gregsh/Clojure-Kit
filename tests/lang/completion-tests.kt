package org.intellij.clojure.lang

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.Lookup.REPLACE_SELECT_CHAR
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase

/**
 * @author gregsh
 */
class ClojureCompletionTest : LightPlatformCodeInsightFixtureTestCase() {

  fun testCoreNs1() = doTest("<caret>", "clojure.core", "clojure.core")
  fun testCoreNs2() = doTest(":<caret>", "clojure.core", ":clojure.core")
  fun testCoreDefn1() = doTest("(<caret>)", "defn", "(defn)")

  fun testRefsInBindings1() = doTestNo("(let [abc 10 <caret>])", "abc")
  fun testRefsInBindings2() = doTestNo("(let [abc 10 <caret>])", "clojure.core")
  fun testRefsInBindings3() = doTest("(let [abc 10 edf <caret>])", "abc")
  fun testRefsInBindings4() = doTest("(let [{:keys [<caret>]} m])", "clojure.core")

  fun testKeyword1() = ":keyword".let { doTest("$it :<caret>", it, "$it $it") }
  fun testKeyword2() = ":keyword".let { doTest("$it :key<caret>", it, "$it $it") }
  fun testKeyword3() = ":keyword".let { doTest("$it <caret>", it, "$it $it") }
  fun testKeywordMeta1() = ":keyword".let { doTest("^meta $it ^meta :<caret>", it, "^meta $it ^meta $it") }
  fun testKeywordMeta2() = ":keyword".let { doTest("^meta $it ^meta :key<caret>", it, "^meta $it ^meta $it") }
  fun testKeywordMeta3() = ":keyword".let { doTest("^meta $it ^meta <caret>", it, "^meta $it ^meta $it") }
  fun testKeywordUser1() = "::keyword".let { doTest("$it :<caret>", it, "$it $it") }
  fun testKeywordUser2() = "::keyword".let { doTest("$it ::key<caret>", it, "$it $it") }
  fun testKeywordUser3() = "::keyword".let { doTest("$it <caret>", it, "$it $it") }
  fun testKeywordNs1() = ":namespace/keyword".let { doTest("$it :<caret>", it, "$it $it") }
  fun testKeywordNs2() = ":namespace/keyword".let { doTest("$it :key<caret>", it, "$it $it") }
  fun testKeywordNs3() = ":namespace/keyword".let { doTest("$it <caret>", it, "$it $it") }
  fun testKeywordNs4() = ":namespace/keyword".let { doTest("$it :namespace/<caret>", it, "$it $it") }
  fun testKeywordNs5() = ":namespace/keyword".let { doTest("$it :namespace/key<caret>", it, "$it $it") }
  fun testKeywordNs6() = ":namespace/keyword".let { doTest("$it :name<caret>", it, "$it $it") }
  fun testKeywordNs7() = ":namespace/keyword".let { doTest("$it :key<caret>", it, "$it $it") }
  fun testKeywordNs8() = ":namespace/keyword".let { doTest("$it :nk<caret>", it, "$it $it") }
  fun testKeywordNs9() = ":namespace/keyword".let { doTest("$it nk<caret>", it, "$it $it") }

  fun testFqn1() = doTest("(clojure.string/<caret>)", "blank?", "(clojure.string/blank?)")
  fun testFqn2() = "clojure.string/blank?".let { doTest("(bla<caret>)", it, "($it)", 2) }
  fun testFqn3() = "clojure.string/blank?".let { doTest("(clostribla<caret>)", it, "($it)", 2) }


  private fun doTest(text: String, select: String,
                     expected: String = text.replace("<caret>", select), count: Int = 1) = myFixture.run {
    configureByText("a.clj", text)
    complete(CompletionType.BASIC, count)?.let { variants ->
      val chosen = variants.find { it.lookupString == select }
      assertNotNull("'$select' variant not suggested", chosen)
      lookup.currentItem = chosen
      finishLookup(REPLACE_SELECT_CHAR)
    }
    checkResult(expected)
  }

  private fun doTestNo(text: String, select: String) = myFixture.run {
    configureByText("a.clj", text)
    complete(CompletionType.BASIC, 1)
    assertFalse("'$select' unexpected", lookupElementStrings!!.contains(select))
    checkResult(text)
  }
}