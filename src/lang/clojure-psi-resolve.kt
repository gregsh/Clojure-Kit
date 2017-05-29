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
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.*
import com.intellij.psi.impl.AnyPsiChangeListener
import com.intellij.psi.impl.PsiManagerImpl
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.scope.BaseScopeProcessor
import com.intellij.psi.scope.NameHint
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
import org.intellij.clojure.util.*

val RENAMED_KEY: Key<String> = Key.create("RENAMED_KEY")
val ALIAS_KEY: Key<String> = Key.create("ALIAS_KEY")
val PRIVATE_KEY: Key<Boolean> = Key.create("PRIVATE_KEY")
val DIALECT_KEY: Key<Dialect> = Key.create("DIALECT_KEY")

class ClojureTypeCache(val service: ClojureDefinitionService) {
  companion object {
    val INSTANCE_KEY = ServiceManager.createLazyKey(ClojureTypeCache::class.java)!!
  }

  val map = ContainerUtil.createConcurrentWeakMap<CForm, String>()
  init {
    service.project.messageBus.connect().subscribe(PsiManagerImpl.ANY_PSI_CHANGE_TOPIC, object : AnyPsiChangeListener.Adapter() {
      override fun beforePsiChanged(isPhysical: Boolean) {
        map.clear()
      }
    })
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

  private fun ClojureDefinitionService.javaType(form: CForm?): String? {
    if (form == null) return null
    val ourTypeCache = ClojureTypeCache.INSTANCE_KEY.getValue(project).map
    val cached = ourTypeCache[form] ?: ourTypeGuard.doPreventingRecursion(form, false) {
      val stamp = ourTypeGuard.markStack()
      val type = javaTypeImpl(form) ?: NULL_TYPE
      if (!stamp.mayCacheNow()) type
      else ConcurrencyUtil.cacheOrGet(ourTypeCache, form, type)
    }
    return if (cached === NULL_TYPE) null else cached
  }

  private fun ClojureDefinitionService.javaTypeImpl(form: CForm): String? {
    val formMeta = form.typeHintMeta()?.resolveQualifiedName()
    if (formMeta != null) return formMeta
    return when (form) {
      is CSymbol -> {
        when (form.name) {
          "*out*", "*err*" -> return ClojureConstants.J_WRITER
          "*in*" -> return ClojureConstants.J_READER
          "*ns*" -> return ClojureConstants.C_NAMESPACE
        }
        val target = form.reference.resolve() ?: return null
        val navElement = target.navigationElement
        when {
          navElement === form ->
            form.parent.let {
              when {
                it is CVec && navElement.parent?.parent is CList -> javaType(navElement.nextForm)
                it is CList && it.first?.name == "catch" -> javaType(navElement.prevForm)
                else -> null
              }
            }
          navElement is CForm -> javaType(navElement)
          navElement.asXTarget != null -> (navElement.asXTarget?.resolveStub() as? CListStub)?.run {
            resolveName(this@javaTypeImpl, meta[TYPE_META])
          }
          target is NavigatablePsiElement && target.asNonCPom() != null ->
            if (form.parent.let { it is CSymbol || it is CList && it.first?.name == "catch" }) (target as? PsiQualifiedNamedElement)?.qualifiedName
            else java.getMemberTypes(target).firstOrNull()?.let {
              val index = it.indexOf('<'); if (index > 0) return it.substring(0, index) else it
            }
          else -> null
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
            is CAccess -> when (first.lastChild.elementType) {
              ClojureTypes.C_DOT -> first.symbol.resolveQualifiedName()
              else -> javaType(first.symbol)
            }
            is CSymbol -> when (first.name) {
              "new" -> (first.nextForm as? CSymbol)?.resolveQualifiedName()
              "." -> (first.nextForm as? CSymbol)?.resolveQualifiedName() ?:
                  javaType(first.nextForm?.nextForm) // method call
              ".." -> javaType(first.siblings().filter(CForm::class).last())
              "var" -> ClojureConstants.C_VAR
              else -> javaType(first)
            }
            else -> javaType(first)
          }
        }
      }
      else -> null
    }
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
        NameHint.KEY -> this
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
                o is PsiQualifiedNamedElement && refText == o.qualifiedName ||
                o.asCTarget?.key?.run {
                  type == JS_OBJ && refText == "$namespace.$name" } ?: false) o
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

  override fun isReferenceTo(element: PsiElement?): Boolean {
    return super.isReferenceTo(element)
  }

  override fun bindToElement(element: PsiElement): PsiElement? {
    return super.bindToElement(element)
  }

  override fun handleElementRename(newElementName: String?): PsiElement? {
    element.lastChild.replace(newLeafPsiElement(element.project, newElementName!!))
    return element
  }

  fun processDeclarations(service: ClojureDefinitionService, refText: String?, state: ResolveState, processor: PsiScopeProcessor): Boolean {
    val element = element
    val qualifier = qualifier
    val containingFile = element.containingFile.originalFile as CFileImpl
    val langKind = containingFile.placeLanguage(element)
    val isCljs = langKind == Dialect.CLJS
    val parent = element.parent

    if (parent is CSymbol && element.nextSibling.elementType == ClojureTypes.C_DOT) {
      if (isCljs) return processor.skipResolve()
    }

    if (parent is CSymbol && element.nextSibling.elementType == ClojureTypes.C_SLASH) {
      if (parent.parent is CKeyword) {
        return when (parent.prevSibling.elementType) {
          ClojureTypes.C_COLON -> processor.execute(service.getNamespace(element), state)
          ClojureTypes.C_COLONCOLON -> containingFile.processDeclarations(processor, state.put(ALIAS_KEY, refText), element, element)
          else -> true
        }
      }
      if (!containingFile.processDeclarations(processor, state, element, element)) return false
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
      return true
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
    if (!containingFile.processPrecomputedDeclarations(refText, element, langKind, service, state, processor)) return false

    val lastParentRef = Ref.create<PsiElement>(element)
    if (!processParentForms(langKind, refText, element, service, state, processor, lastParentRef)) return false

    if (!containingFile.processDeclarations(processor, state, lastParentRef.get(), element)) return false
    if (!processNamespace(containingFile.namespace, langKind, state, processor, containingFile)) return false
    if (!processSpecialForms(langKind, refText, element, service, state, processor)) return false
    if (!isCljs) findClass(refText, service)?.let { if (!processor.execute(it, state)) return false }
    else if (refText == "js" && myElement.parent is CConstructor || refText == "Object") {
      return processor.execute(service.getDefinition(refText, "js", JS_OBJ), state)
    }
    return true
  }

  private fun processParentForms(dialect: Dialect, refText: String?, place: CForm,
                                 service: ClojureDefinitionService,
                                 state: ResolveState, processor: PsiScopeProcessor,
                                 lastParentRef: Ref<PsiElement>): Boolean {
    val isCljs = dialect == Dialect.CLJS
    val element = place.thisForm!!
    var prevO: CForm = element
    for (o in prevO.parents().takeWhile { it is CForm }) {
      val type = formType(o as CForm)
      if (o !is CList || type == null) {
        lastParentRef.set(o)
        if (refText == "&") {
          if (o.role == Role.ARG_VEC || o.role == Role.BND_VEC) return processor.skipResolve()
        }
        if (prevO == place) {
          if (o.role == Role.ARG_VEC || o.role == Role.FIELD_VEC) return processor.execute(place, state)
        }
        prevO = o
        continue
      }
      val innerType = if (type.endsWith("->") || type.endsWith("->>")) formType(prevO) ?: type else type
      val isFnLike = ClojureConstants.FN_ALIKE_SYMBOLS.contains(type)
      if (ClojureConstants.OO_ALIKE_SYMBOLS.contains(type)) {
        for (part in (prevO as? CVec ?: o.findChild(Role.FIELD_VEC) as? CVec).iterate(CSymbol::class)) {
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
            }
          }
        }
      }
      else if (type == "defmethod") {
        if (!processBindings(o, "fn", state, processor, myElement)) return false
      }
      else if (o.role == Role.DEF || isFnLike) {
        if (isFnLike) {
          val nameSymbol = (if (isFnLike) o.first.nextForm else o.first) as? CSymbol
          if (nameSymbol != null) {
            if (!processor.execute(nameSymbol.parent.asDef ?: nameSymbol, state)) return false
          }
        }
        // def and fn bindings
        for (prototype in prototypes(o)) {
          if (!prototype.isAncestorOf(prevO)) continue
          if (!processBindings(prototype, "fn", state, processor, myElement)) return false
        }
      }
      else if (ClojureConstants.LET_ALIKE_SYMBOLS.contains(type)) {
        if (!processBindings(o, if (type == "for" || type == "doseq") "for" else "let", state, processor, myElement)) return false
      }
      else if (type == "letfn") {
        for (fn in (o.first.nextForm as? CVec).iterate(CList::class)) {
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
        if (prevO == element && prevO.parent == o || prevO is CList && prevO.firstForm == element) {
          val isProp = innerType == ".-"
          val isProp2 = refText != null && refText.startsWith("-")
          val isMethod = innerType == ". id"
          var scope = if (isProp) JavaHelper.Scope.INSTANCE else JavaHelper.Scope.STATIC
          val isInFirst = o.firstForm.isAncestorOf(element)

          val siblings = if (type.endsWith("->")) JBIterable.of(o.childForms[1])
          else if (type.endsWith("->>")) JBIterable.of(prevO.prevForm)
          else o.firstForm.siblings().filter(CForm::class).skip(if (innerType == "." || innerType == ".." || isInFirst) 1 else 0)

          val index = if (isInFirst) 0 else siblings.takeWhile { !it.isAncestorOf(element) }.size()
          val className = if (isCljs) null
          else siblings.first()?.let { form ->
            if (form == element) return@let null
            if ((innerType == "." || innerType == "..") && form is CSymbol && form.qualifier == null) {
              val resolvedName = form.resolveQualifiedName()
              if (resolvedName != null) {
                scope = JavaHelper.Scope.STATIC
                return@let resolvedName
              }
            }
            scope = JavaHelper.Scope.INSTANCE
            service.javaType(form)
          }
          val javaClass = findClass(className, service)
          if (index == 0 && javaClass != null && (innerType == "." || innerType == "..")) {
            if (!processor.execute(javaClass, state)) return false
          }
          if (javaClass != null || !isCljs) {
            val classNameAdjusted = (javaClass as? PsiQualifiedNamedElement)?.qualifiedName ?: className ?: ClojureConstants.J_OBJECT
            if (innerType == ".." && index >= 2) scope = JavaHelper.Scope.INSTANCE
            val processFields = isProp || isMethod || isInFirst && siblings.size() == 1 || !isInFirst && element.nextForm == null
            val processMethods = !isProp
            if (processFields) {
              val fields = service.java.findClassFields(classNameAdjusted, scope, "*")
              fields.forEach { if (!processor.execute(it, if (isProp) state else state.put(RENAMED_KEY, "-${it.name}"))) return false }
            }
            if (processMethods) {
              val methods = service.java.findClassMethods(classNameAdjusted, scope, StringUtil.notNullize(refText, "*"), -1)
              methods.forEach { if (!processor.execute(it, state)) return false }
            }
          }
          // stop processing in certain cases
          if (index == 0 && (isMethod || isProp) ||
              (isProp2 || isCljs) && (innerType == "." || innerType == ".." && index >= 1)) {
            return processor.skipResolve()
          }
          if (javaClass != null && (isProp || isMethod) && refText == null) {
            return true
          }
        }
      }
      else if (isCljs && type == "this-as") {
        (o.childForms[1] as? CSymbol)?.let {
          if (!processor.execute(service.getLocalBinding(it, o), state)) return false
        }
      }

      prevO = o
      lastParentRef.set(o)
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

fun prototypes(o: CList) = o.iterate(CList::class)
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
  FileBasedIndex.getInstance().getContainingFiles(NS_INDEX, namespace, scope).forEach { file ->
    if (lastFile == file) return@forEach
    val it = lastParent.manager.findFile(file) as? CFile ?: return@forEach
    if (!it.processDeclarations(processor, state.put(DIALECT_KEY, dialect), lastParent, lastParent)) return false
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
      place.parents().find { it.parent == bindings }?.let { o ->
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

fun CFile.placeLanguage(place: PsiElement): Dialect =
    if (language == ClojureScriptLanguage) Dialect.CLJS
    else {
      place.parents().filter { it is CForm && it.prevForm is CKeyword &&
          it.parent.run { role == Role.RCOND || role == Role.RCOND_S }
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

fun CSymbol.resolveQualifiedName() = (reference.resolve().asNonCPom() as? PsiQualifiedNamedElement)?.qualifiedName
fun CForm.typeHintMeta(): CSymbol? = formPrefix().filter(CMetadata::class)
      .map { it.form as? CSymbol }
      .notNulls().first()
fun CForm.keyMetas(): JBIterable<CKeyword> = formPrefix().filter(CMetadata::class)
      .map { it.form as? CKeyword }
      .notNulls()

private val SKIP_RESOLVE = object : PsiScopeProcessor.Event {}
private val DESTRUCTURING = JBTreeTraverser<CForm>(f@ {
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
                  keys = it.iterate()
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
})

