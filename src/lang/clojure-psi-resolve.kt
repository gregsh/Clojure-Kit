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

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.Language
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.RecursionManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.Navigatable
import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.*
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.scope.BaseScopeProcessor
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.stubs.StubIndex
import com.intellij.util.ConcurrencyUtil
import com.intellij.util.containers.*
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.ClojureConstants.CLJS_CORE
import org.intellij.clojure.ClojureConstants.CLOJURE_CORE
import org.intellij.clojure.ClojureIcons
import org.intellij.clojure.inspections.RESOLVE_SKIPPED
import org.intellij.clojure.java.JavaHelper
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.lang.ClojureScriptLanguage
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.stubs.CKeywordStub
import org.intellij.clojure.psi.stubs.KEYWORD_INDEX_KEY
import org.intellij.clojure.psi.stubs.NS_INDEX_KEY
import org.intellij.clojure.util.*
import java.util.*
import java.util.HashMap
import java.util.concurrent.ConcurrentMap

val RENAMED_KEY: Key<String> = Key.create("RENAMED_KEY")
val ALIAS_KEY: Key<String> = Key.create("ALIAS_KEY")

class CSymbolReference(o: CSymbol, r: TextRange = o.lastChild.textRange.shiftRight(-o.textRange.startOffset)) :
    PsiPolyVariantReferenceBase<CSymbol>(o, r), PsiQualifiedReference {
  override fun getReferenceName() = myElement.lastChild.text!!
  override fun getQualifier() = myElement.lastChild.findPrevSibling(CSymbol::class)

  override fun equals(other: Any?): Boolean = (other as? CSymbolReference)?.myElement?.equals(myElement) ?: false
  override fun hashCode() = myElement.hashCode()
  val language: Language get() = myElement.containingFile.language

  override fun getVariants(): Array<Any> {
    val service = ClojureDefinitionService.getInstance(myElement.project)
    val result = arrayListOf<Any>()
    val major = myElement.findParent(CStubBase::class)
    if (major is CKeyword) {
      return StubIndex.getInstance().getAllKeys(KEYWORD_INDEX_KEY, major.project).toTypedArray()
    }

    processDeclarations(service, null, ResolveState.initial(), object : BaseScopeProcessor() {
      override fun execute(it: PsiElement, state: ResolveState): Boolean {
        when (it) {
          NULL_FORM -> null
          is CDef -> LookupElementBuilder.create(it, state.get(RENAMED_KEY) ?: it.def.name)
              .withIcon((it as NavigationItem).presentation!!.getIcon(false))
              .withTailText(" (${it.def.namespace})", true)
          is CSymbol -> LookupElementBuilder.create(it, it.name)
              .withIcon(ClojureIcons.SYMBOL)
          is PsiNamedElement -> LookupElementBuilder.create(it, state.get(RENAMED_KEY) ?: it.name!!)
              .withIcon(it.getIcon(0))
          else -> null
        }?.let { result.add(it) }
        return true
      }
    })
    return result.toTypedArray()
  }

  override fun resolve(): PsiElement? {
    return super.resolve()
  }

  companion object {
    private object RESOLVER : ResolveCache.PolyVariantResolver<CSymbolReference> {
      override fun resolve(t: CSymbolReference, incompleteCode: Boolean): Array<out ResolveResult> =
          PsiElementResolveResult.createResults(t.multiResolveInner())
    }

    val NULL_TYPE = "*unknown*"
    val ourTypeGuard = RecursionManager.createGuard("javaType")!!
    val ourTypeCache: ConcurrentMap<CForm, String> = ContainerUtil.createConcurrentWeakKeySoftValueMap<CForm, String>()
  }

  private fun ClojureFileImpl.javaType(form: CForm?): String? {
    if (form == null) return null
    val cached = ourTypeCache[form] ?: ourTypeGuard.doPreventingRecursion(form, false) {
      val stamp = ourTypeGuard.markStack()
      val type = javaTypeImpl(form) ?: NULL_TYPE
      if (!stamp.mayCacheNow()) type
      else ConcurrencyUtil.cacheOrGet(ourTypeCache, form, type)
    }
    return if (cached === NULL_TYPE) null else cached
  }

  private fun ClojureFileImpl.javaTypeImpl(form: CForm): String? {
    fun CForm.javaTypeMeta() = metas.firstOrNull()?.let {
      ((it.form as? CSymbol)?.reference?.resolve() as? PsiQualifiedNamedElement)?.qualifiedName }
    return form.javaTypeMeta()  ?: when (form) {
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
                it is CVec && navElement.parent?.parent is CList -> javaType(navElement.nextForm())
                it is CList && it.first?.name == "catch" -> javaType(navElement.prevForm())
                else -> null
              }
            }
          navElement is CForm -> javaType(navElement)
          target is NavigatablePsiElement && target !is PomTargetPsiElement ->
            if (form.parent.let { it is CSymbol || it is CList && it.first?.name == "catch" }) (target as? PsiQualifiedNamedElement)?.qualifiedName
            else ClojureDefinitionService.getInstance(project).java.getMemberTypes(target).firstOrNull()?.let {
              val index = it.indexOf('<'); if (index > 0) return it.substring(0, index) else it
            }
          else -> null
        }
      }
      is CDef -> form.nameSymbol?.let {
        it.javaTypeMeta() ?: it.findNextSibling(CVec::class)?.javaTypeMeta()
      }
      is CList -> form.first?.let {
        when (it.name) {
          "new" -> ((it.nextForm() as? CSymbol)?.reference?.resolve() as? PsiQualifiedNamedElement)?.qualifiedName
          "." -> javaType(it.firstChild as? CSymbol) ?:
              ((it.nextForm() as? CSymbol)?.reference?.resolve() as? PsiQualifiedNamedElement)?.qualifiedName ?:
              javaType(it.nextForm()?.nextForm())
          ".." -> javaType(it.siblings().filter(CForm::class).last())
          "var" -> ClojureConstants.C_VAR
          else -> javaType(it)
        }
      }
      else -> null
    }
  }

  override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> {
    return if (myElement.lastChild.elementType == ClojureTypes.C_DOT) {
      myElement.putUserData(RESOLVE_SKIPPED, true)
      emptyArray()
    }
    else resolveNameOrKeyword()?.let { PsiElementResolveResult.createResults(it) } ?:
    ResolveCache.getInstance(element.project).resolveWithCaching(this, RESOLVER, true, incompleteCode)
  }

  private fun resolveNameOrKeyword(): PsiElement? {
    val service = ClojureDefinitionService.getInstance(myElement.project)
    val major = myElement.findParent(CStubBase::class)
    if (major is CKeyword) {
      return service.getKeyword(major)
    }
    else if (major is CDef) {
      if (major.nameSymbol == myElement) {
        return service.getDefinition(major)
      }
    }
    val refText = rangeInElement.substring(myElement.text)
    if ((refText == "&form" || refText == "&env" || refText.endsWith("#"))
        && myElement.findParent(CDef::class)?.def?.type == "defmacro") {
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

  fun multiResolveInner(): List<PsiElement> {
    val service = ClojureDefinitionService.getInstance(myElement.project)
    val refText = rangeInElement.substring(myElement.text)
    val result = arrayListOf<PsiElement>()
    val nsQualifier = myElement.nextSibling.elementType == ClojureTypes.C_SLASH
    processDeclarations(service, refText, ResolveState.initial(), object : BaseScopeProcessor() {
      override fun execute(it: PsiElement, state: ResolveState): Boolean {
        val target = when (it) {
          NULL_FORM -> {
            myElement.putUserData(RESOLVE_SKIPPED, true)
            null
          }
          is CDef -> if (nsQualifier) null else
            if (refText == state.get(RENAMED_KEY) || refText == it.def.name) service.getDefinition(it)
            else null
          is CKeyword -> if (nsQualifier) null else
            if (refText == it.symbol.name) service.getKeyword(it)
            else null
          is CSymbol -> if (refText == it.name) service.getSymbol(it) else null
          is PsiNamedElement -> if (refText == state.get(RENAMED_KEY) || refText == it.name ||
              it is PsiQualifiedNamedElement && refText == it.qualifiedName) it else null
//          is PomTargetPsiElement -> if (refText == (it.target as? PomNamedTarget)?.name) it else null
          else -> null
        }
        if (target != null) {
          myElement.putUserData(RESOLVE_SKIPPED, null)
          result.add(target)
          return false // todo
        }
        return true
      }
    })
    return result
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
    val containingFile = element.containingFile.originalFile as ClojureFileImpl
    val language = containingFile.language
    // constructor reference 'java.lang.String.'
    if (qualifier != null && refText == ".") return processor.execute(NULL_FORM, state)
    val isCljs = language == ClojureScriptLanguage

    if (element.parent is CSymbol && element.nextSibling.elementType == ClojureTypes.C_SLASH) {
      if (!containingFile.processFileImports(containingFile.imports, processor, state, element)) return false
      if (!isCljs) {
        findClass(refText, service)?.let { return processor.execute(it, state) }
      }
      if ((service.getNamespace(element).navigationElement as? Navigatable)?.canNavigate() ?: false) {
        if (!processor.execute(service.getNamespace(element), state)) return false
      }
      if (isCljs && refText == ClojureConstants.JS_GOOG) {
        return processor.execute(NULL_FORM, state)
      }
    }
    if (element.parent.let { it is CReaderMacro && it.firstChild.elementType == ClojureTypes.C_SHARP_NS}) {
      return when (element.prevSibling.elementType) {
        ClojureTypes.C_COLON -> processor.execute(service.getNamespace(element), state)
        ClojureTypes.C_COLONCOLON -> containingFile.processFileImports(
            containingFile.imports, processor, state.put(ALIAS_KEY, element.name), element)
        else -> true
      }
    }

    // regular qualified resolve
    if (qualifier != null) {
      val resolve = qualifier.reference.resolve() ?: return true
      val target = (resolve as? PomTargetPsiElement)?.target as? CTarget
      if (target != null) {
        // name.space/definition
        if (target.key.type == "alias" || target.key.type == "ns" || target.key.type == "in-ns") {
          if (target.key.namespace == CLOJURE_CORE && ClojureConstants.SPECIAL_FORMS.contains(refText)) {
            return processor.execute(element, state)
          }
          if (isCljs && target.key.namespace.startsWith(ClojureConstants.JS_GOOG_DOT)) return processor.execute(NULL_FORM, state)
          if (!containingFile.processNamespace(target.key.namespace, true, state, processor)) return false
        }
      }
      else if (resolve is PsiQualifiedNamedElement && !service.java.getMemberTypes(target).isEmpty()) {
        // java.class/method-or-field
        val parent = element.parent
        val processFields = parent !is CLForm || parent.first != element
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

    // regular unqualified resolve
    var prevO: CForm = element
    for (o in element.parents().filter(CLForm::class)) {
      if (prevO == element && refText != null) {
        if (o is CDefImpl && o.nameSymbol == prevO) {
          return processor.execute(o, state)
        }
      }
      val type = listType(o) ?: continue
      if (prevO.parent is CVec && refText == "&") return processor.execute(NULL_FORM, state)
      val isFnLike = ClojureConstants.FN_ALIKE_SYMBOLS.contains(type)
      val isExtendProtocol = !isFnLike && o.parent.run {
        this is CList && first?.name.let {
          it == "proxy" || it == "defrecord" || it == "extend-protocol" || it == "extend-type"} }
      if (o is CDef && type == "deftype" || type == "reify") {
        if (type == "deftype") {
          val binding = o.first.findNextSibling(CVec::class)
          for (part in binding.iterate().filter(CSymbol::class)) {
            if (!processor.execute(part, state)) return false
          }
        }
        for (fn in o.iterate().filter(CList::class)) {
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
      else if (o is CDef && type == "defprotocol") {
        for (prototype in o.iterate().filter(CDef::class)) {
          if (!processor.execute(prototype, state)) return false
          for (binding in prototype.iterate().filter(CVec::class)) {
            if (!binding.isAncestorOf(element)) continue
            for (part in binding.iterate().filter(CSymbol::class)) {
              if (!processor.execute(part, state)) return false
            }
          }
        }

      }
      else if (o is CDef || isFnLike || isExtendProtocol) {
        if (isFnLike || isExtendProtocol) {
          val nameSymbol = (if (isFnLike) o.first.findNextSibling(CForm::class) else o.first) as? CSymbol
          if (nameSymbol != null) {
            if (!processor.execute(nameSymbol.parent.let { it as? CDef ?: nameSymbol }, state)) return false
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
        for (fn in (o.first.findNextSibling(CForm::class) as? CVec).iterate().filter(CList::class)) {
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
      else if (type == "catch") {
        val forms = o.forms
        if (forms.size > 2) {
          if (!processor.execute(forms[2], state)) return false
        }
      }
      else if (type == "." || type == ".." || type == ".-" || type == ". id") {
        if (prevO == element && prevO.parent == o || prevO is CList && prevO.first == element) {
          val isProp = type == ".-"
          val isProp2 = refText != null && refText.startsWith("-")
          val isMethod = type == ". id"
          var scope = if (isProp) JavaHelper.Scope.INSTANCE else JavaHelper.Scope.STATIC
          val isInFirst = o.first.isAncestorOf(myElement)
          val siblings = o.first.siblings().filter(CForm::class)
              .skip(if (type == "." || type == ".." || isInFirst) 1 else 0)
          val index = if (isInFirst) 0 else siblings.takeWhile { !it.isAncestorOf(myElement) }.size()
          val className = if (isCljs) null else siblings.first()?.let {form ->
              if (form == myElement) null
              else if ((type == "." || type == "..") && form is CSymbol && form.qualifier == null) {
                  (form.reference.resolve() as? PsiQualifiedNamedElement)?.qualifiedName?.apply { scope = JavaHelper.Scope.STATIC }
                      ?: containingFile.javaType(form).apply { scope = JavaHelper.Scope.INSTANCE }
              }
              else containingFile.javaType(form).apply { scope = JavaHelper.Scope.INSTANCE }
          }
          val javaClass = findClass(className, service)
          if (index == 0 && javaClass != null && (type == "." || type == "..")) {
            if (!processor.execute(javaClass, state)) return false
          }
          if (javaClass != null || !isCljs) {
            val classNameAdjusted = (javaClass as? PsiQualifiedNamedElement)?.qualifiedName ?: className ?: ClojureConstants.J_OBJECT
            if (type == ".." && index >= 2) scope = JavaHelper.Scope.INSTANCE
            val processFields = isProp || isMethod && isCljs || isInFirst && siblings.size() == 1 || !isInFirst && myElement.findNextSibling(CForm::class) == null
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
              isProp2 && (type == "." || type == ".." && index >= 1)) {
            return processor.execute(NULL_FORM, state)
          }
          if (javaClass != null && (isProp || isMethod) && refText == null) {
            return true
          }
        }
      }
      prevO = o
    }

    if (!containingFile.processDeclarations(processor, state, prevO, element)) return false
    if (!containingFile.processNamespace(containingFile.namespace, false, state, processor)) return false
    if (!containingFile.processSpecialForms(refText, element, service, state, processor)) return false
    findClass(refText, service)?.let { if (!processor.execute(it, state)) return false }
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

fun prototypes(o: CLForm) = o.iterate().filter(CLForm::class).
    filter { it.firstChild?.nextSibling is CVec }.append(o)

fun listType(o: CLForm) =
    if (o is CDef) o.def.type
    else o.first?.run {
      if (firstChild.elementType == ClojureTypes.C_DOT) ". id"
      else if (firstChild.elementType == ClojureTypes.C_DOTDASH) ".-"
      else name
    }

fun findBindingsVec(o: CLForm, mode: String): CVec? {
  val isMethod = mode == "fn" && o is CDef && o.def.type == "defmethod"
  return (if (isMethod) o.first.siblings().filter(CForm::class).get(3) as? CVec
  else o.iterate().filter(CVec::class).first())
}

fun destruct(o: CForm?) = DESTRUCTURING.withRoot(o).traverse()

fun ClojureFile.processNamespace(namespace: String, forced: Boolean, state: ResolveState, processor: PsiScopeProcessor): Boolean {
  if (state.get(ALIAS_KEY) != null) return true
  StubIndex.getElements(NS_INDEX_KEY, namespace, project,
      ClojureDefinitionService.getClojureSearchScope(project), ClojureFile::class.java).forEach {
    val enabled = forced || it != this
    if (enabled && !it.processDeclarations(processor, state, this, this)) return false
  }
  return true
}

fun ClojureFile.processSpecialForms(refText: String?, place: PsiElement, service: ClojureDefinitionService, state: ResolveState, processor: PsiScopeProcessor): Boolean {
  val useSpecialForms = true // language != ClojureScriptLanguage
  // special unqualified forms
  if (refText != null) {
    if (useSpecialForms && ClojureConstants.SPECIAL_FORMS.contains(refText)) {
      return processor.execute(service.getDefinition(refText, CLOJURE_CORE, "defn"), state)
    }
    // reserved bindings '*ns*' and etc.
    if (refText.startsWith("*") && refText.endsWith("*")) {
      return processor.execute(place, state)
    }
  }
  else {
    ClojureConstants.SPECIAL_FORMS.forEach { if (!processor.execute(service.getDefinition(it, CLOJURE_CORE, "defn"), state)) return false }
  }
  return true
}

fun CMap.resolveNsPrefix(): String? {
  val nsMacro = iterate().filter(CReaderMacro::class).filter { it.firstChild.elementType == ClojureTypes.C_SHARP_NS }.first() ?: return null
  return when (nsMacro.firstChild.nextSibling.elementType) {
    ClojureTypes.C_COLON -> nsMacro.symbol?.name
    ClojureTypes.C_COLONCOLON -> (nsMacro.symbol?.reference?.resolve() as? PsiNamedElement)?.name ?: (containingFile as ClojureFile).namespace
    else -> throw AssertionError(nsMacro.text)
  }
}

fun processBindings(o: CLForm, mode: String, state: ResolveState, processor: PsiScopeProcessor, place: PsiElement): Boolean {
  val bindings = findBindingsVec(o, mode) ?: return true
  val roots = when (mode) {
    "fn" -> JBIterable.of(bindings)
    "let" -> bindings.iterateForms().filter(EachNth(2)).run {
      if (!bindings.isAncestorOf(place)) this
      else place.parents().filter { (it as PsiElement).parent == bindings }.first()!!.let { o ->
        if (contains(o)) takeWhile { it != o }.append(o as CForm)
        else o.findPrevSibling(CForm::class)!!.let { o0 -> takeWhile { it != o0 } }
      }
    }
    "for" -> bindings.iterateForms().filter(EachNth(2)).transform {
      if (it is CKeyword && it.text == ":let") it.findNextSibling(CForm::class) as? CVec else it
    }.notNulls()
    else -> throw AssertionError("processBindings(): unknown mode: $mode")
  }
  for (part in DESTRUCTURING.withRoots(roots)) {
    if (!processor.execute(part, state)) return false
  }
  return true
}

internal fun ClojureFileImpl.processFileImports(imports: JBIterable<CList>,
                                                processor: PsiScopeProcessor,
                                                state: ResolveState,
                                                place: PsiElement): Boolean {
  val service = ClojureDefinitionService.getInstance(project)
  val startOffset = place.textRange.startOffset
  val isCljs = language == ClojureScriptLanguage
  val defaultNS = when (language) {
    is ClojureLanguage -> CLOJURE_CORE
    is ClojureScriptLanguage -> CLJS_CORE
    else -> null
  }
  var importDefaultNS = defaultNS != null/* && defaultNS != namespace*/
  val traverser = cljTraverserRCAware().expand {
    ((it as? CList)?.findChild(CForm::class) as? CSForm)?.let {
      val name = if (it is CSymbol) it.name else if (it is CKeyword) it.name else ""
      ClojureConstants.NS_ALIKE_SYMBOLS.contains(name) } ?: false
  }.filter { it is CForm }

  fun importInfo(o: CForm, st: String): ImportInfo {
    val iterator = (if (o is CSymbol) o.siblings() else o.iterateRCAware()).filter(CForm::class).iterator()
    val namespace = if (st == "refer-clojure") null
    else ((if (iterator.hasNext()) iterator.next() as? CSymbol else null) ?: return EMPTY_IMPORT)

    val withRefer = st != "require" && st != "require-macros"
    var alias: CSymbol? = null
    var only: CPForm? = null
    var refer: CPForm? = null
    var exclude: CPForm? = null
    var rename: JBIterable<CSymbol>? = null
    for (item in iterator) {
      val flag = (item as? CKeyword)?.symbol?.name ?: ""
      when (flag) {
        "as" -> alias = if (iterator.hasNext()) iterator.next() as? CSymbol else null
        "refer", "require-macros" -> refer = if (!withRefer && iterator.hasNext()) iterator.next().listOrVec() else null
        "only" -> only = if (withRefer && iterator.hasNext()) iterator.next().listOrVec() else null
        "exclude" -> exclude = if (withRefer && iterator.hasNext()) iterator.next().listOrVec() else null
        "rename" -> rename = if (withRefer && iterator.hasNext()) (iterator.next() as? CMap)?.iterate()?.filter(CSymbol::class) else null
      }
    }
    val ranges = JBIterable.empty<CForm>().append(refer).append(only).append(exclude).append(rename?.filter(EachNth(2)) ?: JBIterable.empty())
        .transform { it.textRange }.addAllTo(TreeSet(
        { r1, r2 -> r1.startOffset - r2.startOffset }))
    val info = ImportInfo(namespace, alias,
        refer.iterateRCAware().filter(CSymbol::class).transform { it.text }.toSet(),
        only.iterateRCAware().filter(CSymbol::class).transform { it.text }.toSet(),
        exclude.iterateRCAware().filter(CSymbol::class).transform { it.text }.toSet(),
        rename?.partition(2, true)?.reduce(HashMap()) { map, o -> if (o.size == 2) map.put(o[0].name, o[1]); map } ?: emptyMap(),
        if (ranges.isEmpty()) null else ranges)
    return info
  }

  for (import in imports.takeWhile { it.textRange.startOffset < startOffset }) {
    val inThisImport = import.textRange.contains(startOffset)
    var expectKeys = false
    var curP: PsiElement? = null
    var prevState = ""
    var st = ""

    fun stateRenamed(prefix: String?, e: CSymbol) =
        if (!inThisImport || prefix == null || !e.textRange.containsOffset(startOffset)) state
        else state.put(RENAMED_KEY, e.name)

    fun processVec(o: CForm, prefix: String): Boolean {
      val info = (offsetMap[o] ?: ConcurrencyUtil.cacheOrGet(offsetMap, o, importInfo(o, st))) as ImportInfo
      val infoNamespace = if (st == "refer-clojure") defaultNS else (info.namespace?.name ?: return true)
      val namespace = prefix + infoNamespace
      if (namespace == defaultNS) importDefaultNS = false
      val inRanges = info.ranges?.floor(TextRange.from(startOffset, 0))?.containsOffset(startOffset) ?: false
      val useFilterNot = info.alias == null && info.ranges == null || inRanges
      if (inThisImport && info.namespace?.textRange?.containsOffset(startOffset) ?: false) {
        if (!processor.execute(service.getNamespace(namespace), stateRenamed(prefix, info.namespace!!))) return false
      }
      if (info.alias != null) {
        if (!processor.execute(service.getAlias(info.alias.name, namespace, info.alias), state)) return false
        if (info.alias.textRange.containsOffset(startOffset)) return true
      }
      if (useFilterNot) {
        if (inRanges) importDefaultNS = false
        if (!processNamespace(namespace, false, state, processor)) return false
      }
      else if (info.ranges != null) {
        if (!processNamespace(namespace, false, state, object : PsiScopeProcessor by processor {
          override fun execute(element: PsiElement, state: ResolveState): Boolean {
            val name = (element as? CDef)?.name ?: return true
            if (info.refer.contains(name)) return processor.execute(element, state)
            if (info.only.contains(name)) return processor.execute(element, state)
            if (info.exclude.contains(name)) return true
            val renamed = info.rename[name] ?: return true
            return processor.execute(renamed, state)
          }
        })) return false
      }
      return true
    }
    val git = traverser.withRoot(import).traverse().skip(1).iterator() as TreeTraversal.TracingIt<PsiElement>
    f@ for (o in git) {
      if (inThisImport && o is CPForm && !o.textRange.containsOffset(startOffset)) continue@f
      if (curP != null && git.parent() != curP) st = prevState
      when (st) {
        "", "ns" -> {
          prevState = st
          val s = ((if (expectKeys) ((o as? CKeyword)?.symbol ?: o) else o) as? CSymbol)?.name ?: continue@f
          st = when (s) {
            "ns" -> if (st == "") { expectKeys = true; "ns" } else continue@f
            "import", "require", "use", "refer", "refer-clojure", "alias" -> s
            "require-macros" -> s // todo only cljs & cljc
            else -> continue@f
          }
          curP = o.parent
          if (st == "refer-clojure") {
            if (!processVec(o as CForm, "")) return false
          }
        }
        "alias" -> {
          val alias = (o as? CSymbol)?.name ?: continue@f
          val namespace = (o.findNextSibling(CForm::class) as? CSymbol)?.name ?: continue@f
          if (!processor.execute(service.getAlias(alias, namespace, o as CSymbol), state)) return false
        }
        "import" -> {
          if (isCljs) {
            if (inThisImport && o.isAncestorOf(place)) return processor.execute(NULL_FORM, state)
          }
          else if (o is CSymbol) {
            service.java.findClass(o.name)?.let { if (!processor.execute(it, state)) return false }
          }
          else if (o is CList || o is CVec) {
            val first = o.findChild(CForm::class) as? CSymbol
            val packageName = first?.name ?: continue@f
            val packageItems = o.iterateRCAware().skipWhile { it != first }.skip(1).filter(CSymbol::class)
            if (inThisImport && first?.textRange?.containsOffset(startOffset) ?: false) {
              return service.java.findPackage(packageName, packageItems.first()?.name)?.let { processor.execute(it, state) } ?: true
            }
            for (item in packageItems) {
              service.java.findClass("$packageName.${item.name}")?.let { if (!processor.execute(it, state)) return false }
            }
          }
        }
        else -> {
          if (o is CSymbol || o is CVec && o.iterateForms()[1] !is CSymbol) {
            if (!processVec(o as CForm, "")) return false
          }
          else if (o is CList || o is CVec) {
            val first = o.findChild(CForm::class) as? CSymbol ?: continue@f
            if (inThisImport && first.textRange.contains(startOffset)) return processor.execute(NULL_FORM, state)
            val prefix = first.name + "."
            for (item in o.iterateRCAware().skipWhile { it != first }.skip(1).filter(CForm::class)) {
              if (item is CSymbol) {
                val namespace = "$prefix${item.name}"
                if (!processor.execute(service.getNamespace(namespace), stateRenamed(prefix, item))) return false
                if (!processNamespace(namespace, false, state, processor)) return false
              }
              else if (item is CVec) {
                if (!processVec(item, prefix)) return false
              }
            }
          }
        }
      }
    }
  }
  if (importDefaultNS) {
    if (!processNamespace(defaultNS!!, false, state, processor)) return false
  }
  return true
}

private val EMPTY_IMPORT = ImportInfo(null, null, emptySet(), emptySet(), emptySet(), emptyMap(), null)

private data class ImportInfo(val namespace: CSymbol?,
                              val alias: CSymbol?,
                              val refer: Set<String>,
                              val only: Set<String>,
                              val exclude: Set<String>,
                              val rename: Map<String, CSymbol>,
                              val ranges: NavigableSet<TextRange>?)

private val NULL_FORM: CForm = CKeywordImpl(CKeywordStub("", "", null))
private val DESTRUCTURING = JBTreeTraverser<CForm>(f@ {
  return@f when (it) {
    is CVec -> {
      it.iterateForms().filter { (it !is CSymbol) || it.text != "&" }
    }
    is CMap -> {
      it.iterateForms().intercept x@{ delegate ->
        return@x object : JBIterator<CForm>() {
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
              val key = t.text
              when (key) {
                ":or" -> skip = true
                ":as" -> skip = false
                ":keys", ":syms", ":strs" -> (t.findNextSibling(CForm::class) as? CVec)?.let {
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

