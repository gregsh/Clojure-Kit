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

package org.intellij.clojure.tools

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionManager
import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.console.*
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.ConsoleViewUtil
import com.intellij.execution.process.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.icons.AllIcons
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.util.PropertiesComponent
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Comparing
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.psi.PsiDocumentManager
import com.intellij.remote.BaseRemoteProcessHandler
import com.intellij.remote.RemoteProcess
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.ArrayUtil
import com.intellij.util.ExceptionUtil
import com.intellij.util.containers.JBIterable
import com.intellij.util.text.UniqueNameGenerator
import com.intellij.util.text.nullize
import com.intellij.util.ui.EmptyIcon
import org.intellij.clojure.lang.ClojureFileType
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.nrepl.NReplClient
import org.intellij.clojure.nrepl.PING_DELAY
import org.intellij.clojure.nrepl.PING_TIMEOUT
import org.intellij.clojure.nrepl.dumpObject
import org.intellij.clojure.psi.*
import org.intellij.clojure.util.*
import org.jetbrains.concurrency.AsyncPromise
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import javax.swing.JList

/**
 * @author gregsh
 */
private val LOG = Logger.getInstance(ReplConsole::class.java)

object ReplConsoleRootType : ConsoleRootType("nrepl", "nREPL Consoles")

private val NREPL_CLIENT_KEY = Key.create<NReplClient>("NREPL_CLIENT_KEY")
private val NREPL_PROMISE_KEY = Key.create<AsyncPromise<*>>("NREPL_PROMISE_KEY")
private val EXCLUSIVE_MODE_KEY = Key.create<Boolean>("EXCLUSIVE_MODE_KEY")
private val REMOTE_ICON = AllIcons.RunConfigurations.Remote
private val LOCAL_ICON = AllIcons.RunConfigurations.Application


class ReplConnectAction : DumbAwareAction() {
  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val regex = "(?:nrepl://)?+(?:(\\S+)\\s*:)?\\s*(\\S+)".toRegex()
    val props = PropertiesComponent.getInstance(project)
    val propName = "clojure.repl.connect.LAST_VALUE"
    val prevValue = props.getValue(propName)
    val result = Messages.showInputDialog(project, "Enter nREPL server address:",
        e.presentation.text, Messages.getQuestionIcon(), prevValue, object : InputValidator {
      override fun checkInput(inputString: String?) = regex.find(inputString ?: "") != null

      override fun canClose(inputString: String?) = checkInput(inputString)
    }) ?: return
    props.setValue(propName, result)
    val matcher = regex.find(result) ?: return
    val host = StringUtil.nullize(matcher.groupValues[1], true) ?: "localhost"
    val port = StringUtil.parseInt(matcher.groupValues[2], -1)
    val addressString = "Connected to nREPL server running on port $port and host $host"
    createNewRunContent(project, "REPL [$host:$port]", REMOTE_ICON) {
      newRemoteProcess(addressString)
    }
  }
}

class ReplExecuteAction : DumbAwareAction() {
  override fun update(e: AnActionEvent) {
    val project = e.project
    val editor = CommonDataKeys.EDITOR.getData(e.dataContext)
    val repl = e.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR)?.executionConsole as? ReplConsole
    val file = (repl?.consoleView?.file ?: CommonDataKeys.PSI_FILE.getData(e.dataContext)) as? CFile
    e.presentation.isEnabledAndVisible = project != null && editor != null &&
        (file != null && ScratchFileService.getInstance().getRootType(file.virtualFile) !is IdeConsoleRootType ||
            repl?.inputHandler != null)
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val editor = CommonDataKeys.EDITOR.getData(e.dataContext) ?: return
    val repl = e.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR)?.executionConsole as? ReplConsole
    val file = (repl?.consoleView?.file ?: CommonDataKeys.PSI_FILE.getData(e.dataContext)) ?: return
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    var hasNamespace = false
    var pos = editor.caretModel.logicalPosition
    val text = if (editor.selectionModel.hasSelection() || file !is CFile) {
      val text = editor.selectionModel.getSelectedText(true).nullize() ?: editor.document.text
      hasNamespace = text.contains("(ns ")
      pos = editor.offsetToLogicalPosition(editor.selectionModel.leadSelectionOffset)
      text
    }
    else {
      var first = true
      val text = editor.document.immutableCharSequence
      editor.caretModel.allCarets.jbIt().map {
        val elementAt = file.findElementAt(
            if (it.offset > 0 && (it.offset >= text.length || Character.isWhitespace(text[it.offset]))) it.offset - 1
            else it.offset)
        elementAt.parents().filter(CForm::class).last()
      }
          .notNulls()
          .joinToString(separator = "\n") {
            if (first) {
              pos = editor.offsetToLogicalPosition(it.textRange.startOffset)
              if (it.role == Role.NS && (it as? CList)?.first?.name?.let { it == "ns" || it == "in-ns" } == true) {
                hasNamespace = true
              }
            }
            else first = false
            it.text
          }
    }
    val namespace = if (repl == null && !hasNamespace && file is CFile) file.namespace.nullize(true) else null
    val consumeRepl: (ReplConsole) -> Unit = { executeInRepl(it, editor, text, namespace, pos, file.virtualFile) }
    if (repl != null) {
      consumeRepl(repl)
    }
    else {
      findOrCreateRepl(project, file.virtualFile, consumeRepl)
    }
  }
}

class ReplExclusiveModeAction : ToggleAction() {
  override fun isDumbAware() = true

  override fun isSelected(e: AnActionEvent): Boolean {
    val project = e.project ?: return false
    return chooseRepl(e)?.let { isExclusive(it.consoleView) } ?:
    (allRepls(project).find { isExclusive(it.consoleView) } != null)
  }

  override fun setSelected(e: AnActionEvent, state: Boolean) {
    val project = e.project ?: return
    val contentManager = ExecutionManager.getInstance(project).contentManager
    chooseRepl(e) { chosen, fromPopup ->
      allRepls(project).forEach { repl ->
        val isChosen = (state || fromPopup) && repl === chosen
        setExclusive(repl.consoleView, isChosen)
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        contentManager.findContentDescriptor(executor, repl.processHandler)?.let { descriptor ->
          if (isChosen) {
            contentManager.toFrontRunContent(executor, descriptor)
            descriptor.attachedContent?.displayName = "* " + repl.consoleView.title
          }
          else {
            descriptor.attachedContent?.displayName = repl.consoleView.title
          }
        }
      }
    }
  }

  private fun chooseRepl(e: AnActionEvent, consumer: ((ReplConsole?, Boolean) -> Unit)? = null): ReplConsole? {
    val fromDescr = e.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR)?.executionConsole as? ReplConsole
    if (fromDescr != null) {
      return fromDescr.apply { consumer?.invoke(this, false) }
    }
    val project = e.project ?: return null
    val repls = allRepls(project)
        .addAllTo(mutableListOf<ReplConsole?>())
        .apply { add(null) }
    if (consumer == null || repls.size == 1) {
      return repls[0].apply { consumer?.invoke(this, false) }
    }
    else {
      val list = JBList<ReplConsole>(repls)
      list.cellRenderer = object : ColoredListCellRenderer<ReplConsole>() {
        override fun customizeCellRenderer(list: JList<out ReplConsole>, value: ReplConsole?, index: Int, selected: Boolean, hasFocus: Boolean) {
          if (value != null) {
            val exclusive = isExclusive(value.consoleView)
            val connected = value.repl.isConnected
            val attrs = when {
              connected -> if (exclusive) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
              else -> if (exclusive) SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES
            }
            append(value.consoleView.title, attrs)
            icon = if (value.processHandler is RemoteProcess) REMOTE_ICON else LOCAL_ICON
          }
          else {
            icon = EmptyIcon.ICON_16
            append("<no exclusive REPL>", SimpleTextAttributes.GRAYED_ATTRIBUTES)
          }
        }
      }
      JBPopupFactory.getInstance().createListPopupBuilder(list)
          .setTitle(e.presentation.text!!)
          .setFilteringEnabled { (it as? ReplConsole)?.consoleView?.title ?: "" }
          .setItemChoosenCallback { consumer(list.selectedValue, true) }
          .createPopup()
          .showCenteredInCurrentWindow(project)
      return null
    }
  }

}

private fun isExclusive(consoleView: LanguageConsoleView) =
    EXCLUSIVE_MODE_KEY.get(consoleView.consoleEditor) == true

private fun setExclusive(consoleView: LanguageConsoleView, value: Boolean) =
    EXCLUSIVE_MODE_KEY.set(consoleView.consoleEditor, value)

private fun allRepls(project: Project) = ExecutionManager.getInstance(project).contentManager.allDescriptors
    .iterate().map { it.executionConsole as? ReplConsole }.notNulls()

fun findReplForFile(project: Project, file: VirtualFile?): ReplConsole? {
  val triple = if (file != null) findReplInner(project, file) else null
  return triple?.first?.executionConsole as? ReplConsole ?: allRepls(project).single()
}

fun ConsoleView.println(text: String = "") = print(text + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
fun ConsoleView.printerr(text: String) = print(text + "\n", ConsoleViewContentType.ERROR_OUTPUT)

class ReplActionPromoter : ActionPromoter {
  override fun promote(actions: MutableList<AnAction>, context: DataContext) =
      actions.find { it is ReplExecuteAction }?.let { listOf(it) }
}

private fun executeInRepl(repl: ReplConsole, editor: Editor, text: String, namespace: String?, pos: LogicalPosition, file: VirtualFile) {
  val replEditor = repl.consoleView.consoleEditor
  val b = editor == replEditor || !replEditor.document.text.trim().isEmpty() && editor == repl.consoleView.historyViewer
  val code =
      if (b) WriteAction.compute<String, Exception> { replEditor.document.run { val s = getText(); setText(""); s } }
      else text
  val inputHandler = repl.inputHandler
  if (inputHandler != null) {
    inputHandler(code)
  }
  else {
    ConsoleHistoryController.addToHistory(repl.consoleView, code)
    repl.eval(code) {
      if (b) {
        set("line", 1)
        set("column", 1)
        set("file", repl.consoleView.virtualFile.path)
      }
      else {
        this.namespace = namespace
        set("line", pos.line + 1)
        set("column", pos.column + 1)
        set("file", file.path)
      }
    }
  }
}

private fun findReplInner(project: Project, virtualFile: VirtualFile): Triple<RunContentDescriptor?, String, VirtualFile> {
  val toolProjectDir = JBIterable.generate(virtualFile) { it.parent }
      .find { it.isDirectory && Tool.find(it.toIoFile()) != null }
  val baseDir = project.baseDir
  val workingDir = when {
    toolProjectDir != null -> toolProjectDir
    baseDir != null && VfsUtil.isAncestor(baseDir, virtualFile, true) -> baseDir
    else -> virtualFile.parent
  }

  val workingDirTitle = when {
    workingDir == baseDir -> project.name
    toolProjectDir != null && baseDir != null &&VfsUtil.isAncestor(baseDir, toolProjectDir, false) ->
      VfsUtil.getRelativePath(toolProjectDir, baseDir)
    else -> StringUtil.last(workingDir.path, 30, true)
  }
  val tool = Tool.find(workingDir.toIoFile()) ?: Lein
  val title = "${tool::class.simpleName} REPL ($workingDirTitle)"

  val allDescriptors = ExecutionManager.getInstance(project).contentManager.allDescriptors.asSequence()
      .filter { it.executionConsole is ReplConsole }
      .filterNotNull().toList()
  val ownContent = allDescriptors.find {
    Comparing.equal((it.executionConsole as ReplConsole).virtualFile, virtualFile)
  }
  val exclusiveContent = allDescriptors.find {
    (it.executionConsole as ReplConsole).let { isExclusive(it) }
  }
  val sameTitleContent = allDescriptors.find {
    Comparing.equal(title, it.displayName)
  }
  val contentToReuse = ownContent ?: exclusiveContent ?: sameTitleContent
  return Triple(contentToReuse, title, workingDir)
}

fun findOrCreateRepl(project: Project, virtualFile: VirtualFile, consumer: (ReplConsole) -> Unit) {
  val (contentToReuse, title, workingDir) = findReplInner(project, virtualFile)
  if (contentToReuse != null) {
    contentToReuse.attachedContent!!.run { manager.setSelectedContent(this) }

    val repl = contentToReuse.executionConsole as ReplConsole
    if (repl.processHandler.isProcessTerminating || repl.processHandler.isProcessTerminated) {
      repl.consoleView.println()
      val newProcess = try {
        repl.processFactory()
      }
      catch (ex: Exception) {
        val cause = ExceptionUtil.getRootCause(ex)
        if (cause is IOException || ex is ExecutionException) {
          ExecutionUtil.handleExecutionError(project, ToolWindowId.RUN, title, cause)
        }
        else {
          LOG.error(ex)
        }
        return
      }
      repl.consoleView.attachToProcess(newProcess)
      newProcess.startNotify()
    }
    contentToReuse.processHandler = repl.processHandler
    consumer(repl)
  }
  else {
    val workingDir = workingDir.toIoFile()
    val callback = ProgramRunner.Callback { consumer(it.executionConsole as ReplConsole) }
    createNewRunContent(project, title, LOCAL_ICON, callback) {
      newProcessHandler(workingDir)
    }
  }
}

private fun createNewRunContent(project: Project, title: String, icon: Icon,
                                callback: ProgramRunner.Callback? = null,
                                processFactory: () -> ProcessHandler) {
  val existingTitles = ExecutionManager.getInstance(project).contentManager.allDescriptors
      .asSequence().map { it.displayName }.toSet()
  val uniqueTitle = UniqueNameGenerator.generateUniqueName(title, "", "", " (", ")") { !existingTitles.contains(it) }
  val profile = object : RunProfile {
    override fun getIcon() = icon
    override fun getName() = uniqueTitle

    override fun getState(executor: Executor, env: ExecutionEnvironment): RunProfileState {
      return object : CommandLineState(env) {
        override fun startProcess() = try {
          processFactory()
        }
        catch (e: IOException) {
          throw ExecutionException("${e.javaClass.simpleName}: ${e.message}", e)
        }

        override fun createActions(console: ConsoleView, processHandler: ProcessHandler, executor: Executor): Array<AnAction> {
          return ArrayUtil.mergeArrays(super.createActions(console, processHandler, executor), arrayOf<AnAction>(
              ConsoleHistoryController.getController(console as LanguageConsoleView).browseHistory,
              ActionManager.getInstance().getAction("clojure.repl.exclusive.mode")))
        }

        override fun createConsole(executor: Executor) = ReplConsole(project, uniqueTitle, ClojureLanguage).apply {
          prompt = null
          this.processFactory = processFactory
          consoleEditor.setFile(virtualFile)
          ConsoleHistoryController(ReplConsoleRootType, title, this).install()
          setExclusive(this, allRepls(project).isEmpty)
        }
      }
    }
  }
  val environment = ExecutionEnvironmentBuilder.create(project, DefaultRunExecutor.getRunExecutorInstance(), profile).build()
  // ExecutionManager#restartRunProfile is not dumb-aware, run manually..
  ProgramRunnerUtil.executeConfigurationAsync(environment, false, true, callback)
}

class ReplConsole(project: Project, title: String, language: Language)
  : LanguageConsoleImpl(project, title, language) {
  lateinit var processHandler: ProcessHandler
  lateinit var processFactory: () -> ProcessHandler

  val repl: NReplClient get() = processHandler.getUserData(NREPL_CLIENT_KEY)!!
  private var whenConnected = AsyncPromise<Unit?>()

  var inputHandler: ((String) -> Unit)? = null
    private set
  val consoleView: LanguageConsoleView = this

  override fun attachToProcess(processHandler: ProcessHandler) {
    this.processHandler = processHandler
    super.attachToProcess(processHandler)
    processHandler.getUserData(NREPL_PROMISE_KEY)!!.done {
      afterConnected()
      whenConnected.setResult(null)
    }.rejected {
      whenConnected.cancel()
      val cause = ExceptionUtil.getRootCause(it)
      if (cause is IOException || it is ExecutionException) {
        ExecutionUtil.handleExecutionError(project, ToolWindowId.RUN, title, cause)
      }
      else {
        LOG.error(it)
      }
    }
    this.processHandler.addProcessListener(object : ProcessAdapter() {
      override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
        beforeDisconnected(willBeDestroyed)
        whenConnected.cancel()
        whenConnected = AsyncPromise()
      }
    })
  }

  fun eval(text: String, f: NReplClient.Request.() -> Unit = {}) = whenConnected.done { evalImpl(text, f) }

  private fun evalImpl(text: String, f: NReplClient.Request.() -> Unit) {
    val trimmed = text.trim()
    consoleView.println()
    consoleView.print((consoleView.prompt ?: ""), consoleView.promptAttributes ?: ConsoleViewContentType.USER_INPUT)
    if (!trimmed.isEmpty()) {
      ConsoleViewUtil.printAsFileType(consoleView, trimmed, ClojureFileType)
      consoleView.println()
    }
    if (trimmed.isEmpty()) {
    }
    else if (!repl.isConnected) {
      consoleView.printerr("Not connected")
    }
    else if (text.startsWith("/")) {
      val idx = text.indexOfFirst { Character.isWhitespace(it) }
      val op = if (idx == -1) text.substring(1) else text.substring(1, idx)
      if (op.isIn(setOf("", "help", "?"))) {
        repl.describeSession().whenComplete { result, error ->
          if (error != null) {
            consoleView.printerr(error.toString())
          }
          consoleView.println(dumpObject(result))
          requestScrollingToEnd()
        }
      }
      else {
        val s = cljLightTraverser(text.substring(idx + 1)).expandTypes { it !is ClojureElementType }
        evalImpl {
          this.op = op
          s.traverse().skip(1).split(2, true).forEach {
            val arg = s.api.textOf(it[0]).toString().trimStart(':')
            val value = s.api.textOf(it[1]).toString().let { str ->
              when (s.api.typeOf(it[1])) {
                ClojureTypes.C_LITERAL -> StringUtil.unquoteString(str)
                ClojureTypes.C_KEYWORD -> "\"$str\""
                else -> str
              }
            }
            set(arg, value)
          }
          f.invoke(this)
        }.whenComplete(this::onCommandCompleted)
      }
    }
    else {
      evalImpl {
        this.namespace = namespace
        code = trimmed
        f.invoke(this)
      }.whenComplete(this::onCommandCompleted)
    }
  }

  private fun onCommandCompleted(result: Map<String, Any?>?, error: Throwable?) {
    updatePrompt(result?.get("ns") as? String)
    if (error != null) {
      val cause = ExceptionUtil.getRootCause(error)
      val message: String? = when {
        cause is ProcessCanceledException -> null
        cause is IOException -> error.message.nullize() ?: ExceptionUtil.getThrowableText(cause)
        error is ExecutionException && cause == error -> error.message.nullize() ?: ExceptionUtil.getThrowableText(cause)
        else -> ExceptionUtil.getThrowableText(cause)
      }
      if (message != null) consoleView.print(message, ConsoleViewContentType.ERROR_OUTPUT)
    }
    if (result != null) {
      val ex = result["ex"]
      val stacktrace = result["stacktrace"] as? List<*>
      val value = result["value"] ?: if (ex != null || stacktrace != null) null else result
      when {
        value != null ->
          consoleView.print(dumpObject(value), ConsoleViewContentType.NORMAL_OUTPUT)
        ex != null ->
          if (ex != "class clojure.lang.Compiler\$CompilerException") {
            evalImpl {
              op = "stacktrace"
            }.whenComplete(this::onCommandCompleted)
          }
        stacktrace != null ->
          for (m in stacktrace) {
            if (m !is Map<*, *>) break
            val frame = if (m["type"] == "java") "\tat ${m["class"]}.${m["method"]}(${m["file"]}:${m["line"]})"
              else "\tat ${m["name"]}(${m["file"]}:${m["line"]})"
            consoleView.printerr(frame)
          }
      }
    }
    if (error != null && !repl.isConnected && processHandler is BaseRemoteProcessHandler<*> &&
        !processHandler.isProcessTerminated && !processHandler.isProcessTerminated) {
      processHandler.detachProcess()
    }
    requestScrollingToEnd()
  }

  private fun updatePrompt(namespace: String?) {
    consoleView.prompt = namespace?.let { "$it=> " } ?: "=> "
  }

  fun afterConnected() {
    val info = repl.describeSession().get() as? Map<*, *> ?: emptyMap<Any, Any>()
    val versions = JBIterable.from((info["versions"] as? Map<*, *>)?.entries)
        .append((info["aux"] as? Map<*, *>)?.entries)
        .filter { it.value is Map<*, *> }
        .map {
          val version = (it.value as Map<*, *>)["version-string"]
          if (version != null) "${it.key}:$version" else null
        }.notNulls().joinToString(", ")
    if (versions.isNotEmpty()) {
      consoleView.println(versions)
    }
    updatePrompt((info["aux"] as? Map<*, *>)?.get("current-ns") as? String)
    if (processHandler is BaseRemoteProcessHandler<*>) {
      evalImpl {
        op = "out-subscribe"
        repl.defaultRequest = this
      }.get()
    }
    evalImpl("(when (clojure.core/resolve 'clojure.main/repl-requires)" +
        " (clojure.core/map clojure.core/require clojure.main/repl-requires))")
        .get()
    requestScrollingToEnd()
  }

  fun beforeDisconnected(destroyed: Boolean) {
    if (processHandler is BaseRemoteProcessHandler<*> && !destroyed) {
      evalImpl {
        op = "out-unsubscribe"
        repl.defaultRequest = null
      }.get(PING_TIMEOUT, TimeUnit.MILLISECONDS)
    }
  }

  private fun evalImpl(code: String? = null, f: NReplClient.Request.() -> Unit = {}) = repl.eval(code) {
    f.invoke(this)
    stdout = { s -> consoleView.print(s, ConsoleViewContentType.NORMAL_OUTPUT) }
    stderr = { s ->
      consoleView.print(s.indexOf(", compiling:(").let {
        if (it == -1) s else s.substring(0, it) + "\n" + s.substring(it + 2)
      }, ConsoleViewContentType.ERROR_OUTPUT)
    }
    stdin = { out ->
      TransactionGuard.getInstance().submitTransaction(consoleView, null, Runnable {
        val prevText = consoleView.editorDocument.text
        val prevLang = consoleView.language
        val prevPrompt = consoleView.prompt
        val prevPromptAttrs = consoleView.promptAttributes
        setTextWithoutUndo(consoleView.project, consoleView.editorDocument, "")
        consoleView.prompt = "(input) "
        consoleView.setPromptAttributes(ConsoleViewContentType.USER_INPUT)
        consoleView.language = PlainTextLanguage.INSTANCE
        inputHandler = { s ->
          inputHandler = null
          val adjusted = if (s.endsWith("\n")) s else s + "\n"
          consoleView.print(adjusted, ConsoleViewContentType.USER_INPUT)
          consoleView.prompt = prevPrompt
          prevPromptAttrs?.let { consoleView.setPromptAttributes(it) }
          setTextWithoutUndo(consoleView.project, consoleView.editorDocument, prevText)
          consoleView.language = prevLang
          out.invoke(adjusted)
        }
        requestScrollingToEnd()
      })
    }
  }
}

fun newProcessHandler(workingDir: File): ProcessHandler {
  val port = try { FileUtil.loadFile(File(workingDir, ".nrepl-port")).trim().toInt() } catch (e: Exception) { -1 }
  val addressStr = if (port > 0) "Connected to nREPL server running on port $port and host localhost" else null

  val remoteProcess = try {
    if (addressStr != null) newRemoteProcess(addressStr) else null
  }
  catch (e: Exception) {
    null
  }
  return remoteProcess ?: newLocalProcess(workingDir)
}


fun newLocalProcess(workingDir: File): ProcessHandler {
  val tool = Tool.find(workingDir) ?: Lein
  val processHandler = OSProcessHandler(tool.getRepl().withWorkDirectory(workingDir.path))
  val repl = NReplClient()
  val promise = AsyncPromise<Unit?>()
  processHandler.putUserData(NREPL_CLIENT_KEY, repl)
  processHandler.putUserData(NREPL_PROMISE_KEY, promise)
  ProcessTerminatedListener.attach(processHandler)
  processHandler.addProcessListener(object : ProcessAdapter() {
    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
      val text = event.text ?: return
      if (!text.startsWith("nREPL server started on port ")) return
      (event.source as ProcessHandler).removeProcessListener(this)
      processHandler.executeTask {
        try {
          repl.connect(text)
          promise.setResult(null)
        }
        catch (e: Exception) {
          promise.processed { }
          promise.setError(e)
        }
      }
    }

    override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
      processHandler.executeTask {
        repl.disconnect()
      }
    }
  })
  return processHandler
}

fun newRemoteProcess(addressString: String): ProcessHandler {
  val emptyIn = object : InputStream() { override fun read() = -1 }
  val emptyOut = object : OutputStream() { override fun write(b: Int) = Unit }
  val pingLock = Object()
  val repl = NReplClient()
  val promise = AsyncPromise<Unit?>()
  val process = object : RemoteProcess() {
    init {
      try {
        repl.connect(addressString)
        promise.setResult(null)
      }
      catch (e: Exception) {
        promise.processed { }
        promise.setError(e)
        throw e
      }
    }

    override fun destroy() {
      try {
        repl.eval("(System/exit 0)") { session = repl.toolSession }
      }
      catch (e: Exception) {
        LOG.info(e)
      }
    }

    override fun exitValue(): Int {
      if (repl.isConnected) throw IllegalThreadStateException()
      else return 0
    }

    override fun isDisconnected() = !repl.isConnected

    override fun waitFor(): Int {
      while (repl.ping()) {
        synchronized(pingLock) { pingLock.wait(PING_DELAY) }
      }
      if (repl.isConnected) {
        repl.disconnect()
      }
      return exitValue()
    }

    override fun getLocalTunnel(remotePort: Int) = null
    override fun getOutputStream() = emptyOut
    override fun getErrorStream() = emptyIn
    override fun getInputStream() = emptyIn
    override fun killProcessTree() = false
  }
  val processHandler = object : BaseRemoteProcessHandler<RemoteProcess>(process, addressString, null) {

    override fun isSilentlyDestroyOnClose() = false

    override fun detachIsDefault() = true

    override fun destroyProcessImpl() {
      super.destroyProcessImpl()
      executeTask {
        repl.disconnect()
        synchronized(pingLock) { pingLock.notifyAll() }
      }
    }

    override fun detachProcessImpl() {
      executeTask {
        repl.disconnect()
        synchronized(pingLock) { pingLock.notifyAll() }
      }
      super.detachProcessImpl()
    }

    override fun notifyProcessDetached() {
      notifyTextAvailable("\nProcess connection closed", ProcessOutputTypes.SYSTEM)
      super.notifyProcessDetached()
    }

    override fun notifyProcessTerminated(exitCode: Int) {
      notifyTextAvailable("\nProcess connection lost", ProcessOutputTypes.SYSTEM)
      super.notifyProcessTerminated(exitCode)
    }
  }
  processHandler.putUserData(NREPL_CLIENT_KEY, repl)
  processHandler.putUserData(NREPL_PROMISE_KEY, promise)
  return processHandler
}

private fun NReplClient.connect(portHost: String) {
  "port (\\S+).* host (\\S+)".toRegex().find(portHost)?.run {
    val host = groupValues[2]
    val port = StringUtil.parseInt(groupValues[1], -1)
    try {
      connect(host, port)
    }
    catch(e: IOException) {
      throw ExecutionException(e)
    }
  }
}
