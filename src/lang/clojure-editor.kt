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

package org.intellij.clojure.editor

import com.intellij.codeInsight.editorActions.SelectWordUtil
import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.codeInsight.editorActions.wordSelection.AbstractWordSelectioner
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.lang.ASTNode
import com.intellij.lang.Commenter
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.refactoring.NamesValidator
import com.intellij.lexer.StringLiteralLexer
import com.intellij.navigation.LocationPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns
import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameInputValidator
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.Tokenizer
import com.intellij.util.ProcessingContext
import com.intellij.util.containers.JBIterable
import com.intellij.util.containers.TreeTraversal
import com.intellij.util.ui.EmptyIcon
import com.intellij.xml.breadcrumbs.BreadcrumbsInfoProvider
import org.intellij.clojure.ClojureConstants.DEF_ALIKE_SYMBOLS
import org.intellij.clojure.ClojureConstants.FN_ALIKE_SYMBOLS
import org.intellij.clojure.ClojureConstants.LET_ALIKE_SYMBOLS
import org.intellij.clojure.formatter.ClojureCodeStyleSettings
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.lang.ClojureScriptLanguage
import org.intellij.clojure.lang.ClojureTokens
import org.intellij.clojure.parser.ClojureLexer
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.impl.listType
import org.intellij.clojure.util.*
import javax.swing.Icon

/**
 * @author gregsh
 */
class ClojureCommenter : Commenter {
  override fun getLineCommentPrefix() = codeStyleAwarePrefix()
  override fun getBlockCommentPrefix() = null
  override fun getBlockCommentSuffix() = null
  override fun getCommentedBlockCommentPrefix() = null
  override fun getCommentedBlockCommentSuffix() = null

  private fun codeStyleAwarePrefix(): String {
    val project = CommandProcessor.getInstance().currentCommandProject ?: return ";"
    val settings = CodeStyleSettingsManager.getSettings(project).getCustomSettings(ClojureCodeStyleSettings::class.java)
    return if (settings.USE_2SEMI_COMMENT) ";;" else ";"
  }
}

class ClojureQuoteHandler : SimpleTokenSetQuoteHandler(ClojureTokens.STRINGS) {
  override fun hasNonClosedLiteral(editor: Editor?, iterator: HighlighterIterator?, offset: Int) = true
}

class ClojureWordSelectioner : AbstractWordSelectioner() {
  override fun canSelect(e: PsiElement?): Boolean {
    return ClojureTokens.STRINGS.contains(e.elementType)
  }

  override fun select(e: PsiElement, editorText: CharSequence?, cursorOffset: Int, editor: Editor?): MutableList<TextRange> {
    val result = super.select(e, editorText, cursorOffset, editor)
    if (ClojureTokens.STRINGS.contains(e.elementType)) {
      val range = e.textRange
      SelectWordUtil.addWordHonoringEscapeSequences(
          editorText, range, cursorOffset, StringLiteralLexer('\"', e.elementType), result)
      result.add(TextRange(range.startOffset + 1, range.endOffset - 1))
    }
    return result
  }
}

class ClojureSpellCheckingStrategy : SpellcheckingStrategy() {
  override fun getTokenizer(element: PsiElement): Tokenizer<PsiElement> {
    return when (element.elementType) {
      ClojureTypes.C_STRING, ClojureTypes.C_REGEXP -> super.getTokenizer(element)
      ClojureTypes.C_SYMBOL -> EMPTY_TOKENIZER
      else -> EMPTY_TOKENIZER
    }
  }
}

class ClojureNamesValidator : NamesValidator, RenameInputValidator {
  override fun isKeyword(p0: String, p1: Project?) = false
  override fun isInputValid(p0: String, p1: PsiElement, p2: ProcessingContext) = isIdentifier(p0, null)
  override fun getPattern(): ElementPattern<out PsiElement> = PlatformPatterns.psiElement().with(object : PatternCondition<PsiElement>("") {
    override fun accepts(p0: PsiElement, p1: ProcessingContext?): Boolean {
      return (p0 as? PomTargetPsiElement)?.navigationElement is CForm
    }
  })

  override fun isIdentifier(p0: String, p1: Project?) = with(ClojureLexer(ClojureLanguage)) {
    start(p0)
    tokenType == ClojureTypes.C_SYM && tokenEnd == p0.length
  }
}

val SHORT_TEXT_MAX = 10
val LONG_TEXT_MAX = 30

class ClojureBreadCrumbProvider : BreadcrumbsInfoProvider() {
  companion object {
    val LANGUAGES = arrayOf(ClojureLanguage, ClojureScriptLanguage)
  }

  override fun getLanguages() = LANGUAGES

  override fun acceptElement(o: PsiElement) = when (o) {
    !is CPForm -> false
    is CDef -> true
    is CVec -> !o.isBindingVec()
    is CList -> true
    else -> false
  }

  override fun getElementInfo(o: PsiElement): String = when (o) {
    is CDef -> o.def.run { "($type $name)" }
    is CList -> o.firstForm?.let {
      val first = (it as? CSymbol)?.name
      val next = it.nextForm
      when {
        o.textRange.length <= SHORT_TEXT_MAX -> getFormPlaceholderText(o)
        first == null -> "(${getFormPlaceholderText(it)})"
        next is CVec -> if (first.isIn(LET_ALIKE_SYMBOLS) || first.isIn(FN_ALIKE_SYMBOLS))
          "($first ${getFormPlaceholderText(next)})" else "($first …)"
        next is CList -> "($first ${getFormPlaceholderText(next)})"
        next != null -> "($first …)"
        else -> "($first)"
      }
    } ?: "(…)"
    is CForm -> getFormPlaceholderText(o)
    else -> "???"
  }

  override fun getElementTooltip(o: PsiElement) = null
}

class ClojureStructureViewFactory : PsiStructureViewFactory {
  override fun getStructureViewBuilder(psiFile: PsiFile) = object : TreeBasedStructureViewBuilder() {
    override fun createStructureViewModel(editor: Editor?) = MyModel(psiFile)
    override fun isRootNodeShown() = false
  }

  private class MyModel constructor(o: PsiFile) : StructureViewModelBase(o, MyElement(o)), StructureViewModel.ElementInfoProvider {
    init {
      withSuitableClasses(CForm::class.java)
    }

    override fun isAlwaysShowsPlus(o: StructureViewTreeElement) = false
    override fun isAlwaysLeaf(o: StructureViewTreeElement) = o.value !is PsiFile
    override fun shouldEnterElement(o: Any?) = false
    override fun isSuitable(o: PsiElement?) = o is PsiFile || o is CForm
  }

  private class MyElement(o: PsiElement) : PsiTreeElementBase<PsiElement>(o), SortableTreeElement, LocationPresentation {

    override fun getAlphaSortKey() = presentableText

    override fun getChildrenBase(): Collection<MyElement> = element.let { o ->
      when {
        o is CFile -> o.cljTopLevelTraverser().traverse().filter(CForm::class)
        o is CForm && o.parent !is CForm -> o.cljTraverser().traverse().filter(CDef::class)
        else -> JBIterable.empty()
      }.transform(::MyElement).toList()
    }

    override fun getPresentableText() = (element as? CForm)?.let { (it as? CDef)?.def?.name ?: getFormPlaceholderText(it, LONG_TEXT_MAX) } ?: ""
    override fun getLocationString() = (element as? CDef)?.def?.type ?: ""
    override fun getLocationPrefix() = " "
    override fun getLocationSuffix() = ""

    override fun getIcon(open: Boolean): Icon = (element as? NavigationItem)?.presentation?.getIcon(open) ?: EmptyIcon.ICON_16
  }
}

class ClojureFoldingBuilder : FoldingBuilderEx() {
  override fun getPlaceholderText(node: ASTNode): String = getFormPlaceholderText(node.psi as CForm, LONG_TEXT_MAX)

  override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> = root.cljTraverser()
      .traverse()
      .filter(CPForm::class)
      .filter {
        it.textRange.let { r -> document.getLineNumber(r.endOffset) - document.getLineNumber(r.startOffset) > 1 }
      }
      .transform { FoldingDescriptor(it, it.textRange) }.toList().toTypedArray()

  override fun isCollapsedByDefault(node: ASTNode) = node.psi.let { it is CDef && it.def.type == "ns" }
}

private fun getFormPlaceholderText(o: CForm, max: Int = SHORT_TEXT_MAX) = when (o) {
  is CSForm -> o.text
  is CDef -> o.def.run { "($type ${o.nameSymbol?.qualifiedName ?: name})" }
  is CPForm -> StringBuilder().run {
    val iterator = o.cljTraverser()
        .expand { it !is CMetadata }
        .traverse(TreeTraversal.LEAVES_DFS)
        .typedIterator<TreeTraversal.TracingIt<PsiElement>>()
    var wsOrComment = false
    loop@ for (part in iterator) {
      wsOrComment = if (part is PsiWhiteSpace || part is PsiComment) true
      else { if (wsOrComment) append(" "); false }
      when {
        part is PsiWhiteSpace || part is PsiComment -> Unit
        part is CMetadata -> append("^")
        length < max -> {
          part.text.let { text ->
            if (max - length + 1 >= text.length) append(text)
            else append(text, 0, max - length).append("…")
          }
        }
        else -> {
          var first = true
          iterator.backtrace()
              .skip(1)
              .transform { PsiTreeUtil.getDeepestLast(it) }
              .filter { ClojureTokens.PAREN2_ALIKE.contains(it.elementType) }
              .unique().forEach {
            if (first && it !== part) { first = false; if (!endsWith("… ") && !endsWith("…")) append("…") }
            append(it.text)
          }
          break@loop
        }
      }
    }
    toString()
  }
  else -> o.javaClass.simpleName ?: "???"
}

private fun CVec.isBindingVec(): Boolean = (parent as? CList)?.let {
  listType(it).run {
    (isIn(LET_ALIKE_SYMBOLS) || isIn(DEF_ALIKE_SYMBOLS)) } &&
      it.iterate(CVec::class).first() == this
} ?: false