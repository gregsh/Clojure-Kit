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
import com.intellij.lang.Language
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.stubs.StubTreeLoader
import com.intellij.util.SmartList
import com.intellij.util.containers.JBIterable
import com.intellij.util.containers.JBTreeTraverser
import com.intellij.util.containers.TreeTraversal
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.editor.arguments
import org.intellij.clojure.lang.ClojureFileType
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.lang.ClojureScriptLanguage
import org.intellij.clojure.lang.ClojureTokens
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.stubs.CFileStub
import org.intellij.clojure.psi.stubs.CListStub
import org.intellij.clojure.psi.stubs.CStub
import org.intellij.clojure.psi.stubs.isBuildingStubs
import org.intellij.clojure.util.*
import java.lang.ref.SoftReference
import java.util.*

private val EXPLICIT_RESOLVE_KEY: Key<SymKey> = Key.create("EXPLICIT_RESOLVE_KEY")
private val ALL: Set<String> = setOf("*all*")

class CFileImpl(viewProvider: FileViewProvider, language: Language) :
    PsiFileBase(viewProvider, language), CFile {

  override fun getFileType() = ClojureFileType
  override fun toString() = "${javaClass.simpleName}:$name"

  @Volatile
  private var fileStubRef: SoftReference<CFileStub>? = null
  internal var fileStub: CFileStub?
    set(stub) {
      fileStubRef = if (stub == null) null else SoftReference(stub)
    }
    get() = fileStubRef?.get().let { stub0 ->
      when {
        virtualFile !is VirtualFileWithId -> null
        stub0 != null || treeElement != null || isBuildingStubs() -> stub0
        else -> {
          val stub = StubTreeLoader.getInstance().readOrBuild(project, virtualFile, this)?.root as? CFileStub
          fileStubRef = if (stub != null) SoftReference(stub) else null
          stub
        }
      }
    }
  internal val fileStubForced: CFileStub?
    get() = fileStubRef?.get() ?: run {
      val stub = StubTreeLoader.getInstance().readOrBuild(project, virtualFile, this)?.root as? CFileStub
      return stub.also { fileStub = it }
    }


  override val namespace: String
    get() {
      return fileStub?.namespace ?: state.namespace
    }

  internal fun role(form: CElement): Role {
    state
    return (form as? CComposite)?.roleImpl ?: run {
      (form as? CComposite)?.roleImpl = Role.NONE
      Role.NONE
    }
  }

  override fun defs(dialect: LangKind): JBIterable<CList> {
    return state.definitions.jbIt()
  }

  private data class State(
      val timeStamp: Long,
      val namespace: String,
      val definitions: List<CList>,
      val imports: List<Imports>
  )

  @Volatile
  private var myRolesDirty: Boolean = false
  @Volatile
  private var myState: State? = null
  private val state: State
    get() {
      val curTimeStamp = manager.modificationTracker.outOfCodeBlockModificationCount
      val curState = myState
      if (curState != null && curState.timeStamp == curTimeStamp) return curState
      myState = null

      // makes sure half-applied roles due to ProcessCanceledException
      // are cleared on subtreeChanged
      myRolesDirty = true

      val helper = RoleHelper()
      helper.assignRoles(this)
      val definitions = cljTraverser().traverse()
          .filter { (it as? CComposite)?.roleImpl == Role.DEF }
          .filter(CList::class).toList()
      val state = State(curTimeStamp, helper.fileNS, definitions, helper.imports)
      myState = state
      return state
    }

  override fun subtreeChanged() {
    super.subtreeChanged()
    val clearRoles = myRolesDirty || myState != null
    fileStub = null
    myState = null
    if (clearRoles) {
      clearRoles()
      myRolesDirty = false
    }
  }

  private fun clearRoles() {
    for (e in cljTraverser().traverse()) {
      if (e is CComposite && e.roleImpl != null) {
        e.roleImpl = null
        if (e is CListBase) e.defImpl = null
      }
    }
  }

  override fun processDeclarations(processor: PsiScopeProcessor, state: ResolveState, lastParent: PsiElement?, place: PsiElement): Boolean {
    val placeFile = place.containingFile.originalFile
    val placeNs = (placeFile as? CFile)?.namespace
    val namespace = namespace
    val publicOnly = language == ClojureLanguage && namespace != placeNs
    val defService = ClojureDefinitionService.getInstance(project)
    if (placeFile !== this) fileStub?.let { fileStub ->
      val s = JBTreeTraverser<CStub> { o -> o.childrenStubs }.withRoot(fileStub)
          .filter(CListStub::class.java)
          .filter { it.key.namespace == namespace && !(publicOnly && it.key.type == "defn-") }
      s.forEach { stub ->
        if (!processor.execute(defService.getDefinition(stub.key), state)) return false
      }
      return true
    }
    val langKind = placeLanguage(place)
    val defs = defs().filter { it.def!!.namespace == namespace && !(publicOnly && it.def!!.type == "defn-") }
    defs.forEach { if (!processor.execute(it, state)) return false }

    if (placeFile !== this) return true

    var langKindNSVisited = false
    val isQualifier = place.parent is CSymbol && place.nextSibling.elementType == ClojureTypes.C_SLASH
    val forceAlias = state.get(ALIAS_KEY)
    val placeOffset = place.textRange.startOffset
    val index = this.state.imports.binarySearchBy(placeOffset, 0, this.state.imports.size, { it.range.startOffset })
        .let { if (it < 0) Math.min(-it - 1, this.state.imports.size) else it }
    val insideImport = this.state.imports.subList(0, index).find { it.range.contains(placeOffset) } != null
    for (import in this.state.imports.subList(0, index).asReversed()
        .filter { it.langKind == langKind && (placeOffset < it.scopeEnd || it.scopeEnd < 0) }
        .flatMap { it.imports }) {
      if (!import.isPlatform && import.aliasSym != null) {
        if (!processor.execute(defService.getAlias(import.alias, import.namespace, import.aliasSym), state)) return false
      }
      if (forceAlias != null) continue
      if (import.isPlatform) {
        import.refer.forEach { className ->
          val target =
              if (langKind == LangKind.CLJS) defService.getDefinition(
                  StringUtil.getShortName(className), StringUtil.getPackageName(className), ClojureConstants.JS_OBJ)
              else defService.java.findClass(className)
          if (target != null && !processor.execute(target, state)) return false
        }
      }
      else if (!isQualifier) {
        // require only loads a lib and creates an alias,
        // but does not import symbols unless :refer :all
        if (import.nsType == "require" &&
            import.refer.isEmpty() && import.only.isEmpty() && import.rename.isEmpty()) continue
        langKindNSVisited = langKindNSVisited || langKind.ns == import.namespace
        if (!processNamespace(import.namespace, state, if (insideImport) processor else object : PsiScopeProcessor by processor {
          override fun execute(element: PsiElement, state: ResolveState): Boolean {
            val name = element.asCTarget?.key?.name ?: element.asDef?.def?.name ?: return true
            if (import.exclude.contains(name)) return true
            val renamed = import.rename[name]
            if (renamed != null) {
              return processor.execute(defService.getAlias(renamed.name, namespace, renamed), state)
            }
            if (import.refer == ALL || import.refer.contains(name) ||
                import.only.contains(name) || import.only.isEmpty()) {
              return processor.execute(element, state)
            }
            return true
          }
        }, this)) return false
      }
    }
    if (!isQualifier && !langKindNSVisited) {
      return processNamespace(langKind.ns, state, processor, this)
    }
    return true
  }


  internal fun processPrecomputedDeclarations(refText: String?, place: CSymbol, langKind: LangKind,
                                              service: ClojureDefinitionService, state: ResolveState,
                                              processor: PsiScopeProcessor): Boolean {
    val key = EXPLICIT_RESOLVE_KEY.get(place) ?: return true
    val target = when (key.type) {
      "java package" ->
        if (langKind == LangKind.CLJS) return processor.skipResolve()
        else service.java.findPackage(key.namespace, key.name)
      "java class" ->
        if (langKind == LangKind.CLJS) return processor.skipResolve()
        else service.java.findClass(key.name.withPackage(key.namespace))
      "def" -> service.getDefinition(key)
      "ns" -> service.getDefinition(key)
      "alias" -> service.getAlias(key.name, key.namespace, place)
      else -> throw AssertionError("unknown explicit resolve type: ${key.type}")
    }
    if (target != null) {
      if (key.type == "def") {
        if (!processNamespace(key.namespace, state, processor, this)) return false
        return processSpecialForms(langKind, refText, place, service, state, processor)
      }
      if (refText != null) return processor.execute(target, state)
      return true
    }
    return true
  }
}

private class RoleHelper {
  val langStack = ArrayDeque<LangKind>()
  val nsReader = NSReader(this)
  val fileNS: String get() = nsReader.fileNS
  val imports: List<Imports> get() = nsReader.result

  fun currentLangKind() = langStack.peek()!!

  fun resolveAlias(alias: String?): String? {
    if (alias == null || alias == "") return alias
    val langKind = langStack.peek()
    return imports.asReversed().filter { it.langKind == langKind }
        .flatMap { it.imports }
        .firstOrNull { !it.isPlatform && it.alias == alias }?.namespace
  }

  fun setDefRole(o: CListBase, role: Role, def: IDef) { o.defImpl = def; (o as CComposite).roleImpl = role }

  fun setRole(o: CElement?, role: Role) { if (o is CComposite) o.roleImpl = role }

  fun assignRoles(file: CFile) {
    val seenDefs = mutableSetOf<String>()
    val delayedDefs = mutableMapOf<CList, IDef>()
    langStack.push(if (file.language == ClojureScriptLanguage) LangKind.CLJS else LangKind.CLJ)

    val s = file.cljTraverser().expand { it !is CListBase ||
        it is CComposite && it.roleImpl != Role.DEF && it.roleImpl != Role.NS }.traverse()
    val traceIt : TreeTraversal.TracingIt<PsiElement> = s.typedIterator() // can be used for parent()/parents()

    for (e in traceIt) {
      if (e is CKeywordBase) {
        processKeyword(e)
      }
      else if (e is CToken) {
        processInRCParenToken(e)
        // optimization: finishing up the delayed def
        if (e.elementType == ClojureTypes.C_PAREN2 && e.parent is CList) {
          val parent = e.parent as CListBase
          val def = delayedDefs.remove(parent) ?: continue
          setDefRole(parent, Role.DEF, def)
        }
      }
      // optimization: take other threads work into account
      else if (e is CListBase && (e as CComposite).roleImpl == Role.DEF) {
        seenDefs.add(e.def!!.name)
      }
      else if (e is CListBase && processRCParenForm(e)) {
        Unit
      }
      else if (e is CListBase) {
        val first = e.first
        val firstName = first?.name ?: continue
        val langKind = currentLangKind()
        val ns = first.qualifier?.name?.let { resolveAlias(it) } ?:
            if (seenDefs.contains(firstName)) fileNS else langKind.ns
        if (ClojureConstants.DEF_ALIKE_SYMBOLS.contains(firstName) && ns == langKind.ns ||
            firstName.startsWith("def") && firstName != "default" && firstName != "def" /* clojure.spec/def */) {
          val nameSym = first.nextForm as? CSymbol
          if (nameSym != null && nameSym.firstChild !is CReaderMacro ) {
            // optimization: delay up until the end, so that other threads may skip this
            val type = if (firstName == "create-ns") "ns" else firstName
            val key = SymKey(nameSym.name, fileNS, type)
            setRole(nameSym, Role.NAME)
            delayedDefs.put(e, createDef(e, key))
            seenDefs.add(key.name)
          }
        }
        else if (delayedDefs[e.parentForm]?.type.let { t -> t == "defprotocol" || t == "defrecord"}) {
          val key = SymKey(firstName, fileNS, "method")
          setRole(first, Role.NAME)
          setRole(first.nextForm as? CVec, Role.ARG_VEC)
          delayedDefs.put(e, createDef(e, key))
          seenDefs.add(key.name)
        }
        else if (ClojureConstants.NS_ALIKE_SYMBOLS.contains(firstName) && ns == langKind.ns) {
          setRole(e, Role.NS) // prevents deep traversal for e
          processNSElement(e)
        }
        else if (ClojureConstants.LET_ALIKE_SYMBOLS.contains(firstName) && ns == langKind.ns) {
          setRole(e.findChild(CVec::class), Role.BND_VEC)
        }
      }
    }
  }

  private fun createDef(e: CListBase, key: SymKey): IDef {
    val prototypes = e.iterate(CLForm::class)
        .map {
          (it.firstChild?.nextSibling as? CVec)?.also { setRole(it, Role.PROTOTYPE) }
        }
        .append(e.first.siblings().filter(CVec::class).first())
        .notNulls()
        .onEach { setRole(it, Role.ARG_VEC) }
        .map { Prototype(arguments(it).toList(), null) }
        .toList()
    if (prototypes.isEmpty()) return key
    return Defn(key, prototypes)
  }

  fun processInRCParenToken(e: CToken): Boolean {
    if (ClojureTokens.PAREN_ALIKE.contains(e.elementType) &&
        e.parent?.parent?.fastRole.let { it == Role.RCOND || it == Role.RCOND_S }) {
      if (ClojureTokens.PAREN1_ALIKE.contains(e.elementType)) {
        langStack.push(if ((e.prevForm as? CKeyword)?.name == "cljs") LangKind.CLJS else LangKind.CLJ)
      }
      else {
        langStack.pop()
      }
      return true
    }
    return false
  }

  fun processRCParenForm(e: CListBase): Boolean {
    val dft = e.deepFirst.elementType
    if (e.firstChild.elementType != ClojureTypes.C_READER_MACRO ||
        dft != ClojureTypes.C_SHARP_QMARK_AT && dft != ClojureTypes.C_SHARP_QMARK) {
      return false
    }
    setRole(e, if (dft == ClojureTypes.C_SHARP_QMARK_AT) Role.RCOND_S else Role.RCOND)
    return true
  }

  fun processKeyword(e: CKeywordBase) {
    val symbol = e.symbol
    val isUserNS = symbol.prevSibling.elementType == ClojureTypes.C_COLONCOLON
    val qualifierName = symbol.qualifier?.name
    val ns = when {
      qualifierName != null && isUserNS -> resolveAlias(qualifierName)
      qualifierName != null -> qualifierName
      isUserNS -> fileNS
      e.parentForm is CMap -> e.parentForm.iterate(CReaderMacro::class)
          .filter { it.firstChild.elementType == ClojureTypes.C_SHARP_NS }.first()?.let { nsMacro ->
        val isUserNS = nsMacro.firstChild.nextSibling.elementType == ClojureTypes.C_COLONCOLON
        val qualifierName = nsMacro.symbol?.name
        when {
          qualifierName != null && isUserNS -> resolveAlias(qualifierName)
          qualifierName != null -> qualifierName
          isUserNS -> fileNS
          else -> null
        }
      }
      else -> null
    }
    e.resolvedNs = ns ?: ""
    setRole(e, Role.NONE)
  }

  fun processNSElement(e: CListBase) {
    e.cljTraverser().filter(CKeywordBase::class.java).onEach { processKeyword(it) }
    nsReader.processElement(e)
  }
}

private class NSReader(val helper: RoleHelper) {
  var fileNS: String = ClojureConstants.NS_USER
  val result = mutableListOf<Imports>()

  fun processElement(e: CListBase) {
    val nsType = (e as CList).first!!.name
    if (nsType == "in-ns" || nsType == "ns") updateFileNS(e)
    val hasRC = e.cljTraverser().filter(CListBase::class.java)
        .reduce(false, { flag, it -> helper.processRCParenForm(it) || flag })
    if (hasRC) {
      //todo two pass overwrites EXPLICIT_RESOLVE_KEY
      readNSElement(e, e.rcTraverser("cljs"), nsType, LangKind.CLJS)?.let { result.add(it) }
      readNSElement(e, e.rcTraverser("clj"), nsType, LangKind.CLJ)?.let { result.add(it) }
    }
    else {
      readNSElement(e, e.cljTraverser(), nsType, helper.langStack.peek())?.let { result.add(it) }
    }
  }

  fun updateFileNS(e: CListBase) {
    val nameSym = e.first.nextForm as? CSymbol ?: return
    val name = nameSym.name
    helper.setRole(nameSym, Role.NAME)
    helper.setDefRole(e, Role.NS, SymKey(name, "", "ns"))
    if (result.isEmpty()) {
      fileNS = name
    }
  }

  fun readNSElement(e: CListBase, traverser: SyntaxTraverser<PsiElement>, nsType: String, langKind: LangKind): Imports? {
    val s = traverser
        .forceDisregard { it is CMetadata || it is CReaderMacro }
        .expand { it !is CKeyword && it !is CSymbol }
        .filter { it is CForm }
    val imports = if (nsType == "ns") {
      val imports = s.expandAndSkip { it == e }.filter(CListBase::class.java).flatMap {
        val name = it.findChild(CForm::class)?.let {
          (it as? CKeyword)?.name ?:
              (it as? CSymbol)?.name /* clojure < 1.9 */
        } ?: return@flatMap emptyList<Import>()
        readNSElement2(it, s.withRoot(it), name, langKind)
      }.toList()
      imports
    }
    else {
      val imports = readNSElement2(e, s, nsType, langKind)
      imports
    }
    if (imports.isEmpty()) return null
    val scope = e.parentForms.filter { it.fastRole != Role.RCOND && it.fastRole != Role.RCOND_S }.first()
    return Imports(imports, langKind, e.textRange, scope?.textRange?.endOffset ?: -1)
  }

  fun readNSElement2(root: CListBase, traverser: SyntaxTraverser<PsiElement>, nsType: String, langKind: LangKind): List<Import> {
    val content = traverser.iterate(root).skip(1)
    return when (nsType) {
      "import" -> readNSElement_import(content, traverser)
      "alias" -> readNSElement_alias(content)
      "refer" -> readNSElement_refer("", content, traverser, nsType).asListOrEmpty()
      "refer-clojure" -> readNSElement_refer(langKind.ns, content, traverser, nsType).asListOrEmpty()
      "use", "require", "require-macros" -> readNSElement_require_use(content, traverser, nsType)
      else -> emptyList() // load, gen-class, ??
    }
  }

  private fun readNSElement_import(content: JBIterable<PsiElement>, traverser: SyntaxTraverser<PsiElement>): List<Import> {
    val iterator = content.iterator()
    val classes = mutableSetOf<String>()
    fun addClass(o: CSymbol, prefix: String) {
      val name = o.name
      val qualifiedName = name.withPackage(prefix)
      classes.add(qualifiedName)
      o.putUserData(EXPLICIT_RESOLVE_KEY, SymKey(name, prefix, "java class"))
    }
    for (item in iterator) {
      when (item) {
        is CSymbol -> addClass(item, "")
        is CLVForm -> {
          traverser.iterate(item).iterator().apply {
            val packageSym = safeNext() as? CSymbol ?: return@apply
            val packageName = packageSym.name
            var anyClass = ""
            forEach {
              addClass(it as? CSymbol ?: return@forEach, packageName)
              if (anyClass == "") anyClass = it.name
            }
            packageSym.putUserData(EXPLICIT_RESOLVE_KEY, SymKey(anyClass, packageName, "java package"))
          }
        }
      }
    }
    return listOf(Import("import", "", "", null, classes))
  }

  private fun readNSElement_alias(content: JBIterable<PsiElement>): List<Import> {
    val iterator = content.iterator()
    val aliasSym = iterator.safeNext() as? CSymbol ?: return emptyList()
    val nsSym = iterator.safeNext() as? CSymbol
    val namespace = nsSym?.name ?: ""
    helper.setRole(aliasSym, Role.NAME)
    nsSym?.putUserData(EXPLICIT_RESOLVE_KEY, SymKey(namespace, "", "ns"))
    return listOf(Import("alias", namespace, aliasSym.name, aliasSym))
  }

  private fun readNSElement_refer(nsPrefix: String, content: JBIterable<PsiElement>,
                                  traverser: SyntaxTraverser<PsiElement>, nsType: String): Import? {
    val iterator = content.iterator()
    val forcedNamespace = nsType == "refer-clojure"
    val nsSym = if (forcedNamespace) null else (iterator.safeNext() as? CSymbol ?: return null)
    var aliasSym: CSymbol? = null
    var refer: CForm? = null
    var only: CPForm? = null
    var exclude: CPForm? = null
    var rename: JBIterable<CSymbol>? = null
    for (e in iterator) {
      val flag = (e as? CKeyword)?.symbol?.name ?: ""
      when (flag) {
        "as" -> if (nsType != "refer") aliasSym = iterator.safeNext() as? CSymbol else iterator.safeNext()
        "refer" -> if (nsType != "refer") refer = iterator.safeNext().let { it as? CPForm ?: it as? CKeyword }
        "only" -> only = iterator.safeNext() as? CLVForm
        "exclude" -> exclude = iterator.safeNext() as? CLVForm
        "rename" -> rename = (iterator.safeNext() as? CMap)?.iterate(CSymbol::class)
      }
    }
    val namespace = if (forcedNamespace) nsPrefix else (nsSym?.name?.withPackage(nsPrefix) ?: "")
    val alias = aliasSym?.name ?: ""
    nsSym?.putUserData(EXPLICIT_RESOLVE_KEY, SymKey(namespace, "", "ns"))
    aliasSym?.putUserData(EXPLICIT_RESOLVE_KEY, SymKey(alias, namespace, "alias"))
    fun CPForm?.toNames() = traverser.withRoot(this).filter(CSymbol::class.java)
        .transform { sym -> sym.name.also { sym.putUserData(EXPLICIT_RESOLVE_KEY, SymKey(it, namespace, "def")) } }.toSet()

    val import = Import(nsType, namespace, alias, aliasSym,
        (refer as? CPForm)?.toNames() ?: (refer as? CKeyword)?.let { if (it.name == "all") ALL else null } ?: emptySet(),
        only.toNames(), exclude.toNames(),
        rename?.split(2, true)?.reduce(HashMap()) { map, o ->
          if (o.size == 2) map.put(o[0].name,
              o[1].also { it.putUserData(EXPLICIT_RESOLVE_KEY, SymKey(it.name, namespace, "alias")) }); map
        } ?: emptyMap())
    return import
  }


  private fun readNSElement_require_use(content: JBIterable<PsiElement>, traverser: SyntaxTraverser<PsiElement>, nsType: String): List<Import> {
    val iterator = content.iterator()
    val result = SmartList<Import>()
    fun addImport(item: CForm, nsPrefix: String) {
      when (item) {
        is CSymbol -> item.name.withPackage(nsPrefix).let { ns ->
          result.add(Import(nsType, ns, "", null))
          item.putUserData(EXPLICIT_RESOLVE_KEY, SymKey(ns, "", "ns"))
        }
        is CVec -> result.addAll(readNSElement_refer(nsPrefix, traverser.iterate(item), traverser, nsType).asListOrEmpty())
      }
    }
    for (item in iterator) {
      when (item) {
        is CKeyword -> if (item.name == "as") iterator.safeNext()  // ignore the next form to get it highlighted
        is CSymbol -> addImport(item, "")
        is CVec -> addImport(item, "")
        is CList -> {
          traverser.iterate(item).iterator().apply {
            val prefixSym = safeNext() as? CSymbol ?: return@apply
            val nsPrefix = prefixSym.name
            forEach { e -> addImport(e as? CForm ?: return@forEach, nsPrefix) }
          }
        }
      }
    }
    return result
  }
}

internal data class Imports (
    val imports: List<Import>,
    val langKind: LangKind,
    val range: TextRange,
    val scopeEnd: Int)

internal data class Import (
    val nsType: String,
    val namespace: String,
    val alias: String,
    val aliasSym: CSymbol?,
    val refer: Set<String> = emptySet(),
    val only: Set<String> = emptySet(),
    val exclude: Set<String> = emptySet(),
    val rename: Map<String, CSymbol> = emptyMap()) {
  val isPlatform: Boolean get() = nsType == "import"
}


fun PsiElement?.rcTraverser(rcKey: String) = cljTraverser()
    .forceDisregard { e ->
      val r = e.fastRole
      if (r == Role.RCOND || r == Role.RCOND_S) return@forceDisregard true
      val pr = e.parent.fastRole
      pr == Role.RCOND_S && e.prevForm is CKeyword
    }
    .forceIgnore { e ->
      val pr = e.parentForm?.fastRole
      if (pr != Role.RCOND && pr != Role.RCOND_S) return@forceIgnore false
      val prev = e.prevForm
      prev !is CKeyword || prev.name != rcKey
    }
