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

import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiCodeFragment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.ILeafElementType
import com.intellij.util.containers.JBIterable
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.lang.ClojureLanguage

enum class Dialect(val coreNs: String) {
  CLJ(ClojureConstants.CLOJURE_CORE),
  CLJS(ClojureConstants.CLJS_CORE),
  CLJR(ClojureConstants.CLOJURE_CORE)
}

enum class Role {
  NONE, DEF, NS, NAME,
  RCOND, RCOND_S,
  PROTOTYPE,
  ARG_VEC, BND_VEC, FIELD_VEC, BODY,
  ARG, BND, FIELD // currently not set
}

const val FLAG_COMMENTED = 0x1
const val FLAG_QUOTED = 0x2
const val FLAG_UNQUOTED = 0x4

interface CElement : NavigatablePsiElement {
  val role: Role
  val def: IDef?
  val resolvedNs: String?
  val flags: Int
}

interface ClojureElementType
class ClojureTokenType(name: String) : IElementType(name, ClojureLanguage), ILeafElementType {
  override fun createLeafNode(leafText: CharSequence) = CToken(this, leafText)
}
class ClojureNodeType(name: String) : IElementType(name, ClojureLanguage), ClojureElementType
class CToken(tokenType: ClojureTokenType, text: CharSequence) : LeafPsiElement(tokenType, text)

interface CFile : PsiFile {
  val namespace: String

  fun defs(dialect: Dialect = Dialect.CLJ): JBIterable<CList>
}

interface CCodeFragment : CFile, PsiCodeFragment {
  fun setContext(context: PsiElement?)
}

interface IDef {
  val type: String
  val name: String
  val namespace: String
}

data class SymKey(
    override val name: String,
    override val namespace: String,
    override val type: String
) : IDef {
  constructor(def : IDef): this(def.name, def.namespace, def.type)
}

class Def(
    val key: SymKey,
    val protos: List<Prototype>,
    val meta: Map<String, String>
) : IDef by key

class Prototype(val args: List<String>, val typeHint: String?)

