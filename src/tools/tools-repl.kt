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
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.impl.ConsoleViewUtil
import com.intellij.execution.process.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.ActionCallback
import com.intellij.openapi.util.Comparing
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiUtilCore
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
import org.intellij.clojure.nrepl.dumpObject
import org.intellij.clojure.psi.*
import org.intellij.clojure.util.*
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import javax.swing.Icon
import javax.swing.JList

/**
 * @author gregsh
 */

object ReplConsoleRootType : ConsoleRootType("nrepl", "nREPL Consoles")

private val NREPL_CLIENT_KEY = Key.create<ReplConnection>("NREPL_CLIENT_KEY")
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
      newRemoteProcess(addressString, false)
    }
  }
}

class ReplExecuteAction : DumbAwareAction() {
  override fun update(e: AnActionEvent) {
    val editor = CommonDataKeys.EDITOR.getData(e.dataContext)
    val console = ConsoleViewImpl.CONSOLE_VIEW_IN_EDITOR_VIEW.get(editor) as? LanguageConsoleView
    val file = (console?.file ?: CommonDataKeys.PSI_FILE.getData(e.dataContext)) as? CFile
    e.presentation.isEnabledAndVisible = e.project != null && file != null && editor != null &&
        ScratchFileService.getInstance().getRootType(file.virtualFile) !is IdeConsoleRootType
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val editor = CommonDataKeys.EDITOR.getData(e.dataContext) ?: return
    val console = ConsoleViewImpl.CONSOLE_VIEW_IN_EDITOR_VIEW.get(editor) as? LanguageConsoleView
    val file = (console?.file ?: CommonDataKeys.PSI_FILE.getData(e.dataContext)) as? CFile ?: return
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val hasNamespace = Ref.create(false)
    val text = if (editor.selectionModel.hasSelection()) {
      val text = editor.selectionModel.getSelectedText(true) ?: ""
      hasNamespace.set(text.contains("(ns "))
      text
    }
    else {
      val text = editor.document.immutableCharSequence
      editor.caretModel.allCarets.jbIt().map {
        val elementAt = file.findElementAt(
            if (it.offset > 0 && (it.offset >= text.length || Character.isWhitespace(text[it.offset]))) it.offset - 1
            else it.offset)
        elementAt.parents().filter(CForm::class).last()
      }
          .notNulls()
          .joinToString(separator = "\n") {
            if (it.role == Role.NS && (it as? CList)?.first?.name?.let { it == "ns" || it == "in-ns" } == true) {
              hasNamespace.set(true)
            }
            it.text
          }
    }
    val namespace = if (console == null && !hasNamespace.get()) file.namespace.nullize(true) else null
    executeInRepl(project, file, editor, text, namespace)
  }
}

class ReplExclusiveModeAction : ToggleAction() {
  override fun isDumbAware() = true

  override fun isSelected(e: AnActionEvent): Boolean =
      chooseRepl(e)?.let { isExclusive(it.consoleView) } ?:
          (allRepls(e.project).find { isExclusive(it.consoleView) } != null)

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

  private fun chooseRepl(e: AnActionEvent, consumer: ((ReplConnection?, Boolean)->Unit)? = null): ReplConnection? =
      NREPL_CLIENT_KEY.get(e.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR)?.processHandler)?.let { consumer?.invoke(it, false); it } ?: run {
        val repls = allRepls(e.project)
//            .sort(Comparator { o1: ReplConnection, o2: ReplConnection -> Comparing.compare(o1.consoleView.title, o2.consoleView.title) })
            .addAllTo(mutableListOf<ReplConnection?>())
            .apply { add(null) }
        if (consumer == null || repls.size == 1) return repls[0].apply { consumer?.invoke(this, false) }
        else {
          val list = JBList<ReplConnection>(repls)
          list.cellRenderer = object : ColoredListCellRenderer<ReplConnection>() {
            override fun customizeCellRenderer(list: JList<out ReplConnection>, value: ReplConnection?, index: Int, selected: Boolean, hasFocus: Boolean) {
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
              .setFilteringEnabled { (it as? ReplConnection)?.consoleView?.title ?: "" }
              .setItemChoosenCallback { consumer(list.selectedValue, true) }
              .createPopup()
              .showCenteredInCurrentWindow(e.project!!)
          return null
        }
      }

}

private fun isExclusive(consoleView: LanguageConsoleView) =
    EXCLUSIVE_MODE_KEY.get(consoleView.consoleEditor) == true

private fun setExclusive(consoleView: LanguageConsoleView, value: Boolean) =
    EXCLUSIVE_MODE_KEY.set(consoleView.consoleEditor, value)

private fun allRepls(project: Project?) =
    if (project == null) JBIterable.empty()
    else ExecutionManager.getInstance(project).contentManager.allDescriptors.jbIt()
        .map { NREPL_CLIENT_KEY.get(it.processHandler) }.notNulls()

fun ConsoleView.println(text: String = "") = print(text + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
fun ConsoleView.printerr(text: String) = print(text + "\n", ConsoleViewContentType.ERROR_OUTPUT)

class ReplActionPromoter : ActionPromoter {
  override fun promote(actions: MutableList<AnAction>, context: DataContext) =
      actions.find { it is ReplExecuteAction }?.let { listOf(it) }
}

fun executeInRepl(project: Project, file: CFile, editor: Editor, text: String, namespace: String?) {
  executeInRepl(project, PsiUtilCore.getVirtualFile(file) ?: return) { repl ->
    val replEditor = repl.consoleView.consoleEditor
    if (editor == replEditor || !replEditor.document.text.trim().isEmpty() && editor == repl.consoleView.historyViewer) {
      val command = WriteAction.compute<String, Exception> { replEditor.document.run { val s = getText(); setText(""); s } }
      repl.sendCommand(command, null)
      ConsoleHistoryController.addToHistory(repl.consoleView, command)
    }
    else {
      repl.sendCommand(text, namespace)
    }
  }
}

fun executeInRepl(project: Project, virtualFile: VirtualFile, command: (ReplConnection) -> Unit) {
  val projectDir = JBIterable.generate(virtualFile) { it.parent }.find {
    it.isDirectory && Tool.find(it.toIoFile()) != null }
  val title = if (projectDir == null) "REPL (default)"
  else ProjectFileIndex.SERVICE.getInstance(project).getContentRootForFile(projectDir).let {
    "REPL [${if (it == null || it == projectDir) projectDir.name else VfsUtil.getRelativePath(projectDir, it)}]"
  }

  val allDescriptors = ExecutionManager.getInstance(project).contentManager.allDescriptors
  val ownContent = allDescriptors.find {
    Comparing.equal((it.executionConsole as? LanguageConsoleView)?.virtualFile, virtualFile)
  }
  val exclusiveContent = allDescriptors.find {
    (it.executionConsole as? LanguageConsoleView)?.let { isExclusive(it) } ?: false
  }
  val sameTitleContent = allDescriptors.find { Comparing.equal(title, it.displayName) }
  val contentToReuse = ownContent ?: exclusiveContent ?: sameTitleContent
  if (contentToReuse != null) {
    contentToReuse.attachedContent!!.run { manager.setSelectedContent(this) }

    var repl = NREPL_CLIENT_KEY.get(contentToReuse.processHandler)
    if (repl.processHandler.isProcessTerminating || repl.processHandler.isProcessTerminated) {
      val oldRepl = repl
      oldRepl.consoleView.println()
      val newProcess = try {
        oldRepl.processFactory()
      }
      catch(e: ExecutionException) {
        ExecutionUtil.handleExecutionError(project, ToolWindowId.RUN, title, e)
        return
      }
      repl.consoleView.attachToProcess(newProcess)
      repl = NREPL_CLIENT_KEY.get(newProcess)
      repl.processFactory = oldRepl.processFactory
      repl.consoleView = oldRepl.consoleView
      newProcess.startNotify()
    }
    contentToReuse.processHandler = repl.processHandler
    command(repl)
  }
  else {
    createNewRunContent(project, title, LOCAL_ICON) {
      newProcessHandler(project, projectDir?.toIoFile())
    }.done {
      command(it)
    }
  }
}

private fun createNewRunContent(project: Project, title: String, icon: Icon,
                                processFactory: () -> ProcessHandler): Promise<ReplConnection> {
  val promise = AsyncPromise<ReplConnection>()
  val existingDescriptors = ExecutionManager.getInstance(project).contentManager.allDescriptors
  val uniqueTitle = UniqueNameGenerator.generateUniqueName(title, "", "", " (", ")",
      existingDescriptors.map { it.displayName }.toSet().let { { s: String -> !it.contains(s) }})
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
          NREPL_CLIENT_KEY.get(processHandler).run {
            this.processFactory = processFactory
            consoleView = console as LanguageConsoleView
            promise.setResult(this)
          }
          return ArrayUtil.mergeArrays(super.createActions(console, processHandler, executor), arrayOf<AnAction>(
              ConsoleHistoryController.getController(console as LanguageConsoleView).browseHistory,
              ActionManager.getInstance().getAction("clojure.repl.exclusive.mode")))
        }

        override fun createConsole(executor: Executor) =
            LanguageConsoleImpl(project, uniqueTitle, ClojureLanguage).apply {
              consoleEditor.setFile(virtualFile)
              ConsoleHistoryController(ReplConsoleRootType, title, this).install()
              setExclusive(this, allRepls(project).find { isExclusive(it.consoleView) } == null)
            }
      }
    }
  }
  val environment = ExecutionEnvironmentBuilder.create(project, DefaultRunExecutor.getRunExecutorInstance(), profile).build()
  // ExecutionManager#restartRunProfile is not dumb-aware, run manually..
  ProgramRunnerUtil.executeConfiguration(environment, false, true)
  return promise
}

class ReplConnection(val repl: NReplClient,
                     var processHandler: ProcessHandler,
                     var whenConnected: ActionCallback) {
  lateinit var consoleView: LanguageConsoleView
  lateinit var processFactory: () -> ProcessHandler

  init {
    whenConnected.doWhenDone { dumpReplInfo() }.doWhenDone { initRepl() }
  }

  fun sendCommand(text: String, namespace: String?) = whenConnected.doWhenDone {
    val trimmed = text.trim()
    consoleView.println()
    consoleView.print((consoleView.prompt ?: ""), ConsoleViewContentType.NORMAL_OUTPUT)
    if (!trimmed.isEmpty()) {
      ConsoleViewUtil.printAsFileType(consoleView, trimmed + "\n", ClojureFileType)
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
        consoleView.println("operations:\n" + dumpObject(repl.clientInfo["ops"]))
      }
      else {
        val s = cljLightTraverser(text.substring(idx + 1)).expandTypes { it !is ClojureElementType }
        val map = LinkedHashMap<String, String>().apply {
          put("op", op)
          s.traverse().skip(1).split(2, true).forEach {
            put(s.api.textOf(it[0]).toString().trimStart(':'), s.api.textOf(it[1]).toString()) }
        }
        repl.rawAsync(map).whenComplete { result, e ->
          onCommandCompleted(result, e)
        }
      }
    }
    else
      (if (namespace != null) repl.evalNsAsync(trimmed, namespace) else repl.evalAsync(trimmed))
          .whenComplete { result, e -> onCommandCompleted(result, e)
    }
    (consoleView as ConsoleViewImpl).requestScrollingToEnd()
  }

  private fun onCommandCompleted(result: Map<String, Any?>?, e: Throwable?) {
    if (e != null) {
      consoleView.print(ExceptionUtil.getThrowableText(e), ConsoleViewContentType.ERROR_OUTPUT)
    }
    else if (result != null) {
      result["ns"]?.let { consoleView.prompt = "$it=> " }
      val value = result["value"]
      val output = result["out"] as? String
      val error = result["err"] as? String
      var newline = true
      for (msg in arrayOf(output, error).filterNotNull()) {
        if (!newline) consoleView.println()
        val adjusted =
            if (msg != error) msg
            else msg.indexOf(", compiling:(").let {
              if (it == -1) msg else msg.substring(0, it) + "\n" + msg.substring(it + 2) } + "\n"
        consoleView.print(adjusted, if (msg == output) ConsoleViewContentType.NORMAL_OUTPUT else ConsoleViewContentType.ERROR_OUTPUT)
        newline = adjusted.endsWith("\n")
      }
      value?.let {
        if (!newline) consoleView.println()
        consoleView.print(dumpObject(it), ConsoleViewContentType.NORMAL_OUTPUT)
      }
      if (value == null && output == null && error == null) {
        consoleView.print(dumpObject(result), ConsoleViewContentType.NORMAL_OUTPUT)
      }
    }
    (consoleView as ConsoleViewImpl).requestScrollingToEnd()
  }

  private fun initRepl() {
    consoleView.prompt = ((repl.clientInfo["aux"] as? Map<*, *>)?.get("current-ns") as? String)?.let { "$it=> " } ?: "=> "
    val onCompleted: (Map<String, Any?>, Throwable) -> Unit = { _, throwable ->
      onCommandCompleted(null, throwable)
    }
    repl.evalAsync("(when (clojure.core/resolve 'clojure.main/repl-requires)" +
        " (clojure.core/map clojure.core/require clojure.main/repl-requires))").whenComplete(onCompleted)
  }

  private fun dumpReplInfo() {
    consoleView.println("session description:\n${dumpObject(repl.clientInfo)}")
  }

}

fun newProcessHandler(project: Project, projectDir: File?): ProcessHandler {
  val workingDir = projectDir ?: project.baseDir.toIoFile()
  val port = try { FileUtil.loadFile(File(workingDir, ".nrepl-port")).trim().toInt() } catch (e: Exception) { -1 }
  val addressStr = if (port > 0) "Connected to nREPL server running on port $port and host localhost" else null

  return try { addressStr?.let { newRemoteProcess(it, true) } } catch(e: Exception) { null }
      ?: newLocalProcess(workingDir)
}


fun newLocalProcess(workingDir: File): ProcessHandler {
  val callback = ActionCallback()
  val repl = NReplClient()
  val tool = Tool.find(workingDir) ?: Lein
  return OSProcessHandler(tool.getRepl().withWorkDirectory(workingDir.path)).apply {
    ProcessTerminatedListener.attach(this)
    putUserData(NREPL_CLIENT_KEY, ReplConnection(repl, this, callback))
    addProcessListener(object : ProcessAdapter() {
      override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        val text = event.text ?: return
        if (!text.startsWith("nREPL server started on port ")) return
        (event.source as ProcessHandler).removeProcessListener(this)
        executeTask {
          try {
            repl.connect(text)
            callback.setDone()
          }
          catch(e: Exception) {
            onTextAvailable(ProcessEvent(this@apply, ExceptionUtil.getThrowableText(e)), ProcessOutputTypes.STDERR)
            callback.setRejected()
          }
        }
      }
    })
    addProcessListener (object : ProcessAdapter() {
      override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
        repl.disconnect()
      }
    })
  }
}

fun newRemoteProcess(addressString: String, canDestroy: Boolean): ProcessHandler {
  val callback = ActionCallback()
  val repl = NReplClient()
  repl.connect(addressString)

  val emptyIn = object : InputStream() { override fun read() = -1 }
  val emptyOut = object : OutputStream() { override fun write(b: Int) = Unit }
  val pingLock = Object()
  val process = object : RemoteProcess() {

    override fun destroy() {
      if (canDestroy) {
        repl.evalAsync("(System/exit 0)", repl.toolSession)
            .handle { t, u -> repl.disconnect() }
      }
      else {
        repl.disconnect()
      }
    }

    override fun exitValue(): Int {
      if (repl.isConnected) throw IllegalThreadStateException()
      else return 0
    }

    override fun isDisconnected() = !repl.isConnected

    override fun waitFor(): Int {
      while (repl.isConnected) {
        repl.ping()
        if (!repl.isConnected) break
        synchronized(pingLock) { pingLock.wait(1000L) }
      }
      return exitValue()
    }

    override fun getLocalTunnel(remotePort: Int) = null
    override fun getOutputStream() = emptyOut
    override fun getErrorStream() = emptyIn
    override fun getInputStream() = emptyIn
    override fun killProcessTree() = false
  }
  return object : BaseRemoteProcessHandler<RemoteProcess>(process, addressString, null) {
    override fun startNotify() {
      super.startNotify()
      callback.setDone()
    }

    override fun isSilentlyDestroyOnClose() = !canDestroy

    override fun detachIsDefault() = true

    override fun detachProcessImpl() {
      executeTask {
        repl.disconnect()
        synchronized(pingLock) { pingLock.notifyAll() }
      }
      super.detachProcessImpl()
    }
  }.apply {
    ProcessTerminatedListener.attach(this)
    putUserData(NREPL_CLIENT_KEY, ReplConnection(repl, this, callback))
  }
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
