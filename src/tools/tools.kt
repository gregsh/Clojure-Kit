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
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.CharsetToolkit
import com.intellij.util.EnvironmentUtil
import org.intellij.clojure.ClojureConstants
import java.io.File

/**
 * @author gregsh
 */

private val LOG = Logger.getInstance(Tool::class.java)
const val DEPS_NOTIFICATION = "clojure-kit.tool.deps.group"

object Repo {
  val path = File(File(com.intellij.util.SystemProperties.getUserHome(), ".m2"), "repository")
}


interface Tool {
  fun getDeps(projectFile: File): List<Dependency>
  fun getRepl(): GeneralCommandLine

  companion object {
    fun choose(file: File) = choose(file.name)
    fun choose(fileName: String) = when (fileName) {
      Lein.projectFile -> Lein
      Boot.projectFile -> Boot
      Deps.projectFile -> Deps
      else -> null
    }

    fun find(dir: File) = when {
      File(dir, Lein.projectFile).exists() -> Lein
      File(dir, Boot.projectFile).exists() -> Boot
      File(dir, Deps.projectFile).exists() -> Deps
      else -> null
    }
  }
}

object Lein : Tool {
  val projectFile = ClojureConstants.LEIN_CONFIG
  private val command = findCommandPath("lein")

  override fun getDeps(projectFile: File) = readProcessOutput(
      GeneralCommandLine(command, "deps", ":tree"), projectFile.parent)
      .mapNotNull(::parseCoordVector)


  override fun getRepl() = GeneralCommandLine(command, *mutableListOf(
      "update-in", ":dependencies", "conj", "[nrepl \"RELEASE\"]", "--",
      "update-in", ":plugins", "conj", "[cider/cider-nrepl \"RELEASE\"]", "--",
      "update-in", ":nrepl-middleware", "conj ", "[cider-nrepl.plugin/middleware \"RELEASE\"]", "--")
      .apply {
        val vmOpts = System.getProperty(ClojureConstants.LEIN_VM_OPTS)
        if (vmOpts != null) {
          addAll(listOf("update-in", ":jvm-opts", "into", "[\"$vmOpts\"]", "--"))
        }
        addAll(listOf("repl", ":headless"))
      }.toTypedArray())
}

object Boot : Tool {
  val projectFile = ClojureConstants.BOOT_CONFIG
  private val command = findCommandPath("boot")

  override fun getDeps(projectFile: File) = readProcessOutput(
      GeneralCommandLine(command, "--no-colors", "show", "-d"), projectFile.parent)
      .mapNotNull {
        val trimmed = it.trimEnd()
        val idx = trimmed.indexOf("[")
        if (idx != -1 && trimmed.endsWith("]")) {
          val coordVec = StringUtil.repeat(" ", idx) + trimmed.substring(idx)
          parseCoordVector(coordVec)
        }
        else null
      }

  override fun getRepl() = GeneralCommandLine(command,
      "--no-colors",
      "-d", "nrepl",
      "-d", "cider/cider-nrepl",
      "repl", "-m", "cider.nrepl/cider-middleware", "-s", "wait")
}

object Deps : Tool {
  val projectFile = ClojureConstants.DEPS_CONFIG
  private val command = findCommandPath("clojure")

  override fun getDeps(projectFile: File) = readProcessOutput(
      GeneralCommandLine(command, "-Stree"), projectFile.parent)
      .mapNotNull { line ->
        Regex("(?:\\. )?(.*)/(.*) (.*)").matchEntire(line.trim())?.let {
          val (group, artifact, version) = it.destructured
          Dependency(group, artifact, version)
        }
      }

  override fun getRepl() = GeneralCommandLine(command,
      "-Sdeps", "{:deps { nrepl {:mvn/version \"RELEASE\"}" +
      "                   cider/cider-nrepl {:mvn/version \"RELEASE\"}}}",
      "--eval", "(do (use '[nrepl.server :only (start-server stop-server)])" +
      "              (use '[cider.nrepl :only (cider-nrepl-handler)])" +
      "              (println (str \"nREPL server started on port \" (:port (start-server :handler cider-nrepl-handler)) \" host localhost\")))")

}


private fun readProcessOutput(commandLine: GeneralCommandLine, workingDirectory: String): List<String> {
  val stdout = mutableListOf<String>()
  val stderr = mutableListOf<String>()
  var exitCode: Int? = null
  val process = try {
    OSProcessHandler(commandLine.withWorkDirectory(workingDirectory).withCharset(CharsetToolkit.UTF8_CHARSET))
  }
  catch (e: Exception) {
    val message = e.message ?: e.toString()
    Notifications.Bus.notify(Notification(DEPS_NOTIFICATION, "", message, NotificationType.ERROR))
    throw e
  }
  process.addProcessListener(object : ProcessAdapter() {
    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
      when (outputType) {
        ProcessOutputTypes.STDOUT -> stdout.add(event.text)
        ProcessOutputTypes.STDERR -> stderr.add(event.text)
      }
    }

    override fun processTerminated(event: ProcessEvent) {
      exitCode = event.exitCode
    }
  })
  LOG.info("${commandLine.commandLineString} (in $workingDirectory)")
  process.startNotify()
  process.waitFor()
  LOG.info("${commandLine.commandLineString} (exit code: $exitCode)")
  if (exitCode == null || exitCode != 0) {
    val title = commandLine.exePath + ": exit code " + exitCode
    val message = if (stderr.isEmpty()) "" else stderr.joinToString("")
    Notifications.Bus.notify(Notification(DEPS_NOTIFICATION, title, message, NotificationType.ERROR))
    throw ExecutionException(title)
  }
  return stdout
}

private fun findCommandPath(commandName: String): String {
  return (EnvironmentUtil.getValue("PATH") ?: "").split(File.pathSeparator).mapNotNull {
    val path = "$it${File.separator}$commandName${if (SystemInfo.isWindows) ".bat" else ""}"
    if (File(path).exists()) path else null
  }.firstOrNull() ?: commandName
}
