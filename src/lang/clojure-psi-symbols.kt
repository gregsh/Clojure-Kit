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

package org.intellij.clojure.psi.impl

import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.pom.PomRenameableTarget
import com.intellij.pom.PomTarget
import com.intellij.pom.PomTargetPsiElement
import com.intellij.pom.references.PomService
import com.intellij.psi.*
import com.intellij.psi.impl.PomTargetPsiElementImpl
import com.intellij.psi.search.EverythingGlobalScope
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.FactoryMap
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.ID
import com.intellij.util.ui.EmptyIcon
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.ClojureConstants.CORE_NAMESPACES
import org.intellij.clojure.getIconForType
import org.intellij.clojure.java.JavaHelper
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.stubs.CStub
import org.intellij.clojure.util.*
import javax.swing.Icon
import kotlin.reflect.KClass

/**
 * @author gregsh
 */
private val SOURCE_KEY: Key<Any> = Key.create("C_SOURCE_KEY")
private val POM_MAP_KEY: Key<Map<SymKey, PsiElement>> = Key.create("C_POM_MAP_KEY")

private object NULL_TARGET : PomTarget {
  override fun canNavigate() = false
  override fun canNavigateToSource() = false
  override fun navigate(requestFocus: Boolean) = Unit
  override fun isValid() = true
}

fun IDef?.matches(info: IDef?) = this != null && info != null && name == info.name &&
    (type == info.type && namespace == info.namespace ||
        namespace.isIn(CORE_NAMESPACES) && info.namespace.isIn(CORE_NAMESPACES))

fun CSymbol?.resolveInfo(): IDef? = this?.reference?.resolve().asCTarget?.key
internal fun CSymbol?.resolveXTarget(): XTarget? = this?.reference?.resolve()?.let {
  val target = it.asCTarget ?: return@let null
  target as? XTarget ?: wrapWithNavigationElement(it.project, target.key, PsiUtilCore.getVirtualFile(it)).asXTarget
}

class ClojureDefinitionService(val project: Project) {
  companion object {
    @JvmStatic fun getInstance(project: Project) = ServiceManager.getService(project, ClojureDefinitionService::class.java)!!

    @JvmStatic fun getClojureSearchScope(project: Project): GlobalSearchScope = EverythingGlobalScope(project)

    @JvmStatic val COMMENT_SYM = SymKey("comment", ClojureConstants.CLOJURE_CORE, "defmacro")
  }

  val java = JavaHelper.getJavaHelper(project)

  private val map: Map<SymKey, PsiElement> = createPomMap()
  private val PsiElement.map: Map<SymKey, PsiElement>
    get() = getUserData(POM_MAP_KEY).let f@ {
      return@f if (it != null) it
      else {
        val map = createPomMap(this)
        putUserData(POM_MAP_KEY, map)
        map
      }
    }


  fun getNamespace(symbol: CSymbol): PsiElement {
    val namespace = (symbol.qualifier ?: symbol).name
    return map[SymKey(namespace, "", "ns")]!!
  }

  fun getNamespace(namespace: String): PsiElement {
    return map[SymKey(namespace, "", "ns")]!!
  }

  fun getAlias(alias: String, namespace: String, o: CSymbol): PsiElement {
    return o.containingFile.map[SymKey(alias, namespace, "alias")]!!
        .let { it.putUserData(SOURCE_KEY, PsiAnchor.create(o)); it }
  }

  fun getDefinition(o: CList): PsiElement {
    val def = o.def ?: throw AssertionError("not definition")
    val targetMap = if (def.type == "defn-") o.containingFile.map else map
    return targetMap[SymKey(def.name, def.namespace, if (def.type == "defmethod") "defmulti" else def.type)]!!
        .let { it.putUserData(SOURCE_KEY, PsiAnchor.create(o)); it }
  }

  fun getDefinition(name: String, namespace: String, type: String): PsiElement {
    return map[SymKey(name, namespace, type)]!!
  }

  fun getDefinition(key: SymKey): PsiElement {
    return map[key]!!
  }

  fun getKeyword(o: CKeyword): PsiElement {
    return map[SymKey(o.name, o.namespace, "keyword")]!!
        .let { it.putUserData(SOURCE_KEY, PsiAnchor.create(o)); it }
  }

  fun getSymbol(o: CSymbol): PsiElement {
    val topVec = o.parentForms.filter(CVec::class).last()
    val isLocal = topVec != null && !(topVec.parent.let { it is CList && it.first?.name == "binding" })
    val symKey = if (isLocal) {
      val isArgument = o.findParent(CList::class)?.let {
        it.role == Role.DEF || it.first.let { it == null || it.name.isIn(ClojureConstants.FN_ALIKE_SYMBOLS) }
      } ?: false
      SymKey(o.name, "", if (isArgument) "argument" else "let-binding")
    }
    else SymKey(o.name, o.qualifier?.let { it.resolveInfo()?.namespace } ?: "", "symbol")
    return (if (isLocal) topVec!!.parent.map else map)[symKey]!!
        .let { it.putUserData(SOURCE_KEY, PsiAnchor.create(o)); it }
  }

  fun getFnParam(o: CSymbol): PsiElement {
    return o.findParent(CFun::class)!!.map[SymKey(o.name, "", "argument")]!!
        .let { it.putUserData(SOURCE_KEY, PsiAnchor.create(o)); it }
  }

  fun getLocalBinding(o: CSymbol, parent: CForm): PsiElement {
    return parent.map[SymKey(o.name, "", "let-binding")]!!
        .let { it.putUserData(SOURCE_KEY, PsiAnchor.create(o)); it }
  }

  private fun createPomMap(owner : PsiElement? = null): Map<SymKey, PsiElement> {
    return object : FactoryMap<SymKey, PsiElement>() {
      override fun createMap(): MutableMap<SymKey, PsiElement> {
        return ContainerUtil.createConcurrentWeakValueMap<SymKey, PsiElement>()
      }

      override fun create(key: SymKey): PsiElement {
        return createPomElement(owner, YTarget(project, key, this))
      }
    }
  }

  private fun createPomElement(owner: PsiElement?, target: YTarget): PsiElement {
    return if (owner == null) CPomTargetElement(project, target)
    else object : CPomTargetElement(project, target) {
      override fun getUseScope() = LocalSearchScope(owner)
    }
  }

}

private open class CPomTargetElement(project: Project, target: CTarget) :
    PomTargetPsiElementImpl(project, target), PsiQualifiedNamedElement {

  override fun getTarget(): CTarget = super.getTarget() as CTarget

  override fun getName() = target.key.name
  override fun getQualifiedName() = target.key.qualifiedName

  override fun getPresentableText(): String? {
    val key = target.key
    if (key.type == "keyword") return ":" + key.qualifiedName
    return "(${key.type} ${key.qualifiedName})"
  }

  override fun getLocationString(): String? {
    val file = containingFile
    if (file != null) return "(" + file.name + ")"
    return null
  }

  override fun getIcon(): Icon? = getIconForType(target.key.type) ?: EmptyIcon.ICON_16

  override fun getTypeName(): String = target.key.type

  override fun getContainingFile(): PsiFile? {
    val psiFile = (target as? YTarget)?.navigationElement?.containingFile ?: (target as? XTarget)?.psiFile
    if (psiFile != null) return psiFile
    return super.getContainingFile()
  }
}

abstract internal class CTarget(val project: Project,
                                val key: SymKey) : PomRenameableTarget<Any> {
  override fun canNavigate() = true
  override fun canNavigateToSource() = true
  override fun isValid() = true
  override fun getName(): String = key.name
  override fun setName(newName: String) = null
  override fun isWritable() = true
}

internal class YTarget(project: Project,
                       key: SymKey,
                       private val map: Map<SymKey, PsiElement>) : CTarget(project, key), PsiTarget {
  override fun navigate(requestFocus: Boolean) =
      (navigationElement as Navigatable).navigate(requestFocus)

  override fun getNavigationElement(): PsiElement {
    val data = target
    return data ?: PomService.convertToPsi(project, NULL_TARGET)
  }

  private val target: PsiElement?
    get() {
      val userData = map[key]?.getUserData(SOURCE_KEY)
      val retrieved = (userData as? PsiAnchor)?.retrieve()
      if (retrieved is PsiElement) return retrieved
      val modificationCount = PsiModificationTracker.SERVICE.getInstance(project).modificationCount
      if (userData is Long && userData == modificationCount) return null
      if (DumbService.getInstance(project).isDumb) return null
      if (key.namespace == "" && key.type != "ns") return null

      fun <K, V> findFile(id: ID<K, V>, key: K): VirtualFile? = FileBasedIndex.getInstance().run {
        val found = Ref.create<VirtualFile>()
        val scope = ClojureDefinitionService.getClojureSearchScope(project)
        processValues(id, key, null, { file, _:V -> found.set(file); false }, scope)
        found.get()
      }
      val file = when (key.type) {
        in "ns" -> findFile(NS_INDEX, key.name)
        "keyword" -> findFile(KEYWORD_FQN_INDEX, key.qualifiedName)
        else -> findFile(DEF_FQN_INDEX, key.qualifiedName)
      }

      val result: PsiElement? = wrapWithNavigationElement(project, key, file)

      return result.apply {
        map[key]?.putUserData(SOURCE_KEY, if (this == null) modificationCount else PsiAnchor.create(this))
      }
    }

}

internal fun wrapWithNavigationElement(project: Project, key: SymKey, file: VirtualFile?): NavigatablePsiElement {
  fun <C : CForm> locate(k: SymKey, clazz: KClass<C>): (CFile) -> Navigatable? = { f ->
    f.cljTraverser().traverse().filter(clazz).find {
      if (k.type == "keyword") it is CKeyword && it.namespace == k.namespace
      else it is CList && it.def?.run { name == k.name && namespace == k.namespace } ?: false
    }
  }

  val result = when (key.type) {
    in "ns" -> CPomTargetElement(project, XTarget(project, key, file, { it }))
    "keyword" -> CPomTargetElement(project, XTarget(project, key, file, locate(key, CKeywordBase::class)))
    else -> CPomTargetElement(project, XTarget(project, key, file, locate(key, CListBase::class)))
  }
  return result
}

internal class XTarget(project: Project,
                       key: SymKey,
                       val file: VirtualFile?,
                       private val resolver: (CFile) -> Navigatable?) : CTarget(project, key) {
  val psiFile: PsiFile?
    get() = if (file == null) null else PsiManager.getInstance(project).findFile(file)

  override fun canNavigate() = canNavigateToSource()
  override fun canNavigateToSource(): Boolean = psiFile?.let { PsiNavigationSupport.getInstance().canNavigate(it) } ?: false
  override fun isValid(): Boolean = file == null || file.isValid

  override fun navigate(requestFocus: Boolean): Unit = (resolve() as? Navigatable ?: psiFile)?.navigate(requestFocus) ?: Unit

  fun resolve(): PsiElement? = psiFile?.let { if (it is CFile) resolver(it) as PsiElement else it }

  fun resolveStub(): CStub? = (psiFile as? CFileImpl)?.fileStubForced?.findChildStub(key)
}

internal val PsiElement?.asCTarget: CTarget?
  get() = (this as? PomTargetPsiElement)?.target as? CTarget

internal val PsiElement?.asXTarget: XTarget?
  get() = (this as? PomTargetPsiElement)?.target as? XTarget
