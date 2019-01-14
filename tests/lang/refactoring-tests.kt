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

import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase

/**
 *
 *
 * @author gregsh
 */
class ClojureRenameTest : LightCodeInsightFixtureTestCase() {
  fun testRename1() = doTest("Bar", "(defrecord Foo| [a b]) (Foo. 1 2)")

  private fun doTest(newName: String, before: String,
                     after: String = defaultRename(newName, before)) {
    myFixture.configureByText("a.clj", before.replace("|", "<caret>"))
    myFixture.renameElementAtCaret(newName)
    myFixture.checkResult(after)
  }

  private fun defaultRename(newName: String, str: String): String {
    val oldName = "(\\w+)\\|".toRegex().find(str)?.groups?.get(1)?.value!!
    return str.replace(oldName, newName).replace("|", "")
  }
}