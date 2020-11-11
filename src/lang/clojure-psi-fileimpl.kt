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
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.psi.*
import com.intellij.psi.impl.source.DummyHolder
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.stubs.StubTreeLoader
import com.intellij.util.SmartList
import com.intellij.util.containers.JBIterable
import com.intellij.util.containers.JBTreeTraverser
import com.intellij.util.indexing.FileBasedIndex
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.lang.ClojureFileType
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.lang.ClojureScriptLanguage
import org.intellij.clojure.lang.ClojureTokens
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.stubs.*
import org.intellij.clojure.util.*
import java.lang.ref.SoftReference
import java.util.*

private data class ResolveTo(val key: SymKey)
private val ALL: Set<String> = setOf("* all *")
val PRIVATE_META = "#private"
val TYPE_META = "#typeHint"

open class CFileImpl(viewProvider: FileViewProvider, language: Language) :
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
        else -> fileStubForced
      }
    }
  internal val fileStubForced: CFileStub?
    get() = fileStubRef?.get() ?: run {
      val stub = if (virtualFile !is VirtualFileWithId) buildStubTree(this)
      else StubTreeLoader.getInstance().readFromVFile(project, virtualFile)?.root as? CFileStub ?: buildStubTree(this)
      fileStub = stub
      return stub
    }


  override val namespace: String
    get() {
      return fileStub?.namespace ?: state.namespace
    }

  internal fun checkState() { state.namespace }

  override fun defs(dialect: Dialect): JBIterable<CList> {
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
  private var myState: State? = State(-1, "", emptyList(), emptyList())
  private val state: State
    get() {
      val curTimeStamp = manager.modificationTracker.modificationCount
      val curState = myState
      if (curState != null && curState.timeStamp == curTimeStamp) return curState
      myState = null

      // makes sure half-applied roles due to ProcessCanceledException
      // are cleared on subtreeChanged
      myRolesDirty = true

      val helper = RoleHelper()
      helper.assignRoles(this, curState != null && curState.timeStamp < 0)
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
      setData(e, null)
      resetFlags(e)
      setFlag((e as? CCommented)?.form, FLAG_COMMENTED)
    }
  }

  override fun processDeclarations(processor: PsiScopeProcessor, state: ResolveState, lastParent: PsiElement?, place: PsiElement): Boolean {
    val context = context
    val placeCF = (lastParent ?: place).containingFile.originalFile
    val placeFile = ((placeCF as? DummyHolder)?.context?.containingFile ?: placeCF) as? CFileImpl ?: return true
    val placeNs = placeFile.namespace
    val namespace = namespace
    val publicOnly = language == ClojureLanguage && namespace != placeNs
    val defService = ClojureDefinitionService.getInstance(project)
    val refText = processor.getHint(NAME_HINT)?.getName(state)
    val langKind = state.get(DIALECT_KEY) ?: placeFile.placeLanguage(place)
    val checkPrivate = publicOnly && langKind != Dialect.CLJS
    val placeParent = (place as? CElement)?.parent
    val isAccess = placeParent is CAccess && place.nextSibling.elementType != ClojureTypes.C_DOT

    fun processDef(defOrKey: Any, private: Boolean, parentDef: IDef?, processor: PsiScopeProcessor, state: ResolveState): Boolean {
      if (isAccess && (parentDef == null || parentDef.type == "defprotocol")) return true
      val def = (defOrKey as? CElement)?.def ?: defOrKey as SymKey
      if (!isAccess && def.namespace != namespace || publicOnly && private && refText != def.name) return true
      if (!isAccess && def.type == "method" && parentDef?.type != "defprotocol") return true
      if (!processor.execute(
              defOrKey as? CElement ?: defService.getDefinition(defOrKey as SymKey),
              if (private) state.put(PRIVATE_KEY, true) else state)) return false
      if (!processSyntheticDeclarations(def, private, processor, state, refText, defService)) return false
      return true
    }

    if (placeFile !== this) {
      val fileStub = fileStub
      if (fileStub != null) {
        val s = JBTreeTraverser<CStub> { o -> o.childrenStubs }
            .withRoot(fileStub)
            .filter(CListStub::class.java)
        s.forEach { stub ->
          val private = checkPrivate && (stub.key.type == "defn-" || stub.meta[PRIVATE_META] != null)
          val parentKey = (stub.parentStub as? CListStub)?.key
          val skip = stub.key.type == "method" && parentKey?.type != "defprotocol"
          if (!skip && !processDef(stub.key, private, parentKey, processor, state)) return false
        }
      }
      else {
        defs().forEach {
          val private = checkPrivate && (it.def!!.type == "defn-" || (it.def as? Def)?.meta?.containsKey(PRIVATE_META) ?: false)
          val def = it.def!!
          val parentDef = it.parentForm?.def
          val skip = def.type == "method" && parentDef?.type != "defprotocol"
          if (!skip && !processDef(it, private, parentDef, processor, state)) return false
        }
      }
      return true
    }

    val placeOffset = place.textRange.startOffset
    val inMacro = (placeParent as? CList)?.first?.let {
      !it.textRange.containsOffset(placeOffset) && it.resolveInfo()?.type == "defmacro"
    } ?: false

    defs().takeWhile { inMacro ||
        (if (it.def!!.type == "method") it.parentForm!! else it).textRange.startOffset < placeOffset }
        .forEach {
          val private = checkPrivate && (it.def!!.type == "defn-" || (it.def as? Def)?.meta?.containsKey(PRIVATE_META) ?: false)
          val def = it.def!!
          val parentDef = it.parentForm?.def
          val skip = def.type == "method" && !it.parentForm!!.textRange.contains(placeOffset)
          if (!skip && !processDef(it, private, parentDef, processor, state)) return false
        }

    val isQualifier = place.parent is CSymbol && place.nextSibling.elementType == ClojureTypes.C_SLASH
    if (!processImports(processor, state, place, refText, placeOffset, langKind, isQualifier, defService)) return false
    val contextCFile = context?.containingFile as? CFileImpl
    return contextCFile == null || contextCFile.processDeclarations(processor, state, context, place)
  }

  internal fun processImports(processor: PsiScopeProcessor, state: ResolveState, place: PsiElement,
                              refText: String?, placeOffset: Int, dialect: Dialect, isQualifier: Boolean,
                              defService: ClojureDefinitionService): Boolean {
    val namespace = namespace
    val forceAlias = state.get(ALIAS_KEY)
    var langKindNSVisited = false
    val thisImport = this.state.imports.find { it.range.contains(placeOffset) }
    val imports = importsAtOffset(placeOffset, dialect)
    for (import in imports.flatMap { it.imports }) {
      if (refText == null || isQualifier) {
        if (!import.isPlatform && import.aliasSym != null) {
          if (!processor.execute(defService.getAlias(import.alias, import.namespace, import.aliasSym), state)) return false
        }
      }
      if (import.nsType == "alias" || forceAlias != null) continue
      if (import.isPlatform) {
        import.refer.forEach { className ->
          val target =
              if (dialect == Dialect.CLJS) defService.getDefinition(
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
        langKindNSVisited = langKindNSVisited || dialect.coreNs == import.namespace
        val refersByDefault = import.nsType == "refer" || import.nsType == "refer-clojure" || import.nsType == "use"
        if (!processNamespace(import.namespace, dialect, state,
            if (thisImport != null && thisImport.imports.contains(import)) processor
            else object : PsiScopeProcessor by processor {
              override fun execute(element: PsiElement, state: ResolveState): Boolean {
                val name = element.asCTarget?.key?.name ?: element.asDef?.def?.name ?: return true
                if (import.exclude.contains(name)) return true
                val renamed = import.rename[name] as? CSymbol
                if (renamed != null) {
                  return processor.execute(defService.getAlias(renamed.name, namespace, renamed), state)
                }
                if (import.refer == ALL || import.refer.contains(name) ||
                    import.only.contains(name) ||
                    refersByDefault && import.only.isEmpty()) {
                  return processor.execute(element, state)
                }
                return true
              }
            }, this, place)) return false
      }
    }
    if (!isQualifier && !langKindNSVisited) {
      return processNamespace(dialect.coreNs, dialect, state, processor, this, place)
    }
    return true
  }

  private fun importsAtOffset(placeOffset: Int, dialect: Dialect): List<Imports> {
    val index = this.state.imports.binarySearchBy(placeOffset, 0, this.state.imports.size, { it.range.startOffset })
        .let { if (it < 0) Math.min(-it - 1, this.state.imports.size) else it }
    val imports = this.state.imports.subList(0, index).asReversed()
        .filter { it.dialect == dialect && (placeOffset < it.scopeEnd || it.scopeEnd < 0) }
    return imports
  }

  fun aliasesAtPlace(place: PsiElement?): Map<String, String> {
    val langKind =
        if (place != null) placeLanguage(place)
        else if (language == ClojureScriptLanguage) Dialect.CLJS else Dialect.CLJ
    val placeOffset = place?.textRange?.startOffset ?: textLength

    val imports = importsAtOffset(placeOffset, langKind)
    val insideImport = imports.find { it.range.contains(placeOffset) } != null
    if (insideImport) return emptyMap()
    return imports.flatMap { it.imports }
        .filter { it.aliasSym != null }
        .associateBy({ it.namespace }, { it.alias })
  }
}

internal fun processPrecomputedDeclarations(dialect: Dialect, refText: String?,
                                            place: CSymbol,
                                            service: ClojureDefinitionService,
                                            state: ResolveState,
                                            processor: PsiScopeProcessor,
                                            containingFile: CFile): Boolean {
  val key = ((place as? CComposite)?.data as? ResolveTo)?.key ?: return true
  val target = when (key.type) {
    "java package" ->
      if (dialect == Dialect.CLJS) return processor.skipResolve()
      else service.java.findPackage(key.namespace, key.name)
    "java class" ->
      if (dialect == Dialect.CLJS) return processor.skipResolve()
      else service.java.findClass(key.name.withPackage(key.namespace))
    "def" -> service.getDefinition(key)
    "ns" -> service.getDefinition(key)
    "alias" -> service.getAlias(key.name, key.namespace, place)
    else -> throw AssertionError("unknown explicit resolve type: ${key.type}")
  }
  if (target != null) {
    if (key.type == "def") {
      if (!processNamespace(key.namespace, dialect, state, processor, containingFile, place)) return false
      if (key.namespace == dialect.coreNs) {
        if (!processSpecialForms(dialect, refText, place, service, state, processor)) return false
      }
    }
    else if (refText != null) {
      if (!processor.execute(target, state.put(RENAMED_KEY, refText))) return false
    }
    else if (key.type == "ns") {
      FileBasedIndex.getInstance().processAllKeys(NS_INDEX, { ns ->
        processor.execute(service.getNamespace(ns), state)
      }, service.project)
    }
  }
  return false
}

private fun processSyntheticDeclarations(def: IDef, private: Boolean,
                                         processor: PsiScopeProcessor, state: ResolveState,
                                         refText: String?, defService: ClojureDefinitionService): Boolean {
  val type = if (private) "defn-" else "defn"
  if (def.type == "deftype") {
    if (!processor.execute(defService.getSynthetic(SymKey("->${def.name}", def.namespace, type), def), state)) return false
  }
  if (def.type == "defrecord") {
    if (!processor.execute(defService.getSynthetic(SymKey("->${def.name}", def.namespace, type), def), state)) return false
    if (!processor.execute(defService.getSynthetic(SymKey("map->${def.name}", def.namespace, type), def), state)) return false
  }
  return true
}

private class RoleHelper {
  val langStack = ArrayDeque<Dialect>()
  val nsReader = NSReader(this)
  val fileNS: String get() = nsReader.fileNS
  val imports: List<Imports> get() = nsReader.result

  fun currentLangKind() = langStack.peek()!!

  fun resolveAlias(alias: String?): String? {
    if (alias == null || alias == "") return alias
    val langKind = langStack.peek()
    return imports.asReversed().filter { it.dialect == langKind }
        .flatMap { it.imports }
        .firstOrNull { !it.isPlatform && it.alias == alias }?.namespace
  }

  fun assignRoles(file: CFile, firstTime: Boolean) {
    val seenDefs = mutableSetOf<String>()
    val delayedDefs = mutableMapOf<CList, IDef>()
    langStack.push(if (file.language == ClojureScriptLanguage) Dialect.CLJS else Dialect.CLJ)

    val s = file.cljTraverser().expand {
      it !is CListBase || (it as CComposite).roleImpl != Role.DEF && (it as CComposite).roleImpl != Role.NS
    }.traverse()

    if (firstTime) {
      s.onEach(this::initFlags)
    }
    for (e in s) {
      initFlags(e)
      if (e is CKeywordBase) {
        processKeyword(e)
      }
      else if (e is CToken) {
        processInRCParenToken(e)
        // optimization: finishing up the delayed def
        if (e.elementType == ClojureTypes.C_PAREN2 && e.parent is CList) {
          val parent = e.parent as CListBase
          val def = delayedDefs.remove(parent) ?: continue
          setData(parent, def)
        }
      }
      // optimization: take other threads work into account
      else if (e is CListBase && (e as CComposite).roleImpl == Role.DEF) {
        seenDefs.add(e.def!!.qualifiedName)
      }
      else if (e is CListBase && processRCParenForm(e)) {
        // nothing
      }
      else if (e is CListBase) {
        val first = e.first
        val firstName = first?.name ?: continue
        val langKind = currentLangKind()
        val ns = first.qualifier?.name?.let { resolveAlias(it) } ?:
            if (seenDefs.contains(firstName.withNamespace(fileNS))) fileNS else langKind.coreNs
        setData(first.qualifier, ns)
        if (ClojureConstants.DEF_ALIKE_SYMBOLS.contains(firstName) && ns == langKind.coreNs ||
            firstName != "defmethod" &&
            firstName.startsWith("def") && firstName != "default" && firstName != "def" /* clojure.spec/def */) {
          val nameSym = first.nextForm as? CSymbol
          if (nameSym != null && nameSym.firstChild !is CReaderMacro ) {
            // optimization: delay up until the end, so that other threads may skip this
            val type = if (firstName == "create-ns") "ns" else firstName
            val key = SymKey(nameSym.name, resolveAlias(nameSym.qualifier?.name) ?: fileNS, type)
            setData(nameSym.qualifier, key.namespace)
            setData(nameSym, Role.NAME)
            delayedDefs[e] = createDef(e, nameSym, key)
            seenDefs.add(key.qualifiedName)

            if (ClojureConstants.OO_ALIKE_SYMBOLS.contains(firstName)) {
              if (firstName == "defrecord" || firstName == "deftype" || firstName == "definterface") {
                setData(e.childForm(CVec::class), Role.FIELD_VEC)
              }
              e.childForms(CListBase::class).forEach {
                val first = it.first ?: return@forEach
                setData(first, Role.NAME)
                it.childForms(CVec::class).forEach { setData(it, Role.ARG_VEC) }
                delayedDefs[it] = createDef(it, first, SymKey(first.name, key.qualifiedName, "method"))
              }
            }
          }
        }
        else if (delayedDefs[e.parentForm]?.type == "defprotocol") {
          val key = SymKey(firstName, fileNS, "method")
          setData(first, Role.NAME)
          delayedDefs[e] = createDef(e, first, key)
          seenDefs.add(key.name)
        }
        else if (ClojureConstants.NS_ALIKE_SYMBOLS.contains(firstName) && ns == langKind.coreNs) {
          setData(e, Role.NS) // prevents deep traversal for e
          processNSElement(e)
        }
        else if (ClojureConstants.LET_ALIKE_SYMBOLS.contains(firstName) && ns == langKind.coreNs) {
          setData(e.childForm(CVec::class), Role.BND_VEC)
        }
        else if (ClojureConstants.FN_ALIKE_SYMBOLS.contains(firstName)/* && ns == langKind.ns*/) {
          processPrototypes(e).size()
        }
        else if (firstName == "letfn" && ns == langKind.coreNs) {
          (first.nextForm as? CVec).childForms(CListBase::class).forEach {
            setData(it.first ?: return@forEach, Role.NAME)
            processPrototypes(it).size()
          }
        }
        else if (firstName == "defmethod" && ns == langKind.coreNs) {
          setData(first.nextForm, Role.NAME)
          setData((e.childForms[3] as? CVec), Role.ARG_VEC)
        }
        else if (ClojureConstants.OO_ALIKE_SYMBOLS.contains(firstName)) {
          e.childForms(CList::class).forEach {
            setData(it.first ?: return@forEach, Role.NAME)
            prototypes(it).flatMap { it.childForms(CVec::class) }.forEach { setData(it, Role.ARG_VEC) }
          }
        }
        else if (firstName == "comment" && ns == langKind.coreNs) {
          setFlag(e, FLAG_COMMENTED)
        }
      }
    }
  }

  private fun createDef(e: CListBase, nameSym: CSymbol, key: SymKey): IDef {
    val meta = HashMap<String, String>()
    val prototypes = processPrototypes(e)
        .map { vec ->
          val args = vec.childForms.map { Arg((it as? CSymbol)?.name ?: it.text, it.typeHintMeta()?.qualifiedName) }.toList()
          Prototype(args, vec.typeHintMeta()?.qualifiedName)
        }
        .toList()
    var typeHint: String? = null
    var private = false
    val metaProcessor = { it: CKeyword ->
      when (it.name) {
        "tag" -> typeHint = (it.nextForm as? CSymbol)?.qualifiedName  // todo JVM type strings
        "private" -> private = (it.nextForm as? CLiteral)?.text == "true"
        else -> Unit
      }
    }
    // the order of processing from less important to more : ^{..}, ^:, {..}
    nameSym.formPrefix().filter(CMetadata::class)
        .flatMap { (it.form as? CMap).childForms(CKeyword::class) }
        .unique { it.name }
        .forEach(metaProcessor)
    nameSym.typeHintMeta()?.let { typeHint = it.qualifiedName }
    nameSym.keyMetas().find { it.name == "private" }?.let { private = true }
    nameSym.nextForms.find { it is CMap }
        .childForms(CKeyword::class)
        .forEach(metaProcessor)
    if (typeHint == null && prototypes.isNotEmpty()) {
      typeHint = prototypes.jbIt().reduce(prototypes[0].typeHint) { r, p -> if (r == p.typeHint) r else null }
    }

    if (typeHint != null) meta[TYPE_META] = typeHint!!
    if (private) meta[PRIVATE_META] = ""
    if (prototypes.isEmpty() && meta.isEmpty()) return key

    return Def(key, prototypes, if (meta.isNotEmpty()) meta else emptyMap())
  }

  private fun processPrototypes(e: CListBase): JBIterable<CVec> = e.childForms(CList::class)
      .map { list -> (list.firstForm as? CVec)?.also { setData(list, Role.PROTOTYPE) } }
      .append(e.childForms(CVec::class).first())
      .notNulls()
      .onEach { setData(it, Role.ARG_VEC) }

  fun processInRCParenToken(e: CToken): Boolean {
    if (ClojureTokens.PAREN_ALIKE.contains(e.elementType) &&
        e.parent?.parent?.fastRole.let { it == Role.RCOND || it == Role.RCOND_S }) {
      if (ClojureTokens.PAREN1_ALIKE.contains(e.elementType)) {
        langStack.push(if ((e.prevForm as? CKeyword)?.name == "cljs") Dialect.CLJS else Dialect.CLJ)
      }
      else {
        langStack.pop()
      }
      return true
    }
    return false
  }

  fun processRCParenForm(e: CListBase): Boolean {
    val rcType = e.formPrefix().find { it is CReaderMacro && it.firstChild.elementType.let {
      it == ClojureTypes.C_SHARP_QMARK_AT || it == ClojureTypes.C_SHARP_QMARK
    }}?.firstChild.elementType ?: return false
    setData(e, if (rcType == ClojureTypes.C_SHARP_QMARK_AT) Role.RCOND_S else Role.RCOND)
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
    setData(e, ns ?: "")
  }

  private fun processNSElement(e: CListBase) {
    e.cljTraverser().onEach { initFlags(it); if (it is CKeywordBase) processKeyword(it) }
    nsReader.processElement(e)
  }

  private fun initFlags(e: PsiElement) {
    setFlag((e as? CCommented)?.form, FLAG_COMMENTED)
    when ((e as? CReaderMacro)?.firstChild?.elementType) {
      ClojureTypes.C_QUOTE, ClojureTypes.C_SYNTAX_QUOTE -> setFlag(e.parent, FLAG_QUOTED)
      ClojureTypes.C_TILDE, ClojureTypes.C_TILDE_AT -> setFlag(e.parent, FLAG_UNQUOTED)
    }
  }
}

private class NSReader(val helper: RoleHelper) {
  var fileNS: String = ClojureConstants.NS_USER
  val result = mutableListOf<Imports>()

  fun processElement(e: CListBase) {
    val nsType = (e as CList).first!!.name
    var imports: MutableList<Imports>? = null
    if (nsType == "in-ns" || nsType == "ns") {
      val nameSym = e.first.nextForm as? CSymbol
      imports = mutableListOf()
      if (nameSym != null) {
        val name = nameSym.name
        setData(nameSym, Role.NAME)
        setData(e, NSDef(SymKey(name, "", "ns"), imports))
        if (result.isEmpty()) {
          fileNS = name
        }
      }
    }
    val hasRC = e.cljTraverser().filter(CListBase::class.java)
        .reduce(false, { flag, it -> helper.processRCParenForm(it) || flag })
    val addResult = { it: Imports -> result.add(it); imports?.add(it) ?: setData(e, it) }
    if (hasRC) {
      //todo two pass overwrites EXPLICIT_RESOLVE_KEY
      readNSElement(e, e.rcTraverser("cljs"), nsType, Dialect.CLJS)?.let(addResult)
      readNSElement(e, e.rcTraverser("clj"), nsType, Dialect.CLJ)?.let(addResult)
    }
    else {
      readNSElement(e, e.cljTraverser(), nsType, helper.langStack.peek())?.let(addResult)
    }
  }

  fun readNSElement(e: CListBase, traverser: SyntaxTraverser<PsiElement>, nsType: String, dialect: Dialect): Imports? {
    val s = traverser
        .forceDisregard { it is CMetadata || it is CReaderMacro }
        .expand { it !is CKeyword && it !is CSymbol }
        .filter { it is CForm }
    val imports = if (nsType == "ns") {
      val imports = s.expandAndSkip { it == e }.filter(CListBase::class.java).flatMap {
        val name = it.childForm(CForm::class)?.let {
          (it as? CKeyword)?.name ?:
              (it as? CSymbol)?.name /* clojure < 1.9 */
        } ?: return@flatMap emptyList<Import>()
        readNSElement2(it, s.withRoot(it), name, true, dialect)
      }.toList()
      imports
    }
    else {
      val imports = readNSElement2(e, s, nsType, false, dialect)
      imports
    }
    if (imports.isEmpty()) return null
    val scope = e.parentForms.skip(1).filter { it.fastRole != Role.RCOND && it.fastRole != Role.RCOND_S }.first()
    return Imports(imports, dialect, e.textRange, scope?.textRange?.endOffset ?: -1)
  }

  fun readNSElement2(root: CListBase, traverser: SyntaxTraverser<PsiElement>, nsType: String, inNs: Boolean, dialect: Dialect): List<Import> {
    val content = traverser.iterate(root).skip(1)
    return when (nsType) {
      "import" -> readNSElement_import(content, traverser)
      "alias" -> readNSElement_alias(content)
      "refer" -> readNSElement_refer("", content, traverser, nsType).asListOrEmpty()
      "refer-clojure" -> readNSElement_refer(dialect.coreNs, content, traverser, nsType).asListOrEmpty()
      "use", "require", "require-macros" -> readNSElement_require_use(content, traverser, nsType, inNs)
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
      setResolveTo(o, SymKey(name, prefix, "java class"))
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
            setResolveTo(packageSym, SymKey(anyClass, packageName, "java package"))
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
    val aliasQuoted = aliasSym.fastFlags and FLAG_QUOTED != 0
    val nsQuoted = nsSym.fastFlags and FLAG_QUOTED != 0
    val namespace = if (nsQuoted) nsSym?.name ?: "" else ""
    if (aliasQuoted) {
      setResolveTo(aliasSym, SymKey(aliasSym.name, namespace, "alias"))
    }
    if (nsQuoted) {
      setResolveTo(nsSym, SymKey(namespace, "", "ns"))
    }
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
        "rename" -> rename = (iterator.safeNext() as? CMap).childForms(CSymbol::class)
      }
    }
    val namespace = if (forcedNamespace) nsPrefix else (nsSym?.name?.withPackage(nsPrefix) ?: "")
    val alias = aliasSym?.name ?: ""
    setResolveTo(nsSym, SymKey(namespace, "", "ns"))
    setResolveTo(aliasSym, SymKey(alias, namespace, "alias"))
    fun CPForm?.toNames() = traverser.withRoot(this).filter(CSymbol::class.java)
        .transform { sym -> sym.name.also { setResolveTo(sym, SymKey(it, namespace, "def")) } }.toSet()

    val import = Import(nsType, namespace, alias, aliasSym,
        (refer as? CPForm)?.toNames() ?: (refer as? CKeyword)?.let { if (it.name == "all") ALL else null } ?: emptySet(),
        only.toNames(), exclude.toNames(),
        rename?.split(2, true)?.reduce(HashMap()) { map, o ->
          if (o.size == 2) map[o[0].name] = o[1].also { setResolveTo(it, SymKey(it.name, namespace, "alias")) }; map
        } ?: emptyMap())
    return import
  }


  private fun readNSElement_require_use(content: JBIterable<PsiElement>, traverser: SyntaxTraverser<PsiElement>,
                                        nsType: String, inNs: Boolean): List<Import> {
    val iterator = content.iterator()
    val result = SmartList<Import>()
    fun addImport(item: CForm, nsPrefix: String) {
      when (item) {
        is CSymbol -> item.name.withPackage(nsPrefix).let { ns ->
          result.add(Import(nsType, ns, "", null))
          setResolveTo(item, SymKey(ns, "", "ns"))
        }
        is CVec -> result.addAll(readNSElement_refer(nsPrefix, traverser.iterate(item), traverser, nsType).asListOrEmpty())
      }
    }
    for (item in iterator) {
      when (item) {
        is CKeyword -> if (item.name == "as") iterator.safeNext()  // ignore the next form to get it highlighted
        is CSymbol -> addImport(item, "")
        is CLVForm -> if (inNs == (item.fastFlags and FLAG_QUOTED == 0)) {
          if (item is CVec && (item.childForm(CKeyword::class) != null || item.childForm(CLVForm::class) == null)) {
            addImport(item, "")
          }
          else {
            // one-level ns prefix, ns suffixes cannot have dots
            traverser.iterate(item).iterator().apply {
              val prefixSym = safeNext() as? CSymbol ?: return@apply
              val nsPrefix = prefixSym.name
              forEach { e -> addImport(e as? CForm ?: return@forEach, nsPrefix) }
            }
          }
        }
      }
    }
    return result
  }
}

internal class NSDef(
    val key: SymKey,
    val imports: List<Imports>
) : IDef by key

internal data class Imports(
    val imports: List<Import>,
    val dialect: Dialect,
    val range: TextRange,
    val scopeEnd: Int)

internal data class Import(
    val nsType: String,
    val namespace: String,
    val alias: String,
    val aliasSym: CSymbol?,
    val refer: Set<String> = emptySet(),
    val only: Set<String> = emptySet(),
    val exclude: Set<String> = emptySet(),
    val rename: Map<String, Any> = emptyMap()) {
  val isPlatform: Boolean get() = nsType == "import"
}

private fun setData(o: PsiElement?, data: Any?) {
  if (o is CComposite) o.dataImpl = data
}

private fun setFlag(o: PsiElement?, flag: Int) {
  if (o is CComposite) o.flagsImpl = o.flagsImpl or flag
}

private fun resetFlags(o: PsiElement?) {
  if (o is CComposite) o.flagsImpl = 0
}

private fun setResolveTo(o: CSymbol?, key: SymKey) {
  setData(o, ResolveTo(key))
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

fun CList.resolveName(defService: ClojureDefinitionService, refText: String?): String? {
  if (refText == null) return null
  val file = containingFile as CFileImpl
  var result = refText
  file.processImports(object: PsiScopeProcessor {
    override fun execute(o: PsiElement, state: ResolveState): Boolean {
      if (o is PsiQualifiedNamedElement && refText == o.name) {
        result = o.qualifiedName
        return false
      }
      return true
    }
  }, ResolveState.initial(), this, refText, textRange.startOffset, file.placeLanguage(this), false, defService)
  return result
}

fun CListStub.resolveName(name: String?): String? {
  if (name == null) return null
  var prev: CStub? = null
  var cur: CStub? = this
  while (cur != null) {
    cur.childrenStubs.reversed().jbIt()
        .skipWhile { it != prev }
        .filter(CImportStub::class)
        .forEach { stub ->
          val import = stub.import
          if (import.nsType == "require" &&
              import.refer.isEmpty() && import.only.isEmpty() && import.rename.isEmpty()) return@forEach

          if (import.isPlatform) {
            import.refer.forEach {
              if (it.endsWith(".$name")) return it
            }
          }
          else if (!import.exclude.contains(name)) {
            val rename = import.rename[name] as? String
            if (rename != null) return rename.withNamespace(import.namespace)
            if (import.refer.contains(name) || import.only.contains(name)) return name.withNamespace(import.namespace)
            if (import.refer.contains("* all *") || import.only.isEmpty()) {
              // todo process namespace / check alias
            }
          }
        }
    prev = cur
    cur = cur.parentStub
  }
  return name
}