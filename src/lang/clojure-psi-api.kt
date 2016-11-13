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

package org.intellij.clojure.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiQualifiedNamedElement
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.ICompositeElementType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.ILeafElementType
import com.intellij.util.containers.JBIterable
import org.intellij.clojure.lang.ClojureLanguage

interface ClojureElementType : ICompositeElementType {
  override fun createCompositeNode(): ASTNode = ClojureTreeElement(this as IElementType)
}
class ClojureTokenType(name: String) : IElementType(name, ClojureLanguage), ILeafElementType {
  override fun createLeafNode(leafText: CharSequence) = ClojureToken(this, leafText)
}
class ClojureNodeType(name: String) : IElementType(name, ClojureLanguage), ClojureElementType
class ClojureToken(tokenType: ClojureTokenType, text: CharSequence) : LeafPsiElement(tokenType, text) { }

interface ClojureFile : PsiFile {
  val namespace: String
  val definitions: JBIterable<CDef>
  val imports: JBIterable<CList>
}

interface CElement : NavigatablePsiElement
interface CNamed : CForm, PsiNameIdentifierOwner, PsiQualifiedNamedElement {
  val def: DefInfo
  val nameSymbol: CSymbol?

  fun meta(key: String): String? = null

  override fun getName(): String
}

interface CDef : CList, CNamed

interface DefInfo {
  val type: String
  val name: String
  val namespace: String
}

private class ClojureTreeElement(type: IElementType) : CompositeElement(type) {
  override fun clearCaches() {
    super.clearCaches()
    if (elementType == ClojureTypes.C_LIST) {
      // prevent accidental confusion of CList and CDef
      clearPsi()
    }
  }
}