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

import com.intellij.codeInsight.navigation.GotoTargetHandler
import com.intellij.codeInsight.navigation.GotoTargetRendererProvider
import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.ide.util.DefaultPsiElementCellRenderer
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.GotoClassContributor
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageTargetProvider
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor
import com.intellij.util.containers.JBIterable
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.io.URLUtil
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.ClojureConstants.CLJS_CORE_PATH
import org.intellij.clojure.ClojureConstants.CLJ_CORE_PATH
import org.intellij.clojure.ClojureConstants.CLJ_SPEC_PATH
import org.intellij.clojure.ClojureIcons
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.impl.*
import org.intellij.clojure.util.*
import java.util.*
import javax.swing.Icon

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
  override fun getTargets(editor: Editor, file: PsiFile): Array<UsageTarget>? =
      UsageTarget.EMPTY_ARRAY

  override fun getTargets(psiElement: PsiElement): Array<UsageTarget>? {
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
  override fun getAdditionalProjectLibraries(project: Project): MutableCollection<SyntheticLibrary> = JBIterable
      .of(CLJ_CORE_PATH, CLJ_SPEC_PATH, CLJS_CORE_PATH)
      .flatten {
        javaClass.classLoader.getResources(it.trimStart('/'))
            .asSequence()
            .map {
              val pair = URLUtil.splitJarUrl(it.toExternalForm()) ?: return@map null
              val vFile = LocalFileSystem.getInstance().findFileByPath(pair.first) ?: return@map null
              JarFileSystem.getInstance().getJarRootForLocalFile(vFile)
            }.asIterable()
      }
      .notNulls()
      .unique()
      .map { CljLib(it) }
      .addAllTo(ArrayList())
}

class CljLib(val root: VirtualFile) : SyntheticLibrary(), ItemPresentation {
  fun getBinaryRoots(): MutableCollection<VirtualFile> = Collections.singletonList(root)
  override fun getSourceRoots(): MutableCollection<VirtualFile> = Collections.singletonList(root)
  override fun getLocationString(): String? = null
  override fun getIcon(unused: Boolean) = ClojureIcons.CLOJURE_ICON
  override fun getPresentableText() = root.name
  override fun equals(other: Any?) = root == (other as? CljLib)?.root
  override fun hashCode() = root.hashCode()
}

class MapDestructuringUsagesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
  override fun processQuery(queryParameters: ReferencesSearch.SearchParameters, consumer: Processor<PsiReference>) {
    val targetKey = queryParameters.elementToSearch.asCTarget?.key ?: return
    if (targetKey.type == "keyword" && (targetKey.name == "keys" || targetKey.name == "syms")) return
    val keyName = if (targetKey.type == "keyword") "keys" else "syms"
    val project = queryParameters.elementToSearch.project

    for (keyNs in arrayOf("", targetKey.namespace)) {
      val mapKeyElement = ClojureDefinitionService.getInstance(project).getDefinition(keyName, keyNs, "keyword")
      val nameFilter: (CSymbol)->Boolean =
          if (keyNs.isEmpty()) {{ it.qualifiedName == targetKey.qualifiedName }}
          else {{ it.name == targetKey.name }}
      ReferencesSearch.searchOptimized(mapKeyElement, queryParameters.effectiveSearchScope, false, queryParameters.optimizer) {
        val form = it.element.thisForm as? CKeyword ?: return@searchOptimized true
        val vec = (if (form.parentForm is CMap) form.nextForm as? CVec else null) ?: return@searchOptimized true
        vec.childForms.filter(CSymbol::class).filter(nameFilter).forEach { consumer.process(it.reference) }
        return@searchOptimized true
      }
    }
  }
}

class ClojureImplementationSearch : QueryExecutor<PsiElement, DefinitionsScopedSearch.SearchParameters> {
  override fun execute(queryParameters: DefinitionsScopedSearch.SearchParameters, consumer: Processor<PsiElement>): Boolean {
    val target = queryParameters.element
    val type = target.asCTarget?.key?.type ?: return true
    if (type != "defmulti" && type != "method") return true
    return ReadAction.compute<Boolean, RuntimeException> {
      ReferencesSearch.search(queryParameters.element, queryParameters.scope).forEach { ref ->
        val e = ref.element as? CSymbol ?: return@forEach
        val parent = e.parentForm
        if (parent?.def != null) return@forEach
        if (parent?.firstForm?.name == "defmethod") {
          if (parent.firstForm == e) return@forEach
          if (!consumer.process(parent)) return@compute false
        }
        else {
          val grandName = parent?.parentForm?.firstForm?.name
          if (grandName != null && ClojureConstants.OO_ALIKE_SYMBOLS.contains(grandName)) {
            if (!consumer.process(parent)) return@compute false
          }
        }
      }
      return@compute true
    }
  }
}

class ClojureGotoRendererProvider : GotoTargetRendererProvider {
  override fun getRenderer(element: PsiElement, gotoData: GotoTargetHandler.GotoData) =
      if (element is CListBase) ClojureGotoRenderer() else null
}

class ClojureGotoRenderer : DefaultPsiElementCellRenderer() {
  override fun getElementText(o: PsiElement?) = calcName(o as CListBase)
  override fun getIcon(o: PsiElement?): Icon = ClojureIcons.METHOD

  private fun calcName(form: CListBase): String {
    val def = form.def
    if (def != null) {
      return "(${def.name}) declaration"
    }
    val formName = form.first?.name
    if (formName == "defmethod") {
      return "(${(form.childForms[1] as? CSymbol)?.name} ${form.childForms[2]?.text ?: ""})"
    }
    val parentForm = form.parentForm as CListBase
    val grandName = parentForm.firstForm?.name
    if (grandName == "reify") return "(${formName ?: ""}) in (reify …)"
    val selector =
        if (grandName == "extend-protocol") form.prevSiblings().filter(CSymbol::class).first()?.qualifiedName
        else (parentForm.childForms[1] as? CSymbol)?.name
    return "(${formName ?: ""} ${selector ?: ""})"
  }
}