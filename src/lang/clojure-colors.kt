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

import com.intellij.lang.Language
import com.intellij.lang.LanguageUtil
import com.intellij.lexer.Lexer
import com.intellij.lexer.LookAheadLexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.util.containers.ContainerUtil
import org.intellij.clojure.parser.ClojureLexer
import org.intellij.clojure.parser.ClojureTokens
import org.intellij.clojure.psi.ClojureTypes.*

object ClojureColors {
  @JvmField val ILLEGAL = createTextAttributesKey("C_ILLEGAL", DefaultLanguageHighlighterColors.INVALID_STRING_ESCAPE)
  @JvmField val COMMENT = createTextAttributesKey("C_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
  @JvmField val STRING = createTextAttributesKey("C_STRING", DefaultLanguageHighlighterColors.STRING)
  @JvmField val NUMBER = createTextAttributesKey("C_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
  @JvmField val KEYWORD = createTextAttributesKey("C_KEYWORD", DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE)
  @JvmField val SYMBOL = createTextAttributesKey("C_SYMBOL", DefaultLanguageHighlighterColors.IDENTIFIER)
  @JvmField val BOOLEAN = createTextAttributesKey("C_BOOLEAN", DefaultLanguageHighlighterColors.KEYWORD)
  @JvmField val NIL = createTextAttributesKey("C_NIL", DefaultLanguageHighlighterColors.KEYWORD)
  @JvmField val CALLABLE = createTextAttributesKey("C_CALLABLE", DefaultLanguageHighlighterColors.KEYWORD)

  @JvmField val COMMA = createTextAttributesKey("C_COMMA", DefaultLanguageHighlighterColors.COMMA)
  @JvmField val DOT = createTextAttributesKey("C_DOT", DefaultLanguageHighlighterColors.DOT)
  @JvmField val SLASH = createTextAttributesKey("C_SLASH", DefaultLanguageHighlighterColors.DOT)
  @JvmField val QUOTE = createTextAttributesKey("C_QUOTE", DefaultLanguageHighlighterColors.DOT)
  @JvmField val SYNTAX_QUOTE = createTextAttributesKey("C_SYNTAX_QUOTE", DefaultLanguageHighlighterColors.DOT)
  @JvmField val PARENS = createTextAttributesKey("C_PARENS", DefaultLanguageHighlighterColors.PARENTHESES)
  @JvmField val BRACES = createTextAttributesKey("C_BRACES", DefaultLanguageHighlighterColors.BRACES)
  @JvmField val BRACKETS = createTextAttributesKey("C_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)

  @JvmField val METADATA = createTextAttributesKey("C_METADATA")
  @JvmField val READER_MACRO = createTextAttributesKey("C_READER_MACRO")
  @JvmField val FN_ARGUMENT = createTextAttributesKey("C_FN_ARGUMENT", DefaultLanguageHighlighterColors.PARAMETER)
  @JvmField val LET_BINDING = createTextAttributesKey("C_LET_BINDING", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
  @JvmField val NAMESPACE = createTextAttributesKey("C_NAMESPACE", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL)
  @JvmField val DYNAMIC = createTextAttributesKey("C_DYNAMIC", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL)

  @JvmField val NS_COLORS: Map<String, TextAttributes> = ContainerUtil.newConcurrentMap()
}

class ClojureSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
  override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) =
      ClojureSyntaxHighlighter((if (project == null) null else LanguageUtil.getLanguageForPsi(project, virtualFile)) ?: ClojureLanguage)
}

class ClojureSyntaxHighlighter(val language: Language) : SyntaxHighlighterBase() {
  override fun getHighlightingLexer() = ClojureHighlightingLexer(language)

  override fun getTokenHighlights(tokenType: IElementType?): Array<out TextAttributesKey> {
    return when (tokenType) {
      TokenType.BAD_CHARACTER -> pack(ClojureColors.ILLEGAL)
      ClojureTokens.LINE_COMMENT -> pack(ClojureColors.COMMENT)
      C_STRING -> pack(ClojureColors.STRING)
      C_NUMBER, C_HEXNUM, C_RDXNUM, C_RATIO -> pack(ClojureColors.NUMBER)
      C_BOOL -> pack(ClojureColors.BOOLEAN)
      C_NIL -> pack(ClojureColors.NIL)
      C_COLON -> pack(ClojureColors.KEYWORD)
      C_COLONCOLON -> pack(ClojureColors.KEYWORD)
      C_SYM -> pack(ClojureColors.SYMBOL)
      C_COMMA -> pack(ClojureColors.COMMA)
      C_DOT, C_DOTDASH -> pack(ClojureColors.DOT)
      C_SLASH -> pack(ClojureColors.SLASH)
      C_QUOTE -> pack(ClojureColors.QUOTE)
      C_SYNTAX_QUOTE -> pack(ClojureColors.SYNTAX_QUOTE)
      C_SHARP_QMARK, C_SHARP_QMARK_AT, C_SHARP_EQ, C_SHARP_HAT, C_SHARP_QUOTE -> pack(ClojureColors.READER_MACRO)
      C_PAREN1, C_PAREN2 -> pack(ClojureColors.PARENS)
      C_BRACE1, C_BRACE2 -> pack(ClojureColors.BRACES)
      C_BRACKET1, C_BRACKET2 -> pack(ClojureColors.BRACKETS)
      ClojureHighlightingLexer.CALLABLE -> pack(ClojureColors.CALLABLE)
      ClojureHighlightingLexer.KEYWORD -> pack(ClojureColors.KEYWORD)
      else -> EMPTY
    }
  }
}

class ClojureHighlightingLexer(language: Language) : LookAheadLexer(ClojureLexer(language)) {
  companion object {
    val CALLABLE = IElementType("C_CALLABLE*", ClojureLanguage)
    val KEYWORD = IElementType("C_KEYWORD*", ClojureLanguage)
  }

  override fun lookAhead(baseLexer: Lexer) {
    fun skipWs(l: Lexer) {
      while (l.tokenType.let {
        ClojureTokens.WHITESPACES.contains(it) ||
            ClojureTokens.COMMENTS.contains(it)
      }) advanceLexer(l)
    }

    val tokenType0 = baseLexer.tokenType
    if (tokenType0 === C_QUOTE) {
      advanceAs(baseLexer, tokenType0)
      skipWs(baseLexer)
      advanceLexer(baseLexer)
    }
    else if (tokenType0 === C_COLON || tokenType0 === C_COLONCOLON) {
      advanceAs(baseLexer, tokenType0)
      if (baseLexer.tokenType === C_SYM) {
        advanceAs(baseLexer, KEYWORD)
      }
    }
    else if (tokenType0 === C_PAREN1) {
      advanceAs(baseLexer, tokenType0)
      skipWs(baseLexer)
      w@ while (true) {
        val tokenType = baseLexer.tokenType
        when (tokenType) {
          C_DOT, C_DOTDASH, C_SLASH, C_SYM -> advanceAs(baseLexer, CALLABLE)
          C_COLON, C_COLONCOLON -> advanceAs(baseLexer, CALLABLE)
          C_SLASH -> advanceAs(baseLexer, tokenType)
          else -> break@w
        }
      }
    }
    else {
      super.lookAhead(baseLexer)
    }
  }
}

class ClojureColorSettingsPage : ColorSettingsPage {

  override fun getDisplayName() = "Clojure"
  override fun getIcon() = ClojureFileType.icon
  override fun getAttributeDescriptors() = ATTRS
  override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
  override fun getHighlighter() = ClojureSyntaxHighlighter(ClojureLanguage)

  override fun getDemoText() =
      """
(ns <meta>^{:doc "The core Clojure language."
       :author "Rich Hickey"}</meta>
  <ns>clojure.core</ns>)

(def
 <meta>^{:arglists '([& items])
   :doc "Creates a new list containing the items."
   :added "1.0"}</meta>
  list (. clojure.lang.PersistentList creator))

(def
 <meta>^{:arglists '([x seq])
    :doc "Returns a new seq where x is the first element and seq is
    the rest."
   :added "1.0"
   :static true}</meta>

 cons (fn* <meta>^:static</meta> cons [<arg>x</arg> <arg>seq</arg>] (. clojure.lang.RT (cons <arg>x</arg> <arg>seq</arg>))))
  """

  override fun getAdditionalHighlightingTagToDescriptorMap() = hashMapOf(
      "ns" to ClojureColors.NAMESPACE,
      "meta" to ClojureColors.METADATA,
      "reader" to ClojureColors.READER_MACRO,
      "arg" to ClojureColors.FN_ARGUMENT,
      "bnd" to ClojureColors.LET_BINDING)

  companion object {
    private val ATTRS = arrayOf(
        AttributesDescriptor("Illegal symbol", ClojureColors.ILLEGAL),
        AttributesDescriptor("Comment", ClojureColors.COMMENT),
        AttributesDescriptor("Literals//String", ClojureColors.STRING),
        AttributesDescriptor("Literals//Number", ClojureColors.NUMBER),
        AttributesDescriptor("Literals//Boolean", ClojureColors.BOOLEAN),
        AttributesDescriptor("Literals//nil", ClojureColors.NIL),
        AttributesDescriptor("Literals//Symbol", ClojureColors.SYMBOL),
        AttributesDescriptor("Literals//Keyword", ClojureColors.KEYWORD),
        AttributesDescriptor("Punctuation//Comma", ClojureColors.COMMA),
        AttributesDescriptor("Punctuation//Dot", ClojureColors.DOT),
        AttributesDescriptor("Punctuation//Slash", ClojureColors.SLASH),
        AttributesDescriptor("Punctuation//Quote", ClojureColors.QUOTE),
        AttributesDescriptor("Punctuation//Syntax quote", ClojureColors.SYNTAX_QUOTE),
        AttributesDescriptor("Braces//Parens", ClojureColors.PARENS),
        AttributesDescriptor("Braces//Braces", ClojureColors.BRACES),
        AttributesDescriptor("Braces//Brackets", ClojureColors.BRACKETS),

        AttributesDescriptor("First list item", ClojureColors.CALLABLE),

        AttributesDescriptor("Entities//Function argument", ClojureColors.FN_ARGUMENT),
        AttributesDescriptor("Entities//Local binding", ClojureColors.LET_BINDING),
        AttributesDescriptor("Entities//Namespace", ClojureColors.NAMESPACE),
        AttributesDescriptor("Entities//Dynamic", ClojureColors.DYNAMIC),
        AttributesDescriptor("Entities//Metadata", ClojureColors.METADATA),
        AttributesDescriptor("Entities//Reader macro", ClojureColors.READER_MACRO))
  }
}