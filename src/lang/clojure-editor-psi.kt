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
import com.intellij.codeInsight.daemon.RainbowVisitor
import com.intellij.codeInsight.daemon.UsedColors
import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.codeInsight.daemon.impl.PsiElementListNavigator
import com.intellij.codeInsight.documentation.QuickDocUtil
import com.intellij.codeInsight.editorActions.moveLeftRight.MoveElementLeftRightHandler
import com.intellij.codeInsight.generation.actions.PresentableCodeInsightActionHandler
import com.intellij.codeInsight.hint.DeclarationRangeHandler
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
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.*
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.patterns.StandardPatterns
import com.intellij.pom.Navigatable
import com.intellij.pom.PomNamedTarget
import com.intellij.pom.PomTargetPsiElement
import com.intellij.pom.references.PomService
import com.intellij.psi.*
import com.intellij.psi.meta.PsiPresentableMetaData
import com.intellij.psi.scope.BaseScopeProcessor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiElementProcessor
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiUtilCore
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.UnresolvableCollisionUsageInfo
import com.intellij.refactoring.rename.inplace.MemberInplaceRenamer
import com.intellij.refactoring.rename.inplace.VariableInplaceRenameHandler
import com.intellij.usageView.UsageInfo
import com.intellij.util.ExceptionUtil
import com.intellij.util.ProcessingContext
import com.intellij.util.SingleAlarm
import com.intellij.util.SmartList
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.JBIterable
import com.intellij.util.indexing.FileBasedIndex
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.ClojureIcons
import org.intellij.clojure.getTokenDescription
import org.intellij.clojure.inspections.RESOLVE_SKIPPED
import org.intellij.clojure.java.JavaHelper
import org.intellij.clojure.lang.ClojureColors
import org.intellij.clojure.lang.usages.ClojureGotoRenderer
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.ClojureTypes.*
import org.intellij.clojure.psi.impl.*
import org.intellij.clojure.psi.stubs.CPrototypeStub
import org.intellij.clojure.tools.findReplForFile
import org.intellij.clojure.util.*
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * @author gregsh
 */
class ClojureAnnotator : Annotator {

  override fun annotate(element: PsiElement, holder: AnnotationHolder) {
    if (element is CSForm && (element.parentForm as? CList).firstForm == element
        && element.firstChild.elementType != C_SYM
        && element.parentForm.iterate(CReaderMacro::class).isEmpty) {
      holder.createInfoAnnotation(element.valueRange, null).textAttributes = ClojureColors.CALLABLE
    }
    if (element is CCommented || element.flags and FLAG_COMMENTED != 0) {
      holder.createInfoAnnotation(element, null).textAttributes = ClojureColors.FORM_COMMENT
    }
    if (element is CSymbol && element.flags and FLAG_QUOTED != 0 &&
        !element.parentForm.iterate().find { it is CSymbol || it.elementType == C_SYM }
            ?.prevSibling.let { it is CReaderMacro && it.firstChild.elementType.let { it == C_QUOTE || it == C_SYNTAX_QUOTE } }) {
      holder.createInfoAnnotation(element.valueRange, null).textAttributes = ClojureColors.QUOTED_SYM
    }
    if (element is CSymbol && element.flags and FLAG_QUOTED == 0) {
      var enforced: TextAttributes? = null
      val attrs = when (element.role) {
        Role.NAME -> ClojureColors.DEFINITION
        Role.ARG -> ClojureColors.FN_ARGUMENT
        Role.BND -> ClojureColors.LET_BINDING
        Role.FIELD -> ClojureColors.TYPE_FIELD
        else -> {
          val resolved = element.reference.resolve()
          val target = resolved.asCTarget?.key
          when {
            element.getUserData(RESOLVE_SKIPPED) != null ->
              if (element.name.let { it != "&" && it != "." }) ClojureColors.DYNAMIC else null
            target != null -> when (target.type) {
              "ns" -> ClojureColors.NAMESPACE.also { enforced = ClojureColors.NS_COLORS[target.name] }
              "alias" -> ClojureColors.ALIAS
              "field" -> ClojureColors.TYPE_FIELD
              "argument" -> ClojureColors.FN_ARGUMENT
              "let-binding" -> ClojureColors.LET_BINDING
              else -> if (target.type.startsWith("#")) ClojureColors.DATA_READER else null
            }
            resolved != null -> when (JavaHelper.getJavaHelper(resolved.project).getElementType(resolved)) {
              JavaHelper.ElementType.CLASS -> ClojureColors.JAVA_CLASS
              JavaHelper.ElementType.STATIC_METHOD -> ClojureColors.JAVA_STATIC_METHOD
              JavaHelper.ElementType.STATIC_FIELD -> ClojureColors.JAVA_STATIC_FIELD
              JavaHelper.ElementType.INSTANCE_METHOD -> ClojureColors.JAVA_INSTANCE_METHOD
              JavaHelper.ElementType.INSTANCE_FIELD -> ClojureColors.JAVA_INSTANCE_FIELD
              else -> null
            }
            else -> null
          }
        }
      }
      if (attrs != null) {
        holder.createInfoAnnotation(element.valueRange, null).run {
          textAttributes = attrs
          enforcedTextAttributes = enforced
        }
      }
    }
  }
}

class ClojureRainbowVisitor : RainbowVisitor() {
  override fun suitableForFile(file: PsiFile) = file is CFile
  override fun clone(): HighlightVisitor = ClojureRainbowVisitor()
  override fun visit(element: PsiElement) {
    if (element !is CSymbol) return
    val resolved = element.reference.resolve() ?: return
    val target = resolved.asCTarget?.key ?: return
    val attrs = when (target.type) {
      "argument" -> ClojureColors.FN_ARGUMENT
      "let-binding" -> ClojureColors.LET_BINDING
      else -> return
    }
    val context = (resolved.asCTarget as? PsiTarget)?.navigationElement.parentForms.find {
      it is CList && it.role != Role.PROTOTYPE || it is CMetadata } as? CList ?: return
    val colorIndex = UsedColors.getOrAddColorIndex(context as UserDataHolderEx, element.name, highlighter.colorsCount)
    val range = element.valueRange
    addInfo(highlighter.getInfo(colorIndex, range.startOffset, range.endOffset, attrs))
  }
}

class ClojureCompletionContributor : CompletionContributor() {

  init {
    extend(CompletionType.BASIC, psiElement().inFile(StandardPatterns.instanceOf(CFile::class.java)), object: CompletionProvider<CompletionParameters>() {
      override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
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
          val visited = ContainerUtil.newTroveSet<String>(qualifiedResult.prefixMatcher.prefix)
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
                        else if (size == 1 && (it as? PsiPresentableMetaData)?.typeName == "method") withPresentableText("$name()")
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
    if (context.file.findElementAt(context.startOffset).elementType == C_SYM) {
      context.dummyIdentifier = ""
    }
  }
}

class ClojureDocumentationProvider : DocumentationProviderEx() {
  override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?): PsiElement? {
    val elementType = contextElement?.elementType
    if (elementType != null && getTokenDescription(elementType) != null) {
      return PomService.convertToPsi(object : DelegatePsiTarget(contextElement), PomNamedTarget {
        override fun getName() = elementType.toString()
      })
    }
    return null
  }

  override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
    val project = element?.project ?: return null
    val target = (element as? PomTargetPsiElement)?.target
    val key = (target as? CTarget)?.key
    val resolved =
        if (key != null && key.type == "keyword" &&
            key.namespace == "" && ClojureConstants.NS_ALIKE_SYMBOLS.contains(key.name)) {
          // adjust resolved element for (ns ...) macro keywords
          ClojureDefinitionService.getInstance(project).getDefinition(key.name, Dialect.CLJ.coreNs, "def").navigationElement
        }
        else {
          (element as? PomTargetPsiElement)?.navigationElement
        }?.let {
          it.asXTarget?.resolveForm() ?: it
        } ?: element
    val def = (resolved as? CList)?.def ?: key ?: return getTokenDescription(originalElement?.elementType)
    if (def.type == "tag") return "tag ${def.qualifiedName}"

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
    sb.append("<code><b>(${def.type}</b> ${def.qualifiedName}<b>)</b></code>").append("<br>")
    val docLiteral =
        if (def.type == "method") nameSymbol.nextForm(CLiteral::class)
        else nameSymbol.nextForm as? CLiteral
    if (docLiteral != null) {
      if (docLiteral.literalType == C_STRING) {
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
    val originalFile = PsiUtilCore.getVirtualFile(originalElement)
    return scheduleSlowDocumentation(element, result) { appender ->
      if (def.type == "defmacro") {
        val repl = findReplForFile(project, originalFile)
        val qualifiedText = readAction { originalElement.parentForms.filter(CList::class).first()?.qualifiedText() }
        if (repl != null && qualifiedText != null) {
          val code = """
            (binding [*print-meta* true
                      *out* (java.io.StringWriter.)]
              (clojure.pprint/pprint (macroexpand '$qualifiedText))
              (.toString *out*))"""
          val future = repl.repl.eval(code)
          val value = try { future.get()?.get("value") } catch (ex : Exception) { ExceptionUtil.getThrowableText(ex) }
          if (value is String) {
            val adjusted = StringUtil.unescapeStringCharacters(StringUtil.unquoteString(value))
            appender("<p><p><b>:macroexpand</b><br><pre>$adjusted</pre>")
          }
        }
      }
      DumbService.getInstance(project).runReadActionInSmartMode {
        ProgressIndicatorUtils.runInReadActionWithWriteActionPriority {
          loadSlowDocumentationInReadAction(project, appender, def, element, sourceFile)
        }
      }
    }
  }

  private fun loadSlowDocumentationInReadAction(project: Project, appender: (String)->Any?, def: IDef, element: PsiElement, sourceFile: VirtualFile?) {
    if (def.type == "ns") {
      appender("<br>")
      FileBasedIndex.getInstance().getFilesWithKey(NS_INDEX, mutableSetOf(def.name), { file ->
        appender("<br><i>${file.presentableUrl}</i>")
        true
      }, ClojureDefinitionService.getClojureSearchScope(project))
    }
    else {
      var first = true
      for (ref in ReferencesSearch.search(element, ClojureDefinitionService.getClojureSearchScope(project))) {
        val form = ref.element.thisForm
        val maybeSpec = form.parentForm as? CList
        val callSym = maybeSpec?.first ?: continue
        if (callSym.nextForm != form) continue
        val key = callSym.resolveInfo() ?: continue
        if (key.namespace != ClojureConstants.NS_SPEC && key.namespace != ClojureConstants.NS_SPEC_ALPHA ||
            key.name != "def" && key.name != "fdef") {
          continue
        }
        appender("<p>")
        if (first) {
          appender("<p><b>:specs</b>")
          first = false
        }
        appender("<br><pre>${maybeSpec.text}</pre>")
        val specFile = PsiUtilCore.getVirtualFile(maybeSpec)
        if (specFile != null && sourceFile != specFile) {
          val url = specFile.presentableUrl
          appender("<i>${StringUtil.last(url, 80, true)}</i>")
        }
      }
    }
  }

  private fun scheduleSlowDocumentation(element: PsiElement,
                                        prefix: CharSequence,
                                        provider: (appender: (String)->Any?) -> Unit): String {
    val sb = StringBuilder(prefix)
    val project = element.project
    val queue = ConcurrentLinkedQueue<Any>()
    val alarmDisposable = Disposer.newDisposable()
    Disposer.register(project, alarmDisposable)
    val alarm = SingleAlarm(Runnable {
      val component = QuickDocUtil.getActiveDocComponent(project)
      if (component == null) {
        Disposer.dispose(alarmDisposable)
        return@Runnable
      }
      var s = queue.poll()
      while (s != null) {
        if (s !is String) break
        sb.append(s)
        s = queue.poll()
      }
      if (s != null && s !is String) {
        sb.append("<p><p>")
        Disposer.dispose(alarmDisposable)
      }
      val newText = sb.toString()
      val prevText = component.text
      if (!Comparing.equal(newText, prevText)) {
        component.replaceText(newText, element)
      }
    }, 100, alarmDisposable)
    AppExecutorUtil.getAppExecutorService().submit {
      try {
        provider { str ->
          ProgressManager.checkCanceled()
          if (alarm.isDisposed) throw ProcessCanceledException()
          queue.add(str)
          alarm.cancelAndRequest()
        }
      }
      finally {
        queue.add(false)
        alarm.cancelAndRequest()
      }
    }
    return "$prefix<p><p>"
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
      def?.type == "defmulti" ->
        if (!MM1.isEnabled) null else
        LineMarkerInfo(leaf, leaf.textRange, MM1.icon!!, Pass.UPDATE_ALL, { "Show implementations" },
            { event, o -> showNavPopup(o, event) }, GutterIconRenderer.Alignment.RIGHT)
      def == null && parentName == "defmethod" ->
        if (!MM2.isEnabled) null else
        LineMarkerInfo(leaf, leaf.textRange, MM2.icon!!, Pass.UPDATE_ALL, { "Show declaration" },
            { _, o -> navigate(o) }, GutterIconRenderer.Alignment.LEFT)
      def?.type == "method" && (grandName == "defprotocol" || grandName == "definterface") -> {
        if (!P1.isEnabled) null
        else LineMarkerInfo(leaf, leaf.textRange, P1.icon!!, Pass.UPDATE_ALL, { "Show implementations" },
            { event, o -> showNavPopup(o, event) }, GutterIconRenderer.Alignment.LEFT)
      }
      ClojureConstants.OO_ALIKE_SYMBOLS.contains(grandName) ->
        if (!P1.isEnabled) null else
        LineMarkerInfo(leaf, leaf.textRange, P2.icon!!, Pass.UPDATE_ALL, { "Show declaration" },
            { _, o -> navigate(o) }, GutterIconRenderer.Alignment.LEFT)
      else -> null
    }
  }

  private fun navigate(e: PsiElement?) {
    val targetSym = e.parentForm.asDef?.prevForm as? CSymbol ?: e.findParent(CSymbol::class)
    val target = targetSym?.reference?.resolve() as? Navigatable
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
        updater.getCaption(firstFound.size), "Implementing methods of $name", renderer, updater)
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

    override fun collectRefs(referencesSearchScope: SearchScope): MutableCollection<PsiReference> {
      val (result, more) = doFindReferences(myElementToRename, referencesSearchScope)
      if (result == null) return SmartList()
      if (more != null) result.addAll(more)
      return result
    }
  }
}

class ClojureRenamePsiElementProcessor : RenamePsiElementProcessor() {
  override fun canProcessElement(element: PsiElement): Boolean = element.asCTarget != null

  override fun findReferences(element: PsiElement): MutableCollection<PsiReference> {
    val scope = GlobalSearchScope.projectScope(element.project)
    val (result, more) = doFindReferences(element, scope)
    if (result == null) return SmartList()
    if (more == null || more.isEmpty()) return result
    element.putUserData(MORE_OFFSET_KEY, result.size)
    result.addAll(more)
    return result
  }

  override fun findCollisions(element: PsiElement,
                              newName: String,
                              allRenames: MutableMap<out PsiElement, String>,
                              result: MutableList<UsageInfo>) {
    val offset = MORE_OFFSET_KEY.get(element) ?: return
    element.putUserData(MORE_OFFSET_KEY, null)
    val more = SmartList<UsageInfo>()
    val vecCond: (PsiElement) -> Boolean = { o -> o.role.let { it == Role.BND_VEC || it == Role.ARG_VEC } }
    for (usage in result.subList(offset, result.size)) {
      val usageElement = usage.element ?: continue
      val defVec = usage.reference?.resolve()?.navigationElement.parentForms.find(vecCond)
      val defScope = defVec.parentForm ?: continue
      val conflicts = SyntaxTraverser.psiTraverser(defScope)
          .expand { it is CPForm }
          .filter { it is CSymbol && it.name == newName && it.qualifier == null }
      for (conflict in conflicts) {
        val t = (conflict as CSymbol).resolveInfo() ?: continue
        more.add(object : UnresolvableCollisionUsageInfo(conflict, usageElement) {
          override fun getDescription() = "Renamed destructuring binding will hide ${t.type} '${t.qualifiedName}'"
        })
      }
    }
    result.addAll(more)
  }
}

private val MORE_OFFSET_KEY = Key.create<Int>("MORE_OFFSET_KEY")
private fun doFindReferences(element: PsiElement, scope: SearchScope): Array<MutableCollection<PsiReference>?> {
  val symKey = element.asCTarget?.key
  val result = ReferencesSearch.search(element, scope).findAll()
  if (symKey?.type != "keyword") return arrayOf(result, null)
  val more = SmartList<PsiReference>()
  for (ref in result) {
    val target = ref.resolve()
    val type = target?.asCTarget?.key?.type
    if (type != "argument" && type != "binding") continue
    val refElement = ref.element
    ReferencesSearch.search(target, scope).filter { it.element != refElement }.toCollection(more)
  }
  return arrayOf(result, more)
}

class ClojureParamInfoProvider : ParameterInfoHandlerWithTabActionSupport<CList, Any, CForm> {
  override fun updateParameterInfo(parameterOwner: CList, context: UpdateParameterInfoContext) =
      context.setCurrentParameter(parameterOwner.childForms.skip(1)
          .filter { !it.textMatches("&") }
          .indexOf { it.textRange.containsOffset(context.offset) })

  override fun getArgListStopSearchClasses() = emptySet<Class<*>>()

  override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext) =
      if (context.parameterOwner?.textRange?.containsOffset(context.offset) == true) context.parameterOwner as? CList
      else null

  override fun getActualParameterDelimiterType() = TokenType.WHITE_SPACE!!
  override fun getArgumentListAllowedParentClasses() = setOf(CList::class.java)
  override fun getParametersForDocumentation(p: Any?, context: ParameterInfoContext?) = arrayOf(p)
  override fun tracksParameterIndex() = true

  override fun findElementForParameterInfo(context: CreateParameterInfoContext) =
      context.file.findElementAt(context.offset)
          .parents()
          .filter(CList::class)
          .find {
            it.findChild(C_PAREN1)?.textRange?.startOffset ?: context.offset < context.offset
          }?.
          let {
            context.itemsToShow = resolvePrototypes(it).toList().toTypedArray()
            it
          }

  override fun getActualParametersRBraceType() = C_PAREN2!!

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
        candidate.add(InlayInfo(if (vararg) "...$arg" else arg, param.textRange.startOffset))
      }
      if (args.hasNext() || params.hasNext() && !vararg) return@Function null
      candidate
    }.notNulls().first() ?: emptyList()
  }

  override fun getHintInfo(element: PsiElement): HintInfo? = HintInfo.OptionInfo(OPTION)
  override fun getDefaultBlackList(): Set<String> = emptySet()
  override fun getSupportedOptions(): List<Option> = ContainerUtil.newSmartList(OPTION)
  override fun isBlackListSupported(): Boolean = false

}

fun resolvePrototypes(call: CList): JBIterable<*> {
  return call.first?.resolveXTarget()?.resolveStub()?.childrenStubs?.jbIt() ?: JBIterable.empty<Any>()
}

private fun arguments(proto: Any): JBIterable<String> {
  if (proto is CVec) return proto.childForms.map { it.text }
  if (proto is CPrototypeStub) return proto.args.jbIt().map {
    (if (it.typeHint != null && !it.name.startsWith("^${it.typeHint} ")) "^${it.typeHint} " else "") + it.name }
  return JBIterable.empty()
}

class ClojureTypeInfoProvider : ExpressionTypeProvider<CForm>() {
  override fun getErrorHint() = "No form found"

  override fun getInformationHint(element: CForm): String {
    val type = ClojureDefinitionService.getInstance(element.project).exprType(element)
    return StringUtil.escapeXml(type as? String ?: (type as? SymKey)?.run { "(${this.type} $qualifiedName)" } ?: "unknown")
  }

  override fun getExpressionsAt(elementAt: PsiElement): List<CForm> =
      JBIterable.generate(elementAt.thisForm) { it.parentForm }.notNulls().toList()
}

class ClojureDeclarationRangeHandler : DeclarationRangeHandler<CPForm> {
  override fun getDeclarationRange(container: CPForm): TextRange =
      (container.parentForms.last() ?: container).run {
        val name = childForms.find { it.role == Role.NAME } ?: firstForm
        if (name != null) TextRange.create(textRange.startOffset, name.textRange.endOffset)
        else textRange
      }
}

class ClojureMoveLeftRightHandler : MoveElementLeftRightHandler() {
  override fun getMovableSubElements(p0: PsiElement): Array<PsiElement> = when (p0) {
    is CList -> p0.childForms.skip(1)
    is CPForm -> p0.childForms
    else -> JBIterable.empty()
  }.toList().toTypedArray()
}

