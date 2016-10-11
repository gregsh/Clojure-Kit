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

import com.intellij.codeHighlighting.Pass
import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.icons.AllIcons
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.documentation.DocumentationProviderEx
import com.intellij.lang.parameterInfo.*
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.*
import com.intellij.refactoring.rename.inplace.MemberInplaceRenamer
import com.intellij.refactoring.rename.inplace.VariableInplaceRenameHandler
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.inspections.RESOLVE_SKIPPED
import org.intellij.clojure.lang.ClojureColors
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.impl.CTarget
import org.intellij.clojure.psi.impl.prototypes
import org.intellij.clojure.util.*

/**
 * @author gregsh
 */
class ClojureAnnotator : Annotator {

  override fun annotate(element: PsiElement, holder: AnnotationHolder) {
    when (element) {
      is CKeyword -> {
        holder.createInfoAnnotation(element.lastChild.textRange, null).textAttributes = ClojureColors.KEYWORD
      }
      is CSymbol -> {
        val resolve = element.reference.resolve()
        if (element.getUserData(RESOLVE_SKIPPED) != null) {
          if (element.name.let { it != "&" && it != "." }) {
            holder.createInfoAnnotation(element, null).textAttributes = ClojureColors.DYNAMIC
          }
          return
        }
        val target = (resolve as? PomTargetPsiElement)?.target as? CTarget ?: return
        if (target.key.type != "ns") {
          val nsAttrs = ClojureColors.NS_COLORS[target.key.namespace]
          if (nsAttrs != null) {
            holder.createInfoAnnotation(element.valueRange, null).enforcedTextAttributes = nsAttrs
          }
        }
        val attrs = when (target.key.type) {
          "argument" -> ClojureColors.FN_ARGUMENT
          "let-binding" -> ClojureColors.LET_BINDING
          "ns" -> ClojureColors.NAMESPACE
          else -> null
        }
        if (attrs != null) {
          holder.createInfoAnnotation(element.valueRange, null).textAttributes = attrs
        }
      }
      is CMetadata -> {
        holder.createInfoAnnotation(element, null).textAttributes = ClojureColors.METADATA
      }
//      is CReaderMacro -> {
//        val targetForm = element.parent as? CForm
//        holder.createInfoAnnotation(targetForm, null).textAttributes = ClojureColors.READER_MACRO
//      }
    }
  }
}

class ClojureCompletionContributor : CompletionContributor() {
  override fun beforeCompletion(context: CompletionInitializationContext) {
    if (context.file.findElementAt(context.startOffset).findParent(CSymbol::class) != null) {
      context.dummyIdentifier = ""
    }
  }
}

class ClojureDocumentationProvider : DocumentationProviderEx() {
  override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
    return super.getQuickNavigateInfo(element, originalElement)
  }

  override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
    val def = ((element as? PomTargetPsiElement)?.navigationElement ?: element)
        as? CNamed ?: return null
    val nameSymbol = def.nameSymbol ?: return null
    val sb = StringBuilder("<html>")
    sb.append("<b>(${def.def.type}</b> ${def.def.namespace}/${def.def.name}<b>${if (def is CLForm) " …" else ""})</b>").append("<br>")
    val docString = if (def.def.type == ClojureConstants.TYPE_PROTOCOL_METHOD) nameSymbol.findNext(CLiteral::class)
    else nameSymbol.findNext(CForm::class) as? CLiteral
    docString?.let {
      if (it.literalType == ClojureTypes.C_STRING) {
        sb.append("<br>").append(StringUtil.unquoteString(it.literalText)).append("<br>")
      }
    }
    fun appendMap(m: CForm?) {
      val forms = (m as? CMap)?.forms ?: return
      for ((i, form) in forms.withIndex()) {
        if (i % 2 == 0) sb.append("<br>").append("<b>").append(form.text).append("</b>   ")
        else sb.append(StringUtil.unquoteString(form.text))
      }
    }
    appendMap(docString.findNext(CForm::class))
    for (m in nameSymbol.metas) {
      appendMap(m.form)
    }
    return sb
        .replace("\n(\\s*\n)+".toRegex(), "<p>")
        .replace("(\\.\\s)".toRegex(), ".<p>")
        .replace("(:\\s)".toRegex(), "$1<br>")
  }
}

class ClojureTargetElementEvaluator : TargetElementEvaluatorEx2() {
  override fun includeSelfInGotoImplementation(element: PsiElement) = false
  override fun getNamedElement(element: PsiElement) = (element.parent as? CDef)?.run { if (nameSymbol == element) this else null }
  override fun getGotoDeclarationTarget(element: PsiElement, navElement: PsiElement?) = (navElement as? CDef)?.nameSymbol ?: navElement
  override fun getElementByReference(ref: PsiReference, flags: Int) = ref.resolve()
}

class ClojureLineMarkerProvider : LineMarkerProviderDescriptor() {
  companion object {
    val P1 = Option("clojure.defprotocol", "Protocol method", AllIcons.Gutter.ImplementedMethod)
    val P2 = Option("clojure.extend-protocol", "Protocol method implementation", AllIcons.Gutter.ImplementingMethod)
    val MM1 = Option("clojure.defmulti", "Multi-method", AllIcons.Gutter.ImplementedMethod)
    val MM2 = Option("clojure.defmethod", "Multi-method implementation", AllIcons.Gutter.ImplementingMethod)
  }

  override fun getName() = "Clojure line markers"
  override fun getOptions() = arrayOf(P1, P2, MM1, MM2)

  override fun collectSlowLineMarkers(elements: MutableList<PsiElement>, result: MutableCollection<LineMarkerInfo<PsiElement>>) {
  }

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    // todo extend, extend-type, extend-protocol
    val def = element.parent as? CDef ?: return null
    val nameSym = def.run { if (nameSymbol == element) element else null } ?: return null
    if (def.def.type == ClojureConstants.TYPE_PROTOCOL_METHOD) {
      return LineMarkerInfo(nameSym, nameSym.textRange, P1.icon!!, Pass.UPDATE_ALL,
          { "Protocol method implementations" },
          { mouseEvent, t -> }, GutterIconRenderer.Alignment.LEFT)
    }
    else if (def.def.type == "defmulti") {
      return LineMarkerInfo(nameSym, nameSym.textRange, MM1.icon!!, Pass.UPDATE_ALL,
          { "Multi-method implementations" }, { mouseEvent, t -> }, GutterIconRenderer.Alignment.RIGHT)
    }
    else if (def.def.type == "defmethod") {
      return LineMarkerInfo(nameSym, nameSym.textRange, MM2.icon!!, Pass.UPDATE_ALL,
          { "Multi-method" }, { mouseEvent, t -> }, GutterIconRenderer.Alignment.LEFT)
    }
    return null
  }
}

class ClojureInplaceRenameHandler : VariableInplaceRenameHandler() {
  override fun isAvailable(element: PsiElement?, editor: Editor?, file: PsiFile?): Boolean {
    if (editor == null || !editor.settings.isVariableInplaceRenameEnabled) return false
    return (element as? PomTargetPsiElement)?.target is CTarget
  }

  override fun createRenamer(elementToRename: PsiElement, editor: Editor?) = MyRenamer(elementToRename as PsiNamedElement, editor)

  class MyRenamer(elementToRename: PsiNamedElement, editor: Editor?, initialName: String?, oldName: String?) :
      MemberInplaceRenamer(elementToRename, null, editor, initialName, oldName) {
    constructor(elementToRename: PsiNamedElement, editor: Editor?) :
    this(elementToRename, editor, elementToRename.name, elementToRename.name)

    override fun checkLocalScope() =
        ((myElementToRename as? PomTargetPsiElement)?.target as CTarget).let { target ->
          if (target.key.namespace == "" && (target.key.type == "argument" || target.key.type == "let-binding"))
            myElementToRename.navigationElement.findParent(CList::class)
          else super.checkLocalScope() }

    override fun getNameIdentifier() = myElementToRename.navigationElement as? CSymbol

    override fun createInplaceRenamerToRestart(variable: PsiNamedElement, editor: Editor?, initialName: String?) =
        MyRenamer(variable, editor, initialName, initialName)
  }
}

class ClojureParamInfoProvider : ParameterInfoHandlerWithTabActionSupport<CList, CVec, CForm> {
  override fun updateParameterInfo(parameterOwner: CList, context: UpdateParameterInfoContext) =
      context.setCurrentParameter(parameterOwner.iterateForms().skip(1)
          .filter { !it.textMatches("&") }
          .indexOf { it.textRange.containsOffset(context.offset) })

  override fun getArgListStopSearchClasses() = emptySet<Class<*>>()

  override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext) =
      if (context.parameterOwner?.textRange?.containsOffset(context.offset) ?: false) context.parameterOwner as? CList else null

  override fun getActualParameterDelimiterType() = TokenType.WHITE_SPACE!!
  override fun getArgumentListAllowedParentClasses() = setOf(CList::class.java)
  override fun getParametersForDocumentation(p: CVec?, context: ParameterInfoContext?) = arrayOf(p)
  override fun tracksParameterIndex() = true

  override fun findElementForParameterInfo(context: CreateParameterInfoContext) =
      context.file.findElementAt(context.offset)
          .parents()
          .find { it is CList && it.findChild(ClojureTypes.C_PAREN1)?.textRange?.startOffset ?: context.offset < context.offset }?.let {
        (((it as CList).first as? CSymbol)?.reference?.resolve()?.navigationElement as? CList)?.run {
          context.itemsToShow = prototypes(this).transform { it.iterateForms().find { it is CVec } }.notNulls().toList().toTypedArray()
        }
        it as CList
      }

  override fun getActualParametersRBraceType() = ClojureTypes.C_PAREN2!!

  override fun updateUI(proto: CVec?, context: ParameterInfoUIContext) {
    val element = context.parameterOwner
    if (!element.isValid || proto == null || !proto.isValid) return

    val sb = StringBuilder()
    val highlight = intArrayOf(-1, -1)
    var i = 0
    proto.iterateForms().forEach { o ->
      if (i > 0) sb.append(" ")
      if (o.textMatches("&")) {
        sb.append("&")
      }
      else {
        if (i == context.currentParameterIndex) highlight[0] = sb.length
        sb.append(o.text)
        if (i == context.currentParameterIndex) highlight[1] = sb.length
        i ++
      }
    }
    if (sb.length == 0) {
      sb.append(CodeInsightBundle.message("parameter.info.no.parameters"))
    }
    context.setupUIComponentPresentation(sb.toString(), highlight[0], highlight[1], false, false, false, context.defaultParameterColor)
  }

  override fun getArgumentListClass(): Class<CList> = CList::class.java
  override fun getActualParameters(o: CList) = o.iterateForms().skip(1).toList().toTypedArray()
  override fun couldShowInLookup() = true
  override fun getParametersForLookup(item: LookupElement?, context: ParameterInfoContext?) = emptyArray<Any>()
  override fun getParameterCloseChars(): String = ")"

  override fun showParameterInfo(element: CList, context: CreateParameterInfoContext) =
      context.showHint(element, element.textRange.startOffset, this)
}

