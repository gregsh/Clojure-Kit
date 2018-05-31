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

package org.intellij.clojure.debugger

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProviderBase
import org.intellij.clojure.lang.ClojureFileType
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.lang.ClojureTokens
import org.intellij.clojure.lang.newCodeFragment
import org.intellij.clojure.util.thisForm

/**
 *
 *
 * @author gregsh
 */

class ClojureLineBreakpointType : XLineBreakpointType<ClojureBreakpointProperties>("clojure-line", "Clojure breakpoint") {
  override fun createBreakpointProperties(file: VirtualFile, line: Int): ClojureBreakpointProperties {
    return ClojureBreakpointProperties()
  }

  override fun getSourcePosition(breakpoint: XBreakpoint<ClojureBreakpointProperties>): XSourcePosition? {
    return super.getSourcePosition(breakpoint)
  }

  override fun computeVariants(project: Project, position: XSourcePosition): MutableList<out XLineBreakpointVariant> {
    return super.computeVariants(project, position)
  }

  override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean {
    return file.fileType == ClojureFileType
  }

  override fun getEditorsProvider(breakpoint: XLineBreakpoint<ClojureBreakpointProperties>, project: Project): XDebuggerEditorsProvider? {
    return object : XDebuggerEditorsProviderBase() {
      override fun getFileType() = ClojureFileType

      override fun createExpressionCodeFragment(project: Project, text: String, context: PsiElement?, isPhysical: Boolean): PsiFile {
        return newCodeFragment(project, ClojureLanguage, ClojureTokens.CLJ_FILE_TYPE, "eval.clj", text, context.thisForm ?: context, isPhysical)
      }
    }
  }
}

class ClojureBreakpointProperties : XBreakpointProperties<ClojureBreakpointProperties.State>() {
  class State

  private val myState: State = State()

  override fun getState() = myState

  override fun loadState(state: State) {
  }
}



