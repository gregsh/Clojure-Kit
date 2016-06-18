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

package org.intellij.clojure.lang.usages

import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.ElementDescriptionProvider
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.containers.JBIterable
import org.intellij.clojure.ClojureConstants.CLJS_CORE_PATH
import org.intellij.clojure.ClojureConstants.CLJ_CORE_PATH
import org.intellij.clojure.psi.CDef
import org.intellij.clojure.psi.CKeyword
import org.intellij.clojure.psi.ClojureFile
import org.intellij.clojure.psi.impl.CTarget
import org.intellij.clojure.psi.stubs.DEF_INDEX_KEY
import org.intellij.clojure.psi.stubs.KEYWORD_INDEX_KEY
import org.intellij.clojure.psi.stubs.NS_INDEX_KEY
import java.util.*

/**
 * @author gregsh
 */
class ClojureFindUsagesProvider : FindUsagesProvider {
  override fun getWordsScanner() = null
  override fun getDescriptiveName(o: PsiElement) = getNodeText(o, true)
  override fun getHelpId(psiElement: PsiElement) = null

  override fun canFindUsagesFor(o: PsiElement) =
      o is CKeyword || o is CDef || o is PomTargetPsiElement && o.target is CTarget

  override fun getType(o: PsiElement) = when (o) {
    is CKeyword -> "keyword"
    is CDef -> "(${o.def.type})"
    is PomTargetPsiElement -> (o.target as CTarget).key.run { if (namespace == "") type else "($type)" }
    else -> ""
  }

  override fun getNodeText(o: PsiElement, useFullName: Boolean) = when (o) {
    is CKeyword -> o.name
    is CDef -> o.def.name
    is PomTargetPsiElement -> (o.target as CTarget).name
    else -> ""
  }
}

class ClojureElementDescriptionProvider : ElementDescriptionProvider {
  override fun getElementDescription(element: PsiElement, location: ElementDescriptionLocation): String? {
    if (location == UsageViewTypeLocation.INSTANCE) {
      return when (element) {
        is CKeyword -> "keyword"
        is CDef -> "(${element.def.type})"
        is PomTargetPsiElement -> (element.target as? CTarget)?.key?.run { if (namespace == "") type else "($type)" } ?: null
        else -> null
      }
    }
    return null
  }
}

class ClojureGotoSymbolContributor : ChooseByNameContributor {
  override fun getItemsByName(name: String, pattern: String?, project: Project, includeNonProjectItems: Boolean): Array<NavigatablePsiElement> {
    val scope = if (!includeNonProjectItems) GlobalSearchScope.projectScope(project) else null
    val elements = JBIterable.empty<PsiElement>()
        .append(StubIndex.getElements(DEF_INDEX_KEY, name, project, scope, CDef::class.java))
        .append(StubIndex.getElements(NS_INDEX_KEY, name, project, scope, ClojureFile::class.java))
        .append(StubIndex.getElements(KEYWORD_INDEX_KEY, name, project, scope, CKeyword::class.java).firstOrNull())
    return (elements as JBIterable<NavigatablePsiElement>).toList().toTypedArray()
  }

  override fun getNames(project: Project, includeNonProjectItems: Boolean): Array<String> = StubIndex.getInstance().let {
    JBIterable.from(it.getAllKeys<String>(DEF_INDEX_KEY, project))
        .append(it.getAllKeys<String>(NS_INDEX_KEY, project))
        .append(it.getAllKeys<String>(KEYWORD_INDEX_KEY, project))
        .toList().toTypedArray()
  }
}

class ClojureLibraryRootsProvider : AdditionalLibraryRootsProvider() {
  override fun getAdditionalProjectLibrarySourceRoots(project: Project): Set<VirtualFile> = JBIterable.of(CLJ_CORE_PATH, CLJS_CORE_PATH)
      .flatten {
        PathManager.getResourceRoot(javaClass, it)?.let {
          LocalFileSystem.getInstance().findFileByPath(it)?.let {
            JarFileSystem.getInstance().getJarRootForLocalFile(it)?.let {
              Collections.singleton(it)
            }
          }
        } ?: Collections.emptySet() }
      .toSet()
}