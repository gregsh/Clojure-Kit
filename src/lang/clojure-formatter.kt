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

package org.intellij.clojure.formatter

import com.intellij.formatting.*
import com.intellij.formatting.alignment.AlignmentStrategy
import com.intellij.lang.ASTNode
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.CustomCodeStyleSettings
import com.intellij.psi.formatter.common.AbstractBlock
import com.intellij.psi.tree.IFileElementType
import com.intellij.util.Function
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.parser.ClojureTokens
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.ClojureTypes.*
import org.intellij.clojure.util.findChild
import org.intellij.clojure.util.iterate
import org.intellij.clojure.util.notNulls
import org.intellij.clojure.util.parents

/**
 * @author gregsh
 */
class ClojureCodeStyleSettings (container: CodeStyleSettings?) : CustomCodeStyleSettings("ClojureCodeStyleSettings", container) {
}

class ClojureFormattingModelBuilder : FormattingModelBuilder {
  override fun getRangeAffectingIndent(file: PsiFile?, offset: Int, elementAtOffset: ASTNode?) = null

  override fun createModel(element: PsiElement, settings: CodeStyleSettings): FormattingModel {
    val common = settings.getCommonSettings(ClojureLanguage)
    val custom = settings.getCustomSettings(ClojureCodeStyleSettings::class.java)
    val spacingBuilder = createSpacingBuilder(common, custom)
    val props = Context(common, custom, spacingBuilder)
    val block = ClojureFormattingBlock(element.node, props, null, null, null, -1)
    return FormattingModelProvider.createFormattingModelForPsiFile(element.containingFile, block, settings)
  }

  fun createSpacingBuilder(common: CommonCodeStyleSettings, custom: ClojureCodeStyleSettings): SpacingBuilder {
    return SpacingBuilder(common.rootSettings, ClojureLanguage)
        .before(C_COMMA).spaceIf(common.SPACE_BEFORE_COMMA)
        .after(C_COMMA).spaceIf(common.SPACE_AFTER_COMMA)
        .between(C_PAREN1, C_PAREN2).lineBreakOrForceSpace(false, false)
        .between(C_BRACE1, C_BRACE2).lineBreakOrForceSpace(false, false)
        .between(C_BRACKET1, C_BRACKET2).lineBreakOrForceSpace(false, false)
        .after(C_PAREN1).lineBreakOrForceSpace(false, false)
        .after(C_BRACE1).lineBreakOrForceSpace(false, false)
        .after(C_BRACKET1).lineBreakOrForceSpace(false, false)
        .before(C_PAREN2).lineBreakOrForceSpace(false, false)
        .before(C_BRACE2).lineBreakOrForceSpace(false, false)
        .before(C_BRACKET2).lineBreakOrForceSpace(false, false)
        .after(C_HAT).lineBreakOrForceSpace(false, false)
        .after(C_SHARP).lineBreakOrForceSpace(false, false)
        .after(C_SHARP_COMMENT).lineBreakOrForceSpace(false, false)
        .after(C_SHARP_QMARK).lineBreakOrForceSpace(false, false)
        .after(C_SHARP_QMARK_AT).lineBreakOrForceSpace(false, false)
        .after(C_SHARP_EQ).lineBreakOrForceSpace(false, false)
        .after(C_SHARP_HAT).lineBreakOrForceSpace(false, false)
        .after(C_SHARP_QUOTE).lineBreakOrForceSpace(false, false)
  }
}


data class Context(val common: CommonCodeStyleSettings,
                   val custom: ClojureCodeStyleSettings,
                   val spacingBuilder: SpacingBuilder) {
  val SHORT_ENOUGH = common.rootSettings.getRightMargin(ClojureLanguage) * 2 / 3
}

class ClojureFormattingBlock(node: ASTNode,
                             val context: Context,
                             wrap: Wrap?,
                             alignment: Alignment?,
                             val alignmentStrategy: AlignmentStrategy?,
                             val sequenceIndex: Int) :
    AbstractBlock(node, wrap, alignment) {
  val children: List<ClojureFormattingBlock> by lazy { calcChildren() }
  val childAlignment = if (ClojureTokens.LIST_ALIKE.contains(myNode.elementType)) Alignment.createAlignment() else null

  override fun buildChildren() = children
  override fun isLeaf() = node.elementType !is IFileElementType && node.firstChildNode?.let {
    it == node.lastChildNode || node.elementType == C_SYMBOL && it.elementType is ClojureTokenType } ?: true
  override fun getIndent(): Indent =
      if (sequenceIndex >= 0) Indent.getNormalIndent() else Indent.getNoneIndent()

  override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
    val nodeType = node.elementType
    if (nodeType is IFileElementType) return ChildAttributes(Indent.getNoneIndent(), null)
    if (ClojureTokens.LIST_ALIKE.contains(nodeType)) {
      if (children.asSequence().take(newChildIndex).find { ClojureTokens.PARENS.contains(it.node.elementType) } != null) {
        return ChildAttributes(Indent.getNormalIndent(), childAlignment)
      }
    }
    return ChildAttributes(Indent.getNoneIndent(), null)
  }

  override fun getSpacing(child1: Block?, child2: Block): Spacing? {
    val block1 = child1 as? ClojureFormattingBlock ?: return null
    val block2 = child2 as? ClojureFormattingBlock ?: return null
    val psi = myNode.psi
    val psi1 = block1.node.psi!!
    val psi2 = child2.node.psi!!
    if (psi1 is CDef && ClojureConstants.NS_ALIKE_SYMBOLS.contains(psi1.def.type)) {
      return Spacing.createSpacing(1, 1, 2, context.common.KEEP_LINE_BREAKS, 10)
    }
    val newLine = Spacing.createSpacing(1, 1, 1, context.common.KEEP_LINE_BREAKS, context.common.KEEP_BLANK_LINES_IN_CODE)
    fun dependentSpacing(r: TextRange) = Spacing.createDependentLFSpacing(
        0, 1, r, context.common.KEEP_LINE_BREAKS, context.common.KEEP_BLANK_LINES_IN_CODE)
    fun dependentSpacing1() = dependentSpacing(TextRange.create(textRange.startOffset, block2.textRange.startOffset))
    fun dependentSpacing2() = dependentSpacing(TextRange.create(block1.textRange.startOffset,
        if (block2.node.psi is CSForm) block1.textRange.endOffset else block2.textRange.endOffset))
    if (psi is CSymbol) {
      if (psi1 is CMetadata) return dependentSpacing1()
    }
    if (block2.sequenceIndex >= 0 && !ClojureTokens.PARENS.contains(child2.node.elementType)) {
      when (psi) {
        is CMap -> {
          return if (block2.sequenceIndex > 0 && block2.sequenceIndex % 2 == 0) newLine
          else dependentSpacing(block1.textRange)
        }
        is CDef -> when {
          psi.def.type == "ns" -> if (block2.sequenceIndex > 1) return newLine
          psi.def.type.startsWith("def") -> (children.find { it.node.psi == psi.nameSymbol }?.sequenceIndex ?: 1).let {
            return if (block2.sequenceIndex > it) {
              if (block2.textRange.length < context.SHORT_ENOUGH &&
                  block2.sequenceIndex == children[children.size - 2].sequenceIndex) null
              else newLine
            }
            else dependentSpacing2()
          }
        }
        is CVec -> {
          val parent = psi.parent.run { if (findChild(CForm::class) == psi) parent else this }
          if (parent is CDef) return null
          else (parent as? CList)?.first?.name.let {
            if (it != null && ClojureConstants.FN_ALIKE_SYMBOLS.contains(it)) return null
            else if (it != null && ClojureConstants.LET_ALIKE_SYMBOLS.contains(it) && parent == psi.parent) {
              return if (block2.sequenceIndex > 0 && block2.sequenceIndex % 2 == 0) newLine else dependentSpacing2()
            }
          }
        }
        is CList -> {
          val type = psi.findChild(CSForm::class)?.let { if (it is CSymbol) it.name else if (it is CKeyword) it.name else null }
          return if (ClojureConstants.NS_ALIKE_SYMBOLS.contains(type)) {
            if (block2.sequenceIndex > 1 && ClojureTokens.LIST_ALIKE.contains(block2.node.elementType)) newLine else dependentSpacing1()
          }
          else if (type == "if") {
            if (block2.sequenceIndex > 1) dependentSpacing(textRange) else null
          }
          else if (type == "cond" || type == "condp" || type == "assert-args") {
            if (block2.sequenceIndex > 1 && block2.sequenceIndex % 2 == 1) newLine else dependentSpacing2()
          }
          else if (type == "cond->" || type == "cond->>") {
            if (block2.sequenceIndex > 2 && block2.sequenceIndex % 2 == 0) newLine else dependentSpacing2()
          }
          else null
        }
      }
      return dependentSpacing1()
    }
    return context.spacingBuilder.getSpacing(this, child1, child2)
  }

  private fun calcChildren(): List<ClojureFormattingBlock> {
    if (isLeaf) return emptyList()
    ProgressManager.checkCanceled()
    val nodeType = node.elementType
    var leftParen = false
    var prevIndex = -1
    val nextIndex: ()->Int = if (!ClojureTokens.LIST_ALIKE.contains(nodeType)) {{ -1 }} else {{ if (!leftParen) -1 else ++prevIndex }}

    val minAlignIndex = if (nodeType != C_LIST || node.firstChildNode.elementType == C_READER_MACRO) 0
    else if (node.psi is CDef || node.psi.parents().skip(1).filter(CForm::class.java).isEmpty) Integer.MAX_VALUE
    else if ((node.psi as? CList)?.first?.name?.let {
      ClojureConstants.FN_ALIKE_SYMBOLS.contains(it) || ClojureConstants.LET_ALIKE_SYMBOLS.contains(it) } ?: false) Integer.MAX_VALUE
    else 1

    val metaAlign = if (node.firstChildNode?.elementType == C_METADATA) Alignment.createAlignment() else null
    val mapAlign = if (node.psi is CMap) Alignment.createAlignment(true) else null
    return node.iterate().transform(Function<ASTNode, ClojureFormattingBlock?> f@ {
      val type = it.elementType
      if (type == TokenType.WHITE_SPACE && it.textLength == 1 && it.text == "," ||
          type == TokenType.ERROR_ELEMENT && it.textLength > 0 ||
          ClojureTokens.COMMENTS.contains(type)) {
        return@f ClojureFormattingBlock(it, context, wrap, if (leftParen && prevIndex >= minAlignIndex) childAlignment else null, alignmentStrategy, prevIndex)
      }
      if (type == TokenType.WHITE_SPACE || type == TokenType.ERROR_ELEMENT) return@f null
      val index = if (leftParen) nextIndex()
      else { if (ClojureTokens.PARENS.contains(type)) leftParen = true; -1 }
      val align = if (index >= minAlignIndex) if (mapAlign != null && index % 2 == 1) mapAlign else childAlignment else metaAlign
      ClojureFormattingBlock(it, context, wrap, align, alignmentStrategy, index)
    }).notNulls().toList()
  }

}