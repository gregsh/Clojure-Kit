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

package org.intellij.clojure.lang.usages

import com.intellij.ide.util.treeView.AbstractTreeStructure
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.slicer.*
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.FontUtil
import com.intellij.util.Processor
import com.intellij.util.containers.JBIterable
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.impl.*
import org.intellij.clojure.util.*
import java.util.*

/**
 * @author gregsh
 */
class ClojureSliceSupportProvider : SliceLanguageSupportProvider {
  override fun createRootUsage(element: PsiElement, params: SliceAnalysisParams) = ClojureSliceUsage(element, params)

  override fun getExpressionAtCaret(atCaret: PsiElement, dataFlowToThis: Boolean) =
      (atCaret.parentForm as? CSForm)?.let {
        when (it) {
          is CSymbol -> it.let { sym -> (sym.reference.resolve() as? PomTargetPsiElement)?.let {
            if ((it.target as? CTarget)?.key?.type?.let { it == "let-binding" || it == "argument"} ?: false) it
            else null
          } }
          else -> if (dataFlowToThis) null else it
        }}

  override fun getElementForDescription(element: PsiElement) =
      (element as? CSymbol)?.reference?.resolve() ?: element

  override fun getRenderer() = object : SliceUsageCellRendererBase() {
    override fun customizeCellRendererFor(usage: SliceUsage) {
      for ((index, chunk) in usage.presentation.text.withIndex()) {
        append(chunk.text, chunk.simpleAttributesIgnoreBackground)
        if (index == 0) append(FontUtil.spaceAndThinSpace())
      }
      append(FontUtil.spaceAndThinSpace())
      append("in ", SimpleTextAttributes.GRAY_ATTRIBUTES)
      val def = (usage.element.parents().filter(CForm::class).last() as? CDef)?.def
      if (def != null) {
        append(def.type, SimpleTextAttributes.GRAY_ATTRIBUTES)
        append(" " + def.name + " ")
      }
      append("(${usage.file.name})", SimpleTextAttributes.GRAY_ATTRIBUTES)
    }
  }

  override fun startAnalyzeLeafValues(structure: AbstractTreeStructure, finalRunnable: Runnable) = finalRunnable.run()

  override fun startAnalyzeNullness(structure: AbstractTreeStructure, finalRunnable: Runnable) = finalRunnable.run()

  override fun registerExtraPanelActions(group: DefaultActionGroup, builder: SliceTreeBuilder) = Unit

}

class ClojureSliceUsage : SliceUsage {
  constructor(element: PsiElement, parent: SliceUsage) : super(element, parent)
  constructor(element: PsiElement, params: SliceAnalysisParams) : super(element, params)

  override fun copy() =
      if (parent == null) ClojureSliceUsage(usageInfo.element!!, params)
      else ClojureSliceUsage(usageInfo.element!!, parent)

  override fun processUsagesFlownFromThe(element: PsiElement, uniqueProcessor: Processor<SliceUsage>) {
    if (element is CSymbol && element.reference.resolve()?.navigationElement == element) {
      ReferencesSearch.search(element.reference.resolve() as PsiElement, params.scope.toSearchScope()).forEach {
        if (element != it.element) {
          uniqueProcessor.process(ClojureSliceUsage(it.element, this))
        }
      }
    }
    else {
      val list = element.parents().filter(CList::class).filter { it.iterate(CReaderMacro::class).isEmpty }.first() ?: return
      val type = listType(list) ?: return
      when {
        type == "let" && findBindingsVec(list, "let").isAncestorOf(element) -> {
          val bindings = findBindingsVec(list, "let")
          destruct(bindings.iterateForms().takeWhile { !it.isAncestorOf(element) }.last()).filter(CSymbol::class).forEach {
            uniqueProcessor.process(ClojureSliceUsage(it, this))
          }
        }
        else -> {
          val def = list.first?.reference?.resolve()?.navigationElement as? CDef ?: return
          val argIndex = list.iterateForms().indexOf(element) - 1
          if (argIndex < 0) return
          val argCount = list.iterateForms().size() - 1
          for (prototype in prototypes(def)) {
            val vec = prototype.iterate(CVec::class).first() ?: continue
            val partition = vec.iterateForms().partition(JBIterable.SeparatorOption.SKIP, { (it is CSymbol) && it.text == "&" })
            val sizes = partition.transform { it.size() }.toList()
            val arg = when {
              sizes.size == 1 && sizes[0] == argCount -> partition[0]!![argIndex]
              sizes.size == 2 && sizes[0] <= argCount -> if (sizes[0] > argIndex) partition[0]!![argIndex] else partition[1]!!.get(0)
              else -> null
            }
            if (arg is CSymbol) {
              uniqueProcessor.process(ClojureSliceUsage(arg, this))
            }
          }
        }
      }
    }
  }

  override fun processUsagesFlownDownTo(element0: PsiElement, uniqueProcessor: Processor<SliceUsage>) {
    val element = (element0 as? CSymbol)?.reference?.resolve()?.navigationElement as? CSymbol ?: return
    if (element != element0) {
      uniqueProcessor.process(ClojureSliceUsage(element, this))
      return
    }
    val list = element.parents().filter(CList::class).filter { it.iterate(CReaderMacro::class).isEmpty }.transform {
      if (it.parent is CDef && it.iterateForms().first() is CVec) it.parent as CDef else it
    }.first() ?: return
    val type = listType(list) ?: return
    when {
      type == "let" && findBindingsVec(list, "let").isAncestorOf(element) -> {
        val bindings = findBindingsVec(list, "let")
        bindings.iterateForms().find { it.isAncestorOf(element) }.nextForm?.let {
          uniqueProcessor.process(ClojureSliceUsage(it, this))
        }
      }
      else -> {
        val def = list as? CDef ?: return
        val vec = element.parents().takeWhile { it != def }.filter(CVec::class).last() ?: return
        val partition = vec.iterateForms().partition(JBIterable.SeparatorOption.SKIP, { (it is CSymbol) && it.text == "&" })
        val argIndex = partition.flatten { it }.indexOf { it.isAncestorOf(element) }
        if (argIndex < 0) return
        val sizes = partition.transform { it.size() }.toList()
        val otherSizes = BitSet()
        for (prototype in prototypes(def).filter { it != vec.parent }) {
          val vec1 = prototype.iterate(CVec::class).first() ?: continue
          val partition1 = vec1.iterateForms().partition(JBIterable.SeparatorOption.SKIP, { (it is CSymbol) && it.text == "&" })
          otherSizes.set(partition1[0]?.size() ?: continue)
        }
        ReferencesSearch.search(def, params.scope.toSearchScope()).forEach { usage ->
          usage.element.parents().filter(CList::class).first()?.let { list ->
            if (list.first.let { it == usage.element || it?.name == "." && it == usage.element.parent }) {
              val params = list.iterateForms().skip(1)
              val argCount = params.size()
              val arg: Any? = when {
                sizes.size == 1 && sizes[0] == argCount -> params[argIndex]
                sizes.size == 2 && sizes[0] <= argCount && !otherSizes[argCount] -> if (sizes[0] > argIndex) params[argIndex] else params.skip(sizes[0])
                else -> null
              }
              when (arg) {
                is CForm -> uniqueProcessor.process(ClojureSliceUsage(arg, this))
                is JBIterable<*> -> arg.forEach { if (it is CForm) uniqueProcessor.process(ClojureSliceUsage(it, this)) }
                else -> Unit
              }
            }
          }
        }
      }
    }
  }
}