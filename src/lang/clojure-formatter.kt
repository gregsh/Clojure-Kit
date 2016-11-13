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
import org.intellij.clojure.util.*

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
  val SHORT_REALLY = common.rootSettings.getRightMargin(ClojureLanguage) / 4

  val newLineTop = Spacing.createSpacing(1, 1, 2, common.KEEP_LINE_BREAKS, common.KEEP_BLANK_LINES_IN_DECLARATIONS)!!
  val newLine = Spacing.createSpacing(1, 1, 1, common.KEEP_LINE_BREAKS, common.KEEP_BLANK_LINES_IN_CODE)!!
  val spaceOnly = Spacing.createSpacing(1, 0, 0, false, 0)!!
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
      if (sequenceIndex >= 0) Indent.getNormalIndent(true) else Indent.getNoneIndent()

  override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
    val nodeType = node.elementType
    if (nodeType is IFileElementType) return ChildAttributes(Indent.getNoneIndent(), null)
    if (ClojureTokens.LIST_ALIKE.contains(nodeType)) {
      if (children.asSequence().take(newChildIndex).find { ClojureTokens.PARENS.contains(it.node.elementType) } != null) {
        return ChildAttributes(Indent.getNormalIndent(true), childAlignment)
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
    if (psi !is CForm && (psi1 is CDef || psi1 is CForm && psi2 is CDef)) {
      return context.newLineTop
    }
    val newLine = context.newLine
    fun dependentSpacing(r: TextRange) =
        if (r.length >= context.SHORT_ENOUGH && psi2 is CForm) newLine
        else Spacing.createDependentLFSpacing(0, 1, r, context.common.KEEP_LINE_BREAKS, context.common.KEEP_BLANK_LINES_IN_CODE)
    fun dependentSpacing1() = dependentSpacing(TextRange.create(textRange.startOffset, block2.textRange.startOffset))
    fun dependentSpacing2() = dependentSpacing(TextRange.create(block1.textRange.startOffset,
        if (psi2 is CSForm) block1.textRange.endOffset else block2.textRange.endOffset))
    fun dependentSpacing3() = dependentSpacing(TextRange.create(block1.textRange.startOffset, block2.textRange.endOffset))
    if (psi is CSymbol) {
      if (psi1 is CMetadata) return dependentSpacing1()
    }
    if (block2.sequenceIndex > 0 && !ClojureTokens.PARENS.contains(child2.node.elementType)) {
      val spaceIfShort = if (block1.textRange.length <= context.SHORT_REALLY) context.spaceOnly else null
      when (psi) {
        is CMap -> {
          return if (block2.alignment == childAlignment && textRange.length > context.SHORT_ENOUGH) newLine
          else spaceIfShort
        }
        is CDef -> when {
          psi.def.type == "ns" -> if (block2.sequenceIndex > 1) return newLine
          psi.def.type.startsWith("def") -> return if (psi2 is CVec) null
          else if (block2.textRange.length < context.SHORT_ENOUGH) dependentSpacing3()
          else newLine
          else -> return newLine
        }
        is CVec -> {
          val parent = psi.parent.run { if (findChild(CForm::class) == psi) parent else this }
          if (parent is CDef) return null
          else (parent as? CList)?.first?.name.let {
            if (it != null && ClojureConstants.FN_ALIKE_SYMBOLS.contains(it)) return null
            else if (it != null && ClojureConstants.LET_ALIKE_SYMBOLS.contains(it) && parent == psi.parent) {
              return if (block2.sequenceIndex > 0 && block2.sequenceIndex % 2 == 0) newLine else null
            }
          }
        }
        is CList -> {
          val type = psi.findChild(CSForm::class)?.let { (it as? CSymbol)?.name ?: (it as? CKeyword)?.name }
          return when (type) {
            in ClojureConstants.NS_ALIKE_SYMBOLS ->
              if (block2.sequenceIndex <= 1) dependentSpacing1() else if (ClojureTokens.LIST_ALIKE.contains(block2.node.elementType)) newLine else null
            "if" -> if (block2.sequenceIndex > 1) dependentSpacing(textRange) else null
            "cond", "condp", "cond->", "cond->>", "assert-args", "case" ->
              if (block2.sequenceIndex <= 1) spaceIfShort else if (block1.alignment != childAlignment) newLine else null
            in ClojureConstants.LET_ALIKE_SYMBOLS ->
              if (block2.sequenceIndex == 1 && psi2 is CVec) context.spaceOnly else dependentSpacing2()
            else -> dependentSpacing2()
          }
        }
      }
      return if (block2.alignment == childAlignment) {
        if (block1.sequenceIndex > 0 && block1.alignment != childAlignment) newLine else dependentSpacing1()
      }
      else if (block1.alignment == childAlignment) spaceIfShort else null
    }
    return context.spacingBuilder.getSpacing(this, child1, child2)
  }

  private fun calcChildren(): List<ClojureFormattingBlock> {
    if (isLeaf) return emptyList()
    ProgressManager.checkCanceled()
    val nodeType = node.elementType
    var afterLeftParen = false
    var prevIndex = -1
    val nextIndex: ()->Int = if (!ClojureTokens.LIST_ALIKE.contains(nodeType)) {{ -1 }} else {{ if (afterLeftParen) ++prevIndex else -1 }}

    val psi = node.psi
    val minAlignIndex = if (nodeType != C_LIST || node.firstChildNode.elementType == C_READER_MACRO) 0
    else if (psi is CDef || psi.parents().skip(1).filter(CForm::class).isEmpty) Integer.MAX_VALUE
    else if ((psi as? CList)?.first?.name?.let {
      ClojureConstants.FN_ALIKE_SYMBOLS.contains(it) || ClojureConstants.LET_ALIKE_SYMBOLS.contains(it) } ?: false) Integer.MAX_VALUE
    else 1

    val metaAlign = if (node.firstChildNode?.elementType == C_METADATA) Alignment.createAlignment() else null
    val mapAlign = Alignment.createAlignment(true)
    val useMapAlign: (ASTNode, Int) -> Alignment? =
        if (psi is CMap || psi is CVec && (psi.parent as? CList)?.first?.name.elementOf(ClojureConstants.LET_ALIKE_SYMBOLS)) {
          { node, index -> if (index % 2 == 1) mapAlign else childAlignment }
        }
        else if (psi is CVec || psi is CList && psi !is CDef) {
          val type = (psi as? CList)?.first?.name
          when (type) {
            "cond", "condp", "assert-args" ->  // (cond cond1 action1 ..)
              { node, index -> if (index > 1 && index % 2 == 0) mapAlign else childAlignment }
            "cond->", "cond->>" ->  // (cond-> val cond1 action1 ..)
              { node, index -> if (index > 2 && index % 2 == 1) mapAlign else childAlignment }
            "case" ->  // (case x value1 action1 ..)
              { node, index -> if (index > 1 && index % 2 == 1) mapAlign else childAlignment }
            else ->
              psi.childForms.filter { it is CKeyword && (it.nextForm?.nextForm.let { it is CKeyword || it == null } || it.prevForm.prevForm is CKeyword) }.let {

                if (it.size() > 1) {
                  val set = it.map { it.nextForm!!.node }.toSet();
                  { node: ASTNode, index: Int -> if (set.contains(node)) mapAlign else childAlignment }
                }
                else {
                  { node, index -> childAlignment }
                }
              }
          }
        }
        else {
          { node, index -> childAlignment }
        }
    return node.iterate().transform(Function<ASTNode, ClojureFormattingBlock> f@ {
      val type = it.elementType
      if (type == TokenType.WHITE_SPACE && it.textLength == 1 && it.text == "," ||
          type == TokenType.ERROR_ELEMENT && it.textLength > 0 ||
          ClojureTokens.COMMENTS.contains(type)) {
        return@f ClojureFormattingBlock(it, context, wrap, if (afterLeftParen && prevIndex >= minAlignIndex) childAlignment else null, alignmentStrategy, prevIndex)
      }
      if (type == TokenType.WHITE_SPACE || type == TokenType.ERROR_ELEMENT) return@f null
      val index = if (afterLeftParen) nextIndex()
      else { if (ClojureTokens.PARENS.contains(type)) afterLeftParen = true; -1 }
      val align = if (index < minAlignIndex) metaAlign else useMapAlign(it, index)
      ClojureFormattingBlock(it, context, wrap, align, alignmentStrategy, index)
    }).notNulls().toList()
  }

}