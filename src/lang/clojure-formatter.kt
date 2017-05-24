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
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.CustomCodeStyleSettings
import com.intellij.psi.formatter.common.AbstractBlock
import com.intellij.psi.tree.IFileElementType
import com.intellij.util.SmartList
import com.intellij.util.containers.SmartHashSet
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.lang.ClojureTokens
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.ClojureTypes.*
import org.intellij.clojure.psi.impl.resolveXTarget
import org.intellij.clojure.psi.stubs.CPrototypeStub
import org.intellij.clojure.util.*

/**
 * @author gregsh
 */
class ClojureCodeStyleSettings (container: CodeStyleSettings?) : CustomCodeStyleSettings("ClojureCodeStyleSettings", container) {
  @JvmField var USE_2SEMI_COMMENT: Boolean = false
}

fun CodeStyleSettings.getClojureSettings() = getCustomSettings(ClojureCodeStyleSettings::class.java)!!

class ClojureFormattingModelBuilder : FormattingModelBuilder {
  override fun getRangeAffectingIndent(file: PsiFile?, offset: Int, elementAtOffset: ASTNode?) = null

  override fun createModel(element: PsiElement, settings: CodeStyleSettings): FormattingModel {
    val common = settings.getCommonSettings(ClojureLanguage)
    val custom = settings.getClojureSettings()
    val spacingBuilder = createSpacingBuilder(common, custom)
    val props = Context(common, custom, spacingBuilder)
    val block = ClojureFormattingBlock(element.node, props, null, null, null, -1)
    return FormattingModelProvider.createFormattingModelForPsiFile(element.containingFile, block, settings)
  }

  fun createSpacingBuilder(common: CommonCodeStyleSettings, custom: ClojureCodeStyleSettings): SpacingBuilder {
    return SpacingBuilder(common.rootSettings, ClojureLanguage)
        .between(C_PAREN1, C_PAREN2).lineBreakOrForceSpace(false, false)
        .between(C_BRACE1, C_BRACE2).lineBreakOrForceSpace(false, false)
        .between(C_BRACKET1, C_BRACKET2).lineBreakOrForceSpace(false, false)
        .after(C_HAT).lineBreakOrForceSpace(false, false)
        .after(ClojureTokens.SHARPS).lineBreakOrForceSpace(false, false)
        .after(ClojureTokens.PAREN1_ALIKE).lineBreakOrForceSpace(false, false)
        .before(ClojureTokens.PAREN2_ALIKE).lineBreakOrForceSpace(false, false)
  }
}


data class Context(val common: CommonCodeStyleSettings,
                   val custom: ClojureCodeStyleSettings,
                   val spacingBuilder: SpacingBuilder) {
  val SHORT_ENOUGH = common.rootSettings.getRightMargin(ClojureLanguage) * 2 / 3
  val SHORT_REALLY = common.rootSettings.getRightMargin(ClojureLanguage) / 4

  val newLineTop = Spacing.createSpacing(1, 1, 2, common.KEEP_LINE_BREAKS, common.KEEP_BLANK_LINES_IN_DECLARATIONS)!!
  val newLine = Spacing.createSpacing(1, 1, 1, common.KEEP_LINE_BREAKS, common.KEEP_BLANK_LINES_IN_CODE)!!
  val noSpaces = Spacing.createSpacing(0, 0, 0, common.KEEP_LINE_BREAKS, common.KEEP_BLANK_LINES_IN_CODE)!!
  val spaceOnly = Spacing.createSpacing(1, 0, 0, false, 0)!!
  val spaceOrKeepNL = Spacing.createSpacing(1, 0, 0, common.KEEP_LINE_BREAKS, common.KEEP_BLANK_LINES_IN_CODE)!!
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
      if (children.asSequence().take(newChildIndex).find { ClojureTokens.PAREN_ALIKE.contains(it.node.elementType) } != null) {
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
    if (psi is CFile && (psi1 is CForm || psi2 is CForm)) {
      if (psi1 !is CForm) return context.newLine
      if (psi1 is CList && psi2 is CList && psi1.firstForm?.name == psi2.firstForm?.name) return context.newLine
      if (psi2 is PsiWhiteSpace && psi2.textMatches(",")) return null
      return context.newLineTop
    }
    if (psi2 is PsiWhiteSpace && psi2.textMatches(",")) return context.noSpaces
    val newLine = context.newLine
    fun dependentSpacing(r: TextRange) =
        if (r.length >= context.SHORT_ENOUGH && psi2 is CForm) newLine
        else Spacing.createDependentLFSpacing(1, 1, r, context.common.KEEP_LINE_BREAKS, context.common.KEEP_BLANK_LINES_IN_CODE)
    fun dependentSpacing1() = dependentSpacing(TextRange.create(textRange.startOffset, block2.textRange.startOffset))
    fun dependentSpacing2() = dependentSpacing(TextRange.create(block1.textRange.startOffset,
        if (psi2 is CSForm) block1.textRange.endOffset else block2.textRange.endOffset))
    fun dependentSpacing3() = dependentSpacing(TextRange.create(block1.textRange.startOffset, block2.textRange.endOffset))
    if (psi is CSymbol) {
      if (psi1 is CMetadata) return dependentSpacing1()
    }
    if (block2.sequenceIndex > 0 && !ClojureTokens.PAREN_ALIKE.contains(child2.node.elementType)) {
      val spaceIfShort = if (block1.textRange.length <= context.SHORT_REALLY) context.spaceOrKeepNL else null
      val psiDef = (psi as? CList)?.def
      when {
        psi is CMap -> {
          return if (block2.alignment == childAlignment && textRange.length > context.SHORT_ENOUGH) newLine
          else spaceIfShort
        }
        psi.role == Role.NS -> when {
          psiDef?.type == "ns" -> if (block2.sequenceIndex > 1) return newLine else context.spaceOrKeepNL
          else -> return newLine
        }
        psi.role == Role.DEF -> {
          return when {
            psi2.role == Role.ARG_VEC -> null
            psi1.role == Role.ARG_VEC -> dependentSpacing(textRange)
            block2.textRange.length < context.SHORT_ENOUGH -> dependentSpacing3()
            else -> newLine
          }
        }
        psi is CVec -> {
          if (psi.role == Role.BND_VEC) {
            return if (block2.sequenceIndex > 0 && block2.sequenceIndex % 2 == 0) newLine else null
          }
          else if (block2.alignment == childAlignment && textRange.length > context.SHORT_ENOUGH) return newLine
        }
        psi is CList -> {
          val listName = psi.firstForm?.name
          return when (listName) {
            in ClojureConstants.NS_ALIKE_SYMBOLS ->
              if (block2.sequenceIndex <= 1) dependentSpacing1() else if (ClojureTokens.LIST_ALIKE.contains(block2.node.elementType)) newLine else null
            "defmethod" ->
              if (psi1.role == Role.NAME && psi2 is CForm) context.spaceOnly
              else if (psi2.role == Role.ARG_VEC) dependentSpacing(textRange)
              else null
            "if" -> if (block2.sequenceIndex > 1) dependentSpacing(textRange) else null
            "cond", "assert-args", "condp", "cond->", "cond->>", "case" ->
              if (block1.alignment != null && block1.alignment != childAlignment) newLine
              else if (block2.sequenceIndex <= 1) spaceIfShort
              else null
            in ClojureConstants.LET_ALIKE_SYMBOLS ->
              if (block2.sequenceIndex == 1 && psi2 is CVec) context.spaceOnly else dependentSpacing2()
            "reify", "extend-type", "extend", "deftype" ->
              if (psi2 is CSymbol && psi1 is CPForm) context.newLineTop else newLine
            "extend-protocol" ->
              if (block2.sequenceIndex == 1 && psi2 is CSymbol) return context.spaceOnly
              else if (psi2 is CSymbol && psi1 is CPForm) context.newLineTop else newLine
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
    val listName = (psi as? CList)?.first?.name
    val hasBody = (psi as? CList)?.let {
      val target = it.first.resolveXTarget() ?: return@let false
      val name = target.key.name
      if (target.key.namespace == LangKind.CLJ.ns || target.key.namespace == LangKind.CLJS.ns) {
        ClojureConstants.SPECIAL_FORMS.contains(name) ||
            ClojureConstants.CLJS_SPECIAL_FORMS.contains(name) ||
            ClojureConstants.LET_ALIKE_SYMBOLS.contains(name) ||
            ClojureConstants.DEF_ALIKE_SYMBOLS.contains(name) ||
            name.startsWith("with-") || name.startsWith("if-") ||
            name == "extend-protocol" || name == "extend-type" || name == "extend" ||
            name == "deftype" || name == "defmethod" ||
            target.resolveStub()?.childrenStubs.jbIt().filter(CPrototypeStub::class).find {
              val last = it.args.lastOrNull()
              last == "body" || last == "clauses" || last == "specs"
            } != null
      }
      else false
    } ?: false

    val minAlignIndex = when {
      nodeType != C_LIST || node.firstChildNode.elementType == C_READER_MACRO -> 0
      psi.role == Role.DEF || hasBody -> Integer.MAX_VALUE
      psi.role == Role.PROTOTYPE -> 0
      ClojureConstants.FN_ALIKE_SYMBOLS.contains(listName) || ClojureConstants.LET_ALIKE_SYMBOLS.contains(listName) -> Integer.MAX_VALUE
      else -> {
        if ((psi as? CList)?.first.let { isNLBetween(it, it.nextForm) }) 0 else 1
      }
    }

    val metaAlign = if (node.firstChildNode?.elementType == C_METADATA) Alignment.createAlignment() else null
    val mapAlign = Alignment.createAlignment(true)
    val useMapAlign: (ASTNode, Int) -> Boolean =
        when {
          psi is CMap || psi is CVec && psi.role == Role.BND_VEC ->
            { node, index -> index % 2 == 1 && node is CForm && !isNLBetween(node.prevForm, node) }
          psi is CVec || psi is CList && psi.role != Role.DEF -> when (listName) {
            "cond", "assert-args" ->  // (cond cond1 action1 ..)
              { node, index -> index > 1 && index % 2 == 0 && node is CForm && !isNLBetween(node.prevForm, node) }
            "cond->", "cond->>", "case" ->  // (cond-> val cond1 action1 ..)
              { node, index -> index > 2 && index % 2 == 1 && node is CForm && !isNLBetween(node.prevForm, node) }
            "condp" ->
              { node, index -> index > 3 && index % 2 == 0 && node is CForm && !isNLBetween(node.prevForm, node) }
            else -> {
              val set = psi.childForms.filter {
                it is CKeyword && (it.nextForm?.nextForm.let { it is CKeyword || it == null } || it.prevForm.prevForm is CKeyword)
              }.map { it.nextForm?.node }.notNulls().addAllTo(SmartHashSet())
              if (set.size > 1) {
                { node: ASTNode, _: Int -> set.contains(node) }
              }
              else {
                { _, _ -> false }
              }
            }
          }
          else ->
            { _, _ -> false }
        }
    val result = node.iterate().map {
      val type = it.elementType
      if (type == TokenType.WHITE_SPACE && it.textLength == 1 && it.text == "," ||
          type == TokenType.ERROR_ELEMENT && it.textLength > 0 ||
          ClojureTokens.COMMENTS.contains(type)) {
        return@map ClojureFormattingBlock(
            it, context, wrap,
            if (afterLeftParen && prevIndex >= minAlignIndex) childAlignment else null,
            alignmentStrategy, prevIndex)
      }
      if (type == TokenType.WHITE_SPACE || type == TokenType.ERROR_ELEMENT) return@map null
      val index = if (afterLeftParen) nextIndex()
      else { if (ClojureTokens.PAREN_ALIKE.contains(type)) afterLeftParen = true; -1 }
      val align = if (useMapAlign(it, index)) mapAlign else if (index < minAlignIndex) metaAlign else childAlignment
      ClojureFormattingBlock(it, context, wrap, align, alignmentStrategy, index)
    }.notNulls().addAllTo(SmartList())
    return result
  }

}

private fun isNLBetween(e1: PsiElement?, e2: PsiElement?) =
    e1 != null && e2 != null &&
    StringUtil.indexOf(e1.containingFile.text, '\n', e1.textRange.startOffset, e2.textRange.startOffset) != -1