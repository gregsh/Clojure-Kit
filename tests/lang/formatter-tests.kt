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

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.text.StringUtil.escapeToRegexp
import com.intellij.openapi.util.text.StringUtil.nullize
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.testFramework.ParsingTestCase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import groovy.lang.Binding
import groovy.lang.GroovyShell
import org.intellij.clojure.formatter.ClojureCodeStyleSettings
import org.intellij.clojure.ui.formatter.ClojureLangCodeStyleSettingsProvider

/**
 * @author gregsh
 */
const val DELIMITER = ";;; reformat>"

class ClojureFormatterTest : BasePlatformTestCase() {
  override fun getTestDataPath() = "$TEST_DATA_PATH/formatter"

  private val settingsManager: CodeStyleSettingsManager get() = CodeStyleSettingsManager.getInstance(project)

  override fun setUp() {
    super.setUp()
    val settings = CodeStyleSettingsManager.getInstance(project).cloneSettings(CodeStyle.getSettings(project))
    settingsManager.setTemporarySettings(settings)
  }

  override fun tearDown() {
    settingsManager.dropTemporarySettings()
    super.tearDown()
  }

  fun testSimple() = doTest()
  fun testStyleGuide() = doTest()
  fun testFormatterFixes() = doTest()

  fun testCodeSample() = doTest(ClojureLangCodeStyleSettingsProvider().getCodeSample(
      LanguageCodeStyleSettingsProvider.SettingsType.INDENT_SETTINGS))

  private fun doTest() = myFixture.run {
    val fileName = getTestName(false) + ".clj"
    val fullText = ParsingTestCase.loadFileDefault(testDataPath, fileName)

    val match = "\n*(?:${escapeToRegexp(DELIMITER)}.*\n*)++".toRegex().find(fullText)
        ?: error("separate before/after snippets with:\n$DELIMITER")
    nullize(fullText.substring(match.range).trimMargin(DELIMITER).trim())?.let { evaluate(it)}
    val original = fullText.substring(0, match.range.first)
    val performEnter = original.contains("<caret>")
    configureByText(ClojureFileType, original)
    WriteCommandAction.runWriteCommandAction(null) {
      if (performEnter) type("\n")
      else CodeStyleManager.getInstance(project).reformat(file)
    }
    WriteCommandAction.runWriteCommandAction(null) {
      editor.document.run {
        insertString(0, fullText.substring(0, match.range.last + 1))
      }
    }
    checkResultByFile(fileName, true)
  }

  private fun doTest(original: String) = myFixture.run {
    configureByText(ClojureFileType, original)
    val performEnter = original.contains("<caret>")
    WriteCommandAction.runWriteCommandAction(null) {
      if (performEnter) type("\n")
      else CodeStyleManager.getInstance(project).reformat(file)
    }
    checkResultByFile(getTestName(false) + ".clj", true)
  }

  private fun evaluate(script : String) {
    val tmpSettings = settingsManager.temporarySettings!!
    val common = tmpSettings.getCommonSettings(ClojureLanguage)
    val custom = tmpSettings.getCustomSettings(ClojureCodeStyleSettings::class.java)
    val binding = Binding(mutableMapOf(
        "main" to tmpSettings,
        "common" to common,
        "custom" to custom))
    GroovyShell(binding).evaluate(script)
  }
}