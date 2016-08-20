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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.RecursionManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.CachedValueProvider.Result
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.JBIterable
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.psi.CLiteral
import org.intellij.clojure.psi.CSymbol
import org.intellij.clojure.psi.CVec
import org.intellij.clojure.psi.ClojureFile
import org.intellij.clojure.util.cljTraverser
import org.intellij.clojure.util.filter
import org.intellij.clojure.util.iterateForms
import org.intellij.clojure.util.sort
import java.io.File
import java.security.MessageDigest

/**
 * @author gregsh
 */
class LeinLibraryRootsProvider : AdditionalLibraryRootsProvider() {

  companion object {
    val LOG = Logger.getInstance(LeinLibraryRootsProvider::class.java)
    val ourTypeGuard = RecursionManager.createGuard("leinProject")!!
    val ourLocalRepo = File(File(com.intellij.util.SystemProperties.getUserHome(), ".m2"), "repository")

    val ourLeinProjectFiles: Key<String> = Key.create("leinProjectFiles")
  }


  override fun getAdditionalProjectLibrarySourceRoots(project: Project): Set<VirtualFile> {
    if (ApplicationManager.getApplication().isUnitTestMode) return emptySet<VirtualFile>()
    if (!ourLocalRepo.exists()) return emptySet<VirtualFile>()
    val result = ContainerUtil.newHashSet<VirtualFile>()
    val leinFiles = ContainerUtil.newTreeSet<String>()
    val markStack = ourTypeGuard.markStack()
    ourTypeGuard.doPreventingRecursion(project, false) {
      ApplicationManager.getApplication().runReadAction {
        FilenameIndex.processFilesByName(ClojureConstants.LEIN_PROJECT_CLJ, false, {
          ProgressManager.checkCanceled()
          if (it is ClojureFile) {
            leinFiles.add(it.virtualFile.path)
            result.addAll(CachedValuesManager.getCachedValue(it, { Result(it.collectRoots(), it) }))
          }
          true
        }, ProjectScope.getContentScope(project), project, null)
      }
    }
    if (markStack.mayCacheNow()) {
      val stamp = project.getUserData(ourLeinProjectFiles)
      val sortedSet = JBIterable.from(result).transform { it.path }.sort()
      val digest = MessageDigest.getInstance("MD5")
      val newStamp = StringUtil.toHexString(sortedSet.reduce(digest) { md5, name -> md5.update(name.toByteArray()); md5 }.digest())
      if (stamp != newStamp) {
        leinFiles.forEach { path -> LOG.info("include: $path") }
        sortedSet.forEach { path -> LOG.info("provide: $path") }
        project.putUserData(ourLeinProjectFiles, newStamp)
        TransactionGuard.getInstance().submitTransactionLater(project, Runnable {
          WriteAction.run<Exception> {
            ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(EmptyRunnable.getInstance(), false, true)
          }
        })
      }
    }
    return result
  }

  private fun ClojureFile.collectRoots(): Set<VirtualFile> {
    val localFS = LocalFileSystem.getInstance()
    val jarFS = JarFileSystem.getInstance()
    val files = linkedSetOf<VirtualFile>()
    for (vec in cljTraverser().traverse().filter(CVec::class)) {
      val content = vec.iterateForms()
      val name = (content.get(0) as? CSymbol)?.qualifiedName ?: continue
      val version = (content.get(1) as? CLiteral)?.text?.trim('\"') ?: continue
      val path1 = (if (name.contains("/")) name.indexOf("/").let { name.substring(0, it).replace('.', File.separatorChar) + name.substring(it) }
      else "$name/$name").replace('/', File.separatorChar)
      val gaFile = File(ourLocalRepo, path1)
      val gavFile = File(gaFile, version)
      if (!(gavFile.exists() && gavFile.isDirectory)) {
        LOG.info("$name:$version dependency not found")
      }
      else {
        for (jar in gavFile.listFiles()) {
          if (jar.isFile && jar.extension == "jar") {
            val vFile = localFS.refreshAndFindFileByIoFile(jar)
            val jarFile = if (vFile == null) null else jarFS.getJarRootForLocalFile(vFile)
            if (jarFile == null) {
              LOG.info("$name:$version dependency not found in VFS")
            }
            else {
              files.add(jarFile)
            }
          }
        }
      }
    }
    return files
  }
}