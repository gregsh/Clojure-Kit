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

package org.intellij.clojure.ui.formatter

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.lang.Language
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.ui.DialogPanel
import com.intellij.psi.codeStyle.*
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import org.intellij.clojure.formatter.ClojureCodeStyleSettings
import org.intellij.clojure.formatter.getClojureSettings
import org.intellij.clojure.lang.ClojureFileType
import org.intellij.clojure.lang.ClojureLanguage
import javax.swing.JComponent

/**
 * @author gregsh
 */
class ClojureCodeStyleSettingsProvider : CodeStyleSettingsProvider() {
  override fun getLanguage(): Language = ClojureLanguage
  override fun createCustomSettings(settings: CodeStyleSettings) = ClojureCodeStyleSettings(settings)
  override fun createConfigurable(settings: CodeStyleSettings, modelSettings: CodeStyleSettings): CodeStyleConfigurable =
    ClojureCodeStyleConfigurable(settings, modelSettings)
}

class ClojureCodeStyleConfigurable(settings: CodeStyleSettings, cloneSettings: CodeStyleSettings) : CodeStyleAbstractConfigurable(settings, cloneSettings, "Clojure") {

  override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel = Panel(currentSettings, settings)
  override fun getHelpTopic() = null

  private class Panel(currentSettings: CodeStyleSettings, settings: CodeStyleSettings) :
      TabbedLanguageCodeStylePanel(ClojureLanguage, currentSettings, settings) {
    init {
      addTab(ClojureCodeStyleOtherTab(currentSettings, settings))
    }
  }
}

class ClojureLangCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
  override fun getLanguage(): Language = ClojureLanguage
  override fun getIndentOptionsEditor() = SmartIndentOptionsEditor()
  override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
    indentOptions.INDENT_SIZE = 2
    indentOptions.CONTINUATION_INDENT_SIZE = 4
    indentOptions.TAB_SIZE = 2
  }

  override fun getCodeSample(settingsType: SettingsType): String {
    return """
(ns ^{:author "wikibooks" :doc "Clojure Programming"} wikibooks.sample (:require [clojure.set :as set]))
;; Clojure Programming/Examples/Lazy Fibonacci
(defn fib-seq [] ((fn rfib [a b] (cons a (lazy-seq (rfib b (+ a b))))) 0 1))

;; Recursive Fibonacci with any start point and the amount of numbers that you want
;; note that your 'start' parameter must be a vector with at least two numbers (the two which are your starting points)
(defn fib [start range] "Creates a vector of fibonnaci numbers" (if (<= range 0) start (recur (let [subvector (subvec start (- (count start) 2))
x (nth subvector 0) y (nth subvector 1) z (+ x y)] (conj start z)) (- range 1))))
"""
  }
}

class ClojureCodeStyleOtherTab(
  currentSettings: CodeStyleSettings?,
  settings: CodeStyleSettings
) : CodeStyleAbstractPanel(ClojureLanguage, currentSettings, settings) {
  private lateinit var myPanel: DialogPanel

  override fun getRightMargin(): Int = 0

  override fun createHighlighter(scheme: EditorColorsScheme): EditorHighlighter? = null

  override fun getFileType(): FileType = ClojureFileType

  override fun getPreviewText(): String? = null

  override fun getPanel(): JComponent {
    val options = settings.getClojureSettings()
    myPanel = panel {
      row {
        checkBox("Use ;; comments")
          .bindSelected(options::USE_2SEMI_COMMENT)
      }
    }
      .withBorder(JBUI.Borders.empty(10))
    return myPanel
  }

  override fun apply(settings: CodeStyleSettings) = myPanel.apply()

  override fun isModified(settings: CodeStyleSettings): Boolean = myPanel.isModified()

  override fun resetImpl(settings: CodeStyleSettings) = myPanel.reset()
}