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

import com.intellij.execution.ExecutionManager
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.console.IdeConsoleRootType
import com.intellij.execution.console.LanguageConsoleImpl
import com.intellij.execution.console.LanguageConsoleView
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.impl.ConsoleViewUtil
import com.intellij.execution.process.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.ActionCallback
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiUtilCore
import com.intellij.remote.BaseRemoteProcessHandler
import com.intellij.remote.RemoteProcess
import com.intellij.util.ExceptionUtil
import com.intellij.util.containers.JBIterable
import org.intellij.clojure.lang.ClojureFileType
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.nrepl.NReplClient
import org.intellij.clojure.nrepl.dumpObject
import org.intellij.clojure.psi.CForm
import org.intellij.clojure.psi.ClojureElementType
import org.intellij.clojure.psi.ClojureFile
import org.intellij.clojure.util.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.*

/**
 * @author gregsh
 */
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

fun ConsoleView.println(text: String = "") = print(text + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
fun ConsoleView.printerr(text: String) = print(text + "\n", ConsoleViewContentType.ERROR_OUTPUT)

class ReplActionPromoter : ActionPromoter {
  override fun promote(actions: MutableList<AnAction>, context: DataContext) =
      actions.find { it is ReplExecuteAction }?.let { listOf(it) }
}

fun executeInRepl(project: Project, file: ClojureFile, editor: Editor, text: String) {
  val projectDir = JBIterable.generate(PsiUtilCore.getVirtualFile(file)) { it.parent }.find {
    it.isDirectory && Tool.find(it.toIoFile()) != null
  }
  ExecutionManager.getInstance(project).contentManager.allDescriptors.run {
    find { (it.executionConsole as? LanguageConsoleView)?.virtualFile == PsiUtilCore.getVirtualFile(file) } ?:
        find { projectDir == ReplConnection.getFor(it)?.projectDir }
  }?.apply {
    val rc = ReplConnection.getFor(this)!!.apply { ensureStarted() }
    processHandler = rc.processHandler
    attachedContent!!.run { manager.setSelectedContent(this) }
    rc.consoleView.consoleEditor.let {
      if (editor == it || !it.document.text.trim().isEmpty() && editor == rc.consoleView.historyViewer) {
        val command = WriteAction.compute<String, Exception> { it.document.run { val s = getText(); setText(""); s } }
        rc.sendCommand(command)
      }
      else {
        rc.sendCommand(text)
      }
    }
    return
  }

  val title = if (projectDir == null) "Clojure REPL"
  else ProjectFileIndex.SERVICE.getInstance(project).getContentRootForFile(projectDir).let {
    if (it == null || it == projectDir) "[${projectDir.name}] REPL" else "[${VfsUtil.getRelativePath(projectDir, it)}] REPL"
  }

  val env = ExecutionEnvironmentBuilder.create(project, DefaultRunExecutor.getRunExecutorInstance(),
      object : RunProfile {
        var initialText: String? = text
        override fun getState(executor: Executor, env: ExecutionEnvironment): RunProfileState {
          return object : CommandLineState(env) {
            val connection = ReplConnection(projectDir, newConsoleView(project, title))
            override fun startProcess(): ProcessHandler {
              connection.ensureStarted()
              initialText?.apply { initialText = null; connection.sendCommand(this) }

              return connection.processHandler!!
            }

            override fun createConsole(executor: Executor) = connection.consoleView
          }
        }

        override fun getIcon() = null
        override fun getName() = title
      })
      .build()
  ExecutionManager.getInstance(project).restartRunProfile(env)
}

private fun newConsoleView(project: Project, title: String): LanguageConsoleImpl {
  return LanguageConsoleImpl(project, title, ClojureLanguage).apply {
    consoleEditor.setFile(virtualFile)
  }
}

private val CONNECTION_KEY: Key<ReplConnection> = Key.create("CONNECTION_KEY")

open class ReplConnection(val projectDir: VirtualFile?, val consoleView: LanguageConsoleView) {
  val repl = NReplClient()
  var whenConnected = ActionCallback().doWhenDone { dumpReplInfo() }
  var processHandler: ProcessHandler? = null

  companion object {
    fun getFor(descriptor: RunContentDescriptor?): ReplConnection? =
        (descriptor?.executionConsole as? LanguageConsoleView)?.consoleEditor?.getUserData(CONNECTION_KEY)
  }

  init {
    @Suppress("LeakingThis")
    consoleView.consoleEditor.putUserData(CONNECTION_KEY, this@ReplConnection)
  }

  fun ensureStarted() {
    processHandler?.apply {
      if (isProcessTerminated || isProcessTerminated) {
        consoleView.println()
        processHandler = null
        whenConnected = ActionCallback()
      }
      else return
    }

    processHandler = newProcessHandler()?.apply {
      ProcessTerminatedListener.attach(this)
      consoleView.attachToProcess(this)
      consoleView.isEditable = true
      addProcessListener(object : ProcessAdapter() {
        override fun processTerminated(event: ProcessEvent?) {
          consoleView.isEditable = false
          repl.disconnect()
        }
      })

    }
  }

  protected open fun newProcessHandler(): ProcessHandler? {
    val workingDir = (projectDir ?: consoleView.project.baseDir).toIoFile()
    val port = try { FileUtil.loadFile(File(workingDir, ".nrepl-port")).trim().toInt() } catch (e: Exception) { -1 }
    val existingRemoteStr = if (port > 0) "Connected to nREPL server running on port $port and host localhost" else null

    return newRemoteProcess(existingRemoteStr) ?: newLocalProcess(workingDir)
  }

  protected fun newLocalProcess(workingDir: File): ProcessHandler {
    val tool = Tool.find(workingDir) ?: Lein
    return OSProcessHandler(tool.getRepl().withWorkDirectory(workingDir.path)).apply {
      addProcessListener(object : ProcessAdapter() {
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
          val text = event.text ?: return
          if (!text.startsWith("nREPL server started on port ")) return
          (event.source as ProcessHandler).removeProcessListener(this)
          executeTask {
            if (connect(text)) whenConnected.setDone() else whenConnected.setRejected()
          }
        }
      })
    }
  }

  private fun newRemoteProcess(addressString : String?): ProcessHandler? {
    if (addressString == null || !connect(addressString)) return null
    val emptyIn = object : InputStream() { override fun read() = -1 }
    val emptyOut = object : OutputStream() { override fun write(b: Int) = Unit }
    val pingLock = Object()
    val process = object : RemoteProcess() {

      override fun destroy() {
        repl.evalAsync("(System/exit 0)", repl.toolSession)
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
          synchronized(pingLock) { pingLock.wait(5000L) }
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
      override fun detachIsDefault() = true

      override fun detachProcessImpl() {
        executeTask {
          repl.disconnect()
          synchronized(pingLock) { pingLock.notifyAll() }
        }
        super.detachProcessImpl()
      }

      override fun startNotify() {
        super.startNotify()
        whenConnected.setDone()
      }
    }
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

  private fun connect(portHost: String): Boolean {
    val remote = portHost.startsWith("Connected")
    val matcher = "port (\\S+).* host (\\S+)".toRegex().find(portHost) ?: return false
    try {
      repl.connect(matcher.groupValues[2], StringUtil.parseInt(matcher.groupValues[1], -1))
      consoleView.prompt = ((repl.clientInfo["aux"] as? Map<*, *>)?.get("current-ns") as? String)?.let { "$it=> " } ?: "=> "
      repl.evalAsync("(when (clojure.core/resolve 'clojure.main/repl-requires)" +
          " (clojure.core/map clojure.core/require clojure.main/repl-requires))").whenComplete { map, throwable ->
        onCommandCompleted(null, throwable)
      }
    }
    catch(e: Exception) {
      if (!remote) {
        consoleView.printerr(ExceptionUtil.getThrowableText(e))
      }
    }
    return repl.isConnected
  }

  private fun dumpReplInfo() {
    consoleView.println("session description:\n${dumpObject(repl.clientInfo)}")
  }

}
