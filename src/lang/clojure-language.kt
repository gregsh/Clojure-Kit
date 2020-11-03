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
import com.intellij.ide.actions.QualifiedNameProvider
import com.intellij.lang.BracePair
import com.intellij.lang.Language
import com.intellij.lang.PairedBraceMatcher
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.LanguageSubstitutor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.ClojureIcons
import org.intellij.clojure.psi.CKeyword
import org.intellij.clojure.psi.CSymbol
import org.intellij.clojure.psi.ClojureTypes.*
import org.intellij.clojure.psi.SymKey
import org.intellij.clojure.psi.impl.ClojureDefinitionService
import org.intellij.clojure.psi.impl.asCTarget
import org.intellij.clojure.psi.impl.resolveInfo
import org.intellij.clojure.util.qualifiedName
import org.intellij.clojure.util.thisForm

/**
 * @author gregsh
 */
object ClojureFileType : LanguageFileType(ClojureLanguage) {
  override fun getIcon() = ClojureIcons.FILE
  override fun getName() = "Clojure"
  override fun getDefaultExtension() = ClojureConstants.CLJ
  override fun getDescription() = "Clojure and ClojureScript"
}

object ClojureLanguage : Language("Clojure")

object ClojureScriptLanguage : Language(ClojureLanguage, "ClojureScript")

class ClojureLanguageSubstitutor : LanguageSubstitutor() {
  override fun getLanguage(file: VirtualFile, project: Project): Language? {
    return if (file.extension?.equals(ClojureConstants.CLJS) == true)
      ClojureScriptLanguage
    else null
  }
}

class ClojureBraceMatcher : PairedBraceMatcher {

  override fun getPairs() = ClojureTokens.BRACE_PAIRS.toTypedArray()
  override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int) = openingBraceOffset
  override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, tokenType: IElementType?) = tokenType === null
      || ClojureTokens.WHITESPACES.contains(tokenType) || ClojureTokens.COMMENTS.contains(tokenType)
      || tokenType === C_COMMA || tokenType === C_PAREN2
      || tokenType === C_BRACE2 || tokenType === C_BRACKET2
}

class ClojureQualifiedNameProvider : QualifiedNameProvider {
  override fun getQualifiedName(element: PsiElement?): String? {
    return element.asCTarget?.key?.run { if (type == "keyword") ":$qualifiedName" else qualifiedName }
  }

  override fun qualifiedNameToElement(fqn: String, project: Project): PsiElement? {
    val idx = fqn.indexOf('/')
    val name = fqn.substring(idx + 1)
    val ns = if (idx == -1) "" else fqn.substring(0, idx)
    return ClojureDefinitionService.getInstance(project).getDefinition(SymKey(name, ns, "def"))
  }

  override fun adjustElementToCopy(element: PsiElement) = when {
    element.asCTarget != null -> element
    else -> element.thisForm.let {
      when (it) {
        is CSymbol -> {
          val def = it.resolveInfo()
          if (def != null) ClojureDefinitionService.getInstance(element.project).getDefinition(SymKey(def)) else null
        }
        is CKeyword -> ClojureDefinitionService.getInstance(element.project).getKeyword(it)
        else -> null
      }
    }
  }
}

class ClojureLiveTemplateContext : FileTypeBasedContextType("Clojure", "Clojure", ClojureFileType)

object ClojureTokens {
  @JvmField val CLJ_FILE_TYPE = IFileElementType("CLOJURE_FILE", ClojureLanguage)
  @JvmField val CLJS_FILE_TYPE = IFileElementType("CLOJURE_SCRIPT_FILE", ClojureScriptLanguage)

  @JvmField val LINE_COMMENT = IElementType("C_LINE_COMMENT", ClojureLanguage)

  @JvmField val WHITESPACES = TokenSet.create(C_COMMA, TokenType.WHITE_SPACE)
  @JvmField val COMMENTS = TokenSet.create(LINE_COMMENT)
  @JvmField val STRINGS = TokenSet.create(C_STRING)
  @JvmField val SYM_ALIKE = TokenSet.create(C_BOOL, C_NIL, C_SYM)
  @JvmField val LITERALS = TokenSet.create(C_BOOL, C_CHAR, C_HEXNUM, C_NIL, C_NUMBER, C_RATIO, C_RDXNUM, C_STRING)

  @JvmField val SHARPS = TokenSet.create(C_SHARP, C_SHARP_COMMENT, C_SHARP_QMARK, C_SHARP_QMARK_AT, C_SHARP_EQ, C_SHARP_HAT,
      C_SHARP_QUOTE, C_SHARP_NS, C_SHARP_SYM)
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
