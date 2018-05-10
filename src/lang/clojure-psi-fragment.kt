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

package org.intellij.clojure.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.PsiBuilderFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.SingleRootFileViewProvider
import com.intellij.psi.TokenType
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.impl.source.tree.FileElement
import com.intellij.psi.impl.source.tree.TreeElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.testFramework.LightVirtualFile
import org.intellij.clojure.psi.CCodeFragment
import org.intellij.clojure.psi.CFile
import org.intellij.clojure.psi.impl.CFileImpl

/**
 * @author gregsh
 */
fun newCodeFragment(project: Project,
                    language: Language,
                    elementType: IElementType,
                    fileName: String,
                    text: CharSequence,
                    context: PsiElement?,
                    isPhysical: Boolean): CCodeFragment {
  val file = LightVirtualFile(fileName, language, text)
  val viewProvider = PsiManagerEx.getInstanceEx(project).fileManager.createFileViewProvider(file, isPhysical)
  val fragment = CCodeFragmentImpl(viewProvider, language, elementType, isPhysical)
  fragment.context = context
  return fragment
}

open class CCodeFragmentImpl(
    viewProvider: FileViewProvider,
    language: Language,
    val elementType: IElementType,
    private var myPhysical: Boolean?)
  : CFileImpl(viewProvider, language), CCodeFragment {
  private var myViewProvider: FileViewProvider? = null
  private var myContext: PsiElement? = null
  private var myScope: GlobalSearchScope? = null
  override val namespace: String
    get() = (context?.containingFile as? CFile)?.namespace ?: super.namespace

  init {
    (viewProvider as SingleRootFileViewProvider).forceCachedPsi(this)
    init(TokenType.CODE_FRAGMENT, CCodeFragmentElementType(language, elementType))
  }

  override fun isPhysical() = myPhysical ?: super.isPhysical()
  override fun getViewProvider() = myViewProvider ?: super.getViewProvider()

  override fun getContext() = if (myContext?.isValid == true) myContext else super.getContext()
  override fun setContext(context: PsiElement?) {
    myContext = context
  }

  override fun getForcedResolveScope() = myScope
  override fun forceResolveScope(scope: GlobalSearchScope) {
    myScope = scope
  }

  override fun clone(): CCodeFragmentImpl {
    val clone = cloneImpl(calcTreeElement().clone() as FileElement) as CCodeFragmentImpl
    clone.myPhysical = false
    clone.myOriginalFile = this
    val fileManager = (manager as PsiManagerEx).fileManager
    val fileClone = LightVirtualFile(name, language, text)
    val cloneViewProvider = fileManager.createFileViewProvider(fileClone, false) as SingleRootFileViewProvider
    clone.myViewProvider = cloneViewProvider
    cloneViewProvider.forceCachedPsi(clone)
    clone.init(TokenType.CODE_FRAGMENT, contentElementType)
    return clone
  }
}

class CCodeFragmentElementType constructor(language: Language, val elementType: IElementType) :
    IFileElementType("CLOJURE_CODE_FRAGMENT", language, false) {

  override fun parseContents(chameleon: ASTNode): ASTNode? {
    val project = (chameleon as TreeElement).manager.project
    val languageForParser = language
    val builder = PsiBuilderFactory.getInstance().createBuilder(project, chameleon, null, languageForParser, chameleon.chars)
    val parser = LanguageParserDefinitions.INSTANCE.forLanguage(languageForParser).createParser(project)
    val node = parser.parse(elementType, builder)
    return if (elementType is IFileElementType) node.firstChildNode else node
  }
}
