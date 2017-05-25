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

import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.GotoClassContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageTargetProvider
import com.intellij.util.Processor
import com.intellij.util.containers.JBIterable
import com.intellij.util.indexing.FileBasedIndex
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.ClojureConstants.CLJS_CORE_PATH
import org.intellij.clojure.ClojureConstants.CLJ_CORE_PATH
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.impl.*
import org.intellij.clojure.util.*
import java.util.*

/**
 * @author gregsh
 */
class ClojureFindUsagesProvider : FindUsagesProvider {
  override fun getWordsScanner() = null
  override fun getDescriptiveName(o: PsiElement) = getNodeText(o, true)
  override fun getHelpId(psiElement: PsiElement) = null

  override fun canFindUsagesFor(o: PsiElement) =
      o is CKeyword || o is CList && o.def != null || o.asCTarget != null

  override fun getType(o: PsiElement) = getTypeTextImpl(o) ?: "???"

  override fun getNodeText(o: PsiElement, useFullName: Boolean) = when (o) {
    is CKeyword -> o.name
    is CList -> o.def?.name ?: "null"
    is PomTargetPsiElement -> o.asCTarget!!.name
    else -> ""
  }
}

class ClojureUsageTargetProvider : UsageTargetProvider {
  override fun getTargets(editor: Editor?, file: PsiFile?): Array<UsageTarget>? =
      UsageTarget.EMPTY_ARRAY

  override fun getTargets(psiElement: PsiElement?): Array<UsageTarget>? {
    val target = psiElement.asXTarget?.resolve() ?: return UsageTarget.EMPTY_ARRAY
    return arrayOf(PsiElement2UsageTargetAdapter(target))
  }
}

class ClojureElementDescriptionProvider : ElementDescriptionProvider {
  override fun getElementDescription(element: PsiElement, location: ElementDescriptionLocation): String? {
    if (location == UsageViewTypeLocation.INSTANCE) {
      return getTypeTextImpl(element)
    }
    return null
  }
}

private fun getTypeTextImpl(o: PsiElement): String? = when (o) {
  is CKeyword -> "keyword"
  // add parens to suppress capitalization in find dialog
  is CList -> "(${o.def?.type ?: "???"})"
  is PomTargetPsiElement -> "(${o.asCTarget?.key?.type ?: "???"})"
  else -> null
}

class ClojureGotoSymbolContributor : ChooseByNameContributor, GotoClassContributor {

  private val indices = arrayOf(DEF_FQN_INDEX to "def", NS_INDEX to "ns", KEYWORD_FQN_INDEX to "keyword")

  override fun getItemsByName(name: String, pattern: String?, project: Project, includeNonProjectItems: Boolean): Array<NavigatablePsiElement> {
      val scope =
          if (!includeNonProjectItems) GlobalSearchScope.projectScope(project)
          else ClojureDefinitionService.getClojureSearchScope(project)
    val idx = name.indexOf('/')
    val namespace = if (idx > 0) name.substring(0, idx) else ""
    val shortName = name.substring(idx + 1)

    return indices.map { it.second to FileBasedIndex.getInstance().getContainingFiles(it.first, name, scope) }.
        flatMap { (type, files) ->
          (if (type == "keyword") files.jbIt().take(1) else files.jbIt()).map { file ->
            wrapWithNavigationElement(project, SymKey(shortName, namespace, type), file)
          }
        }.toTypedArray()
  }

  override fun getNames(project: Project, includeNonProjectItems: Boolean): Array<String> {
    return indices.flatMap { FileBasedIndex.getInstance().getAllKeys(it.first, project).jbIt() }.toTypedArray()
  }

  override fun getQualifiedName(item: NavigationItem?): String? {
    return (item as? PsiElement).asCTarget?.key?.qualifiedName
  }

  override fun getQualifiedNameSeparator() = "/"
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

class MapDestructuringUsagesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
  override fun processQuery(queryParameters: ReferencesSearch.SearchParameters, consumer: Processor<PsiReference>) {
    val targetKey = queryParameters.elementToSearch.asCTarget?.key ?: return
    if (targetKey.type == "keyword" && (targetKey.name == "keys" || targetKey.name == "syms")) return
    val keyName = if (targetKey.type == "keyword") "keys" else "syms"
    val project = queryParameters.elementToSearch.project
    val mapKeyElement = ClojureDefinitionService.getInstance(project).getDefinition(
        keyName, targetKey.namespace.let { if (it == ClojureConstants.NS_USER) "" else it }, "keyword")

    for (usage in ReferencesSearch.search(mapKeyElement, queryParameters.effectiveSearchScope)) {
      val form = usage.element.thisForm as? CKeyword ?: continue
      val vec = (if (form.parentForm is CMap) form.nextForm as? CVec else null) ?: continue
      for (symbol in vec.childForms.filter(CSymbol::class)) {
        if (symbol.name == targetKey.name)  {
          consumer.process(symbol.reference)
        }
      }
    }
  }
}