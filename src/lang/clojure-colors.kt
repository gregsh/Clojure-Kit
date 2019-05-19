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

import com.intellij.codeHighlighting.RainbowHighlighter
import com.intellij.lang.Language
import com.intellij.lang.LanguageUtil
import com.intellij.lexer.Lexer
import com.intellij.lexer.LookAheadLexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.RainbowColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.util.containers.ContainerUtil
import org.intellij.clojure.parser.ClojureLexer
import org.intellij.clojure.psi.ClojureTypes.*

object ClojureColors {
  @JvmField val LINE_COMMENT = createTextAttributesKey("C_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
  @JvmField val FORM_COMMENT = createTextAttributesKey("C_FORM_COMMENT")
  @JvmField val STRING = createTextAttributesKey("C_STRING", DefaultLanguageHighlighterColors.STRING)
  @JvmField val CHARACTER = createTextAttributesKey("C_CHARACTER", DefaultLanguageHighlighterColors.STRING)
  @JvmField val NUMBER = createTextAttributesKey("C_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
  @JvmField val KEYWORD = createTextAttributesKey("C_KEYWORD", DefaultLanguageHighlighterColors.METADATA)
  @JvmField val SYMBOL = createTextAttributesKey("C_SYMBOL", DefaultLanguageHighlighterColors.IDENTIFIER)
  @JvmField val BOOLEAN = createTextAttributesKey("C_BOOLEAN", DefaultLanguageHighlighterColors.KEYWORD)
  @JvmField val NIL = createTextAttributesKey("C_NIL", DefaultLanguageHighlighterColors.KEYWORD)
  @JvmField val CALLABLE = createTextAttributesKey("C_CALLABLE", DefaultLanguageHighlighterColors.KEYWORD)

  @JvmField val COMMA = createTextAttributesKey("C_COMMA", DefaultLanguageHighlighterColors.COMMA)
  @JvmField val DOT = createTextAttributesKey("C_DOT", DefaultLanguageHighlighterColors.KEYWORD)
  @JvmField val SLASH = createTextAttributesKey("C_SLASH", DefaultLanguageHighlighterColors.DOT)
  @JvmField val QUOTE = createTextAttributesKey("C_QUOTE", DefaultLanguageHighlighterColors.STRING)
  @JvmField val SYNTAX_QUOTE = createTextAttributesKey("C_SYNTAX_QUOTE", DefaultLanguageHighlighterColors.OPERATION_SIGN)
  @JvmField val UNQUOTE = createTextAttributesKey("C_UNQUOTE", DefaultLanguageHighlighterColors.OPERATION_SIGN)
  @JvmField val DEREF = createTextAttributesKey("C_DEREF", DefaultLanguageHighlighterColors.OPERATION_SIGN)
  @JvmField val PARENS = createTextAttributesKey("C_PARENS", DefaultLanguageHighlighterColors.PARENTHESES)
  @JvmField val BRACES = createTextAttributesKey("C_BRACES", DefaultLanguageHighlighterColors.BRACES)
  @JvmField val BRACKETS = createTextAttributesKey("C_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)

  @JvmField val QUOTED_SYM = createTextAttributesKey("C_QUOTED_SYM", DefaultLanguageHighlighterColors.STRING)
  @JvmField val METADATA = createTextAttributesKey("C_METADATA")
  @JvmField val READER_MACRO = createTextAttributesKey("C_READER_MACRO")
  @JvmField val DATA_READER = createTextAttributesKey("C_DATA_READER", DefaultLanguageHighlighterColors.LABEL)
  @JvmField val DEFINITION = createTextAttributesKey("C_DEFINITION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
  @JvmField val FN_ARGUMENT = createTextAttributesKey("C_FN_ARGUMENT", DefaultLanguageHighlighterColors.PARAMETER)
  @JvmField val LET_BINDING = createTextAttributesKey("C_LET_BINDING", DefaultLanguageHighlighterColors.LOCAL_VARIABLE)
  @JvmField val TYPE_FIELD = createTextAttributesKey("C_TYPE_FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
  @JvmField val NAMESPACE = createTextAttributesKey("C_NAMESPACE", DefaultLanguageHighlighterColors.IDENTIFIER)
  @JvmField val ALIAS = createTextAttributesKey("C_ALIAS", DefaultLanguageHighlighterColors.IDENTIFIER)
  @JvmField val DYNAMIC = createTextAttributesKey("C_DYNAMIC", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL)

  @JvmField val JAVA_CLASS = createTextAttributesKey("C_JAVA_CLASS", DefaultLanguageHighlighterColors.CLASS_NAME)
  @JvmField val JAVA_STATIC_METHOD = createTextAttributesKey("C_JAVA_STATIC_METHOD", DefaultLanguageHighlighterColors.STATIC_METHOD)
  @JvmField val JAVA_STATIC_FIELD = createTextAttributesKey("C_JAVA_STATIC_FIELD", DefaultLanguageHighlighterColors.STATIC_FIELD)
  @JvmField val JAVA_INSTANCE_FIELD = createTextAttributesKey("C_JAVA_INSTANCE_FIELD", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
  @JvmField val JAVA_INSTANCE_METHOD = createTextAttributesKey("C_JAVA_INSTANCE_METHOD", DefaultLanguageHighlighterColors.INSTANCE_METHOD)

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
      TokenType.BAD_CHARACTER -> pack(HighlighterColors.BAD_CHARACTER)
      ClojureTokens.LINE_COMMENT -> pack(ClojureColors.LINE_COMMENT)
      C_STRING -> pack(ClojureColors.STRING)
      C_CHAR -> pack(ClojureColors.CHARACTER)
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
      C_TILDE, C_TILDE_AT -> pack(ClojureColors.UNQUOTE)
      C_AT -> pack(ClojureColors.DEREF)
      C_HAT, C_SHARP_HAT -> pack(ClojureColors.METADATA)
      C_SHARP, C_SHARP_COMMENT, C_SHARP_EQ, C_SHARP_NS -> pack(ClojureColors.READER_MACRO)
      C_SHARP_QMARK, C_SHARP_QMARK_AT, C_SHARP_QUOTE -> pack(ClojureColors.READER_MACRO)
      C_PAREN1, C_PAREN2 -> pack(ClojureColors.PARENS)
      C_BRACE1, C_BRACE2 -> pack(ClojureColors.BRACES)
      C_BRACKET1, C_BRACKET2 -> pack(ClojureColors.BRACKETS)
      ClojureHighlightingLexer.CALLABLE -> pack(ClojureColors.CALLABLE)
      ClojureHighlightingLexer.KEYWORD -> pack(ClojureColors.KEYWORD)
      ClojureHighlightingLexer.CALLABLE_KEYWORD -> pack(ClojureColors.CALLABLE, ClojureColors.KEYWORD)
      ClojureHighlightingLexer.QUOTED_SYM -> pack(ClojureColors.QUOTED_SYM)
      ClojureHighlightingLexer.DATA_READER -> pack(ClojureColors.DATA_READER)
      ClojureHighlightingLexer.HAT_SYM -> pack(ClojureColors.METADATA)
      else -> EMPTY
    }
  }
}

class ClojureHighlightingLexer(language: Language) : LookAheadLexer(ClojureLexer(language)) {
  companion object {
    val CALLABLE = IElementType("C_CALLABLE*", ClojureLanguage)
    val KEYWORD = IElementType("C_KEYWORD*", ClojureLanguage)
    val CALLABLE_KEYWORD = IElementType("C_CALLABLE_KEYWORD*", ClojureLanguage)
    val HAT_SYM = IElementType("C_HAT_SYM*", ClojureLanguage)
    val QUOTED_SYM = IElementType("C_QUOTED_SYM*", ClojureLanguage)
    val DATA_READER = IElementType("C_DATA_READER*", ClojureLanguage)
  }

  override fun lookAhead(baseLexer: Lexer) {
    fun skipWs(l: Lexer) {
      while (ClojureTokens.WHITESPACES.contains(l.tokenType)
          || ClojureTokens.COMMENTS.contains(l.tokenType)) {
        advanceLexer(l)
      }
    }

    val tokenType0 = baseLexer.tokenType

    when (tokenType0) {
      C_SHARP -> {
        baseLexer.advance()
        when (baseLexer.tokenType) {
          C_STRING, C_PAREN1, C_BRACE1 -> advanceAs(baseLexer, baseLexer.tokenType)
          C_SYM -> advanceAs(baseLexer, DATA_READER)
          else -> addToken(baseLexer.tokenStart, C_SHARP)
        }
      }
      C_QUOTE, C_SYNTAX_QUOTE -> {
        advanceAs(baseLexer, tokenType0)
        skipWs(baseLexer)
        if (baseLexer.tokenType === C_SYM) advanceSymbolAs(baseLexer, QUOTED_SYM)
        else advanceLexer(baseLexer)
      }
      C_COLON, C_COLONCOLON -> {
        advanceAs(baseLexer, tokenType0)
        if (baseLexer.tokenType === C_SYM) {
          advanceAs(baseLexer, KEYWORD)
          if (baseLexer.tokenType === C_SLASH) advanceAs(baseLexer, KEYWORD)
          if (baseLexer.tokenType === C_SYM) advanceAs(baseLexer, KEYWORD)
        }
      }
      C_PAREN1 -> {
        advanceAs(baseLexer, tokenType0)
        skipWs(baseLexer)
        val callableType = if (baseLexer.tokenType.let { it == C_COLON || it == C_COLONCOLON }) CALLABLE_KEYWORD else CALLABLE
        advanceSymbolAs(baseLexer, callableType)
      }
      C_HAT -> {
        advanceAs(baseLexer, tokenType0)
        skipWs(baseLexer)
        if (baseLexer.tokenType === C_SYM) advanceSymbolAs(baseLexer, HAT_SYM, true)
      }
      else -> super.lookAhead(baseLexer)
    }
  }

  private fun advanceSymbolAs(baseLexer: Lexer, type: IElementType, strict: Boolean = false) {
    w@ while (true) {
      val tokenType = baseLexer.tokenType
      when (tokenType) {
        C_DOT, C_DOTDASH -> if (!strict) advanceAs(baseLexer, tokenType) else break@w
        C_SLASH, C_SYM -> advanceAs(baseLexer, type)
        C_COLON, C_COLONCOLON -> if (!strict) advanceAs(baseLexer, type) else break@w
        else -> break@w
      }
    }
  }
}

class ClojureColorSettingsPage : RainbowColorSettingsPage {
  override fun isRainbowType(key: TextAttributesKey?): Boolean =
      key == ClojureColors.FN_ARGUMENT || key == ClojureColors.LET_BINDING
  override fun getLanguage() = ClojureLanguage

  override fun getDisplayName() = "Clojure"
  override fun getIcon() = ClojureFileType.icon
  override fun getAttributeDescriptors() = ATTRS
  override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
  override fun getHighlighter() = ClojureSyntaxHighlighter(ClojureLanguage)

  override fun getDemoText() =
      """
(ns ^{<k>:doc</k> "The core Clojure language."
       <k>:author</k> "Rich Hickey"}
  <ns>clojure.core</ns>)

<ign>(<call>comment</call> <str>"clojure code fragments below"</str>)</ign>
(alias 'core 'clojure.core)

(<as>core</as>/defn <def>mod</def>
  "Modulus of num and div. Truncates toward negative infinity."
  {<k>:added</k> "1.0"
   <k>:static</k> true}
  [<arg>num</arg> <arg>div</arg>]
  (let [<bnd>m</bnd> (rem <arg>num</arg> <arg>div</arg>)]
    (if (or (zero? <bnd>m</bnd>) (= (pos? <arg>num</arg>) (pos? <arg>div</arg>)))
      <bnd>m</bnd>
      (+ <bnd>m</bnd> <arg>div</arg>))))

; field access
(. (<call><jc>java.awt.Point</jc>.</call> 1 2) <jif>-x</jif>)
(. <jc>Integer</jc> <jsf>MAX_VALUE</jsf>)
(. (. <jc>Integer</jc> <jsm>parseInt</jsm> "123") <jim>toString</jim>)

; reader conditionals and dynamic resolve
#?(:clj     Double/NaN
   :cljs    <dyn>js</dyn>/NaN
   :default nil)
(def <def>INIT</def> <dr>#js</dr> {})

;${RainbowHighlighter.generatePaletteExample("\n; ")}
  """

  override fun getAdditionalHighlightingTagToDescriptorMap(): MutableMap<String, TextAttributesKey> =
      RainbowHighlighter.createRainbowHLM().apply {
        put("ns", ClojureColors.NAMESPACE)
        put("def", ClojureColors.DEFINITION)
        put("as", ClojureColors.ALIAS)
        put("k", ClojureColors.KEYWORD)
        put("sym", ClojureColors.QUOTED_SYM)
        put("dyn", ClojureColors.DYNAMIC)
        put("dr", ClojureColors.DATA_READER)
        put("arg", ClojureColors.FN_ARGUMENT)
        put("bnd", ClojureColors.LET_BINDING)
        put("ign", ClojureColors.FORM_COMMENT)
        put("call", ClojureColors.CALLABLE)
        put("str", ClojureColors.STRING)
        put("jc", ClojureColors.JAVA_CLASS)
        put("jsm", ClojureColors.JAVA_STATIC_METHOD)
        put("jsf", ClojureColors.JAVA_STATIC_FIELD)
        put("jif", ClojureColors.JAVA_INSTANCE_FIELD)
        put("jim", ClojureColors.JAVA_INSTANCE_METHOD)
      }

  companion object {
    private val ATTRS = arrayOf(
        AttributesDescriptor("Comments//Line comment", ClojureColors.LINE_COMMENT),
        AttributesDescriptor("Comments//Form comment", ClojureColors.FORM_COMMENT),
        AttributesDescriptor("Literals//Symbol", ClojureColors.SYMBOL),
        AttributesDescriptor("Literals//String", ClojureColors.STRING),
        AttributesDescriptor("Literals//Character", ClojureColors.CHARACTER),
        AttributesDescriptor("Literals//Number", ClojureColors.NUMBER),
        AttributesDescriptor("Literals//Boolean", ClojureColors.BOOLEAN),
        AttributesDescriptor("Literals//nil", ClojureColors.NIL),
        AttributesDescriptor("Literals//Quoted symbol", ClojureColors.QUOTED_SYM),
        AttributesDescriptor("Literals//Keyword", ClojureColors.KEYWORD),
        AttributesDescriptor("Punctuation//Comma", ClojureColors.COMMA),
        AttributesDescriptor("Punctuation//Dot", ClojureColors.DOT),
        AttributesDescriptor("Punctuation//Slash", ClojureColors.SLASH),
        AttributesDescriptor("Punctuation//Quote", ClojureColors.QUOTE),
        AttributesDescriptor("Punctuation//Syntax quote", ClojureColors.SYNTAX_QUOTE),
        AttributesDescriptor("Punctuation//Unquote", ClojureColors.UNQUOTE),
        AttributesDescriptor("Punctuation//Dereference", ClojureColors.DEREF),
        AttributesDescriptor("Grouping//Parens", ClojureColors.PARENS),
        AttributesDescriptor("Grouping//Braces", ClojureColors.BRACES),
        AttributesDescriptor("Grouping//Brackets", ClojureColors.BRACKETS),
        AttributesDescriptor("Entities//Callable (list head)", ClojureColors.CALLABLE),
        AttributesDescriptor("Entities//Definition", ClojureColors.DEFINITION),
        AttributesDescriptor("Entities//Data reader (tag)", ClojureColors.DATA_READER),
        AttributesDescriptor("Entities//Function argument", ClojureColors.FN_ARGUMENT),
        AttributesDescriptor("Entities//Local binding", ClojureColors.LET_BINDING),
        AttributesDescriptor("Entities//Type field", ClojureColors.TYPE_FIELD),
        AttributesDescriptor("Entities//Namespace", ClojureColors.NAMESPACE),
        AttributesDescriptor("Entities//Alias", ClojureColors.ALIAS),
        AttributesDescriptor("Entities//Dynamic", ClojureColors.DYNAMIC),
        AttributesDescriptor("Entities//Metadata", ClojureColors.METADATA),
        AttributesDescriptor("Entities//Reader macro", ClojureColors.READER_MACRO),
        AttributesDescriptor("Java//Class", ClojureColors.JAVA_CLASS),
        AttributesDescriptor("Java//Static method", ClojureColors.JAVA_STATIC_METHOD),
        AttributesDescriptor("Java//Static field", ClojureColors.JAVA_STATIC_FIELD),
        AttributesDescriptor("Java//Instance method", ClojureColors.JAVA_INSTANCE_METHOD),
        AttributesDescriptor("Java//Instance field", ClojureColors.JAVA_INSTANCE_FIELD))
  }
}