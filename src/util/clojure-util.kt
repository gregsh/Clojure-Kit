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

import com.intellij.lang.ASTNode
import com.intellij.lang.parser.GeneratedParserUtilBase
import com.intellij.openapi.util.Conditions
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.impl.source.tree.TreeUtil
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.FilteredTraverserBase
import com.intellij.util.containers.JBIterable
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.impl.CReaderCondImpl
import java.util.*
import kotlin.reflect.KClass

/**
 * @author gregsh
 */

fun <E> E.elementOf(c: Collection<E>) = c.contains(this)

val PsiElement?.elementType : IElementType? get() = this?.node?.elementType
fun PsiElement?.isAncestorOf(o: PsiElement) = PsiTreeUtil.isAncestor(this, o, false)
fun <T : PsiElement> PsiElement?.findParent(c: KClass<T>) = PsiTreeUtil.getStubOrPsiParentOfType(this, c.java)
fun <T : PsiElement> PsiElement?.findChild(c: KClass<T>) = PsiTreeUtil.getChildOfType(this, c.java)
fun <T : PsiElement> PsiElement?.findNextSibling(c: KClass<T>) = PsiTreeUtil.getNextSiblingOfType(this, c.java)
fun <T : PsiElement> PsiElement?.findPrevSibling(c: KClass<T>) = PsiTreeUtil.getPrevSiblingOfType(this, c.java)
fun PsiElement?.nextForm() = findNextSibling(CForm::class)
fun PsiElement?.prevForm() = findPrevSibling(CForm::class)
fun PsiElement?.parentForm() = findParent(CForm::class).let { if (it?.parent is CKeyword) it?.parent else it }

fun PsiElement?.findChild(c: IElementType) = this?.node?.findChildByType(c)?.psi?: null
fun PsiElement?.findNextSibling(c: IElementType) = TreeUtil.findSibling(this?.node, c)?.psi ?: null
fun PsiElement?.findPrevSibling(c: IElementType) = TreeUtil.findSiblingBackward(this?.node, c)?.psi ?: null

@Suppress("UNCHECKED_CAST")
fun <T> JBIterable<T?>.notNulls(): JBIterable<T> = filter { it != null } as JBIterable<T>
fun <T: Any, E: Any> JBIterable<T>.filter(c : KClass<E>) = filter(c.java)

fun ASTNode?.iterate(): JBIterable<ASTNode> =
    if (this == null) JBIterable.empty() else cljTraverser().expandAndSkip(Conditions.equalTo(this)).traverse()

fun PsiElement?.iterate(): JBIterable<PsiElement> =
    if (this == null) JBIterable.empty() else cljTraverser().expandAndSkip(Conditions.equalTo(this)).traverse()

fun PsiElement?.iterateForms(): JBIterable<CForm> = iterate().filter(CForm::class)

fun PsiElement?.iterateRCAware(): JBIterable<PsiElement> =
    if (this == null) JBIterable.empty() else cljTraverserRCAware().expandAndSkip(Conditions.equalTo(this)).traverse()

fun PsiElement?.siblings(): JBIterable<PsiElement> =
    if (this == null) JBIterable.empty() else JBIterable.generate(this, { SyntaxTraverser.psiApi().next(it) }).notNulls()

fun PsiElement?.parents(): JBIterable<PsiElement> =
    if (this == null) JBIterable.empty() else SyntaxTraverser.psiApi().parents(this)

fun cljTraverser(): SyntaxTraverser<PsiElement> = SyntaxTraverser.psiTraverser()
    .forceDisregardTypes { it == GeneratedParserUtilBase.DUMMY_BLOCK }

fun cljNodeTraverser(): SyntaxTraverser<ASTNode> = SyntaxTraverser.astTraverser()
    .forceDisregardTypes { it == GeneratedParserUtilBase.DUMMY_BLOCK }

fun PsiElement?.cljTraverser(): SyntaxTraverser<PsiElement> = org.intellij.clojure.util.cljTraverser().withRoot(this)
fun PsiElement?.cljTraverserRCAware(): SyntaxTraverser<PsiElement> = cljTraverser().forceDisregard(
    object : FilteredTraverserBase.EdgeFilter<PsiElement>() {
      override fun value(t: PsiElement?) = t is CReaderCondImpl ||
          (edgeSource as? CReaderCondImpl)?.let { it.splicing && t is CPForm }?: false
    }).forceIgnore { it.parent is CReaderCondImpl && (it !is CForm || it is CKeyword) }

fun PsiElement?.cljTopLevelTraverser(): SyntaxTraverser<PsiElement> = cljTraverser().expand { it !is CForm || it is CReaderCondImpl }

fun ASTNode?.cljTraverser(): SyntaxTraverser<ASTNode> = org.intellij.clojure.util.cljNodeTraverser().withRoot(this)

fun PsiElement?.listOrVec(): CPForm? = this as? CList ?: this as? CVec

fun CForm.valueRange(): TextRange = firstChild
    .siblings()
    .skipWhile { it is CReaderMacro || it is CMetadata || (it !is ClojureToken && it !is CForm) }
    .first()?.textRange?.let { TextRange(it.startOffset, textRange.endOffset)} ?: textRange

fun <T> JBIterable<T>.sort(comparator: Comparator<T>? = null) = JBIterable.from(addAllTo(TreeSet<T>(comparator)))

class EachNth(val each: Int) : JBIterable.StatefulFilter<Any?>() {
  var idx = -1
  override fun value(t: Any?) = run { idx = ++ idx % each; idx == 0 }
}
