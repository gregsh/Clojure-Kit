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

import com.intellij.codeInsight.template.FileTypeBasedContextType
import com.intellij.codeInsight.template.impl.DefaultLiveTemplatesProvider
import com.intellij.lang.BracePair
import com.intellij.lang.Language
import com.intellij.lang.PairedBraceMatcher
import com.intellij.openapi.fileTypes.FileTypeConsumer
import com.intellij.openapi.fileTypes.FileTypeFactory
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.WildcardFileNameMatcher
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.LanguageSubstitutor
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.ClojureIcons
import org.intellij.clojure.parser.ClojureParserDefinitionBase
import org.intellij.clojure.parser.ClojureTokens
import org.intellij.clojure.psi.ClojureTypes

/**
 * @author gregsh
 */
class ClojureFileTypeFactory : FileTypeFactory() {
  override fun createFileTypes(consumer: FileTypeConsumer) {
    consumer.consume(ClojureFileType, "${ClojureConstants.CLJ};${ClojureConstants.CLJS};${ClojureConstants.CLJC}")
    consumer.consume(ClojureFileType, WildcardFileNameMatcher(ClojureConstants.BOOT_BUILD_BOOT))
  }
}

object ClojureFileType : LanguageFileType(ClojureLanguage) {
  override fun getIcon() = ClojureIcons.CLOJURE_ICON
  override fun getName() = "Clojure"
  override fun getDefaultExtension() = ClojureConstants.CLJ
  override fun getDescription() = "Clojure and ClojureScript"
}

object ClojureLanguage : Language("Clojure")

object ClojureScriptLanguage : Language(ClojureLanguage, "ClojureScript")

class ClojureLanguageSubstitutor : LanguageSubstitutor() {
  override fun getLanguage(file: VirtualFile, project: Project): Language? {
    return if (file.extension?.equals(ClojureConstants.CLJS) ?: false)
      ClojureScriptLanguage
    else null
  }
}

class ClojureParserDefinition : ClojureParserDefinitionBase() {
  override fun getFileNodeType() = ClojureTokens.CLJ_FILE_TYPE
}

class ClojureScriptParserDefinition : ClojureParserDefinitionBase() {
  override fun getFileNodeType() = ClojureTokens.CLJS_FILE_TYPE
}

class ClojureBraceMatcher : PairedBraceMatcher {

  override fun getPairs() = PAIRS
  override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int) = openingBraceOffset
  override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, tokenType: IElementType?) =
      tokenType == null || ClojureTokens.WHITESPACES.contains(tokenType) || ClojureTokens.COMMENTS.contains(tokenType)
          || tokenType === ClojureTypes.C_COMMA || tokenType === ClojureTypes.C_PAREN2
          || tokenType === ClojureTypes.C_BRACE2 || tokenType === ClojureTypes.C_BRACKET2

  companion object {
    internal val PAIRS = arrayOf(
        BracePair(ClojureTypes.C_PAREN1, ClojureTypes.C_PAREN2, false),
        BracePair(ClojureTypes.C_BRACE1, ClojureTypes.C_BRACE2, false),
        BracePair(ClojureTypes.C_BRACKET1, ClojureTypes.C_BRACKET2, false))
  }
}

class ClojureLiveTemplateContext : FileTypeBasedContextType("Clojure", "Clojure", ClojureFileType)

class ClojureLiveTemplateProvider : DefaultLiveTemplatesProvider {
  override fun getDefaultLiveTemplateFiles() = arrayOf("liveTemplates/clojureLiveTemplates")
  override fun getHiddenLiveTemplateFiles() = null
}
