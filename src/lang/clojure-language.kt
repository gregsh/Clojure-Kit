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
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.ClojureIcons
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

class ClojureBraceMatcher : PairedBraceMatcher {

  override fun getPairs() = ClojureTokens.BRACE_PAIRS.toTypedArray()
  override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int) = openingBraceOffset
  override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, tokenType: IElementType?) =
      tokenType == null || ClojureTokens.WHITESPACES.contains(tokenType) || ClojureTokens.COMMENTS.contains(tokenType)
          || tokenType === ClojureTypes.C_COMMA || tokenType === ClojureTypes.C_PAREN2
          || tokenType === ClojureTypes.C_BRACE2 || tokenType === ClojureTypes.C_BRACKET2
}

class ClojureLiveTemplateContext : FileTypeBasedContextType("Clojure", "Clojure", ClojureFileType)

class ClojureLiveTemplateProvider : DefaultLiveTemplatesProvider {
  override fun getDefaultLiveTemplateFiles() = arrayOf("liveTemplates/clojureLiveTemplates")
  override fun getHiddenLiveTemplateFiles() = null
}

object ClojureTokens {
  @JvmField val CLJ_FILE_TYPE = IFileElementType("CLOJURE_FILE", ClojureLanguage)
  @JvmField val CLJS_FILE_TYPE = IFileElementType("CLOJURE_SCRIPT_FILE", ClojureScriptLanguage)

  @JvmField val LINE_COMMENT = IElementType("C_LINE_COMMENT", ClojureLanguage)

  @JvmField val WHITESPACES = TokenSet.create(ClojureTypes.C_COMMA, TokenType.WHITE_SPACE)
  @JvmField val COMMENTS = TokenSet.create(LINE_COMMENT)
  @JvmField val STRINGS = TokenSet.create(ClojureTypes.C_STRING, ClojureTypes.C_STRING_UNCLOSED)

  @JvmField val SHARPS = TokenSet.create(ClojureTypes.C_SHARP, ClojureTypes.C_SHARP_COMMENT, ClojureTypes.C_SHARP_QMARK, ClojureTypes.C_SHARP_QMARK_AT, ClojureTypes.C_SHARP_EQ, ClojureTypes.C_SHARP_HAT, ClojureTypes.C_SHARP_QUOTE)
  @JvmField val MACROS = TokenSet.orSet(SHARPS, TokenSet.create(ClojureTypes.C_AT, ClojureTypes.C_COLON, ClojureTypes.C_COLONCOLON, ClojureTypes.C_HAT, ClojureTypes.C_SYNTAX_QUOTE, ClojureTypes.C_TILDE))

  @JvmField val PAREN1_ALIKE = TokenSet.create(ClojureTypes.C_PAREN1, ClojureTypes.C_BRACE1, ClojureTypes.C_BRACKET1)
  @JvmField val PAREN2_ALIKE = TokenSet.create(ClojureTypes.C_PAREN2, ClojureTypes.C_BRACE2, ClojureTypes.C_BRACKET2)
  @JvmField val PAREN_ALIKE = TokenSet.orSet(PAREN1_ALIKE, PAREN2_ALIKE)
  @JvmField val LIST_ALIKE = TokenSet.create(ClojureTypes.C_FUN, ClojureTypes.C_LIST, ClojureTypes.C_MAP, ClojureTypes.C_SET, ClojureTypes.C_VEC)

  @JvmField val FORMS = TokenSet.create(ClojureTypes.C_CONSTRUCTOR, ClojureTypes.C_FORM, ClojureTypes.C_FUN, ClojureTypes.C_KEYWORD,
      ClojureTypes.C_LIST, ClojureTypes.C_LITERAL, ClojureTypes.C_MAP, ClojureTypes.C_REGEXP,
      ClojureTypes.C_SET, ClojureTypes.C_SYMBOL, ClojureTypes.C_VEC)

  @JvmStatic fun wsOrComment(t: IElementType?) = t != null && (WHITESPACES.contains(t) || COMMENTS.contains(t))

  @JvmField val BRACE_PAIRS = listOf(
      BracePair(ClojureTypes.C_PAREN1, ClojureTypes.C_PAREN2, false),
      BracePair(ClojureTypes.C_BRACE1, ClojureTypes.C_BRACE2, false),
      BracePair(ClojureTypes.C_BRACKET1, ClojureTypes.C_BRACKET2, false))
}
