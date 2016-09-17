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
import com.intellij.pom.references.PomService
import com.intellij.psi.PsiAnchor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiTarget
import com.intellij.psi.search.EverythingGlobalScope
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.FactoryMap
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.getIconForType
import org.intellij.clojure.java.JavaHelper
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.stubs.DEF_INDEX_KEY
import org.intellij.clojure.psi.stubs.KEYWORD_INDEX_KEY
import org.intellij.clojure.psi.stubs.NS_INDEX_KEY
import org.intellij.clojure.util.findParent

/**
 * @author gregsh
 */
private val SOURCE_KEY: Key<PsiAnchor> = Key.create("C_SOURCE_KEY")
private val POM_MAP_KEY: Key<Map<ClojureDefinitionService.SymKey, PsiElement>> = Key.create("C_POM_MAP_KEY")

private object NULL_TARGET : PomTarget {
  override fun canNavigate() = false
  override fun canNavigateToSource() = false
  override fun navigate(requestFocus: Boolean) = Unit
  override fun isValid() = true
}

class ClojureDefinitionService(val project: Project) {
  companion object {
    @JvmStatic fun getInstance(project: Project) =
        ServiceManager.getService(project, ClojureDefinitionService::class.java)!!

    @JvmStatic fun getClojureSearchScope(project: Project): GlobalSearchScope {
      return EverythingGlobalScope()
    }
  }

  val java = JavaHelper.getJavaHelper(project)

  private val map: Map<SymKey, PsiElement> = createPomMap()
  private val PsiElement.map: Map<SymKey, PsiElement>
    get() = getUserData(POM_MAP_KEY).let f@ {
      return@f if (it != null) it
      else {
        val map = createPomMap()
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
    return (if (o.parent is CVec && !(o.parent.parent.let { it is CList && it.first?.name == "binding" })) {
      o.parent.map[SymKey(o.name, "", if (o.findParent(CList::class)?.let {
        it is CDef || it.first == null
      } ?: false) "argument" else "let-binding")]
    }
    else map[SymKey(o.name, "", "symbol")])!!
        .let { it.putUserData(SOURCE_KEY, PsiAnchor.create(o)); it }
  }

  fun getFnParam(o: CSymbol): PsiElement {
    return o.findParent(CFun::class)!!.map[SymKey(o.name, "", "argument")]!!.let { it.putUserData(SOURCE_KEY, PsiAnchor.create(o)); it }
  }

  internal data class SymKey(override val name: String, override val namespace: String, override val type: String) : DefInfo

  private fun createPomMap(): Map<SymKey, PsiElement> {

    return object : FactoryMap<SymKey, PsiElement>() {
      override fun createMap(): MutableMap<SymKey, PsiElement>? {
        return ContainerUtil.createConcurrentWeakValueMap<SymKey, PsiElement>()
      }

      override fun create(key: SymKey): PsiElement {
        return createPomElement(this, key)
      }
    }
  }

  private fun createPomElement(map: Map<SymKey, PsiElement>, key: SymKey): PsiElement {
    return PomService.convertToPsi(project, CTarget(project, map, key))
  }

}
internal class CTargetPresentation : PresentationProvider<CTarget>() {
  override fun getName(t: CTarget?) = t?.key?.name
  override fun getTypeName(t: CTarget?) = t?.key?.type

  override fun getIcon(t: CTarget?) = getIconForType(t?.key?.type ?: "")
}

@Presentation(provider=CTargetPresentation::class)
internal class CTarget(val project: Project, val map: Map<ClojureDefinitionService.SymKey, PsiElement>, val key: ClojureDefinitionService.SymKey) : PsiTarget, PomRenameableTarget<Any> {
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
      val retrieved = userData?.retrieve()
      return retrieved ?: run {
        if (DumbService.getInstance(project).isDumb) null
        else if (key.namespace == "") null
        else {
          val scope = ClojureDefinitionService.getClojureSearchScope(project)
          val results = when (key.type) {
            in ClojureConstants.NS_ALIKE_SYMBOLS -> StubIndex.getElements(NS_INDEX_KEY, key.namespace, project, scope, ClojureFile::class.java)
            "keyword" -> StubIndex.getElements(KEYWORD_INDEX_KEY, key.name, project, scope, CKeyword::class.java)
            else -> StubIndex.getElements(DEF_INDEX_KEY, key.name, project, scope, CDef::class.java)
          }
          results.forEach {
            when (it) {
              is ClojureFile -> if (it.namespace == key.namespace) return it.let { map[key]?.putUserData(SOURCE_KEY, PsiAnchor.create(it)); it }
              is CDef -> if (it.def.namespace == key.namespace) return it.let { map[key]?.putUserData(SOURCE_KEY, PsiAnchor.create(it)); it }
              is CKeyword -> if (it.namespace == key.namespace) return it.let { map[key]?.putUserData(SOURCE_KEY, PsiAnchor.create(it)); it }
              else -> {
              }
            }
          }
          null
        }
      }
    }

  override fun setName(newName: String) = null
  override fun isWritable() = true
}
