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

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.CompositePsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiUtilCore
import org.intellij.clojure.ClojureIcons
import org.intellij.clojure.getIconForType
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.psi.*
import org.intellij.clojure.util.*
import javax.swing.Icon

/**
 *
 *
 * @author gregsh
 */
class ClojurePsiImplUtil {
  companion object {
    @JvmStatic fun toString(o: PsiElement) = "${StringUtil.getShortName(o::class.java)}(${PsiUtilCore.getElementType(o)})"

    @JvmStatic fun getReference(o: CSymbol): PsiQualifiedReference = CSymbolReference(o)
    @JvmStatic fun getName(o: CSymbol): String = o.lastChild.text
    @JvmStatic fun getTextOffset(o: CSymbol): Int = o.lastChild.textRange.startOffset
    @JvmStatic fun getQualifier(o: CSymbol): CSymbol? = o.lastChild.findPrev(CSymbol::class)

    @JvmStatic fun getQualifiedName(o: CSymbol): String {
      val offset = o.qualifier?.textRange?.startOffset ?: o.findChild(CToken::class)!!.textRange.startOffset
      val delta = if (o.lastChild.node.elementType == ClojureTypes.C_DOT) -1 else 0
      return o.text.let { it.substring(offset - o.textRange.startOffset, it.length + delta) }
    }

    @JvmStatic fun getName(o: CKeywordBase): String = o.symbol.name
    @JvmStatic fun getNamespace(o: CKeywordBase): String =
        o.resolvedNs ?: o.role.let { o.resolvedNs!! }

    @JvmStatic fun getTextOffset(o: CKeywordBase): Int = o.symbol.textOffset

    @JvmStatic fun getDef(o: CListBase): IDef? =
        if (o.role == Role.DEF || o.role == Role.NS) o.defImpl else null
    @JvmStatic fun getTextOffset(o: CListBase): Int =
        (if (o.role != Role.NONE) o.findChild(Role.NAME) ?: o else o).textOffset

    @JvmStatic fun getFirst(o: CList): CSymbol? = o.findChild(CForm::class) as? CSymbol
    @JvmStatic fun getLiteralType(o: CLiteral): IElementType? = o.lastChild?.elementType
    @JvmStatic fun getLiteralText(o: CLiteral): String = o.lastChild?.text ?: ""
  }
}

open class CComposite(tokenType: IElementType) : CompositePsiElement(tokenType), CElement {
  override val role: Role
    get() = roleImpl ?: (containingFile as CFileImpl).role(this)

  @JvmField
  internal var roleImpl: Role? = null
}

abstract class CListBase(nodeType: IElementType) : CLVFormImpl(nodeType), CList, ItemPresentation {

  override fun getPresentation() = this
  override fun getIcon(unused: Boolean): Icon? = when (role) {
    Role.NS -> ClojureIcons.NAMESPACE
    Role.DEF -> getIconForType(def!!.type)
    Role.FIELD -> ClojureIcons.FIELD
    else -> null
  }
  override fun getLocationString() = containingFile.name
  override fun getPresentableText(): String {
    val prefix = iterate().takeWhile { it is CReaderMacro }.joinToString(separator = " ") { it.text }
    val first = this.first ?: return prefix
    return "$prefix(${first.name} …)"
  }

  @JvmField
  internal var defImpl: IDef? = null
}

abstract class CKeywordBase(nodeType: IElementType) : CFormImpl(nodeType), CKeyword,
    PsiNameIdentifierOwner, PsiQualifiedNamedElement, ItemPresentation {

  override abstract fun getName(): String

  override fun getPresentation() = this
  override fun getIcon(unused: Boolean): Icon? = null
  override fun getLocationString() = containingFile.name
  override fun getNameIdentifier() = lastChild!!
  override fun setName(newName: String): PsiElement {
    nameIdentifier.replace(newLeafPsiElement(project, newName))
    return this
  }

  override fun getPresentableText() = ":$qualifiedName"
  override fun getQualifiedName() = name.withNamespace(namespace)

  @JvmField
  internal var resolvedNs: String? = null
}

fun newLeafPsiElement(project: Project, s: String): PsiElement =
    PsiFileFactory.getInstance(project).createFileFromText(ClojureLanguage, s).firstChild.lastChild

val PsiElement?.fastRole: Role get() = (this as? CComposite)?.roleImpl ?: Role.NONE
val CList?.fastDef: IDef?
  get() = (this as? CListBase)?.run {
    defImpl?.run {
      if (name == "" && type == "") null else this
    } ?: first?.let { type ->
      (type.nextForm as? CSymbol)?.let { name ->
        SymKey(name.name, "", type.name)
      }
    }
  }