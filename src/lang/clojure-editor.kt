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

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
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
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.LocationPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.project.Project
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns
import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.RenameInputValidator
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.Tokenizer
import com.intellij.util.ProcessingContext
import com.intellij.xml.breadcrumbs.BreadcrumbsInfoProvider
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.lang.ClojureScriptLanguage
import org.intellij.clojure.parser.ClojureLexer
import org.intellij.clojure.parser.ClojureTokens
import org.intellij.clojure.psi.*
import org.intellij.clojure.util.cljTopLevelTraverser
import org.intellij.clojure.util.cljTraverser
import org.intellij.clojure.util.elementOf
import org.intellij.clojure.util.elementType
import javax.swing.Icon

/**
 * @author gregsh
 */
class ClojureCommenter : Commenter {
  override fun getLineCommentPrefix() = ";"
  override fun getBlockCommentPrefix() = null
  override fun getBlockCommentSuffix() = null
  override fun getCommentedBlockCommentPrefix() = blockCommentPrefix
  override fun getCommentedBlockCommentSuffix() = blockCommentSuffix
}

class ClojureQuoteHandler : SimpleTokenSetQuoteHandler(ClojureTokens.STRINGS) {
  override fun hasNonClosedLiteral(editor: Editor?, iterator: HighlighterIterator?, offset: Int) = true
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

private fun getFormDisplayText(o: CList) = (o as? ItemPresentation)?.presentableText ?: ""


class ClojureBreadCrumbProvider : BreadcrumbsInfoProvider() {
  companion object {
    val LANGUAGES = arrayOf(ClojureLanguage, ClojureScriptLanguage)
  }

  override fun getLanguages() = LANGUAGES

  override fun acceptElement(o: PsiElement): Boolean {
    return o is CDef || o is CMap || o is CSet || o is CFun ||
        o is CList && (o.firstChild is CReaderMacro ||
            o.parent.elementType !is ClojureElementType ||
            o.first?.name?.elementOf(ClojureConstants.CONTROL_SYMBOLS) ?: false)
  }

  override fun getElementInfo(o: PsiElement): String {
    when (o) {
      is CMap -> return "{<map>}"
      is CSet -> return "{<set>}"
      is CFun -> return "#(<fn>)"
      is CList -> return getFormDisplayText(o)
    }
    return ""
  }

  override fun getElementTooltip(o: PsiElement) = null

}

class ClojureStructureViewFactory : PsiStructureViewFactory {
  override fun getStructureViewBuilder(psiFile: PsiFile) =
      object : TreeBasedStructureViewBuilder() {
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

    override fun getChildrenBase(): Collection<StructureViewTreeElement> {
      val o = element
      val traverser = when {
        o is ClojureFile -> o.cljTopLevelTraverser()
        o is CForm && o.parent !is CForm -> o.cljTraverser()
        else -> return emptyList()
      }
      return traverser.traverse().filter { it is CDef }.transform(::MyElement).toList()
    }

    override fun getPresentableText() = (element as? CDef)?.def?.name ?: ""
    override fun getLocationString() = (element as? CDef)?.def?.type ?: ""
    override fun getLocationPrefix() = " "
    override fun getLocationSuffix() = ""

    override fun getIcon(open: Boolean): Icon? {
      return (element as? NavigationItem)?.presentation?.getIcon(open) ?: null
    }
  }
}

class ClojureFoldingBuilder : FoldingBuilderEx() {
  override fun getPlaceholderText(node: ASTNode) =
      getFormDisplayText(node.psi as CList)

  override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> =
      root.cljTraverser().expandAndSkip { it == root }
          .filter { it is CList }
          .filter {
            val r = it.textRange
            document.getLineNumber(r.endOffset) - document.getLineNumber(r.startOffset) > 1
          }
          .traverse().transform { FoldingDescriptor(it, it.textRange) }.toList().toTypedArray()

  override fun isCollapsedByDefault(node: ASTNode) = false
}