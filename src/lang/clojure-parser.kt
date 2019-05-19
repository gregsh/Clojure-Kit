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
import com.intellij.lang.parser.GeneratedParserUtilBase
import com.intellij.lexer.FlexAdapter
import com.intellij.lexer.Lexer
import com.intellij.lexer.LookAheadLexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import org.intellij.clojure.lang.ClojureTokens
import org.intellij.clojure.psi.ClojureTypes.*
import org.intellij.clojure.psi.impl.CFileImpl
import org.intellij.clojure.util.wsOrComment

/**
 * @author gregsh
 */
class ClojureLexer(language: Language) : LookAheadLexer(FlexAdapter(_ClojureLexer(language))) {
  override fun lookAhead(baseLexer: Lexer) {
    val tokenType0 = baseLexer.tokenType
    val tokenEnd0 = baseLexer.tokenEnd
    when (tokenType0) {
      in ClojureTokens.LITERALS -> {
        baseLexer.advance()
        if (baseLexer.tokenType === C_SYM ||
            baseLexer.tokenType in ClojureTokens.LITERALS) {
          advanceAs(baseLexer, TokenType.BAD_CHARACTER)
        }
        else {
          addToken(tokenEnd0, tokenType0)
        }
      }
      else -> super.lookAhead(baseLexer)
    }
  }
}

class ClojureParserDefinition : ClojureParserDefinitionBase() {
  override fun getFileNodeType() = ClojureTokens.CLJ_FILE_TYPE
}

class ClojureScriptParserDefinition : ClojureParserDefinitionBase() {
  override fun getFileNodeType() = ClojureTokens.CLJS_FILE_TYPE
}

class ClojureASTFactory : ASTFactory() {
  override fun createComposite(type: IElementType): CompositeElement? = Factory.createElement(type)
}

abstract class ClojureParserDefinitionBase : ParserDefinition {

  override fun createLexer(project: Project?) = ClojureLexer(fileNodeType.language)
  override fun createParser(project: Project?) = ClojureParser()
  override fun createFile(viewProvider: FileViewProvider?) = CFileImpl(viewProvider!!, fileNodeType.language)
  override fun createElement(node: ASTNode?) = throw UnsupportedOperationException(
      "$node" + (node?.elementType?.language ?: fileNodeType.language).let {
        "; ASTFactory(${it.id})=${LanguageASTFactory.INSTANCE.forLanguage(it)}"
      })

  override fun getStringLiteralElements() = ClojureTokens.STRINGS
  override fun getWhitespaceTokens() = ClojureTokens.WHITESPACES
  override fun getCommentTokens() = ClojureTokens.COMMENTS

  override fun spaceExistanceTypeBetweenTokens(left: ASTNode, right: ASTNode): ParserDefinition.SpaceRequirements {
    val lt = left.elementType
    val rt = right.elementType
    if (rt == C_COMMA || ClojureTokens.MACROS.contains(lt) || ClojureTokens.SHARPS.contains(lt)) {
      return ParserDefinition.SpaceRequirements.MUST_NOT
    }
    if (lt == C_DOT || rt == C_DOT || lt == C_DOTDASH ||
        lt == C_SLASH && rt == C_SYM ||
        lt == C_SYM && rt == C_SLASH) {
      return ParserDefinition.SpaceRequirements.MUST_NOT
    }
    for (p in ClojureTokens.BRACE_PAIRS) {
      if (lt == p.leftBraceType || rt == p.rightBraceType) {
        return ParserDefinition.SpaceRequirements.MAY
      }
    }
    return ParserDefinition.SpaceRequirements.MUST
  }
}

class ClojureParserUtil {
  @Suppress("UNUSED_PARAMETER")
  companion object {
    @JvmStatic fun adapt_builder_(root: IElementType, builder: PsiBuilder, parser: PsiParser, extendsSets: Array<TokenSet>?): PsiBuilder =
        GeneratedParserUtilBase.adapt_builder_(root, builder, parser, extendsSets).apply {
          (this as? GeneratedParserUtilBase.Builder)?.state?.braces = null
        }

    @JvmStatic fun parseTree(b: PsiBuilder, l: Int, p: GeneratedParserUtilBase.Parser) =
        GeneratedParserUtilBase.parseAsTree(GeneratedParserUtilBase.ErrorState.get(b), b, l,
            GeneratedParserUtilBase.DUMMY_BLOCK, false, p, GeneratedParserUtilBase.TRUE_CONDITION)

    @JvmStatic fun nospace(b: PsiBuilder, l: Int): Boolean {
      if (space(b, l)) {
        b.mark().apply { b.tokenType; error("no <whitespace> allowed") }
            .setCustomEdgeTokenBinders(WhitespacesBinders.GREEDY_LEFT_BINDER, WhitespacesBinders.GREEDY_RIGHT_BINDER)
      }
      return true
    }

    @JvmStatic fun space(b: PsiBuilder, l: Int): Boolean {
      return b.rawLookup(0).wsOrComment() || b.rawLookup(-1).wsOrComment()
    }

    private val RECOVER_SET = TokenSet.orSet(
        ClojureTokens.SHARPS, ClojureTokens.MACROS, ClojureTokens.PAREN_ALIKE, ClojureTokens.LITERALS,
        TokenSet.create(C_DOT, C_DOTDASH))

    @JvmStatic fun formRecover(b: PsiBuilder, l: Int): Boolean {
      return !RECOVER_SET.contains(b.tokenType)
    }

    @JvmStatic fun rootFormRecover(b: PsiBuilder, l: Int): Boolean {
      val type = b.tokenType
      return ClojureTokens.PAREN2_ALIKE.contains(type) || !RECOVER_SET.contains(type)
    }
  }
}
