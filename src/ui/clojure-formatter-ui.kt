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
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsProvider
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import org.intellij.clojure.formatter.ClojureCodeStyleSettings
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.ui.forms.CodeStyleOtherTab

/**
 * @author gregsh
 */
class ClojureCodeStyleSettingsProvider : CodeStyleSettingsProvider() {
  override fun getLanguage(): Language = ClojureLanguage
  override fun createCustomSettings(settings: CodeStyleSettings?) = ClojureCodeStyleSettings(settings)
  override fun createSettingsPage(settings: CodeStyleSettings, originalSettings: CodeStyleSettings) = ClojureCodeStyleConfigurable(settings, originalSettings)
}

class ClojureCodeStyleConfigurable(settings: CodeStyleSettings, cloneSettings: CodeStyleSettings) : CodeStyleAbstractConfigurable(settings, cloneSettings, "Clojure") {

  override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel = Panel(currentSettings, settings)
  override fun getHelpTopic() = null

  private class Panel(currentSettings: CodeStyleSettings, settings: CodeStyleSettings) :
      TabbedLanguageCodeStylePanel(ClojureLanguage, currentSettings, settings) {
    init {
      addTab(CodeStyleOtherTab(ClojureLanguage, currentSettings, settings))
    }
  }
}

class ClojureLangCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
  override fun getLanguage(): Language = ClojureLanguage
  override fun getIndentOptionsEditor() = SmartIndentOptionsEditor()
  override fun getDefaultCommonSettings() = CommonCodeStyleSettings(language).run {
    val indentOptions = initIndentOptions()
    indentOptions.INDENT_SIZE = 2
    indentOptions.CONTINUATION_INDENT_SIZE = 4
    indentOptions.TAB_SIZE = 2
    this
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