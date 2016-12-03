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
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ActionCallback
import com.intellij.openapi.util.Comparing
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiUtilCore
import com.intellij.remote.BaseRemoteProcessHandler
import com.intellij.remote.RemoteProcess
import com.intellij.util.ArrayUtil
import com.intellij.util.ExceptionUtil
import com.intellij.util.containers.JBIterable
import com.intellij.util.text.UniqueNameGenerator
import org.intellij.clojure.lang.ClojureFileType
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.nrepl.NReplClient
import org.intellij.clojure.nrepl.dumpObject
import org.intellij.clojure.psi.CForm
import org.intellij.clojure.psi.ClojureElementType
import org.intellij.clojure.psi.ClojureFile
import org.intellij.clojure.util.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

/**
 * @author gregsh
 */

object ReplConsoleRootType : ConsoleRootType("nrepl", "nREPL Consoles")

private val NREPL_CLIENT_KEY = Key.create<ReplConnection>("NREPL_CLIENT_KEY")
private val EXCLUSIVE_MODE_KEY = Key.create<Boolean>("EXCLUSIVE_MODE_KEY")


class ReplConnectAction : DumbAwareAction() {
  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project
    val regex = "(?:nrepl://)?+(?:(\\S+)\\s*:)?\\s*(\\S+)".toRegex()
    val result = Messages.showInputDialog(project ?: return, "Enter nREPL server address:",
        e.presentation.text, Messages.getQuestionIcon(), null, object : InputValidator {
      override fun checkInput(inputString: String?) = regex.find(inputString ?: "") != null

      override fun canClose(inputString: String?) = checkInput(inputString)
    }) ?: return
    val matcher = regex.find(result) ?: return
    val host = StringUtil.nullize(matcher.groupValues[1], true) ?: "localhost"
    val port = StringUtil.parseInt(matcher.groupValues[2], -1)
    val addressString = "Connected to nREPL server running on port $port and host $host"
    createNewRunContent(project, "REPL [$host:$port]") {
      newRemoteProcess(addressString, false)
    }
  }
}

class ReplExecuteAction : DumbAwareAction() {
  override fun update(e: AnActionEvent) {
    val editor = CommonDataKeys.EDITOR.getData(e.dataContext)
    val console = ConsoleViewImpl.CONSOLE_VIEW_IN_EDITOR_VIEW.get(editor) as? LanguageConsoleView
    val file = (console?.file ?: CommonDataKeys.PSI_FILE.getData(e.dataContext)) as? ClojureFile
    e.presentation.isEnabledAndVisible = e.project != null && file != null && editor != null &&
        ScratchFileService.getInstance().getRootType(file.virtualFile) !is IdeConsoleRootType
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val editor = CommonDataKeys.EDITOR.getData(e.dataContext) ?: return
    val console = ConsoleViewImpl.CONSOLE_VIEW_IN_EDITOR_VIEW.get(editor) as? LanguageConsoleView
    val file = (console?.file ?: CommonDataKeys.PSI_FILE.getData(e.dataContext)) as? ClojureFile ?: return
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val text = if (editor.selectionModel.hasSelection()) {
      editor.selectionModel.getSelectedText(true) ?: ""
    }
    else {
      val text = editor.document.immutableCharSequence
      JBIterable.from(editor.caretModel.allCarets).transform {
        val elementAt = file.findElementAt(
            if (it.offset > 0 && (it.offset >= text.length || Character.isWhitespace(text[it.offset]))) it.offset - 1
            else it.offset)
        elementAt.parents().filter(CForm::class).last()
      }
          .notNulls()
          .reduce(StringBuilder()) { sb, o -> if (!sb.isEmpty()) sb.append("\n"); sb.append(o.text) }
          .toString()
    }
    executeInRepl(project, file, editor, text)
  }
}

class ExclusiveModeToggleAction(val console: LanguageConsoleView)
  : ToggleAction("Exclusive Mode", "Make this REPL an exclusive target for all operations", AllIcons.Welcome.CreateNewProject) {
  override fun isSelected(e: AnActionEvent): Boolean = console.isExclusiveModeOn()

  override fun setSelected(e: AnActionEvent, state: Boolean) {
    ExecutionManager.getInstance(e.project ?: return).contentManager.allDescriptors.forEach {
      NREPL_CLIENT_KEY.get(it.processHandler)?.consoleView?.let {
        EXCLUSIVE_MODE_KEY.set(it.consoleEditor, state && it === console)
      }
    }
  }
}

private fun LanguageConsoleView.isExclusiveModeOn() = EXCLUSIVE_MODE_KEY.get(consoleEditor) == true

fun ConsoleView.println(text: String = "") = print(text + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
fun ConsoleView.printerr(text: String) = print(text + "\n", ConsoleViewContentType.ERROR_OUTPUT)

class ReplActionPromoter : ActionPromoter {
  override fun promote(actions: MutableList<AnAction>, context: DataContext) =
      actions.find { it is ReplExecuteAction }?.let { listOf(it) }
}

fun executeInRepl(project: Project, file: ClojureFile, editor: Editor, text: String) {
  val projectDir = JBIterable.generate(PsiUtilCore.getVirtualFile(file)) { it.parent }.find {
    it.isDirectory && Tool.find(it.toIoFile()) != null }
  val title = if (projectDir == null) "REPL (default)"
  else ProjectFileIndex.SERVICE.getInstance(project).getContentRootForFile(projectDir).let {
    "REPL [${if (it == null || it == projectDir) projectDir.name else VfsUtil.getRelativePath(projectDir, it)}]"
  }

  ExecutionManager.getInstance(project).contentManager.allDescriptors.run {
    find {
      Comparing.equal((it.executionConsole as? LanguageConsoleView)?.virtualFile, PsiUtilCore.getVirtualFile(file))
    } ?: find {
      (it.executionConsole as? LanguageConsoleView)?.isExclusiveModeOn() ?: false
    } ?: find {
      Comparing.equal(title, it.displayName)
    }
  }?.let { content ->
    content.attachedContent!!.run { manager.setSelectedContent(this) }

    NREPL_CLIENT_KEY.get(content.processHandler).run {
      if (processHandler.isProcessTerminating || processHandler.isProcessTerminated) {
        consoleView.println()
        processFactory().run {
          consoleView.attachToProcess(this)
          NREPL_CLIENT_KEY.get(this).let { rcNew ->
            rcNew.processFactory = processFactory
            rcNew.consoleView = consoleView
            startNotify()
            rcNew
          }
        }
      }
      else this
    }.apply {
      content.processHandler = processHandler
      consoleView.consoleEditor.let {
        if (editor == it || !it.document.text.trim().isEmpty() && editor == consoleView.historyViewer) {
          val command = WriteAction.compute<String, Exception> { it.document.run { val s = getText(); setText(""); s } }
          sendCommand(command)
          ConsoleHistoryController.addToHistory(consoleView, command)
        }
        else {
          sendCommand(text)
        }
      }
    }
  } ?: run {
    var initialText: String? = text
    createNewRunContent(project, title) {
      newProcessHandler(project, projectDir?.toIoFile()).apply {
        initialText?.let { text -> NREPL_CLIENT_KEY.get(this).sendCommand(text); initialText = null }
      }
    }
  }
}

private fun createNewRunContent(project: Project, title: String, processFactory: () -> ProcessHandler) {
  val existingDescriptors = ExecutionManager.getInstance(project).contentManager.allDescriptors
  val uniqueTitle = UniqueNameGenerator.generateUniqueName(title, "", "", " (", ")",
      existingDescriptors.map { it.displayName }.toSet().let { { s: String -> !it.contains(s) }})
  object : RunProfile {
    override fun getIcon() = null
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
          }
          return ArrayUtil.mergeArrays(super.createActions(console, processHandler, executor), arrayOf<AnAction>(
              ConsoleHistoryController.getController(console as LanguageConsoleView?).browseHistory,
              ExclusiveModeToggleAction(console)))
        }

        override fun createConsole(executor: Executor) =
            LanguageConsoleImpl(project, uniqueTitle, ClojureLanguage).apply {
              consoleEditor.setFile(virtualFile)
              ConsoleHistoryController(ReplConsoleRootType, title, this).install()
            }
      }
    }
  }.run {
    ExecutionEnvironmentBuilder.create(project, DefaultRunExecutor.getRunExecutorInstance(), this)
        .build().run { ExecutionManager.getInstance(project).restartRunProfile(this) }
  }
}

class ReplConnection(val repl: NReplClient,
                     var processHandler: ProcessHandler,
                     var whenConnected: ActionCallback) {
  lateinit var consoleView: LanguageConsoleView
  lateinit var processFactory: () -> ProcessHandler

  init {
    whenConnected.doWhenDone { dumpReplInfo() }.doWhenDone { initRepl() }
  }

  fun sendCommand(text: String) = whenConnected.doWhenDone {
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
        consoleView.printerr("operations: " + dumpObject(repl.clientInfo["ops"]))
      }
      else {
        val s = cljLightTraverser(text.substring(idx + 1)).expandTypes { it !is ClojureElementType }
        val map = LinkedHashMap<String, String>().apply {
          put("op", op)
          s.traverse().skip(1).split(2, true).forEach { put(s.api.textOf(it[0]).toString(), s.api.textOf(it[1]).toString()) }
        }
        repl.rawAsync(map).whenComplete { result, e ->
          onCommandCompleted(result, e)
        }
      }
    }
    else repl.evalAsync(trimmed).whenComplete { result, e ->
      onCommandCompleted(result, e)
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
      val error = "${result["ex"]?.let { "$it\n" } ?: ""}${result["err"] ?: ""}${result["out"] ?: ""}".run { if (length == 0) null else this }
      value?.let {
        consoleView.print(dumpObject(it), ConsoleViewContentType.NORMAL_OUTPUT)
      }
      error?.let { msg ->
        val adjusted = msg.indexOf(", compiling:(").let { if (it == -1) msg else msg.substring(0, it) + "\n" + msg.substring(it + 2) }
        if (value != null) consoleView.println()
        consoleView.print(adjusted, ConsoleViewContentType.ERROR_OUTPUT)
      }
      if (value == null && error == null) {
        consoleView.print(dumpObject(result), ConsoleViewContentType.NORMAL_OUTPUT)
      }
    }
    (consoleView as ConsoleViewImpl).requestScrollingToEnd()
  }

  private fun initRepl() {
    consoleView.prompt = ((repl.clientInfo["aux"] as? Map<*, *>)?.get("current-ns") as? String)?.let { "$it=> " } ?: "=> "
    repl.evalAsync("(when (clojure.core/resolve 'clojure.main/repl-requires)" +
        " (clojure.core/map clojure.core/require clojure.main/repl-requires))").whenComplete { map, throwable ->
      onCommandCompleted(null, throwable)
    }
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

      override fun processWillTerminate(event: ProcessEvent?, willBeDestroyed: Boolean) {
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
    connect(host, port)
  }
}
