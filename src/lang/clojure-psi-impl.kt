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

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.*
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.JBIterable
import com.intellij.util.containers.JBTreeTraverser
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.ClojureConstants.NS_USER
import org.intellij.clojure.getIconForType
import org.intellij.clojure.lang.ClojureFileType
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.stubs.*
import org.intellij.clojure.util.*
import javax.swing.Icon

class ClojureFileImpl(viewProvider: FileViewProvider, language: Language) :
    PsiFileBase(viewProvider, language), ClojureFile {

  override fun getFileType() = ClojureFileType
  override fun toString() = "${javaClass.simpleName}:$name"

  override fun processDeclarations(processor: PsiScopeProcessor, state: ResolveState, lastParent: PsiElement?, place: PsiElement): Boolean {
    val placeFile = place.containingFile.originalFile
    val insideImport = if (placeFile === this) place.textRange.startOffset.let {
      off -> imports.filter { it.first?.run { textRange.endOffset < off && parent.textRange.endOffset > off } ?: false}.first() } else null
    if (insideImport != null) {
      return processFileImports(JBIterable.of(insideImport), processor, state, place)
    }
    val placeNs = (placeFile as? ClojureFile)?.namespace
    val namespace = namespace
    val publicOnly = language == ClojureLanguage && namespace != placeNs
    val defs = definitions.filter { it.def.namespace == namespace && !(publicOnly && it.def.type == "defn-") }
    defs.forEach { if (!processor.execute(it, state)) return false }
    if (placeFile !== this) return true
    if (!processFileImports(imports, processor, state, place)) return false
    return true
  }

  override val namespace: String
    get() = (stub as? CFileStub)?.namespace ?: state.namespace

  override val definitions: JBIterable<CDef>
    get() = stub?.let { JBTreeTraverser<StubElement<*>> { it.childrenStubs }
        .withRoot(it).traverse().transform { it.psi as? CDef }.notNulls() } ?:
        JBIterable.from(this.state.definitions)

  override val imports: JBIterable<CList>
    get() = JBIterable.from(state.imports)

  internal data class State(val namespace: String, val definitions: List<CDef>, val imports: List<CList>)
  private var myState: State? = null
  internal val state: State
    get() {
      if (myState != null) return myState!!
      val definitions = cljTraverser().filter(CDef::class.java).toList()
      val imports = cljTopLevelTraverser().filter(CList::class.java)
          .filter { (it as CList).first?.name?.let { ClojureConstants.NS_ALIKE_SYMBOLS.contains(it)} ?: false }.toList()
      val namespace = ((imports.firstOrNull() as? CDef)?.first?.nextForm as? CSymbol)?.name ?: NS_USER
      val state = State(namespace, definitions, imports)
      myState = state
      return state
    }
  internal val offsetMap = ContainerUtil.newConcurrentMap<CForm, Any>()
  internal val typeMap = ContainerUtil.newConcurrentMap<CForm, Any>()

  override fun subtreeChanged() {
    super.subtreeChanged()
    myState = null
    offsetMap.clear()
    typeMap.clear()
  }

}

class ClojurePsiImplUtil {
  companion object {
    @JvmStatic fun getReference(o: CSymbol): PsiQualifiedReference = CSymbolReference(o)
    @JvmStatic fun getName(o: CSymbol): String = o.lastChild.text
    @JvmStatic fun getTextOffset(o: CSymbol): Int = o.lastChild.textRange.startOffset
    @JvmStatic fun getQualifier(o: CSymbol): CSymbol? = o.lastChild.findPrev(CSymbol::class)

    @JvmStatic fun getQualifiedName(o: CSymbol): String {
      val offset = o.qualifier?.textRange?.startOffset ?: o.findChild(ClojureToken::class)!!.textRange.startOffset
      val delta = if (o.lastChild.node.elementType == ClojureTypes.C_DOT) -1 else 0
      return o.text.let { it.substring(offset - o.textRange.startOffset, it.length + delta) }
    }

    @JvmStatic fun getName(o: CKeyword): String = (o as CKeywordBase).def.name
    @JvmStatic fun getNamespace(o: CKeyword): String = (o as CKeywordBase).def.namespace
    @JvmStatic fun getTextOffset(o: CKeyword): Int = o.symbol.textOffset
    @JvmStatic fun getFirst(o: CLForm): CSymbol? = o.findChild(CForm::class) as? CSymbol
    @JvmStatic fun getLiteralType(o: CLiteral): IElementType? = o.lastChild?.elementType
    @JvmStatic fun getLiteralText(o: CLiteral): String = o.lastChild?.text ?: ""
  }
}

abstract class CStubBase<Stub>(stub: Stub?, nodeType: IStubElementType<*, *>, node: ASTNode?) :
    StubBasedPsiElementBase<Stub>(stub, if (node == null) nodeType else null, node), CForm, ItemPresentation
    where Stub : StubElement<*> {

  override fun getMetas(): List<CMetadata> = PsiTreeUtil.getChildrenOfTypeAsList(this, CMetadata::class.java)
  override fun getReaderMacros(): List<CReaderMacro> = PsiTreeUtil.getChildrenOfTypeAsList(this, CReaderMacro::class.java)

  override fun getPresentation() = this
  override fun getIcon(unused: Boolean): Icon? = null
  override fun getLocationString() = containingFile.name
  override fun getPresentableText() = ""

  override fun toString() = "${javaClass.simpleName}($elementType)"
}

abstract class CListBase(stub: CListStub?, nodeType: CListElementType, node: ASTNode?) :
    CStubBase<CListStub>(stub, nodeType, node), CList {

  constructor(stub: CListStub) : this(stub, stub.stubType as CListElementType, null)
  constructor(node: ASTNode) : this(null, node.elementType as CListElementType, node)

  override fun getPresentableText(): String {
    val prefix = iterate().takeWhile { it is CReaderMacro }.joinToString(separator = " ") { it.text }
    val first = this.first ?: return prefix
    return "$prefix(${first.name} …)"
  }

  override fun getForms(): MutableList<CForm> = PsiTreeUtil.getChildrenOfTypeAsList(this, CForm::class.java)
  override fun getFirst() = ClojurePsiImplUtil.getFirst(this)
}

open class CDefImpl(stub: CListStub?, nodeType: CListElementType, node: ASTNode?) :
    CListBase(stub, nodeType, node), CDef {

  constructor(stub: CListStub) : this(stub, stub.stubType as CListElementType, null)
  constructor(node: ASTNode) : this(null, node.elementType as CListElementType, node)

  override fun getPresentableText() = def.run { "($type $namespace/$name)" }
  override fun getIcon(unused: Boolean) = getIconForType(def.type)

  override val def: DefInfo
    get() = myDef ?: stub ?: calcDef().apply { myDef = this }

  override fun subtreeChanged() = super.subtreeChanged().run { myDef = null }
  private var myDef: DefInfo? = null

  internal open fun calcDef(): DefInfo {
    val type = first?.name ?: throw AssertionError(text)
    val second = nameSymbol
    val name = (second as? CSymbol)?.name ?: ""
    val namespace = (second as? CSymbol)?.qualifier?.name
    return object : DefInfo {
      override val type: String get() = type
      override val name: String get() = name
      override val namespace: String get() = namespace ?: (containingFile as ClojureFile).namespace
    }
  }

  override val nameSymbol: CSymbol? get() = first.findNext(CSymbol::class)

  override fun getNameIdentifier() = nameSymbol?.lastChild
  override fun getNavigationElement() = this

  override fun getName(): String = def.name
  override fun setName(newName: String) = apply {
    nameIdentifier?.replace(newLeafPsiElement(project, newName))
  }

  override fun getQualifiedName() = def.namespace + "/" + def.name

  override fun meta(key: String): String? {
    val nameSymbol = nameSymbol?: return null
    for (m in nameSymbol.metas) {
      val forms = (m.form as? CMap)?.forms ?: continue
      var next: Boolean = false
      for ((i, form) in forms.withIndex()) {
        if (next) {
          return (form as? CLiteral)?.let {
            if (it.literalType == ClojureTypes.C_STRING)
              StringUtil.unquoteString(it.literalText) else it.literalText
          }
        }
        next = i % 2 == 0 && form is CKeyword && form.text == key
      }
    }
    return null
  }
}

class CMDefImpl(stub: CListStub?, nodeType: CListElementType, node: ASTNode?) :
    CDefImpl(stub, nodeType, node), CDef {

  constructor(stub: CListStub) : this(stub, stub.stubType as CListElementType, null)
  constructor(node: ASTNode) : this(null, node.elementType as CListElementType, node)

  override fun calcDef(): DefInfo {
    val name = first?.name ?: throw AssertionError(text)
    return object: DefInfo {
      override val type: String get() = ClojureConstants.TYPE_PROTOCOL_METHOD
      override val name: String get() = name
      override val namespace: String get() = (containingFile as ClojureFile).namespace
    }
  }

  override val nameSymbol: CSymbol?
    get() = ClojurePsiImplUtil.getFirst(this)

}

class CReaderCondImpl(node: ASTNode, val splicing: Boolean) : CListImpl(node)

abstract class CKeywordBase(stub: CKeywordStub?, nodeType: CKeywordElementType, node: ASTNode?) :
    CStubBase<CKeywordStub>(stub, nodeType, node), CKeyword, CNamed {
  constructor(stub: CKeywordStub) : this(stub, stub.stubType as CKeywordElementType, null)
  constructor(node: ASTNode) : this(null, node.elementType as CKeywordElementType, node)

  override fun getNameIdentifier() = lastChild!!
  override fun setName(newName: String): PsiElement {
    nameIdentifier.replace(newLeafPsiElement(project, newName))
    return this
  }

  override fun getPresentableText() = ":$namespace/$name"
  override fun getQualifiedName() = def.namespace + "/" + def.name
  override val nameSymbol: CSymbol? get() = symbol

  override val def: DefInfo
    get() = myDef ?: stub ?: calcDef().apply { myDef = this }

  override fun subtreeChanged() = super.subtreeChanged().run { myDef = null }
  private var myDef: DefInfo? = null

  internal open fun calcDef(): DefInfo {
    val symbol = symbol
    val name = symbol.name
    val isUserNS = symbol.prevSibling.elementType == ClojureTypes.C_COLONCOLON
    val ns: () -> String = symbol.qualifier?.let {{ it.resolveInfo()?.namespace ?: it.name }} ?:
        if (isUserNS) {{ (containingFile as ClojureFile).namespace }} else
        {{ (parent as? CMap)?.resolveNsPrefix() ?: "_"}}
    return object : DefInfo {
      override val type: String get() = "keyword"
      override val name: String get() = name
      override val namespace: String get() = ns()
    }
  }

}

fun newLeafPsiElement(project: Project, s: String) : PsiElement =
    PsiFileFactory.getInstance(project).createFileFromText(ClojureLanguage, s).firstChild.lastChild