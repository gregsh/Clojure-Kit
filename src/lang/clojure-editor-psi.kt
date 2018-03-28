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
import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.daemon.impl.PsiElementListNavigator
import com.intellij.codeInsight.documentation.QuickDocUtil
import com.intellij.codeInsight.generation.actions.PresentableCodeInsightActionHandler
import com.intellij.codeInsight.hints.HintInfo
import com.intellij.codeInsight.hints.InlayInfo
import com.intellij.codeInsight.hints.InlayParameterHintsProvider
import com.intellij.codeInsight.hints.Option
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.navigation.ListBackgroundUpdaterTask
import com.intellij.icons.AllIcons
import com.intellij.lang.ExpressionTypeProvider
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.documentation.DocumentationProviderEx
import com.intellij.lang.parameterInfo.*
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Comparing
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.patterns.StandardPatterns
import com.intellij.pom.Navigatable
import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.*
import com.intellij.psi.meta.PsiPresentableMetaData
import com.intellij.psi.scope.BaseScopeProcessor
import com.intellij.psi.search.PsiElementProcessor
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiUtilCore
import com.intellij.refactoring.rename.inplace.MemberInplaceRenamer
import com.intellij.refactoring.rename.inplace.VariableInplaceRenameHandler
import com.intellij.util.ProcessingContext
import com.intellij.util.TimeoutUtil
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.JBIterable
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.ui.UIUtil
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.ClojureIcons
import org.intellij.clojure.inspections.RESOLVE_SKIPPED
import org.intellij.clojure.lang.ClojureColors
import org.intellij.clojure.lang.usages.ClojureGotoRenderer
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.impl.*
import org.intellij.clojure.psi.stubs.CPrototypeStub
import org.intellij.clojure.util.*
import java.awt.event.MouseEvent

/**
 * @author gregsh
 */
class ClojureAnnotator : Annotator {

  override fun annotate(element: PsiElement, holder: AnnotationHolder) {
    val callable = element is CSForm && element.parentForm.let {
      it is CList && it.firstForm == element && it.iterate(CReaderMacro::class).isEmpty
    }
    if (callable) {
      holder.createInfoAnnotation(element.valueRange, null).textAttributes = ClojureColors.CALLABLE
    }
    when (element) {
      is CSymbol -> {
        val target = element.resolveInfo()
        if (element.getUserData(RESOLVE_SKIPPED) != null) {
          if (element.name.let { it != "&" && it != "." }) {
            holder.createInfoAnnotation(element, null).textAttributes = ClojureColors.DYNAMIC
          }
          return
        }
        if (target == null) return
        var enforced: TextAttributes? = null
        val attrs = when (target.type) {
          "ns" -> ClojureColors.NAMESPACE.also { enforced = ClojureColors.NS_COLORS[target.name] }
          "alias" -> ClojureColors.ALIAS
          "argument" -> ClojureColors.FN_ARGUMENT
          "let-binding" -> ClojureColors.LET_BINDING
          else -> null
        }
        if (attrs != null) {
          holder.createInfoAnnotation(element.valueRange, null).run {
            textAttributes = attrs
            enforcedTextAttributes = enforced
          }
        }
        if (callable && target.matches(ClojureDefinitionService.COMMENT_SYM)) {
          holder.createInfoAnnotation(element.parentForm!!, null).textAttributes = ClojureColors.FORM_COMMENT
        }
      }
      is CMetadata -> {
        element.firstForm.let {
          if (it is CSymbol) {
            holder.createInfoAnnotation(it, null).textAttributes = ClojureColors.METADATA
          }
        }
      }
    }
    if (element is CForm && element.iterate(CReaderMacro::class).find { it.firstChild.elementType == ClojureTypes.C_SHARP_COMMENT } != null) {
      holder.createInfoAnnotation(element, null).textAttributes = ClojureColors.FORM_COMMENT
    }
  }
}

class ClojureCompletionContributor : CompletionContributor() {

  init {
    extend(CompletionType.BASIC, psiElement().inFile(StandardPatterns.instanceOf(CFile::class.java)), object: CompletionProvider<CompletionParameters>() {
      override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext?, result: CompletionResultSet) {
        val element = parameters.position.findParent(CSymbol::class) ?: return
        val originalFile = parameters.originalFile
        val fileNamespace = (originalFile as? CFile)?.namespace
        val noNsPrefix = element.qualifier?.name == null
        val showAll = parameters.invocationCount > 1
        val thisForm = element.thisForm
        val project = element.project
        val posExt = originalFile.virtualFile.extension
        fun acceptFile(file: VirtualFile): Boolean {
          val fileExt = file.extension
          return !(posExt == "clj" && fileExt == "cljs")
        }

        @Suppress("NON_EXHAUSTIVE_WHEN")
        when (thisForm.role) {
          Role.ARG, Role.BND, Role.FIELD -> return
          Role.NAME -> {
            val type = thisForm.parentForm.asDef?.def?.type
            if (noNsPrefix && type != "def" && type != "defn" && type != "defmacro") {
              completeNamespaces(project, result)
            }
            return
          }
        }
        val ref = if (thisForm !is CKeyword) element.reference as? CSymbolReference else null
        val bindingsVec: CVec? = (element.parent as? CVec)?.run { if (ref?.resolve()?.navigationElement == element) this else null }
        if (bindingsVec != null && bindingsVec.parentForm !is CMap) return

        val aliases = (originalFile as CFileImpl).aliasesAtPlace(parameters.originalPosition)

        val qualifiedResult = if (thisForm == null) result
        else TextRange(thisForm.valueRange.startOffset, parameters.offset)
            .substring(originalFile.text)
            .let { result.withPrefixMatcher(it) }
        val prefixedResult = result.withPrefixMatcher(
            TextRange(parameters.position.textRange.startOffset, parameters.offset)
                .substring(originalFile.text))

        if (thisForm is CKeyword || thisForm == null) {
          val visited = ContainerUtil.newTroveSet<String>()
          val prefixNamespace = (thisForm as? CKeyword)?.namespace
          val consumer: (String, String, VirtualFile) -> Unit = consumer@ { name, ns, file ->
            if (!showAll && !noNsPrefix && ns != prefixNamespace) return@consumer
            val qualifiedName = ":" + name.withNamespace(ns)
            val alias = aliases[ns]
            val s =
                if (noNsPrefix && fileNamespace == ns) "::$name"
                else if (alias != null) "::$alias/$name"
                else qualifiedName
            if (!visited.add(s)) return@consumer
            if (!qualifiedResult.prefixMatcher.prefixMatches(s) &&
                !qualifiedResult.prefixMatcher.prefixMatches(qualifiedName)) return@consumer
            qualifiedResult.addElement(LookupElementBuilder.create(s)
                .withLookupString(qualifiedName)
                .withTypeText(file.name, true)
                .bold())
          }
          originalFile.cljTraverser().traverse().filter(CKeyword::class).filter { it != thisForm }.forEach { o ->
            consumer(o.name, o.namespace, originalFile.virtualFile)
          }
          FileBasedIndex.getInstance().run {
            val scope = ClojureDefinitionService.getClojureSearchScope(project)
            processAllKeys(KEYWORD_FQN_INDEX, { fqn ->
              val idx = fqn.indexOf('/')
              val name = fqn.substring(idx + 1)
              val ns = if (idx > 0) fqn.substring(0, idx) else ""
              getFilesWithKey(KEYWORD_FQN_INDEX, mutableSetOf(fqn), { file ->
                if (acceptFile(file)) {
                  consumer(name, ns, file)
                }
                true
              }, scope)
            }, project)
          }
        }
        if (thisForm !is CKeyword && ref != null && bindingsVec == null) {
          val service = ClojureDefinitionService.getInstance(project)
          val stop = !ref.processDeclarations(service, null, ResolveState.initial(), object : BaseScopeProcessor() {
            override fun execute(it: PsiElement, state: ResolveState): Boolean {
              val name = state.get(RENAMED_KEY) ?:
                  when (it) {
                    is CList -> it.def?.name
                    is CSymbol -> it.name
                    is PsiNamedElement -> it.name
                    else -> null
                  } ?: return true
              if (!prefixedResult.prefixMatcher.prefixMatches(name)) return true
              val lookupItem = when (it) {
                is CList -> LookupElementBuilder.create(it, name)
                    .withIcon((it as NavigationItem).presentation!!.getIcon(false))
                    .withTailText(" (${it.def!!.namespace})", true)
                is CSymbol -> LookupElementBuilder.create(it, name)
                    .withIcon(ClojureIcons.SYMBOL)
                is PsiNamedElement -> {
                  val types = service.java.getMemberTypes(it as NavigatablePsiElement)
                  val size = types.size
                  LookupElementBuilder.create(it, name)
                      .withIcon(it.getIcon(0))
                      .run { if (size > 0) withTypeText(StringUtil.getShortName(types[0]), false) else this }
                      .run { if (size > 1) withPresentableText(types.jbIt().skip(1)
                          .map { StringUtil.getShortName(it) }
                          .filter(EachNth(2))
                          .joinToString(", ", "$name(", ")"))
                        else if (size == 1 && (it as? PsiPresentableMetaData)?.typeName == "Method") withPresentableText("$name()")
                      else this }
                }
                else -> return true
              }
              result.addElement(lookupItem)
              return true
            }
          })
          if (stop && !showAll) return
          if (showAll) {
            FileBasedIndex.getInstance().run {
              val scope = ClojureDefinitionService.getClojureSearchScope(project)
              processAllKeys(DEF_FQN_INDEX, { fqn ->
                val idx = fqn.indexOf('/')
                val ns = if (idx > 0) fqn.substring(0, idx) else ""
                val name = fqn.substring(idx + 1)
                val s = name.withNamespace(aliases[ns] ?: ns)
                if (!qualifiedResult.prefixMatcher.prefixMatches(fqn) &&
                    !qualifiedResult.prefixMatcher.prefixMatches(s)) return@processAllKeys true
                getFilesWithKey(DEF_FQN_INDEX, mutableSetOf(fqn), { file ->
                  if (acceptFile(file)) {
                    qualifiedResult.addElement(LookupElementBuilder.create(s)
                        .withLookupString(fqn)
                        .withIcon(ClojureIcons.DEFN)
                        .withTypeText(file.name, true))
                  }
                  true
                }, scope)
              }, project)
            }
          }
        }
        if (noNsPrefix && (bindingsVec == null || bindingsVec.parent is CMap)) {
          completeNamespaces(project, result)
        }
      }
    })
  }

  private fun completeNamespaces(project: Project, result: CompletionResultSet) {
    FileBasedIndex.getInstance().run {
      processAllKeys(NS_INDEX, { ns ->
        result.addElement(LookupElementBuilder.create(ns).withIcon(ClojureIcons.NAMESPACE))
        true
      }, project)
    }
  }

  override fun beforeCompletion(context: CompletionInitializationContext) {
    if (context.file.findElementAt(context.startOffset).elementType == ClojureTypes.C_SYM) {
      context.dummyIdentifier = ""
    }
  }
}

class ClojureDocumentationProvider : DocumentationProviderEx() {
  override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
    return super.getQuickNavigateInfo(element, originalElement)
  }

  override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
    val target = (element as? PomTargetPsiElement)?.target
    val key = (target as? CTarget)?.key
    val resolved =
        if (key != null && key.type == "keyword" &&
            key.namespace == "" && ClojureConstants.NS_ALIKE_SYMBOLS.contains(key.name)) {
          // adjust resolved element for (ns ...) macro keywords
          ClojureDefinitionService.getInstance(element!!.project).getDefinition(key.name, Dialect.CLJ.coreNs, "def").navigationElement
        }
        else {
          (element as? PomTargetPsiElement)?.navigationElement
        }?.let {
          it.asXTarget?.resolveForm() ?: it
        } ?: element
    val def = (resolved as? CList)?.def ?: key ?: return null

    fun String.sanitize() = StringUtil.escapeXml(StringUtil.unquoteString(this))
    fun StringBuilder.appendMap(m: CForm?) {
      val forms = (m as? CMap)?.childForms ?: return
      for ((i, form) in forms.withIndex()) {
        if (i % 2 == 0) append("<br>").append("<b>").append(form.text.sanitize()).append("</b>   ")
        else append(form.text.sanitize())
      }
    }

    val nameSymbol = resolved.findChild(Role.NAME) as? CSymbol
    val sb = StringBuilder("<html>")
    sb.append("<b>(${def.type}</b> ${def.qualifiedName}<b>${if (resolved is CList) " …" else ""})</b>").append("<br>")
    val docLiteral =
        if (def.type == "method") nameSymbol.findNext(CLiteral::class)
        else nameSymbol.nextForm as? CLiteral
    if (docLiteral != null) {
      if (docLiteral.literalType == ClojureTypes.C_STRING) {
        sb.append("<br>").append(docLiteral.literalText.sanitize()).append("<br>")
      }
    }
    sb.appendMap(docLiteral.nextForm)
    for (m in nameSymbol?.metas ?: emptyList()) {
      sb.appendMap(m.form)
    }
    val result = sb
        .replace("\n(?:\\s*\n)+".toRegex(), "<p>")
        .replace("(?:\\.\\s)".toRegex(), ".<p>")
        .replace("(?:\\:\\s)".toRegex(), ":<br>")
    val sourceFile = PsiUtilCore.getVirtualFile(resolved)
    return scheduleSlowDocumentation(element!!, result, { e, s -> loadMoreInformation(s, def, e, sourceFile) })
  }

  private fun loadMoreInformation(sb: StringBuilder, def: IDef, element: PsiElement, sourceFile: VirtualFile?) {
    val project = element.project
    DumbService.getInstance(project).waitForSmartMode()
    if (def.type == "ns") {
      sb.append("<br>")
      FileBasedIndex.getInstance().getFilesWithKey(NS_INDEX, mutableSetOf(def.name), { file ->
        sb.append("<br><i>${file.presentableUrl}</i>")
        true
      }, ClojureDefinitionService.getClojureSearchScope(project))

    }
    else {
      appendSpecs(sb, element, sourceFile)
    }
  }

  private fun appendSpecs(sb: StringBuilder, element: PsiElement, sourceFile: VirtualFile?) {
    var first = true
    for (ref in ReferencesSearch.search(element)) {
      val form = ref.element.thisForm
      val maybeSpec = form.parentForm as? CList
      val callSym = maybeSpec?.first ?: continue
      if (callSym.nextForm != form) continue
      val key = callSym.resolveInfo() ?: continue
      if (key.namespace != ClojureConstants.NS_SPEC || !(key.name == "def" || key.name == "fdef")) {
        continue
      }
      sb.append("<p>")
      if (first) {
        sb.append("<p><b>").append(":specs").append("</b>")
        first = false
      }
      sb.append("<br><pre>").append(maybeSpec.text).append("</pre>")
      val specFile = PsiUtilCore.getVirtualFile(maybeSpec)
      if (specFile != null && sourceFile != specFile) {
        val url = specFile.presentableUrl
        sb.append("<i>").append(StringUtil.last(url, 80, true)).append("</i>")
      }
    }
  }

  fun scheduleSlowDocumentation(element: PsiElement,
                                prefix: CharSequence,
                                provider: (PsiElement, StringBuilder) -> Unit): String {
    val moreText = "<p><p><i><small>Looking for more...</small></i>"
    val sb = StringBuilder(prefix)
    val project = element.project
    AppExecutorUtil.getAppExecutorService().submit {
      ProgressIndicatorUtils.runInReadActionWithWriteActionPriority {
        ApplicationManager.getApplication().runReadAction pooled@ {
          if (!element.isValid) return@pooled
          provider(element, sb)
        }
      }
      //todo handle "Fetching Documentation..." otherwise; now just wait a bit
      TimeoutUtil.sleep(300)
      UIUtil.invokeLaterIfNeeded later@ {
        val component = QuickDocUtil.getActiveDocComponent(project) ?: return@later
        val prevText = component.text
        val moreIdx = prevText.indexOf(moreText)
        if (moreIdx < 0) return@later
        val newText = sb.toString()
        if (!Comparing.equal(newText, prevText)) {
          component.replaceText(newText, element)
        }
      }
    }
    return "$prefix$moreText"
  }
}

class ClojureTargetElementEvaluator : TargetElementEvaluatorEx2() {
  override fun includeSelfInGotoImplementation(element: PsiElement) = false
  override fun getNamedElement(element: PsiElement) = element.parent.asDef?.run { if (findChild(Role.NAME) == element) this else null }
  override fun getGotoDeclarationTarget(element: PsiElement, navElement: PsiElement?) = (navElement.asDef)?.findChild(Role.NAME) ?: navElement
  override fun getElementByReference(ref: PsiReference, flags: Int) = ref.resolve()
}

class ClojureLineMarkerProvider : LineMarkerProviderDescriptor() {
  companion object {
    val P1 = Option("clojure.method.decl", "Method declaration", AllIcons.Gutter.ImplementedMethod)
    val P2 = Option("clojure.method.impl", "Method implementation", AllIcons.Gutter.ImplementingMethod)
    val MM1 = Option("clojure.defmulti", "Multi-method declaration", AllIcons.Gutter.ImplementedMethod)
    val MM2 = Option("clojure.defmethod", "Multi-method implementation", AllIcons.Gutter.ImplementingMethod)
  }

  override fun getName() = "Clojure line markers"
  override fun getOptions() = arrayOf(P1, P2, MM1, MM2)

  override fun collectSlowLineMarkers(elements: MutableList<PsiElement>, result: MutableCollection<LineMarkerInfo<PsiElement>>) {
  }

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element !is CSymbol || element.role != Role.NAME) return null
    val def = element.parentForm.asDef?.def
    val parentName = element.parentForm.firstForm?.name
    val grandName = element.parentForm.parentForm.firstForm?.name
    val leaf = element.deepLast!!
    return when {
      def?.type == "method" ->
        if (!P1.isEnabled) null else
        LineMarkerInfo(leaf, leaf.textRange, P1.icon!!, Pass.UPDATE_ALL, { "Show implementations" },
          { event, o -> showNavPopup(o, event) }, GutterIconRenderer.Alignment.LEFT)
      def?.type == "defmulti" ->
        if (!MM1.isEnabled) null else
        LineMarkerInfo(leaf, leaf.textRange, MM1.icon!!, Pass.UPDATE_ALL, { "Show implementations" },
            { event, o -> showNavPopup(o, event) }, GutterIconRenderer.Alignment.RIGHT)
      def == null && parentName == "defmethod" ->
        if (!MM2.isEnabled) null else
        LineMarkerInfo(leaf, leaf.textRange, MM2.icon!!, Pass.UPDATE_ALL, { "Show declaration" },
            { _, o -> navigate(o) },
            GutterIconRenderer.Alignment.LEFT)
      def == null && ClojureConstants.OO_ALIKE_SYMBOLS.contains(grandName) ->
        if (!P2.isEnabled) null else
        LineMarkerInfo(leaf, leaf.textRange, P2.icon!!, Pass.UPDATE_ALL, { "Show declaration" },
            { _, o -> navigate(o) },
            GutterIconRenderer.Alignment.LEFT)
      else -> null
    }
  }

  private fun navigate(e: PsiElement?) {
    val target = e.findParent(CSymbol::class)?.reference?.resolve() as? Navigatable
    target?.navigate(true)
  }

  private fun showNavPopup(leaf: PsiElement, event: MouseEvent) {
    val sym = leaf.findParent(CSymbol::class) ?: return
    val target = sym.reference.resolve() ?: return
    val renderer = ClojureGotoRenderer()
    val name = sym.name
    val searchScope = TargetElementUtil.getInstance().getSearchScope(null, target)
    val collectProcessor = PsiElementProcessor.CollectElementsWithLimit(2, mutableSetOf<NavigatablePsiElement>())
    DefinitionsScopedSearch.search(target, searchScope).forEach {
      collectProcessor.execute(it as NavigatablePsiElement)
    }

    val updater = object: ListBackgroundUpdaterTask(sym.project, "Searching for Implementing Methods") {
      override fun getCaption(size: Int): String {
        val suffix = if (isFinished) "" else " so far"
        return "<html><body>Choose Implementation of <b>$name</b> ($size found$suffix)</body></html>"
      }

      override fun onSuccess() {
        super.onSuccess()
        val oneElement = theOnlyOneElement
        if (oneElement is NavigatablePsiElement) {
          oneElement.navigate(true)
          myPopup?.cancel()
        }
      }

      override fun run(indicator: ProgressIndicator) {
        super.run(indicator)
        DefinitionsScopedSearch.search(target, searchScope).forEach {
          if (!updateComponent(it, renderer.comparator)) {
            indicator.cancel()
          }
          indicator.checkCanceled()
        }
      }
    }
    val firstFound = collectProcessor.collection.toTypedArray()
    PsiElementListNavigator.openTargets(event, firstFound,
        updater.getCaption(firstFound.size), "Implementing methods of " + name, renderer, updater)
  }
}

class ClojureGotoSuperHandler : PresentableCodeInsightActionHandler {
  override fun update(editor: Editor, file: PsiFile, presentation: Presentation?) {
  }

  override fun invoke(project: Project, editor: Editor, file: PsiFile) {
    val sym = file.findElementAt(editor.caretModel.offset).thisForm as? CSymbol ?: return
    (sym.reference.resolve() as? Navigatable)?.navigate(true)
  }
}

class ClojureInplaceRenameHandler : VariableInplaceRenameHandler() {
  override fun isAvailable(element: PsiElement?, editor: Editor, file: PsiFile): Boolean {
    if (!editor.settings.isVariableInplaceRenameEnabled) return false
    return element.asCTarget != null
  }

  override fun createRenamer(elementToRename: PsiElement, editor: Editor) = MyRenamer(elementToRename as PsiNamedElement, editor)

  class MyRenamer(elementToRename: PsiNamedElement, editor: Editor, initialName: String?, oldName: String?) :
      MemberInplaceRenamer(elementToRename, null, editor, initialName, oldName) {
    constructor(elementToRename: PsiNamedElement, editor: Editor) :
    this(elementToRename, editor, elementToRename.name, elementToRename.name)

    override fun checkLocalScope() =
        myElementToRename.asCTarget!!.let { target ->
          if (target.key.namespace == "" && (target.key.type == "argument" || target.key.type == "let-binding"))
            myElementToRename.navigationElement.findParent(CList::class)
          else super.checkLocalScope() }

    override fun getNameIdentifier() = myElementToRename.navigationElement as? CSymbol

    override fun createInplaceRenamerToRestart(variable: PsiNamedElement, editor: Editor, initialName: String?) =
        MyRenamer(variable, editor, initialName, initialName)
  }
}

class ClojureParamInfoProvider : ParameterInfoHandlerWithTabActionSupport<CList, Any, CForm> {
  override fun updateParameterInfo(parameterOwner: CList, context: UpdateParameterInfoContext) =
      context.setCurrentParameter(parameterOwner.childForms.skip(1)
          .filter { !it.textMatches("&") }
          .indexOf { it.textRange.containsOffset(context.offset) })

  override fun getArgListStopSearchClasses() = emptySet<Class<*>>()

  override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext) =
      if (context.parameterOwner?.textRange?.containsOffset(context.offset) ?: false) context.parameterOwner as? CList else null

  override fun getActualParameterDelimiterType() = TokenType.WHITE_SPACE!!
  override fun getArgumentListAllowedParentClasses() = setOf(CList::class.java)
  override fun getParametersForDocumentation(p: Any?, context: ParameterInfoContext?) = arrayOf(p)
  override fun tracksParameterIndex() = true

  override fun findElementForParameterInfo(context: CreateParameterInfoContext) =
      context.file.findElementAt(context.offset)
          .parents()
          .filter(CList::class)
          .find {
            it.findChild(ClojureTypes.C_PAREN1)?.textRange?.startOffset ?: context.offset < context.offset
          }?.
          let {
            context.itemsToShow = resolvePrototypes(it).toList().toTypedArray()
            it
          }

  override fun getActualParametersRBraceType() = ClojureTypes.C_PAREN2!!

  override fun updateUI(proto: Any?, context: ParameterInfoUIContext) {
    val element = context.parameterOwner
    if (!element.isValid || proto == null || proto is CVec && !proto.isValid) return

    val sb = StringBuilder()
    val highlight = intArrayOf(-1, -1)
    var i = 0
    arguments(proto).forEach { o ->
      if (i > 0) sb.append(" ")
      if (o == "&") {
        sb.append("&")
      }
      else {
        if (i == context.currentParameterIndex) highlight[0] = sb.length
        sb.append(o)
        if (i == context.currentParameterIndex) highlight[1] = sb.length
        i++
      }
    }
    if (sb.isEmpty()) {
      sb.append(CodeInsightBundle.message("parameter.info.no.parameters"))
    }
    context.setupUIComponentPresentation(sb.toString(), highlight[0], highlight[1], false, false, false, context.defaultParameterColor)
  }

  override fun getArgumentListClass(): Class<CList> = CList::class.java
  override fun getActualParameters(o: CList) = o.childForms.skip(1).toList().toTypedArray()
  override fun couldShowInLookup() = true
  override fun getParametersForLookup(item: LookupElement?, context: ParameterInfoContext?) = emptyArray<Any>()
  override fun getParameterCloseChars(): String = ")"

  override fun showParameterInfo(element: CList, context: CreateParameterInfoContext) =
      context.showHint(element, element.textRange.startOffset, this)
}

class ClojureParamInlayHintsHandler : InlayParameterHintsProvider {

  companion object {
    private val OPTION = Option("clojure.parameter.hints", "Show parameter names", false)
  }

  override fun getParameterHints(element: PsiElement): List<InlayInfo> {
    if (!OPTION.get()) return emptyList()
    if (element !is CList) return emptyList()
    return resolvePrototypes(element).transform Function@ { proto ->
      val candidate = mutableListOf<InlayInfo>()
      val params = element.childForms.skip(1).iterator()
      val args = arguments(proto).iterator()
      var vararg = false
      while (args.hasNext() && params.hasNext()) {
        val param = params.next()
        val arg = args.next().let {
          if (it == "&" && args.hasNext()) {
            vararg = true
            args.next()
          }
          else it
        }
        candidate.add(InlayInfo(if (vararg) "..." + arg else arg, param.textRange.startOffset))
      }
      if (args.hasNext() || params.hasNext() && !vararg) return@Function null
      candidate
    }.notNulls().first() ?: emptyList<InlayInfo>()
  }

  override fun getHintInfo(element: PsiElement): HintInfo? = HintInfo.OptionInfo(OPTION)
  override fun getDefaultBlackList(): Set<String> = emptySet()
  override fun getSupportedOptions(): List<Option> = ContainerUtil.newSmartList(OPTION)
  override fun isBlackListSupported(): Boolean = false

}

fun resolvePrototypes(call: CList): JBIterable<*> {
  return call.first?.resolveXTarget()?.resolveStub()?.childrenStubs?.jbIt() ?: JBIterable.empty<Any>()
}

fun arguments(proto: Any): JBIterable<String> {
  if (proto is CVec) {
    return proto.childForms.map { it.text }
  }
  if (proto is CPrototypeStub) return proto.args.jbIt()
  return JBIterable.empty()
}

class ClojureTypeInfoProvider : ExpressionTypeProvider<CForm>() {
  override fun getErrorHint() = "No form found"

  override fun getInformationHint(element: CForm): String {
    val type = ClojureDefinitionService.getInstance(element.project).javaType(element)
    return StringUtil.escapeXml(type ?: "unknown")
  }

  override fun getExpressionsAt(elementAt: PsiElement): List<CForm> =
      JBIterable.generate(elementAt.thisForm, { it.parentForm }).notNulls().toList()
}