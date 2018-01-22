package org.intellij.clojure.lang

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase
import junit.framework.TestCase


class ClojureUsagesTest : LightPlatformCodeInsightFixtureTestCase() {
  companion object {
    val NS_ALIAS = "(alias 'bar foo.bar)"
  }

  fun testKeywordUsages1() = doTest("(let [{:keys [x y]} {:x| 42}] y)", 2)
  fun testKeywordUsages2() = doTest("$NS_ALIAS (let [{::bar/keys [x y]} {::bar/x| 42}] y)", 2)
  fun testKeywordUsages3a() = doTest("$NS_ALIAS (let [{:keys [foo.bar/x y]} {::bar/x| 42}] y)", 2)
  fun testKeywordUsages3b() = doTest("$NS_ALIAS (let [{:keys [x y]} {::bar/x| 42}] y)", 1)

  fun doTest(text: String, expectedCount: Int) = myFixture.run {
    configureByText("a.clj", text.replace("|", "<caret>"))
    val flags = TargetElementUtil.ELEMENT_NAME_ACCEPTED or TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED
    val element = TargetElementUtil.findTargetElement(myFixture.editor, flags)
    TestCase.assertEquals(expectedCount, findUsages(element!!).size)
  }
}