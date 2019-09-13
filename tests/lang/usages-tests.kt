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

package org.intellij.clojure.lang

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import junit.framework.TestCase


class ClojureUsagesTest : BasePlatformTestCase() {
  companion object {
    const val NS_ALIAS = "(alias 'bar foo.bar)"
  }

  fun testKeywordUsages1() = doTest("(let [{:keys [x y]} {:x| 42}] y)", 2)
  fun testKeywordUsages2() = doTest("$NS_ALIAS (let [{::bar/keys [x y]} {::bar/x| 42}] y)", 2)
  fun testKeywordUsages3a() = doTest("$NS_ALIAS (let [{:keys [foo.bar/x y]} {::bar/x| 42}] y)", 2)
  fun testKeywordUsages3b() = doTest("$NS_ALIAS (let [{:keys [x y]} {::bar/x| 42}] y)", 1)
  fun testDeftypeField1() = doTest("(deftype A [x| y]) (.-x (A.)) (. (A.) -x)", 3)
  fun testDeftypeField2() = doTest("(deftype A [^A x|]) (.-x (. (A.) -x))", 3)

  private fun doTest(text: String, expectedCount: Int) = myFixture.run {
    configureByText("a.clj", text.replace("|", "<caret>"))
    val flags = TargetElementUtil.ELEMENT_NAME_ACCEPTED or TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED
    val element = TargetElementUtil.findTargetElement(myFixture.editor, flags)
    TestCase.assertEquals(expectedCount, findUsages(element!!).size)
  }
}