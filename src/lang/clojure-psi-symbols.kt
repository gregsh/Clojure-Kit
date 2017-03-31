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

import com.intellij.ide.presentation.Presentation
import com.intellij.ide.presentation.PresentationProvider
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.pom.Navigatable
import com.intellij.pom.PomRenameableTarget
import com.intellij.pom.PomTarget
import com.intellij.pom.PomTargetPsiElement
import com.intellij.pom.references.PomService
import com.intellij.psi.PsiAnchor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiTarget
import com.intellij.psi.impl.PomTargetPsiElementImpl
import com.intellij.psi.search.EverythingGlobalScope
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.Processor
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.FactoryMap
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.ClojureConstants.CORE_NAMESPACES
import org.intellij.clojure.ClojureConstants.NS_ALIKE_SYMBOLS
import org.intellij.clojure.getIconForType
import org.intellij.clojure.java.JavaHelper
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.stubs.DEF_INDEX_KEY
import org.intellij.clojure.psi.stubs.KEYWORD_INDEX_KEY
import org.intellij.clojure.psi.stubs.NS_INDEX_KEY
import org.intellij.clojure.util.filter
import org.intellij.clojure.util.findParent
import org.intellij.clojure.util.isIn
import org.intellij.clojure.util.parentForms

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

data class SymKey(override val name: String, override val namespace: String, override val type: String) : DefInfo

fun DefInfo?.matches(info: DefInfo?) = this != null && info != null && name == info.name &&
    (type == info.type && namespace == info.namespace ||
        namespace.isIn(CORE_NAMESPACES) && info.namespace.isIn(CORE_NAMESPACES))

fun CSymbol?.resolveInfo(): DefInfo? = ((this?.reference?.resolve() as? PomTargetPsiElement)?.target as? CTarget)?.key

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
    return map[SymKey(namespace, namespace, "ns")]!!
  }

  fun getNamespace(namespace: String): PsiElement {
    return map[SymKey(namespace, namespace, "ns")]!!
  }

  fun getAlias(alias: String, namespace: String, o: CSymbol): PsiElement {
    return o.containingFile.map[SymKey(alias, namespace, "alias")]!!
        .let { it.putUserData(SOURCE_KEY, PsiAnchor.create(o)); it }
  }

  fun getDefinition(o: CDef): PsiElement {
    val targetMap = if (o.def.type == "defn-") o.containingFile.map else map
    return targetMap[SymKey(o.def.name, o.def.namespace, if (o.def.type == "defmethod") "defmulti" else o.def.type)]!!
        .let { it.putUserData(SOURCE_KEY, PsiAnchor.create(o)); it }
  }

  fun getDefinition(name: String, namespace: String, type: String): PsiElement {
    return map[SymKey(name, namespace, type)]!!
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
        it is CDef || it.first.let { it == null || it.name.isIn(ClojureConstants.FN_ALIKE_SYMBOLS) }
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
      override fun createMap(): MutableMap<SymKey, PsiElement>? {
        return ContainerUtil.createConcurrentWeakValueMap<SymKey, PsiElement>()
      }

      override fun create(key: SymKey): PsiElement {
        return createPomElement(owner, CTarget(project, this, key))
      }
    }
  }

  private fun createPomElement(owner: PsiElement?, target: CTarget): PsiElement {
    return if (owner == null) PomTargetPsiElementImpl(project, target)
    else object : PomTargetPsiElementImpl(project, target) {
      override fun getUseScope() = LocalSearchScope(owner)
    }
  }

}

internal class CTargetPresentation : PresentationProvider<CTarget>() {
  override fun getName(t: CTarget?) = t?.key?.name
  override fun getTypeName(t: CTarget?) = t?.key?.type

  override fun getIcon(t: CTarget?) = getIconForType(t?.key?.type ?: "")
}

@Presentation(provider=CTargetPresentation::class)
internal class CTarget(val project: Project,
                       val map: Map<SymKey, PsiElement>,
                       val key: SymKey) : PsiTarget, PomRenameableTarget<Any> {
  override fun canNavigate() = true
  override fun canNavigateToSource() = true
  override fun isValid() = true
  override fun navigate(requestFocus: Boolean) =
      (navigationElement as Navigatable).navigate(requestFocus)

  override fun getNavigationElement(): PsiElement {
    val data = target
    return data as? PsiElement ?: PomService.convertToPsi(project, NULL_TARGET)
  }

  override fun getName(): String {
    return key.name
  }

  private val target: PsiElement?
    get() {
      val userData = map[key]?.getUserData(SOURCE_KEY)
      val retrieved = (userData as? PsiAnchor)?.retrieve()
      if (retrieved is PsiElement) return retrieved
      val modificationCount = PsiModificationTracker.SERVICE.getInstance(project).modificationCount
      if (userData is Long && userData == modificationCount) return null
      if (DumbService.getInstance(project).isDumb) return null
      if (key.namespace == "") return null
      var result: PsiElement? = null
      val processor = Processor<PsiElement> {
        if (it is ClojureFile && it.namespace == key.namespace ||
            it is CDef && it.def.namespace == key.namespace ||
            it is CKeyword && it.namespace == key.namespace) {
          result = it
        }
        result == null
      }

      val scope = ClojureDefinitionService.getClojureSearchScope(project)
      StubIndex.getInstance().apply {
        when (key.type) {
          in NS_ALIKE_SYMBOLS -> processElements(NS_INDEX_KEY, key.namespace, project, scope, ClojureFile::class.java, processor)
          "keyword" -> processElements(KEYWORD_INDEX_KEY, key.name, project, scope, CKeyword::class.java, processor)
          else -> processElements(DEF_INDEX_KEY, key.name, project, scope, CDef::class.java, processor)
        }
        Unit
      }
      return result.apply {
        map[key]?.putUserData(SOURCE_KEY, if (this == null) modificationCount else PsiAnchor.create(this))
      }
    }

  override fun setName(newName: String) = null
  override fun isWritable() = true
}
