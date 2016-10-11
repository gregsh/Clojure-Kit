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

package org.intellij.clojure.parser

import com.intellij.lang.*
import com.intellij.lang.WhitespacesBinders.GREEDY_LEFT_BINDER
import com.intellij.lang.WhitespacesBinders.GREEDY_RIGHT_BINDER
import com.intellij.lang.parser.GeneratedParserUtilBase
import com.intellij.lang.parser.GeneratedParserUtilBase.*
import com.intellij.lexer.FlexAdapter
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.util.containers.JBIterable
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.lang.ClojureBraceMatcher
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.lang.ClojureScriptLanguage
import org.intellij.clojure.psi.ClojureElementType
import org.intellij.clojure.psi.ClojureTypes
import org.intellij.clojure.psi.ClojureTypes.*
import org.intellij.clojure.psi.impl.CDefImpl
import org.intellij.clojure.psi.impl.CMDefImpl
import org.intellij.clojure.psi.impl.CReaderCondImpl
import org.intellij.clojure.psi.impl.ClojureFileImpl
import org.intellij.clojure.psi.stubs.ClojureFileElementType
import org.intellij.clojure.util.iterate

/**
 * @author gregsh
 */
object ClojureTokens {
  @JvmField val CLJ_FILE_TYPE = ClojureFileElementType("CLOJURE_FILE", ClojureLanguage)
  @JvmField val CLJS_FILE_TYPE = ClojureFileElementType("CLOJURE_SCRIPT_FILE", ClojureScriptLanguage)

  @JvmField val LINE_COMMENT = IElementType("C_LINE_COMMENT", ClojureLanguage)

  @JvmField val WHITESPACES = TokenSet.create(C_COMMA, TokenType.WHITE_SPACE)
  @JvmField val COMMENTS = TokenSet.create(LINE_COMMENT)
  @JvmField val STRINGS = TokenSet.create(C_STRING, C_STRING_UNCLOSED)

  @JvmField val SHARPS = TokenSet.create(C_SHARP, C_SHARP_COMMENT, C_SHARP_QMARK, C_SHARP_QMARK_AT, C_SHARP_EQ, C_SHARP_HAT, C_SHARP_QUOTE)
  @JvmField val MACROS = TokenSet.orSet(SHARPS, TokenSet.create(C_AT, C_COLON, C_COLONCOLON, C_HAT, C_SYNTAX_QUOTE, C_TILDE))

  @JvmField val PARENS = TokenSet.create(C_PAREN1, C_PAREN2, C_BRACE1, C_BRACE2, C_BRACKET1, C_BRACKET2)
  @JvmField val LIST_ALIKE = TokenSet.create(C_FUN, C_LIST, C_MAP, C_SET, C_VEC)

  @JvmStatic fun wsOrComment(t: IElementType?) = t != null && (WHITESPACES.contains(t) || COMMENTS.contains(t))
}

class ClojureLexer(language: Language) : FlexAdapter(_ClojureLexer(language))

class ClojureParserUtil {
  companion object {
    @JvmStatic fun adapt_builder_(root: IElementType, builder: PsiBuilder, parser: PsiParser, extendsSets: Array<TokenSet>?): PsiBuilder {
      val o = GeneratedParserUtilBase.adapt_builder_(root, builder, parser, extendsSets)
//      ErrorState.get(o).braces = null;
      return o
    }

    @JvmStatic fun parseTree(b: PsiBuilder, l: Int, p: Parser) =
        parseAsTree(ErrorState.get(b), b, l, DUMMY_BLOCK, false, p, TRUE_CONDITION)

    @JvmStatic fun nospace(b: PsiBuilder, l: Int): Boolean {
      if (ClojureTokens.WHITESPACES.contains(b.rawLookup(0))) {
        b.mark().apply { b.tokenType; error("no <whitespace> allowed") }
            .setCustomEdgeTokenBinders(GREEDY_LEFT_BINDER, GREEDY_RIGHT_BINDER)
      }
      return true
    }

    @JvmStatic fun formRecover(b: PsiBuilder, l: Int) =
        b.tokenType == TokenType.BAD_CHARACTER || b.tokenType == ClojureTypes.C_STRING_UNCLOSED
  }

}

abstract class ClojureParserDefinitionBase : ParserDefinition {

  override fun createLexer(project: Project?) = ClojureLexer(fileNodeType.language)
  override fun createParser(project: Project?) = ClojureParser()
  override fun createFile(viewProvider: FileViewProvider?) = ClojureFileImpl(viewProvider!!, fileNodeType.language)
  override fun createElement(node: ASTNode?) = createPsiElement(node)

  override fun getStringLiteralElements() = ClojureTokens.STRINGS
  override fun getWhitespaceTokens() = ClojureTokens.WHITESPACES
  override fun getCommentTokens() = ClojureTokens.COMMENTS

  private fun createPsiElement(node: ASTNode?): PsiElement {
    if (node != null && node.elementType == ClojureTypes.C_LIST) run f@ {
      if (node.firstChildNode.elementType == ClojureTypes.C_READER_MACRO) {
        node.firstChildNode.firstChildNode.elementType.let {
          if (it == ClojureTypes.C_SHARP_QMARK) return CReaderCondImpl(node, false)
          if (it == ClojureTypes.C_SHARP_QMARK_AT) return CReaderCondImpl(node, true)
        }
      }
      val forms: JBIterable<out ASTNode> = node.iterate().filter {
        val type = it.elementType
        type != ClojureTypes.C_READER_MACRO && type != ClojureTypes.C_METADATA && type is ClojureElementType
      }.take(3)
      val first = forms.get(0)
      val second = forms.get(1)
      if (first != null && first.elementType == ClojureTypes.C_SYMBOL &&
          first.firstChildNode.elementType.let { it != ClojureTypes.C_DOT && it != ClojureTypes.C_DOTDASH } &&
          first.lastChildNode?.treePrev?.elementType.let { it != ClojureTypes.C_DOTDASH }) {
        if (second?.elementType == ClojureTypes.C_SYMBOL) {
          val text = first.lastChildNode?.text ?: return@f
          if (text == "ns" || text == "in-ns" || text == "create-ns") return CDefImpl(node)
          if (ClojureConstants.DEF_ALIKE_SYMBOLS.contains(text)) return CDefImpl(node)
          if (text.startsWith("def") && !text.startsWith("default") &&
              text == text.toLowerCase() &&
              forms.get(2)?.elementType == ClojureTypes.C_VEC) {
            return CDefImpl(node)
          }
        }
        else if (node.treeParent?.run {
          elementType == C_LIST &&
              firstChildNode?.treeNext?.lastChildNode?.text?.let {
                it == "defprotocol" || it == "defrecord"
              } ?: false
        } ?: false) {
          return CMDefImpl(node)
        }
      }
    }
    return ClojureTypes.Factory.createElement(node)!!
  }

  override fun spaceExistanceTypeBetweenTokens(left: ASTNode, right: ASTNode): ParserDefinition.SpaceRequirements {
    val lt = left.elementType
    val rt = right.elementType
    if (rt == ClojureTypes.C_COMMA || ClojureTokens.MACROS.contains(lt)) {
      return ParserDefinition.SpaceRequirements.MUST_NOT
    }
    if (lt == ClojureTypes.C_DOT || lt == ClojureTypes.C_DOTDASH ||
        lt == ClojureTypes.C_SLASH && rt == ClojureTypes.C_SYM ||
        lt == ClojureTypes.C_SYM && rt == ClojureTypes.C_SLASH) {
      return ParserDefinition.SpaceRequirements.MUST_NOT
    }
    for (p in ClojureBraceMatcher.PAIRS) {
      if (lt == p.leftBraceType || rt == p.rightBraceType) {
        return ParserDefinition.SpaceRequirements.MAY
      }
    }
    return ParserDefinition.SpaceRequirements.MUST
  }
}
