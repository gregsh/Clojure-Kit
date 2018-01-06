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

package org.intellij.clojure.inspections

import com.intellij.codeInspection.*
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import org.intellij.clojure.ClojureConstants
import org.intellij.clojure.ClojureConstants.SYMBOLIC_VALUES
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.impl.*
import org.intellij.clojure.tools.Tool
import org.intellij.clojure.util.elementType
import org.intellij.clojure.util.iterate
import org.intellij.clojure.util.jbIt
import org.intellij.clojure.util.parents
import kotlin.collections.component1
import kotlin.collections.component2

/**
 * @author gregsh
 */
val RESOLVE_SKIPPED: Key<Boolean?> = Key.create("C_SKIP_RESOLVE")

class ClojureInspectionSuppressor : InspectionSuppressor {
  override fun getSuppressActions(element: PsiElement?, toolId: String): Array<out SuppressQuickFix> {
    return arrayOf()
  }

  override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
    if (!toolId.startsWith("Clojure")) return false
    // todo
    return false
  }
}

class ClojureResolveInspection : LocalInspectionTool() {
  override fun getDisplayName() = "Unresolved reference"
  override fun getShortName() = "ClojureResolveInspection"

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): ClojureVisitor {
    if (Tool.choose(holder.file.name) != null) return ClojureVisitor()

    return object : ClojureVisitor() {
      override fun visitSymbol(o: CSymbol) {
        val reference = o.reference
        val multiResolve = (reference as PsiPolyVariantReference).multiResolve(false)
        val (valid, invalid) = multiResolve.jbIt().reduce(arrayOf(0, 0)) { arr, it -> arr[if (it.isValidResult) 0 else 1] ++; arr }
        if (o.getUserData(RESOLVE_SKIPPED) != null) return

        val qualifier = reference.qualifier?.apply {
          if (this.reference?.resolve() == null) return }

        val langKind = (holder.file as CFileImpl).placeLanguage(o)
        val isCljS = langKind == Dialect.CLJS

        if (valid != 0) return
        if (qualifier == null && !isCljS && ClojureConstants.TYPE_META_ALIASES.contains(o.name)) return
        if (qualifier == null && isCljS && ClojureConstants.CLJS_TYPES.contains(o.name)) return
        if (o.parent is CSymbol && o.parent.parent is CKeyword) return
        val quotesAndComments = o.parents().filter { it is CMetadata
            || it is CForm && it.role != Role.RCOND && it.iterate(CReaderMacro::class).find { suppressResolve(it, invalid != 0) } != null
            || it is CList && it.first.resolveInfo().matches(ClojureDefinitionService.COMMENT_SYM)
        }.first()
        if (quotesAndComments != null) return
        holder.registerProblem(reference, "unable to resolve '${reference.referenceName}'", ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
      }
    }
  }
}

private fun suppressResolve(o: CReaderMacro, invalidResolve: Boolean) = when (o.firstChild.elementType) {
  ClojureTypes.C_SHARP_COMMENT -> true
  ClojureTypes.C_QUOTE, ClojureTypes.C_SYNTAX_QUOTE -> true
  ClojureTypes.C_SHARP_QUOTE -> invalidResolve
  ClojureTypes.C_SHARP_SYM -> SYMBOLIC_VALUES.contains((o.parent as? CSymbol)?.name)
  else -> false
}

