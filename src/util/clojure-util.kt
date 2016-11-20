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

package org.intellij.clojure.util

import com.intellij.lang.*
import com.intellij.lang.parser.GeneratedParserUtilBase
import com.intellij.openapi.util.Conditions
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.impl.source.tree.TreeUtil
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ObjectUtils
import com.intellij.util.containers.JBIterable
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.parser.ClojureTokens
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.impl.CReaderCondImpl
import java.util.*
import kotlin.reflect.KClass

/**
 * @author gregsh
 */

@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> Any?.forceCast(): T? = this as? T

fun <E> E.elementOf(c: Collection<E>) = c.contains(this)
fun String?.prefixedBy(c: Iterable<String>) = this != null && c.find { this.startsWith(it + ".") } != null
fun <E> Array<E>?.iterate() = if (this == null) JBIterable.empty<E>() else JBIterable.of(*this)

fun PsiElement?.isAncestorOf(o: PsiElement) = PsiTreeUtil.isAncestor(this, o, false)
fun <T : PsiElement> PsiElement?.findParent(c: KClass<T>) = if (c.java.isInstance(this)) this as T else PsiTreeUtil.getStubOrPsiParentOfType(this, c.java)
fun <T : PsiElement> PsiElement?.findChild(c: KClass<T>) = PsiTreeUtil.getChildOfType(this, c.java)
fun <T : PsiElement> PsiElement?.findNext(c: KClass<T>) = PsiTreeUtil.getNextSiblingOfType(this, c.java)
fun <T : PsiElement> PsiElement?.findPrev(c: KClass<T>) = PsiTreeUtil.getPrevSiblingOfType(this, c.java)

val PsiElement?.elementType : IElementType? get() = this?.node?.elementType
val PsiElement?.firstForm: CForm? get() = findChild(CForm::class)
val PsiElement?.nextForm: CForm? get() = findNext(CForm::class)
val PsiElement?.prevForm: CForm? get() = findPrev(CForm::class)
val PsiElement?.parentForm: CForm? get() = findParent(CForm::class).let { it?.parent.let {
  it as? CKeyword ?: (it as? CSymbol)?.parent as? CKeyword } ?: it }
val PsiElement?.parentForms: JBIterable<CForm> get() = JBIterable.generate(this.parentForm, { it.parentForm })
val PsiElement?.childForms: JBIterable<CForm> get() = iterate(CForm::class)

fun PsiElement?.findChild(c: IElementType) = this?.node?.findChildByType(c)?.psi
fun PsiElement?.findNext(c: IElementType) = TreeUtil.findSibling(this?.node, c)?.psi
fun PsiElement?.findPrev(c: IElementType) = TreeUtil.findSiblingBackward(this?.node, c)?.psi

fun VirtualFile.toIoFile() = VfsUtil.virtualToIoFile(this)

@Suppress("UNCHECKED_CAST")
fun <T> JBIterable<T?>.notNulls(): JBIterable<T> = filter { it != null } as JBIterable<T>
fun <T: Any, E: Any> JBIterable<T>.filter(c : KClass<E>) = filter(c.java)

fun ASTNode?.iterate(): JBIterable<ASTNode> =
    if (this == null) JBIterable.empty() else cljTraverser().expandAndSkip(Conditions.equalTo(this)).traverse()

fun PsiElement?.iterate(): JBIterable<PsiElement> =
    if (this == null) JBIterable.empty() else cljTraverser().expandAndSkip(Conditions.equalTo(this)).traverse()

fun <T: Any> PsiElement?.iterate(c: KClass<T>): JBIterable<T> = iterate().filter(c)

fun PsiElement?.iterateRCAware(): JBIterable<PsiElement> =
    if (this == null) JBIterable.empty() else cljTraverserRCAware().expandAndSkip(Conditions.equalTo(this)).traverse()

fun PsiElement?.siblings(): JBIterable<PsiElement> =
    if (this == null) JBIterable.empty() else JBIterable.generate(this, { it.nextSibling }).notNulls()

fun PsiElement?.parents(): JBIterable<PsiElement> =
    if (this == null) JBIterable.empty() else SyntaxTraverser.psiApi().parents(this)

fun cljTraverser(): SyntaxTraverser<PsiElement> = SyntaxTraverser.psiTraverser()
    .forceDisregardTypes { it == GeneratedParserUtilBase.DUMMY_BLOCK }

fun cljNodeTraverser(): SyntaxTraverser<ASTNode> = SyntaxTraverser.astTraverser()
    .forceDisregardTypes { it == GeneratedParserUtilBase.DUMMY_BLOCK }

fun cljLightTraverser(text: CharSequence,
                      forcedRootType: IElementType = ClojureTokens.CLJ_FILE_TYPE,
                      language: Language = ClojureLanguage): SyntaxTraverser<LighterASTNode> {
  val parserDefinition = LanguageParserDefinitions.INSTANCE.forLanguage(language)
  val lexer = parserDefinition.createLexer(null)
  val parser = parserDefinition.createParser(null) as LightPsiParser
  val builder = PsiBuilderFactory.getInstance().createBuilder(parserDefinition, lexer, text)
  val rootType = ObjectUtils.notNull<IElementType>(forcedRootType, parserDefinition.fileNodeType)
  parser.parseLight(rootType, builder)
  return SyntaxTraverser.lightTraverser(builder).forceDisregardTypes { it == GeneratedParserUtilBase.DUMMY_BLOCK }
}


fun PsiElement?.cljTraverser(): SyntaxTraverser<PsiElement> = org.intellij.clojure.util.cljTraverser().withRoot(this)
fun PsiElement?.cljTraverserRCAware(): SyntaxTraverser<PsiElement> = cljTraverser().forceDisregard {
  it is CReaderCondImpl || it is CPForm && (it.parent as? CReaderCondImpl)?.splicing ?: false
}.forceIgnore { it.parent is CReaderCondImpl && (it !is CForm || it is CKeyword) }

fun PsiElement?.cljTopLevelTraverser(): SyntaxTraverser<PsiElement> = cljTraverserRCAware().expand { it !is CForm || it is CReaderCondImpl }

fun ASTNode?.cljTraverser(): SyntaxTraverser<ASTNode> = org.intellij.clojure.util.cljNodeTraverser().withRoot(this)

fun PsiElement?.listOrVec(): CPForm? = this as? CList ?: this as? CVec

val PsiElement.valueRange: TextRange get() = firstChild.siblings()
      .skipWhile { it is CReaderMacro || it is CMetadata || (it !is ClojureToken && it !is CForm) }
      .first()?.textRange?.let { TextRange(it.startOffset, textRange.endOffset) } ?: textRange

fun <T> JBIterable<T>.sort(comparator: Comparator<T>? = null) = JBIterable.from(addAllTo(TreeSet<T>(comparator)))

class EachNth(val each: Int) : JBIterable.StatefulFilter<Any?>() {
  var idx = -1
  override fun value(t: Any?) = run { idx = ++ idx % each; idx == 0 }
}
