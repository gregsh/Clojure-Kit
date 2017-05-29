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
import org.intellij.clojure.psi.ClojureTypes.*

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
          || tokenType === C_COMMA || tokenType === C_PAREN2
          || tokenType === C_BRACE2 || tokenType === C_BRACKET2
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

  @JvmField val WHITESPACES = TokenSet.create(C_COMMA, TokenType.WHITE_SPACE)
  @JvmField val COMMENTS = TokenSet.create(LINE_COMMENT)
  @JvmField val STRINGS = TokenSet.create(C_STRING, C_STRING_UNCLOSED)
  @JvmField val LITERALS = TokenSet.create(C_BOOL, C_CHAR, C_HEXNUM, C_NIL, C_NUMBER, C_RATIO, C_RDXNUM, C_STRING, C_SYM)

  @JvmField val SHARPS = TokenSet.create(C_SHARP, C_SHARP_COMMENT, C_SHARP_QMARK, C_SHARP_QMARK_AT, C_SHARP_EQ, C_SHARP_HAT, C_SHARP_QUOTE, C_SHARP_NS)
  @JvmField val MACROS = TokenSet.create(C_AT, C_COLON, C_COLONCOLON, C_HAT, C_QUOTE, C_SYNTAX_QUOTE, C_TILDE, C_TILDE_AT)

  @JvmField val PAREN1_ALIKE = TokenSet.create(C_PAREN1, C_BRACE1, C_BRACKET1)
  @JvmField val PAREN2_ALIKE = TokenSet.create(C_PAREN2, C_BRACE2, C_BRACKET2)
  @JvmField val PAREN_ALIKE = TokenSet.orSet(PAREN1_ALIKE, PAREN2_ALIKE)
  @JvmField val LIST_ALIKE = TokenSet.create(C_FUN, C_LIST, C_MAP, C_SET, C_VEC)

  @JvmField val FORMS = TokenSet.create(C_CONSTRUCTOR, C_FORM, C_FUN, C_KEYWORD,
      C_LIST, C_LITERAL, C_MAP, C_REGEXP,
      C_SET, C_SYMBOL, C_VEC)

  @JvmField val BRACE_PAIRS = listOf(
      BracePair(C_PAREN1, C_PAREN2, false),
      BracePair(C_BRACE1, C_BRACE2, false),
      BracePair(C_BRACKET1, C_BRACKET2, false))
}
