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
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.TokenType
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import org.intellij.clojure.lang.ClojureTokens
import org.intellij.clojure.psi.ClojureTypes
import org.intellij.clojure.psi.impl.CFileImpl
import java.lang.reflect.Constructor

/**
 * @author gregsh
 */
class ClojureLexer(language: Language) : FlexAdapter(_ClojureLexer(language))

class ClojureParserDefinition : ClojureParserDefinitionBase() {
  override fun getFileNodeType() = ClojureTokens.CLJ_FILE_TYPE
}

class ClojureScriptParserDefinition : ClojureParserDefinitionBase() {
  override fun getFileNodeType() = ClojureTokens.CLJS_FILE_TYPE
}

class ClojureASTFactory : ASTFactory() {
  val ourMap = mapOf<IElementType, Constructor<*>?>(*ClojureTypes.Classes.elementTypes()
      .map { Pair(it, ClojureTypes.Classes.findClass(it).getConstructor(IElementType::class.java)) }
      .toTypedArray())

  override fun createComposite(type: IElementType?): CompositeElement? =
      ourMap[type]?.newInstance(type) as? CompositeElement
}

abstract class ClojureParserDefinitionBase : ParserDefinition {

  override fun createLexer(project: Project?) = ClojureLexer(fileNodeType.language)
  override fun createParser(project: Project?) = ClojureParser()
  override fun createFile(viewProvider: FileViewProvider?) = CFileImpl(viewProvider!!, fileNodeType.language)
  override fun createElement(node: ASTNode?) = throw UnsupportedOperationException()

  override fun getStringLiteralElements() = ClojureTokens.STRINGS
  override fun getWhitespaceTokens() = ClojureTokens.WHITESPACES
  override fun getCommentTokens() = ClojureTokens.COMMENTS

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
    @JvmStatic fun adapt_builder_(root: IElementType, builder: PsiBuilder, parser: PsiParser, extendsSets: Array<TokenSet>?): PsiBuilder {
      return GeneratedParserUtilBase.adapt_builder_(root, builder, parser, extendsSets)
    }

    @JvmStatic fun parseTree(b: PsiBuilder, l: Int, p: GeneratedParserUtilBase.Parser) =
        GeneratedParserUtilBase.parseAsTree(GeneratedParserUtilBase.ErrorState.get(b), b, l, GeneratedParserUtilBase.DUMMY_BLOCK, false, p, GeneratedParserUtilBase.TRUE_CONDITION)

    @JvmStatic fun nospace(b: PsiBuilder, l: Int): Boolean {
      if (ClojureTokens.WHITESPACES.contains(b.rawLookup(0))) {
        b.mark().apply { b.tokenType; error("no <whitespace> allowed") }
            .setCustomEdgeTokenBinders(WhitespacesBinders.GREEDY_LEFT_BINDER, WhitespacesBinders.GREEDY_RIGHT_BINDER)
      }
      return true
    }

    @JvmStatic fun formRecover(b: PsiBuilder, l: Int) =
        b.tokenType == TokenType.BAD_CHARACTER || b.tokenType == ClojureTypes.C_STRING_UNCLOSED
  }
}
