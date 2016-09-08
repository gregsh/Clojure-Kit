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

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.*
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.*
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.*
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.ProjectScope
import com.intellij.util.EnvironmentUtil
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.JBIterable
import com.intellij.util.io.SafeFileOutputStream
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.parser.ClojureLexer
import org.intellij.clojure.parser.ClojureTokens.wsOrComment
import org.intellij.clojure.psi.ClojureFile
import org.intellij.clojure.psi.ClojureTypes
import org.intellij.clojure.util.notNulls
import java.io.File
import java.io.PrintWriter
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @author gregsh
 */
object Lein {
  val ourLocalRepo = File(File(com.intellij.util.SystemProperties.getUserHome(), ".m2"), "repository")
  val ourLeinPath = (EnvironmentUtil.getValue("PATH") ?: "").split(File.pathSeparator).mapNotNull {
    val path = "$it${File.separator}lein${if (SystemInfo.isWindows) ".bat" else ""}"
    if (File(path).exists()) path else null
  }.firstOrNull() ?: "lein"
}

private class ClojureProjectDeps(val project: Project) {
  class PostStartup : StartupActivity {
    override fun runActivity(project: Project) {
      ClojureProjectDeps.getInstance(project).initialize()
    }
  }

  class RootsProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibrarySourceRoots(project: Project): Set<VirtualFile> {
      if (ApplicationManager.getApplication().isUnitTestMode) return emptySet()
      if (!Lein.ourLocalRepo.exists()) return emptySet()
      return ClojureProjectDeps.getInstance(project).allDependencies
    }
  }

  companion object {
    @JvmStatic
    fun getInstance(project: Project) = ServiceManager.getService(project, ClojureProjectDeps::class.java)!!
    @JvmStatic
    val LOG = Logger.getInstance(ClojureProjectDeps::class.java)
  }

  val cacheFile = File(PathManager.getSystemPath(), "clojure/${project.locationHash}-deps.txt")
  val mapping: MutableMap<String, List<String>> = ContainerUtil.newConcurrentMap()
  val allDependencies: Set<VirtualFile> get() = JBIterable.from(mapping.values)
      .flatten { it }
      .transform { gavToJar(it) }
      .notNulls()
      .addAllTo(LinkedHashSet<VirtualFile>())
  var resolveInProgress = AtomicBoolean(true)

  fun reindex() = TransactionGuard.getInstance().submitTransactionLater(project, Runnable {
    WriteAction.run<Exception> {
      ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), false, true)
      resolveInProgress.set(false)
    }
  })

  fun initialize() {
    if (cacheFile.exists()) {
      try {
        read(cacheFile)
      }
      finally {
        reindex()
      }
    }
    else {
      resolveAllDepsInBackground()
    }
  }

  private fun read(cacheFile: File) {
    LOG.info("reading ${cacheFile.path}")
    mapping.putAll(cacheFile.bufferedReader().useLines { seq ->
      val map = HashMap<String, List<String>>()
      var file = ""
      val list = ArrayList<String>()
      for (line in seq) {
        val trimmed = line.trimEnd()
        if (trimmed.endsWith("]")) list.add(trimmed)
        else if (trimmed.endsWith(".clj")) {
          if (file != "") map.put(file, ArrayList(list))
          file = trimmed
          list.clear()
        }
      }
      map
    })
  }

  private fun write(cacheFile: File) {
    LOG.info("writing ${cacheFile.path}")
    cacheFile.mkdirs()
    PrintWriter(SafeFileOutputStream(cacheFile)).use {
      for ((key, value) in TreeMap(mapping)) {
        it.println(key)
        for (s in value) {
          it.println(s)
        }
      }
    }
  }

  fun resolveAllDepsInBackground() {
    resolveDepsInBackground { mapping.clear(); allProjectFiles(project) }
  }

  fun resolveDepsInBackground(filesGetter: () -> Collection<File>) {
    resolveInProgress.set(true)
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Resolve project dependencies", false) {
      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        DumbService.getInstance(myProject).waitForSmartMode()

        val files: Collection<File> = filesGetter()
        for ((index, file) in files.withIndex()) {
          indicator.isIndeterminate = false
          indicator.fraction = (100.0 * index / files.size)
          indicator.text2 = file.path
          val list = ArrayList<String>()
          runLeinDeps(file.parentFile.path) { gav ->
            if (gav == null) {
              mapping[file.path] = ArrayList(list)
            }
            else {
              list.add(gav)
            }
          }.waitFor()
        }
      }

      override fun onFinished() {
        try {
          write(cacheFile)
        }
        finally {
          reindex()
        }
      }
    })
  }

}

private fun runLeinDeps(workingDir: String, consumer: (String?) -> Unit): ProcessHandler {
  val cmd = GeneralCommandLine(Lein.ourLeinPath, "deps", ":tree")
      .withWorkDirectory(FileUtil.toSystemDependentName(workingDir))
      .withCharset(CharsetToolkit.UTF8_CHARSET)
  val process = OSProcessHandler(cmd)
  process.addProcessListener(object : ProcessAdapter() {
    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>?) {
      if (outputType == ProcessOutputTypes.STDOUT) {
        extractGAV(event.text.trimEnd())
      }
    }

    override fun processTerminated(event: ProcessEvent) {
      consumer(null)
    }

    private fun extractGAV(text: String) {
      if (text.endsWith("]")) {
        consumer(text)
      }
    }
  })
  process.startNotify()
  return process
}

private fun gavToJar(gav: String) : VirtualFile? {
  val lexer = ClojureLexer(ClojureLanguage)
  lexer.start(gav)
  while (lexer.tokenType != null && lexer.tokenType != ClojureTypes.C_SYM) lexer.advance()
  if (lexer.tokenType != ClojureTypes.C_SYM) return null
  val i1 = lexer.tokenStart
  while (lexer.tokenType != null && lexer.tokenType != ClojureTypes.C_STRING && !wsOrComment(lexer.tokenType)) lexer.advance()
  val artifact = gav.substring(i1, lexer.tokenStart)
  while (lexer.tokenType != null && lexer.tokenType != ClojureTypes.C_STRING) lexer.advance()
  val version = if (lexer.tokenType == ClojureTypes.C_STRING) lexer.tokenText.trim('\"') else ""

  val (path, name) = if (artifact.contains("/")) artifact.indexOf("/").let { Pair(artifact.substring(0, it).replace('.', '/') + artifact.substring(it), artifact.substring(it)) }
  else Pair("$artifact/$artifact", artifact)
  val gavFile = File(Lein.ourLocalRepo, "$path/$version/$name-$version.jar")
  if (!(gavFile.exists() && !gavFile.isDirectory)) {
    ClojureProjectDeps.LOG.info("$name:$version dependency not found")
  }
  else {
    val localFS = LocalFileSystem.getInstance()
    val jarFS = JarFileSystem.getInstance()
    val vFile = localFS.refreshAndFindFileByIoFile(gavFile)
    val jarFile = if (vFile == null) null else jarFS.getJarRootForLocalFile(vFile)
    if (jarFile == null) {
      ClojureProjectDeps.LOG.info("$name:$version dependency not found in VFS")
    }
    else {
      return jarFile
    }
  }
  return null
}

private fun allProjectFiles(project: Project): Collection<File> {
  return ReadAction.compute<Collection<File>, RuntimeException> {
    FilenameIndex.getFilesByName(project, ClojureConstants.LEIN_PROJECT_CLJ, ProjectScope.getContentScope(project), false)
        .asSequence()
        .filter { it is ClojureFile }
        .map { VfsUtil.virtualToIoFile(it.virtualFile) }
        .toSortedSet()
  }
}

class SyncDepsAction : AnAction() {
  override fun update(e: AnActionEvent) = updateSyncActionImpl(e)

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val file = VfsUtil.virtualToIoFile((e.getData(CommonDataKeys.PSI_FILE) as? ClojureFile ?: return).virtualFile)
    ClojureProjectDeps.getInstance(project).resolveDepsInBackground { listOf(file) }
  }
}

class SyncAllDepsAction : AnAction() {
  override fun update(e: AnActionEvent) = updateSyncActionImpl(e)

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    ClojureProjectDeps.getInstance(project).resolveAllDepsInBackground()
  }
}

private fun updateSyncActionImpl(e: AnActionEvent) {
  val file = e.getData(CommonDataKeys.PSI_FILE) as? ClojureFile
  val visible = e.project != null && file?.name == ClojureConstants.LEIN_PROJECT_CLJ
  val enabled = visible && !ClojureProjectDeps.getInstance(e.project!!).resolveInProgress.get()
  e.presentation.isVisible = visible
  e.presentation.isEnabled = enabled
}
