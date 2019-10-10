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
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.*
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.ProjectScope
import com.intellij.util.io.SafeFileOutputStream
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.lang.usages.CljLib
import org.intellij.clojure.parser.ClojureLexer
import org.intellij.clojure.psi.ClojureTypes
import org.intellij.clojure.util.*
import java.io.File
import java.io.PrintWriter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @author gregsh
 */

private val LOG = Logger.getInstance(ClojureProjectDeps::class.java)

private class ClojureProjectDeps(val project: Project) {
  class PostStartup : StartupActivity {
    override fun runActivity(project: Project) {
      getInstance(project).initialize()
    }
  }

  class RootsProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): MutableCollection<SyntheticLibrary> {
      if (ApplicationManager.getApplication().isUnitTestMode) return Collections.emptyList()
      if (!Repo.path.exists()) return Collections.emptyList()
      return getInstance(project).allDependencies.jbIt().map { CljLib(it) }.addAllTo(ArrayList())
    }
  }

  companion object {
    @JvmStatic
    fun getInstance(project: Project) = ServiceManager.getService(project, ClojureProjectDeps::class.java)!!
  }

  val cacheFile = File(PathManager.getSystemPath(), "clojure/deps-${project.locationHash}.txt")
  val mapping: MutableMap<String, List<Dependency>> = ConcurrentHashMap()
  val allDependencies: Set<VirtualFile> get() = mapping.values.jbIt()
      .flatten { it }
      .transform(::resolveDependency)
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
      val map = HashMap<String, List<Dependency>>()
      var file : String? = null
      val list = ArrayList<Dependency>()
      for (line in seq) {
        val trimmed = line.trimEnd()
        if (trimmed.endsWith("]")) parseCoordVector(trimmed)?.let { list.add(it)}
        else if (Tool.choose(File(trimmed)) != null) {
          if (file != null) map[file] = ArrayList(list)
          file = trimmed
          list.clear()
        }
      }
      if (file != null) map[file] = ArrayList(list)
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
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Resolving project dependencies...", false) {
      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        DumbService.getInstance(myProject).waitForSmartMode()

        val files: Collection<File> = filesGetter()
        for ((index, file) in files.withIndex()) {
          indicator.isIndeterminate = false
          indicator.fraction = (100.0 * index / files.size)
          indicator.text = file.path
          val tool = Tool.choose(file) ?: continue
          try {
            mapping[file.path] = tool.getDeps(file)
          }
          catch (e: ExecutionException) { }
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

data class Dependency(val group: String?, val artifact: String, val version: String) {
  override fun toString(): String {
    if (group != null) {
      return "[$group/$artifact \"$version\"]"
    }
    return "[$artifact \"$version\"]"
  }
}

fun parseCoordVector(line: String): Dependency? {
  val lexer = ClojureLexer(ClojureLanguage)
  lexer.start(line)
  while (lexer.tokenType != null && lexer.tokenType != ClojureTypes.C_SYM) lexer.advance()
  if (lexer.tokenType != ClojureTypes.C_SYM) return null
  val i1 = lexer.tokenStart
  while (lexer.tokenType != null && lexer.tokenType != ClojureTypes.C_STRING && !lexer.tokenType.wsOrComment()) lexer.advance()
  val libName = line.substring(i1, lexer.tokenStart)
  while (lexer.tokenType != null && lexer.tokenType != ClojureTypes.C_STRING) lexer.advance()
  val version = if (lexer.tokenType == ClojureTypes.C_STRING) lexer.tokenText.trim('\"') else ""

  return if (libName.contains("/")) {
    val idx = libName.indexOf("/")
    Dependency(libName.substring(0, idx), libName.substring(idx + 1), version)
  }
  else Dependency(null, libName, version)
}

private fun resolveDependency(dependency: Dependency) : VirtualFile? {
  val path = if (dependency.group != null) {
    "${dependency.group.replace('.', '/')}/${dependency.artifact}"
  }
  else {
    "${dependency.artifact}/${dependency.artifact}"
  }

  val gavFile = File(Repo.path, "$path/${dependency.version}/${dependency.artifact}-${dependency.version}.jar")
  if (!(gavFile.exists() && !gavFile.isDirectory)) {
    LOG.info("${dependency.artifact}:${dependency.version} dependency not found")
  }
  else {
    val localFS = LocalFileSystem.getInstance()
    val jarFS = JarFileSystem.getInstance()
    val vFile = localFS.refreshAndFindFileByIoFile(gavFile)
    val jarFile = if (vFile == null) null else jarFS.getJarRootForLocalFile(vFile)
    if (jarFile == null) {
      LOG.info("${dependency.artifact}:${dependency.version} dependency not found in VFS")
    }
    else {
      return jarFile
    }
  }
  return null
}

private fun allProjectFiles(project: Project) = ReadAction.compute<Collection<File>, RuntimeException> {
  val contentScope = ProjectScope.getContentScope(project)
  FilenameIndex.getFilesByName(project, Lein.projectFile, contentScope, false).iterate()
      .append(FilenameIndex.getFilesByName(project, Boot.projectFile, contentScope, false))
      .append(FilenameIndex.getFilesByName(project, Deps.projectFile, contentScope, false))
      .transform { it.virtualFile.toIoFile() }
      .toSortedSet()
}

private fun contextProjectFiles(e: AnActionEvent) = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).iterate()
    .filter { Tool.choose(it.name) != null }
    .transform { it.toIoFile() }
    .toSortedSet()

class SyncDepsAction : AnAction() {
  override fun update(e: AnActionEvent) = updateSyncActionImpl(e, false)

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    FileDocumentManager.getInstance().saveAllDocuments()
    val files = contextProjectFiles(e)
    ClojureProjectDeps.getInstance(project).resolveDepsInBackground { files }
  }
}

class SyncAllDepsAction : AnAction() {
  override fun update(e: AnActionEvent) = updateSyncActionImpl(e, true)

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    FileDocumentManager.getInstance().saveAllDocuments()
    ClojureProjectDeps.getInstance(project).resolveAllDepsInBackground()
  }
}

internal fun updateSyncActionImpl(e: AnActionEvent, syncAll: Boolean) {
  val project = e.project
  if (project != null) {
    val busy = ClojureProjectDeps.getInstance(project).resolveInProgress.get()
    val files = contextProjectFiles(e)
    e.presentation.isVisible = !files.isEmpty() || ActionPlaces.isMainMenuOrActionSearch(e.place)
    e.presentation.isEnabled = (!files.isEmpty() || syncAll) && !busy
  }
  else {
    e.presentation.isEnabledAndVisible = false
  }
}