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

package org.intellij.clojure.debugger

import com.google.common.collect.ImmutableBiMap
import com.intellij.debugger.MultiRequestPositionManager
import com.intellij.debugger.NoDataException
import com.intellij.debugger.PositionManagerFactory
import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.*
import com.intellij.debugger.engine.evaluation.*
import com.intellij.debugger.engine.evaluation.expression.EvaluatorBuilder
import com.intellij.debugger.engine.evaluation.expression.ExpressionEvaluator
import com.intellij.debugger.engine.evaluation.expression.Modifier
import com.intellij.debugger.impl.DebuggerContextImpl
import com.intellij.debugger.impl.EditorTextProvider
import com.intellij.debugger.impl.descriptors.data.WatchItemData
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.requests.ClassPrepareRequestor
import com.intellij.debugger.ui.breakpoints.Breakpoint
import com.intellij.debugger.ui.breakpoints.LineBreakpoint
import com.intellij.debugger.ui.tree.NodeDescriptor
import com.intellij.lang.Language
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.*
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.classFilter.ClassFilter
import com.intellij.ui.classFilter.DebuggerClassFilterProvider
import com.intellij.util.ThreeState
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import com.sun.jdi.*
import com.sun.jdi.request.ClassPrepareRequest
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.lang.CCodeFragmentImpl
import org.intellij.clojure.lang.ClojureFileType
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.lang.ClojureTokens
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.impl.ClojureDefinitionService
import org.intellij.clojure.psi.impl.NS_INDEX
import org.intellij.clojure.util.*
import org.jetbrains.java.debugger.breakpoints.properties.JavaBreakpointProperties
import org.jetbrains.java.debugger.breakpoints.properties.JavaLineBreakpointProperties

/**
 *
 *
 * @author gregsh
 */
class ClojurePositionManagerFactory : PositionManagerFactory() {
  override fun createPositionManager(process: DebugProcess) = ClojurePositionManager(process)
}

class ClojurePositionManager(private val process: DebugProcess) : PositionManagerEx(), MultiRequestPositionManager {
  override fun getAcceptedFileTypes(): MutableSet<out FileType> {
    return mutableSetOf(ClojureFileType)
  }

  override fun locationsOfLine(type: ReferenceType, position: SourcePosition): MutableList<Location> {
    try {
      val result = locationsOfLineInner(type, position).toMutableList()
      if (!result.isEmpty()) return result
    }
    catch (ignore: AbsentInformationException) { }
    throw NoDataException.INSTANCE
  }

  override fun getAllClasses(position: SourcePosition): MutableList<ReferenceType> {
    val pattern = classPatternsAtPosition(position)
        .joinToString("|") { it.split("*").joinToString(".*") { if (it.isEmpty()) it else "\\Q$it\\E" } }
        .toRegex()
    val result = process.virtualMachineProxy.allClasses().asSequence()
        .filter { it.name().matches(pattern) }
        .filter { try { locationsOfLineInner(it, position).firstOrNull() != null } catch (e: Exception) { false } }
        .toMutableList()

    return result
  }

  override fun createPrepareRequests(requestor: ClassPrepareRequestor, position: SourcePosition): MutableList<ClassPrepareRequest> {
    return classPatternsAtPosition(position)
        .mapNotNull { process.requestsManager.createClassPrepareRequest(requestor, it) }
        .toMutableList()
  }

  override fun createPrepareRequest(requestor: ClassPrepareRequestor, position: SourcePosition): ClassPrepareRequest? {
    throw AssertionError()
  }

  override fun getSourcePosition(location: Location?): SourcePosition? {
    return getSourcePositionInner(location) ?: throw NoDataException.INSTANCE
  }

  private fun classPatternsAtPosition(position: SourcePosition): List<String> {
    val def = findDefAtPosition(position) ?: return emptyList()
    val fileEval = def is SymKey && def.name == "file eval"
    val pattern = munge(def.namespace) + "$" + (if (fileEval) "eval*" else munge(def.name, true))
    return listOf(pattern, "$pattern\$*")
  }

  private fun findDefAtPosition(classPosition: SourcePosition): IDef? = readAction {
    val form = classPosition.elementAt.thisForm ?: return@readAction null
    form.parentForms.skip(1).last().asDef?.def
        ?: SymKey("file eval", (form.containingFile as? CFile)?.namespace ?: "user", "")
  }

  private fun locationsOfLineInner(type: ReferenceType, position: SourcePosition): Sequence<Location> {
    val locations = type.locationsOfLine("Clojure", null, position.line + 1)
    return locations.asSequence().filter { isFnMethod(it.method().name()) }
  }

  private fun getSourcePositionInner(location: Location?): SourcePosition? {
    val methodName = location?.method()?.name() ?: return null
    if (!isFnMethod(methodName)) return null
    val qname = location.declaringType().name()
    val top = qname.substringBefore("__").let { if (it != qname) it.substringBeforeLast("$") else qname }
    val namespace = top.substringBeforeLast("$")
    val name = top.substringAfterLast("$")
    if (namespace == qname) return null
    val sourceName = try { location.sourceName() } catch (ex : AbsentInformationException) { null } ?: return null
    val sourcePath = try { location.sourcePath() } catch (ex : AbsentInformationException) { null }

    val project = process.project
    val defService = ClojureDefinitionService.getInstance(project)
    val form = defService.getDefinition(demunge(name, true), demunge(namespace), "def").navigationElement
    val psiFile = form.containingFile ?: if (name.startsWith("eval") && name.length > 4 && name[4].isDigit()) {
      val scope = ClojureDefinitionService.getClojureSearchScope(project)
      val nsFiles = FileBasedIndex.getInstance().getContainingFiles(NS_INDEX, namespace, scope)
      val file = nsFiles.find { it.name == sourceName && (sourcePath == null || it.path.endsWith(sourcePath)) }
      if (file != null) PsiManager.getInstance(project).findFile(file) else null
    }
    else { null } ?: return null
    return SourcePosition.createFromLine(psiFile, location.lineNumber() - 1)
  }

  override fun createStackFrame(frame: StackFrameProxyImpl, debugProcess: DebugProcessImpl, location: Location): XStackFrame? {
    return null
  }

  override fun evaluateCondition(context: EvaluationContext, frame: StackFrameProxyImpl, location: Location, expression: String): ThreeState {
    return ThreeState.UNSURE
  }
}

class ClojureSourcePositionProvider : SourcePositionProvider() {
  override fun computeSourcePosition(descriptor: NodeDescriptor, project: Project, context: DebuggerContextImpl, nearest: Boolean): SourcePosition? {
    return null
  }
}

class ClojureSourcePositionHighlighter : SourcePositionHighlighter() {
  override fun getHighlightRange(sourcePosition: SourcePosition?): TextRange {
    return sourcePosition?.elementAt?.parentForm?.textRange ?: TextRange.EMPTY_RANGE
  }
}

class ClojureCodeFragmentFactory : CodeFragmentFactory() {
  override fun getFileType() = ClojureFileType
  override fun getEvaluatorBuilder() = ClojureEvaluatorBuilder
  override fun isContextAccepted(contextElement: PsiElement?) =
      contextElement?.language?.isKindOf(ClojureLanguage) ?: false
  override fun createCodeFragment(item: TextWithImports?, context: PsiElement?, project: Project) =
      newJavaCodeFragment(project, ClojureLanguage, ClojureTokens.CLJ_FILE_TYPE, "eval.clj", item?.text ?: "", context.thisForm ?: context, true)
  override fun createPresentationCodeFragment(item: TextWithImports?, context: PsiElement?, project: Project) =
      createCodeFragment(item, context, project)
}

object ClojureEvaluatorBuilder : EvaluatorBuilder {
  override fun build(codeFragment: PsiElement, position: SourcePosition?) =
      ClojureExpressionEvaluator(codeFragment, position)
}

class ClojureExpressionEvaluator(val codeFragment: PsiElement, val position: SourcePosition?) : ExpressionEvaluator {
  override fun evaluate(context: EvaluationContext): Value? {
    val requires = mutableListOf<String>()
    val symbols = mutableSetOf<String>()
    val fragmentText = readAction {
      codeFragment.qualifiedText(symbols::add, requires::add)
    }
    val castMap = HashMap<String, String>()
    val checkCast = { name: String, type: String -> ClojureConstants.J_BOXED_TYPES[type]?.let { castMap[name] = type }; name }
    val frameProxyImpl = context.frameProxy as StackFrameProxyImpl
    val frameVariables = frameProxyImpl.visibleVariables().jbIt().map { checkCast(it.name(), it.typeName()) }
    val thisFields = (frameProxyImpl.thisObject()?.type() as? ClassType)?.fields().jbIt().map { checkCast(it.name(), it.typeName()) }
    val args = sequenceOf(frameVariables, thisFields)
        .flatten().map { listOf(demunge(it), it) }
        .flatten().distinct().filter(symbols::contains).toList()

    //language=Java prefix="class A {{" suffix="}}"
    val text = """
      clojure.lang.Var.pushThreadBindings(clojure.lang.RT.map(
        clojure.lang.Compiler.LINE, 1, clojure.lang.Compiler.COLUMN, 1));
      try {
        return
        ((clojure.lang.IFn)clojure.lang.RT.var("clojure.core", "eval")
          .invoke(clojure.lang.RT.var("clojure.core", "read-string")
          .invoke("(do ${requires.joinToString("") { "(require '$it)" }}" +
                  "(fn [${args.joinToString(", ")}] ${StringUtil.escapeStringCharacters(fragmentText)}))")))
          .invoke(${args.map { munge(it) }.map { name  -> castMap[name]?.let { "($it)$name"} ?: name }.joinToString(", ")});
      }
      finally {
        clojure.lang.Var.popThreadBindings();
      }
    """.trimIndent()

    val fromText = XExpressionImpl(text, JavaLanguage.INSTANCE, null, EvaluationMode.CODE_FRAGMENT)
    val textWithImports = TextWithImportsImpl.fromXExpression(fromText)
    val descriptor = WatchItemData(textWithImports, null).createDescriptor(codeFragment.project)
    descriptor.setContext(context as EvaluationContextImpl)
    val exception = descriptor.evaluateException
    if (exception != null) {
      val vmException = exception.exceptionFromTargetVM
      val method = (vmException?.type() as? ClassType)?.concreteMethodByName("getMessage", "()Ljava/lang/String;")
      val messageObj =
          if (method == null) null
          else context.debugProcess.invokeMethod(context, vmException, method, emptyList<Value>())
      val message = (messageObj as? StringReference)?.value() ?: exception.message ?: throw exception
      if (vmException?.type()?.name()?.contains("clojure.lang.Compiler\$CompilerException") == true) {
        throw EvaluateException(message.removePrefix("java.lang.RuntimeException: "), null)
      }
      throw EvaluateException(message, exception)
    }
    else {
      return descriptor.value
    }
  }

  override fun getModifier(): Modifier? {
    return null
  }

}

class ClojureEditorTextProvider : EditorTextProvider {
  override fun getEditorText(elementAtCaret: PsiElement?): TextWithImports? {
    return null
  }

  override fun findExpression(elementAtCaret: PsiElement?, allowMethodCalls: Boolean): Pair<PsiElement, TextRange>? {
    val form = elementAtCaret.thisForm ?: return null
    val isFirst = form is CSForm && form == (form.parentForm as? CList)?.firstForm
    if (!allowMethodCalls && (form !is CSForm || isFirst)) return null
    val adjusted = if (isFirst) form.parentForm ?: form else form
    return Pair.create(adjusted, adjusted.textRange)
  }
}

class ClojureLineBreakpointHandlerFactory : JavaBreakpointHandlerFactory {
  override fun createHandler(process: DebugProcessImpl) = ClojureLineBreakpointHandler(process)
}

class ClojureLineBreakpointHandler(process: DebugProcessImpl) : JavaBreakpointHandler(ClojureLineBreakpointType::class.java, process) {
  override fun createJavaBreakpoint(xBreakpoint: XBreakpoint<out XBreakpointProperties<*>>): Breakpoint<out JavaBreakpointProperties<*>>? {
    val breakpoint = object : LineBreakpoint<JavaLineBreakpointProperties>(myProcess.project, xBreakpoint) {
      private val myProperties = JavaLineBreakpointProperties()
      override fun getProperties() = myProperties
    }
    breakpoint.init()
    return breakpoint
  }
}

private val FILTERS = mutableListOf(ClassFilter("clojure.*"))
class ClojureClassesFilterProvider : DebuggerClassFilterProvider {
  override fun getFilters() = FILTERS
}

fun newJavaCodeFragment(project: Project,
                        language: Language,
                        elementType: IElementType,
                        fileName: String,
                        text: CharSequence,
                        context: PsiElement?,
                        isPhysical: Boolean): JavaCodeFragment {
  val file = LightVirtualFile(fileName, language, text)
  val viewProvider = PsiManagerEx.getInstanceEx(project).fileManager.createFileViewProvider(file, isPhysical)
  val fragment = object : CCodeFragmentImpl(viewProvider, language, elementType, isPhysical), JavaCodeFragment {
    private var myThisType: PsiType? = null
    private var mySuperType: PsiType? = null
    private var myExceptionHandler: JavaCodeFragment.ExceptionHandler? = null
    private var myVisibilityChecker: JavaCodeFragment.VisibilityChecker? = null
    private var myImports = StringBuilder()
    override fun getThisType() = myThisType
    override fun setThisType(psiType: PsiType?) { myThisType = psiType }
    override fun getSuperType() = mySuperType
    override fun setSuperType(superType: PsiType?) { mySuperType = superType }
    override fun getExceptionHandler() = myExceptionHandler
    override fun setExceptionHandler(checker: JavaCodeFragment.ExceptionHandler?) { myExceptionHandler = checker }
    override fun getVisibilityChecker() = myVisibilityChecker
    override fun setVisibilityChecker(checker: JavaCodeFragment.VisibilityChecker?) { myVisibilityChecker = checker }
    override fun importsToString() = myImports.toString()
    override fun importClass(aClass: PsiClass): Boolean {
      addImportsFromString(aClass.qualifiedName)
      myManager.beforeChange(false)
      return true
    }
    override fun addImportsFromString(imports: String?) {
      if (imports.isNullOrBlank()) return
      if (myImports.isNotEmpty()) myImports.append(",")
      myImports.append(imports)
    }
  }
  fragment.context = context
  return fragment
}

private fun isFnMethod(methodName: String) = when (methodName) {
  "invoke" -> true // regular
  "invokePrim" -> true // regular primitive
  "doInvoke" -> true // variadic
  "invokeStatic" -> true // no this, no bound vars
  else -> false
}

fun munge(name: String, dot: Boolean = false) = name.replaceAll(MUNGE_PATTERN, MUNGE_MAP).let { if (!dot) it else it.replace(".", "_DOT_") }
fun demunge(name: String, dot: Boolean = false) = name.replaceAll(DEMUNGE_PATTERN, DEMUNGE_MAP).let { if (!dot) it else it.replace("_DOT_", ".") }

private val MUNGE_MAP = arrayOf(
    "-", "_", /*".", "_DOT_", */":", "_COLON_", "+", "_PLUS_", ">", "_GT_", "<", "_LT_", "=", "_EQ_", "~", "_TILDE_",
    "!", "_BANG_", "@", "_CIRCA_", "#", "_SHARP_", "\'", "_SINGLEQUOTE_", "\"", "_DOUBLEQUOTE_", "%", "_PERCENT_",
    "^", "_CARET_", "&", "_AMPERSAND_", "*", "_STAR_", "|", "_BAR_", "{", "_LBRACE_", "}", "_RBRACE_", "[", "_LBRACK_",
    "]", "_RBRACK_", "/", "_SLASH_", "\\", "_BSLASH_", "?", "_QMARK_")
    .jbIt()
    .split(2)
    .map { it.iterator() }
    .reduce(ImmutableBiMap.builder<String, String>()) { b, it -> b.put(it.next(), it.next()) }
    .build()
private val DEMUNGE_MAP = MUNGE_MAP.inverse()
private val MUNGE_PATTERN = buildReplacePattern(MUNGE_MAP.keys)
private val DEMUNGE_PATTERN = buildReplacePattern(DEMUNGE_MAP.keys)

private fun String.replaceAll(regex: Regex, replacements: Map<String, String>) = regex.replace(this) { replacements[it.value]!! }

private fun buildReplacePattern(strings: Iterable<String>) = strings.sortedByDescending { it.length }.jbIt()
    .reduce(StringBuilder()) { sb, s -> sb.append(if (sb.isNotEmpty()) "|" else "").append("\\Q").append(s).append("\\E") }
    .toString().toRegex()

