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

import com.intellij.lang.Language
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.RecursionManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.*
import com.intellij.psi.impl.AnyPsiChangeListener
import com.intellij.psi.impl.PsiManagerImpl
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.scope.BaseScopeProcessor
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.ArrayUtil
import com.intellij.util.ConcurrencyUtil
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.JBIterable
import com.intellij.util.containers.JBIterator
import com.intellij.util.containers.JBTreeTraverser
import com.intellij.util.indexing.FileBasedIndex
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.ClojureConstants.CLOJURE_CORE
import org.intellij.clojure.ClojureConstants.JS_OBJ
import org.intellij.clojure.inspections.RESOLVE_SKIPPED
import org.intellij.clojure.java.JavaHelper
import org.intellij.clojure.lang.ClojureScriptLanguage
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.stubs.CListStub
import org.intellij.clojure.psi.stubs.CPrototypeStub
import org.intellij.clojure.util.*

val RENAMED_KEY: Key<String> = Key.create("RENAMED_KEY")
val ALIAS_KEY: Key<String> = Key.create("ALIAS_KEY")
val PRIVATE_KEY: Key<Boolean> = Key.create("PRIVATE_KEY")
val DIALECT_KEY: Key<Dialect> = Key.create("DIALECT_KEY")

val NAME_HINT = Key.create<NameHint>("NameHint")
interface NameHint {
  fun getName(state: ResolveState): String?
}

class ClojureTypeCache(service: ClojureDefinitionService) {
  companion object {
    val INSTANCE_KEY = ServiceManager.createLazyKey(ClojureTypeCache::class.java)!!
  }

  val map = ContainerUtil.createConcurrentWeakMap<CForm, Any>()
  init {
    service.project.messageBus.connect().subscribe(PsiManagerImpl.ANY_PSI_CHANGE_TOPIC, object : AnyPsiChangeListener.Adapter() {
      override fun beforePsiChanged(isPhysical: Boolean) {
        map.clear()
      }
    })
  }
}

fun ClojureDefinitionService.exprType(form: CForm?): Any? {
  if (form == null) return null
  val ourTypeCache = ClojureTypeCache.INSTANCE_KEY.getValue(project).map
  val cached = ourTypeCache[form] ?: CSymbolReference.ourTypeGuard.doPreventingRecursion(form, false) {
    val stamp = CSymbolReference.ourTypeGuard.markStack()
    val type = exprTypeImpl(form) ?: CSymbolReference.NULL_TYPE
    if (!stamp.mayCacheNow()) type
    else ConcurrencyUtil.cacheOrGet(ourTypeCache, form, type)
  }
  return if (cached === CSymbolReference.NULL_TYPE) null else cached
}

private fun ClojureDefinitionService.exprTypeImpl(form: CForm): Any? {
  val formMeta = form.typeHintMeta()?.resolveExprType()
  if (formMeta != null) return formMeta
  return when (form) {
    is CSymbol -> {
      when (form.name) {
        "*out*", "*err*" -> return ClojureConstants.J_WRITER
        "*in*" -> return ClojureConstants.J_READER
        "*ns*" -> return ClojureConstants.C_NAMESPACE
      }
      val target = form.reference.resolve() ?: return null
      val sourceDef = target.sourceDef
      if (sourceDef != null) {
        return SymKey(sourceDef)
      }
      val navElement = target.navigationElement
      when {
        navElement === form -> {
          val p = form.parent
          when {
            p is CVec && p.role == Role.BND_VEC -> exprType(navElement.nextForm)
            p is CVec && p.role == Role.ARG_VEC && p.firstForm == form ||
                target.asCTarget?.key?.run { type == "field" && name == "this" } == true -> {
              var result: Any? = null
              form.parentForms.filter(CList::class).forEachWithPrev { grandP, prev ->
                when (grandP.first?.name) {
                  "extend-protocol" -> {
                    result = (prev.prevForms.filter(CSymbol::class).first())?.resolveExprType()
                    return@forEachWithPrev
                  }
                  "extend" -> {
                    result = grandP.childForms.filter(CSymbol::class).skip(1).first()?.resolveExprType()
                    return@forEachWithPrev
                  }
                  "reify", "proxy" -> {
                    result = exprType(grandP)
                    return@forEachWithPrev
                  }
                }
              }
              result
            }
            p is CList && p.first?.name == "catch" -> exprType(navElement.prevForm)
            else -> null
          }
        }
        navElement is CForm ->
          if (navElement.def?.type == "def") exprType(navElement.findChild(Role.NAME)?.nextForm)
          else exprType(navElement)
        navElement.asCTarget != null -> (navElement.forceXTarget?.resolveStub() as? CListStub)?.run {
          resolveName(this@exprTypeImpl, meta[TYPE_META])
        }
        target is NavigatablePsiElement && target.asNonCPom() != null ->
          if (form.parent.let { it is CSymbol || it is CList && it.first?.name == "catch" }) (target as? PsiQualifiedNamedElement)?.qualifiedName
          else java.getMemberTypes(target).firstOrNull()?.let {
            val index = it.indexOf('<'); if (index > 0) return it.substring(0, index) else it
          }
        else -> null
      }
    }
    is CAccess -> {
      when (form.lastChild.elementType) {
        ClojureTypes.C_DOT -> form.symbol.resolveExprType()
        else -> exprType(form.symbol)
      }
    }
    is CList -> {
      val def = form.asDef?.def
      if (def != null) {
        form.resolveName(this, (def as? Def)?.meta?.get(TYPE_META))
      }
      else {
        val first = form.firstForm
        when (first) {
          is CSymbol -> when (first.name) {
            "new" -> (first.nextForm as? CSymbol)?.resolveExprType()
            "." -> (first.nextForm as? CSymbol)?.resolveExprType() as? String ?: exprType(first.nextForm?.nextForm)
            ".." -> exprType(first.nextForms.last())
            "var" -> ClojureConstants.C_VAR
            "doto" -> exprType(first.nextForm)
            "reify" -> form.childForms.filter(CSymbol::class).skip(1).map(CSymbol::resolveExprType).toList()
            "proxy" -> form.childForms.filter(CVec::class).first().childForms.filter(CSymbol::class).map(CSymbol::resolveExprType).toList()
            else -> exprType(first)
          }
          else -> exprType(first)
        }
      }
    }
    else -> null
  }
}

class CSymbolReference(o: CSymbol, r: TextRange = o.lastChild.textRange.shiftRight(-o.textRange.startOffset)) :
    PsiPolyVariantReferenceBase<CSymbol>(o, r), PsiQualifiedReference {

  override fun getVariants(): Array<out Any> = ArrayUtil.EMPTY_OBJECT_ARRAY

  override fun getReferenceName() = myElement.lastChild.text!!
  override fun getQualifier() = myElement.lastChild.findPrev(CSymbol::class)

  override fun equals(other: Any?): Boolean = (other as? CSymbolReference)?.myElement?.equals(myElement) ?: false
  override fun hashCode() = myElement.hashCode()

  val language: Language get() = myElement.containingFile.language

  override fun resolve(): PsiElement? {
    return super.resolve()
  }

  companion object {
    private object RESOLVER : ResolveCache.PolyVariantResolver<CSymbolReference> {
      override fun resolve(t: CSymbolReference, incompleteCode: Boolean) = t.multiResolveInner()
    }

    val NULL_TYPE = "*unknown*"
    val ourTypeGuard = RecursionManager.createGuard("javaType")!!
  }

  override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> {
    if (myElement.lastChild.elementType == ClojureTypes.C_DOT) {
      myElement.putUserData(RESOLVE_SKIPPED, true)
      return emptyArray()
    }
    val fast = resolveNameOrKeyword()
    if (fast != null) {
      return PsiElementResolveResult.createResults(fast)
    }
    return ResolveCache.getInstance(element.project).resolveWithCaching(this, RESOLVER, true, incompleteCode)
//    return RESOLVER.resolve(this, incompleteCode)
  }

  private fun resolveNameOrKeyword(): PsiElement? {
    val service = ClojureDefinitionService.getInstance(myElement.project)
    val parent = myElement.parent
    if (parent is CKeyword) {
      if (parent.symbol == myElement) {
        return service.getKeyword(parent)
      }
    }
    else if (parent is CList && myElement.role == Role.NAME && parent.def != null) {
      return service.getDefinition(parent)
    }
    val refText = rangeInElement.substring(myElement.text)
    if ((refText == "&form" || refText == "&env")
        && myElement.parentForms.find { it.asDef?.def?.type == "defmacro" } != null) {
      return service.getSymbol(myElement)
    }
    else if (parent is CConstructor && parent.firstForm == myElement) {
      return service.getDefinition(SymKey(refText, "", "#$refText"))
    }
    // anonymous function param
    if (refText.startsWith("%")) {
      myElement.findParent(CFun::class).let {
        if (it != null) return service.getFnParam(myElement)
      }
    }
    return null
  }

  fun multiResolveInner(): Array<ResolveResult> {
    val service = ClojureDefinitionService.getInstance(myElement.project)
    val refText = rangeInElement.substring(myElement.text)
    val result = arrayListOf<PsiElementResolveResult>()
    val nsQualifier = myElement.nextSibling.elementType == ClojureTypes.C_SLASH
    val namespace = (element.containingFile.originalFile as CFileImpl).namespace
    processDeclarations(service, refText, ResolveState.initial(), object : BaseScopeProcessor(), NameHint {
      override fun handleEvent(event: PsiScopeProcessor.Event, associated: Any?) {
        if (event == SKIP_RESOLVE) {
          myElement.putUserData(RESOLVE_SKIPPED, true)
        }
      }

      @Suppress("UNCHECKED_CAST")
      override fun <T : Any?> getHint(hintKey: Key<T>) = when (hintKey) {
        NAME_HINT -> this
        else -> null
      } as T?

      override fun getName(state: ResolveState) = refText

      override fun execute(o: PsiElement, state: ResolveState): Boolean {
        var validResult = true
        val target = when {
          o is CList && o.role == Role.DEF ->
            if (!nsQualifier && refText == o.def!!.name) service.getDefinition(o) else null
          o is CKeyword ->
            if (!nsQualifier && refText == o.symbol.name) service.getKeyword(o) else null
          o is CSymbol ->
            if (!nsQualifier && refText == o.name) service.getSymbol(o) else null
          o is PsiNamedElement -> {
            if (state.get(PRIVATE_KEY) == true) {
              validResult = o.asCTarget?.key?.namespace == namespace
            }
            if (refText == state.get(RENAMED_KEY) || refText == o.name ||
                o.asCTarget?.key?.run { type == JS_OBJ && refText == "$namespace.$name" } == true ||
                o is PsiQualifiedNamedElement && getJvmName(o)?.run { this == refText || endsWith(".$refText") } == true) o
            else null
          }
          else -> null
        }
        if (target != null) {
          myElement.putUserData(RESOLVE_SKIPPED, null)
          result.add(PsiElementResolveResult(target, validResult))
          return false
        }
        return true
      }
    })
    return if (result.isEmpty()) PsiElementResolveResult.EMPTY_ARRAY else result.toTypedArray()
  }

  override fun isReferenceTo(element: PsiElement): Boolean {
    return super.isReferenceTo(element)
  }

  override fun bindToElement(element: PsiElement): PsiElement? {
    return super.bindToElement(element)
  }

  override fun handleElementRename(newElementName: String): PsiElement? {
    element.lastChild.replace(newLeafPsiElement(element.project, newElementName))
    return element
  }

  fun processDeclarations(service: ClojureDefinitionService, refText: String?, state: ResolveState, processor: PsiScopeProcessor): Boolean {
    val element = element
    val qualifier = qualifier
    val containingFile = element.containingFile.originalFile as CFileImpl
    val langKind = containingFile.placeLanguage(element)
    val isCljs = langKind == Dialect.CLJS
    val parent = element.context

    if (parent is CSymbol && element.nextSibling.elementType == ClojureTypes.C_DOT) {
      if (isCljs) return processor.skipResolve()
    }

    if (parent is CSymbol && element.nextSibling.elementType == ClojureTypes.C_SLASH) {
      return processNamespacePartBeforeSlash(langKind, refText, service, state, processor)
    }
    if (parent.let { it is CReaderMacro && it.firstChild.elementType == ClojureTypes.C_SHARP_NS}) {
      return when (element.prevSibling.elementType) {
        ClojureTypes.C_COLON -> processor.execute(service.getNamespace(element), state)
        ClojureTypes.C_COLONCOLON -> containingFile.processDeclarations(
            processor, state.put(ALIAS_KEY, refText), element, element)
        else -> true
      }
    }

    // regular qualified resolve
    if (qualifier != null) {
      val resolve = qualifier.reference.resolve() ?: return true
      val target = resolve.asCTarget
      // name.space/definition
      if (target != null) {
        val targetNS = target.key.let {
          if (it.type == "alias") it.namespace
          else if (it.type == "ns") it.name
          else null
        }
        if (targetNS != null) {
          if (isKeysDestructuringVec(myElement.context)) {
            return processor.execute(myElement, state)
          }
          if (targetNS == CLOJURE_CORE && ClojureConstants.SPECIAL_FORMS.contains(refText)) {
            return processor.execute(element, state)
          }
          if (isCljs && targetNS.prefixedBy(ClojureConstants.JS_NAMESPACES)) return processor.skipResolve()
          if (targetNS == containingFile.namespace) {
            if (!containingFile.processDeclarations(processor, state, element, element)) return false
          }
          if (!processNamespace(targetNS, langKind, state, processor, containingFile)) return false
        }
      }
      else if (resolve is PsiQualifiedNamedElement && !service.java.getMemberTypes(target).isEmpty()) {
        // java.class/method-or-field
        val processFields = parent !is CList || parent.first != element
        val processMethods = !processFields || element.firstChild is CReaderMacro
        if (processFields) {
          val fields = service.java.findClassFields(resolve.qualifiedName, JavaHelper.Scope.STATIC, StringUtil.notNullize(refText, "*"))
          fields.forEach { if (!processor.execute(it, state)) return false }
        }
        if (processMethods) {
          val methods = service.java.findClassMethods(resolve.qualifiedName, JavaHelper.Scope.STATIC, StringUtil.notNullize(refText, "*"), -1)
          methods.forEach { if (!processor.execute(it, state)) return false }
        }
      }
      else {
        if (!resolve.processDeclarations(processor, state, qualifier, qualifier)) return false
      }
      return true
    }
    if (!processPrecomputedDeclarations(langKind, refText, element, service, state, processor, containingFile)) return false

    if (!processParentForms(langKind, refText, element, service, state, processor)) return false
    if (!containingFile.processDeclarations(processor, state, element, element)) return false

    if (!processNamespace(containingFile.namespace, langKind, state, processor, containingFile)) return false
    if (!processSpecialForms(langKind, refText, element, service, state, processor)) return false
    if (!isCljs) findClass(refText, service)?.let { if (!processor.execute(it, state)) return false }
    else if (refText == "Object") return processor.execute(service.getDefinition(refText, "", JS_OBJ), state)
    return true
  }

  private fun processNamespacePartBeforeSlash(dialect: Dialect, refText: String?, service: ClojureDefinitionService, state: ResolveState, processor: PsiScopeProcessor): Boolean {
    val containingFile = element.containingFile.originalFile as CFileImpl
    val isCljs = dialect == Dialect.CLJS
    val parent = element.context

    val grandParent = parent?.context
    if (grandParent is CKeyword) {
      return when (parent.prevSibling.elementType) {
        ClojureTypes.C_COLON -> processor.execute(service.getNamespace(element), state)
        ClojureTypes.C_COLONCOLON -> containingFile.processDeclarations(processor, state.put(ALIAS_KEY, refText), element, element)
        else -> true
      }
    }
    else if (isKeysDestructuringVec(grandParent)) {
      return processor.execute(service.getNamespace(element), state)
    }
    if (!containingFile.processDeclarations(processor, state, element, element)) return false
    if (refText != null) {
      if (!FileBasedIndex.getInstance().getFilesWithKey(NS_INDEX, setOf(refText), { false },
              ClojureDefinitionService.getClojureSearchScope(service.project))) {
        if (!processor.execute(service.getNamespace(element.name), state)) return false
      }

      if (!isCljs) {
        findClass(refText, service)?.let { return processor.execute(it, state) }
      }
      if (isCljs && ClojureConstants.JS_NAMESPACES.let { refText.isIn(it) || refText.prefixedBy(it) }) {
        return processor.skipResolve()
      }
    }
    return true
  }

  private fun processParentForms(dialect: Dialect, refText: String?, place: CForm,
                                 service: ClojureDefinitionService,
                                 state: ResolveState,
                                 processor: PsiScopeProcessor): Boolean {
    val isCljs = dialect == Dialect.CLJS
    val element = place.thisForm!!
    element.contexts().filter(CForm::class).forEachWithPrev { o, prevO ->
      val type = formType(o)
      if (o !is CList || type == null) {
        if (refText == "&") {
          if (o.role == Role.ARG_VEC || o.role == Role.BND_VEC) return processor.skipResolve()
        }
        if (prevO == place) {
          if (o.role == Role.ARG_VEC || o.role == Role.FIELD_VEC) return processor.execute(place, state)
        }
        return@forEachWithPrev
      }
      val innerType = if (prevO != null && (type.endsWith("->") || type.endsWith("->>") || type == "doto")) formType(prevO) else type
      val isFnLike = ClojureConstants.FN_ALIKE_SYMBOLS.contains(type)
      if (ClojureConstants.OO_ALIKE_SYMBOLS.contains(type)) {
        for (part in (if (prevO.role == Role.FIELD_VEC) prevO else o.findChild(Role.FIELD_VEC) as? CVec).childForms(CSymbol::class)) {
          if (!processor.execute(part, state)) return false
        }
        if (prevO is CList) {
          if (prevO.def != null) {
            if (!processor.execute(prevO, state)) return false
          }
          else {
            val methodName = prevO.first?.name
            if (methodName != null) {
              val protocolSym = if (type == "extend-protocol") o.childForms[1] as? CSymbol
              else prevO.prevSiblings().skip(1).find { it is CSymbol && it.role != Role.NAME }
              val protocol = protocolSym?.reference?.resolve()
              val protocolKey = protocol.asCTarget?.key

              if (protocolKey != null) {
                if (!processor.execute(service.getDefinition(methodName, protocolKey.namespace, "method"), state)) return false
              }
              else if (protocol is PsiQualifiedNamedElement) {
                val methods = service.java.findClassMethods(protocol.qualifiedName, JavaHelper.Scope.INSTANCE, StringUtil.notNullize(refText, "*"), -1)
                methods.forEach { if (!processor.execute(it, state)) return false }
              }
              for (prototype in prototypes(prevO)) {
                if (!prototype.isAncestorOf(myElement)) continue
                if (!processBindings(prototype, "fn", state, processor, myElement)) return false
              }
              if (type == "proxy" || type == "reify") {
                if (refText == "this" && place is CSymbol) {
                  if (!processor.execute(service.getImplicitField(place, o), state)) return false
                }
              }
            }
          }
        }
      }
      else if (type == "defmethod") {
        if (!processBindings(o, "fn", state, processor, myElement)) return false
      }
      else if (o.role == Role.DEF || isFnLike) {
        if (o.first === prevO) return@forEachWithPrev
        if (isFnLike) {
          val nameSymbol = (if (isFnLike) o.first.nextForm else o.first) as? CSymbol
          if (nameSymbol != null) {
            if (!processor.execute(nameSymbol.context.asDef ?: nameSymbol, state)) return false
          }
        }
        // def and fn bindings
        for (prototype in prototypes(o)) {
          if (prevO != null && !prototype.isAncestorOf(prevO)) continue
          if (!processBindings(prototype, "fn", state, processor, myElement)) return false
        }
      }
      else if (ClojureConstants.LET_ALIKE_SYMBOLS.contains(type)) {
        if (!processBindings(o, if (type == "for" || type == "doseq") "for" else "let", state, processor, myElement)) return false
      }
      else if (type == "letfn") {
        for (fn in (o.first.nextForm as? CVec).childForms(CList::class)) {
          val nameSymbol = fn.first
          if (nameSymbol != null) {
            if (!processor.execute(nameSymbol, state)) return false
          }
          for (prototype in prototypes(fn)) {
            if (!prototype.isAncestorOf(myElement)) continue
            if (!processBindings(prototype, "fn", state, processor, myElement)) return false
          }
        }
      }
      else if (type == "as->") {
        val nameSymbol = o.childForms[2] as? CSymbol
        if (nameSymbol != null && !processor.execute(nameSymbol, state)) return false
      }
      else if (type == "catch") {
        val exception = o.childForms[2] as? CSymbol
        if (exception!= null &&  !processor.execute(exception, state)) return false
      }
      else if (innerType == "." || innerType == ".." || innerType == ".-" || innerType == ". id") {
        if (prevO == element && prevO.context == o || prevO is CList && prevO.firstForm == element) {
          val isProp = innerType == ".-"
          val isMethod = innerType == ". id"
          var scope = if (isProp) JavaHelper.Scope.INSTANCE else JavaHelper.Scope.STATIC
          val isInFirst = o.firstForm.isAncestorOf(element)
          val isDots = innerType == "." || innerType == ".."
          val isDotId = isProp || isMethod
          var isInFirstAdjusted = isInFirst

          val siblings = if (type.endsWith("->") || type.endsWith("->>") || type == "..") {
            val first = o.firstForm
            prevO.prevForms.takeWhile { it != first }
          }
          else if (type == "doto") {
            JBIterable.of(o.childForms[1])
          }
          else if (isInFirst) {
            val p = o.parentForm
            val type = if (p != null && p.firstForm.nextForm != o) formType(p) ?: "" else ""
            if (type.endsWith("->") || type.endsWith("->>") || type == "..") {
              isInFirstAdjusted = false
              val first = p.firstForm
              o.prevForms.takeWhile { it != first }
            }
            else if (type == "doto") {
              isInFirstAdjusted = false
              JBIterable.of(p.childForms[1])
            }
            else {
              o.firstForm.nextForms.skip(1).takeWhile { it != prevO }
            }
          }
          else {
            o.firstForm.nextForms.skip(if (isDots) 1 else 0).takeWhile { it != prevO }
          }

          val index = if (isInFirstAdjusted) 0 else siblings.size()
          val exprTypes = (if (isCljs || isDotId && prevO !is CAccess) null
          else siblings.first()?.let expr@ { form ->
            if (form == element) return@expr null
            val sym = form as? CSymbol ?: (form as? CAccess)?.symbol
            if (sym != null && sym.qualifier == null && form.lastChild.elementType != ClojureTypes.C_DOT &&
                (isDotId && prevO !is CAccess|| isDots)) {
              if (!isDots || index > 1) {
                val target = sym.reference.resolve() as? NavigatablePsiElement
                if (target != null && target !is PsiQualifiedNamedElement) {
                  val ret = service.java.getMemberTypes(target).firstOrNull()
                  if (ret != null) {
                    scope = JavaHelper.Scope.INSTANCE
                    val idx = ret.indexOf('<')
                    return@expr if (idx > 0) ret.substring(0, idx) else ret
                  }
                  return@expr null
                }
              }
              val resolvedName = sym.resolveExprType() as? String // only class names
              if (resolvedName != null) {
                scope = JavaHelper.Scope.STATIC
                return@expr resolvedName
              }
            }
            scope = JavaHelper.Scope.INSTANCE
            service.exprType(form)
          })
              .let {
                val iterable = if (it is Iterable<*>) JBIterable.from(it) else JBIterable.of(it)
                if (isCljs) iterable
                else iterable.append(ClojureConstants.J_OBJECT)
                    .map { ClojureConstants.J_BOXED_TYPES[it]
                        ?: if (it is String && it.startsWith("<")) ClojureConstants.J_OBJECT else it } }
              .unique()
          if (innerType == ".." && index >= 2) scope = JavaHelper.Scope.INSTANCE
          val processFields = isDotId || isInFirst && siblings.size() == 1 || !isInFirst && element.nextForm == null
          val processMethods = !isProp
          for (exprType in exprTypes) when (exprType) {
            is SymKey -> {
              val def = service.getDefinition(exprType)
              val stub = def.forceXTarget?.resolveStub() as? CListStub
              if (processFields && scope == JavaHelper.Scope.INSTANCE) {
                (stub?.childrenStubs?.find { it is CPrototypeStub } as? CPrototypeStub)?.args?.onEach {
                  val fk = SymKey(it.name, stub.key.name.withPackage(stub.key.namespace), "field")
                  if (!processor.execute(service.getDefinition(fk), if (isProp) state else state.put(RENAMED_KEY, "-${fk.name}"))) return false
                }
              }
              if (processMethods && scope == JavaHelper.Scope.INSTANCE) {
                stub?.childrenStubs?.onEach {
                  if (it is CListStub && !processor.execute(service.getDefinition(it.key), state)) return false
                }
              }
            }
            is String -> {
              val javaClass = findClass(exprType, service)
              if (isDots && index == 0 && javaClass != null) {
                if (!processor.execute(javaClass, state)) return false
              }
              if (javaClass != null || !isCljs) {
                val classNameAdjusted = (javaClass as? PsiQualifiedNamedElement)?.qualifiedName ?: exprType
                if (processFields) {
                  val fields = service.java.findClassFields(classNameAdjusted, scope, "*")
                  fields.forEach { if (!processor.execute(it, if (isProp) state else state.put(RENAMED_KEY, "-${it.name}"))) return false }
                }
                if (processMethods) {
                  val methods = service.java.findClassMethods(classNameAdjusted, scope, StringUtil.notNullize(refText, "*"), -1)
                  methods.forEach { if (!processor.execute(it, state)) return false }
                }
              }
            }
          }
          if (prevO is CAccess ||
              innerType == ".." && o.firstForm != prevO.prevForm ||
              innerType == "." && o.firstForm == prevO.prevForm.prevForm) {
            processor.skipResolve()
          }
          if (isDotId && refText == null) {
            return true
          }
        }
      }
      else if (isCljs && type == "this-as") {
        (o.childForms[1] as? CSymbol)?.let {
          if (!processor.execute(service.getLocalBinding(it, o), state)) return false
        }
      }
    }
    return true
  }

  private fun findClass(refText: String?, service: ClojureDefinitionService): PsiElement? {
    if (refText == null) return null
    if (refText.contains(".")) return service.java.findClass(refText)
    // clojure.lang.RT.DEFAULT_IMPORTS
    for (p in listOf("java.util.concurrent.Callable", "java.math.BigDecimal", "java.math.BigInteger")) {
      val d = p.length - refText.length
      if (d > 0 && p[d - 1] == '.' && p.endsWith(refText)) {
        return service.java.findClass(p)
      }
    }
    for (p in listOf("clojure.lang", "java.lang")) {
      return service.java.findClass("$p.$refText") ?: continue
    }
    return null
  }

}

fun prototypes(o: CList) = o.childForms(CList::class)
    .filter { it.firstChild?.nextSibling is CVec }.append(o)

fun formType(o: CForm): String? {
  val type = o.asDef?.def?.type
  if (type != null) return type
  val first = (o as? CList)?.firstForm ?: o
  if (first is CSymbol) return first.name
  else if (first is CAccess) {
    val last = first.lastChild
    if (last.elementType == ClojureTypes.C_DOT) return ". new"
    else if (last is CSymbol && last.prevSibling.elementType == ClojureTypes.C_DOTDASH) return ".-"
    else return ". id"
  }
  return null
}

fun findBindingsVec(o: CList, mode: String): CVec? {
  return o.findChild(if (mode == "fn") Role.ARG_VEC else Role.BND_VEC) as? CVec
}

fun destruct(o: CForm?) = DESTRUCTURING.withRoot(o).traverse()

fun processNamespace(namespace: String, dialect: Dialect, state: ResolveState, processor: PsiScopeProcessor, lastParent: CFile): Boolean {
  if (state.get(ALIAS_KEY) != null) return true
  val lastFile = PsiUtilCore.getVirtualFile(lastParent)
  val scope = ClojureDefinitionService.getClojureSearchScope(lastParent.project)
  val nsFiles = FileBasedIndex.getInstance().getContainingFiles(NS_INDEX, namespace, scope)
  for (file in nsFiles) {
    if (lastFile == file) continue
    val psiFile = lastParent.manager.findFile(file) as? CFile
    if (psiFile != null && !psiFile.processDeclarations(processor, state.put(DIALECT_KEY, dialect), lastParent, lastParent)) return false
  }
  return true
}

fun processSpecialForms(dialect: Dialect, refText: String?, place: PsiElement, service: ClojureDefinitionService, state: ResolveState, processor: PsiScopeProcessor): Boolean {
  val isCljs = dialect == Dialect.CLJS
  val forms = if (isCljs) ClojureConstants.CLJS_SPECIAL_FORMS else ClojureConstants.SPECIAL_FORMS
  // special unqualified forms
  if (refText != null) {
    if (forms.contains(refText)) {
      return processor.execute(service.getDefinition(refText, dialect.coreNs, "def"), state)
    }
    // reserved bindings '*ns*' and etc.
    if (refText.startsWith("*") && refText.endsWith("*")) {
      return processor.execute(place, state)
    }
  }
  else {
    forms.forEach { if (!processor.execute(service.getDefinition(it, dialect.coreNs, "def"), state)) return false }
  }
  return true
}

fun processBindings(element: CList, mode: String, state: ResolveState, processor: PsiScopeProcessor, place: PsiElement): Boolean {
  val bindings = findBindingsVec(element, mode) ?: return true
  if (!processor.execute(bindings, state)) return false
  val roots = when (mode) {
    "fn" -> JBIterable.of(bindings)
    "let" -> bindings.childForms.filter(EachNth(2)).run {
      place.contexts().find { it.context == bindings }?.let { o ->
        if (contains(o)) takeWhile { it != o }.append(o as CForm)
        else o.prevForm!!.let { o0 -> takeWhile { it != o0 } }
      } ?: this
    }
    "for" -> bindings.childForms.filter(EachNth(2)).transform {
      if (it is CKeyword && it.text == ":let") it.nextForm as? CVec else it
    }.notNulls()
    else -> throw AssertionError("processBindings(): unknown mode: $mode")
  }
  DESTRUCTURING.withRoots(roots).forEach {
    if (!processor.execute(it, state)) return false
  }
  return true
}

private fun isKeysDestructuringVec(e : PsiElement?) =
    e is CVec && e.prevForm?.let { it is CKeyword && it.name == "keys" } == true

fun CFile.placeLanguage(place: PsiElement): Dialect =
    if (language == ClojureScriptLanguage) Dialect.CLJS
    else {
      place.contexts().filter { it is CForm && it.prevForm is CKeyword &&
          it.context.run { role == Role.RCOND || role == Role.RCOND_S }
      }.first()?.let { e ->
        if ((e.prevForm as? CKeyword)?.name == "cljs") Dialect.CLJS
        else Dialect.CLJ // ":clj", ":default"
      } ?: Dialect.CLJ
    }

fun PsiScopeProcessor.skipResolve() =
    handleEvent(SKIP_RESOLVE, null).run { false }

private fun PsiElement?.asNonCPom() =
    if (this !is PomTargetPsiElement || target !is CTarget) this as? PsiNamedElement
    else null

private fun CSymbol.resolveExprType() = reference.resolve()?.let {
  it.asCTarget?.key ?: (it as? PsiQualifiedNamedElement)?.qualifiedName }

fun CForm.typeHintMeta(): CSymbol? = formPrefix().filter(CMetadata::class)
      .map { it.form as? CSymbol }
      .notNulls().first()
fun CForm.keyMetas(): JBIterable<CKeyword> = formPrefix().filter(CMetadata::class)
      .map { it.form as? CKeyword }
      .notNulls()

fun getJvmName(o: PsiQualifiedNamedElement): String? {
  if (o.context !is PsiQualifiedNamedElement || o.containingFile == null) return o.qualifiedName
  val sb = StringBuilder()
  o.contexts().forEachWithPrev { cur, prev ->
    if (prev == null) return@forEachWithPrev
    else if (!sb.isEmpty()) sb.insert(0, '$')
    if (cur is PsiQualifiedNamedElement) sb.insert(0, (prev as PsiQualifiedNamedElement).name)
    else { sb.insert(0, (prev as PsiQualifiedNamedElement).qualifiedName); return sb.toString() }
  }
  throw AssertionError(o.javaClass.name)
}

private val SKIP_RESOLVE = object : PsiScopeProcessor.Event {}
private val DESTRUCTURING = JBTreeTraverser<CForm> f@ {
  return@f when (it) {
    is CVec -> {
      it.childForms.filter { (it !is CSymbol) || it.text != "&" }
    }
    is CMap -> {
      it.childForms.intercept { delegate: Iterator<CForm> ->
        object : JBIterator<CForm>() {
          var first = true // key-value switcher
          var skip: Boolean? = null
          var keys: Iterator<CForm>? = null
          override fun nextImpl(): CForm? {
            if (keys != null && keys?.hasNext() ?: run { keys = null; false }) return keys?.next()
            if (!delegate.hasNext()) return stop()
            val t = delegate.next()
            first = !first
            if (first && skip != false || skip == true) {
              if (skip != null) skip = null
              return skip()
            }
            if (t is CKeyword) {
              val key = t.name
              when (key) {
                "or" -> skip = true
                "as" -> skip = false
                "keys", "syms", "strs" -> (t.nextForm as? CVec)?.let {
                  skip = true
                  keys = it.childForms
                      .transform { (it as? CKeyword)?.symbol ?: it }
                      .filter(CSymbol::class).iterator()
                }
              }
              return skip()
            }
            return t
          }
        }
      }
    }
    else -> JBIterable.empty()
  }
}

