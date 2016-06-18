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

package org.intellij.clojure.actions

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.intellij.clojure.parser.ClojureTokens
import org.intellij.clojure.psi.CForm
import org.intellij.clojure.psi.CPForm
import org.intellij.clojure.psi.ClojureFile
import org.intellij.clojure.util.*

/**
 * @author gregsh
 */
abstract class EditActionBase : DumbAwareAction() {
  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled =
        e.getData(LangDataKeys.EDITOR) != null &&
        e.getData(LangDataKeys.PSI_FILE) is ClojureFile
  }

  override fun actionPerformed(e: AnActionEvent) {
    val editor = e.getData(LangDataKeys.EDITOR)
    val file = e.getData(LangDataKeys.PSI_FILE) as? ClojureFile
    if (editor != null && file != null) {
      val list = file.findElementAt(editor.caretModel.offset).findParent(CPForm::class)
      if (list == null) {
        HintManager.getInstance().showErrorHint(editor, "Place caret inside a list-like form")
      }
      else {
        WriteCommandAction.runWriteCommandAction(e.project) {
          actionPerformed(list, file, editor)
        }
      }
    }
  }

  abstract fun actionPerformed(form: CPForm, file: ClojureFile, editor: Editor)
}

class SlurpBwdAction : EditActionBase() {
  override fun actionPerformed(form: CPForm, file: ClojureFile, editor: Editor) = slurp(form, false)
}

class SlurpFwdAction : EditActionBase() {
  override fun actionPerformed(form: CPForm, file: ClojureFile, editor: Editor) = slurp(form, true)
}

class BarfBwdAction : EditActionBase() {
  override fun actionPerformed(form: CPForm, file: ClojureFile, editor: Editor) = barf(form, false)
}

class BarfFwdAction : EditActionBase() {
  override fun actionPerformed(form: CPForm, file: ClojureFile, editor: Editor) = barf(form, true)
}

class SpliceAction : EditActionBase() {
  override fun actionPerformed(form: CPForm, file: ClojureFile, editor: Editor) = splice(form, editor)
}

class RiseAction : EditActionBase() {
  override fun actionPerformed(form: CPForm, file: ClojureFile, editor: Editor) = rise(form, editor)
}

class KillAction : EditActionBase() {
  override fun actionPerformed(form: CPForm, file: ClojureFile, editor: Editor) = form.delete()
}

class ClojureBackspaceHandler(val original: EditorWriteActionHandler) : EditorWriteActionHandler(true) {
  override fun executeWriteAction(editor: Editor, caret: Caret?, dataContext: DataContext) =
      if (!kill(editor, caret, dataContext, false)) original.executeWriteAction(editor, caret, dataContext) else Unit
}

class ClojureDeleteHandler(val original: EditorWriteActionHandler) : EditorWriteActionHandler(true) {
  override fun executeWriteAction(editor: Editor, caret: Caret?, dataContext: DataContext) =
      if (!kill(editor, caret, dataContext, true)) original.executeWriteAction(editor, caret, dataContext) else Unit
}

class ClojureTypedHandler() : TypedHandlerDelegate() {

  override fun charTyped(c: Char, project: Project?, editor: Editor, file: PsiFile): Result {
    if (file !is ClojureFile) return Result.CONTINUE
    val pair = if (c == '(') ')' else if (c == '[') ']' else if (c == '{') '}' else return Result.CONTINUE
    val offset = editor.caretModel.offset
    val text = editor.document.immutableCharSequence
    if (offset < text.length && text[offset] == pair) {
      val c2 = if (offset + 1 < text.length) text[offset + 1] else null
      if (c2 != null && c2 != ')' && c2 != ']' && c2 != '}' && !Character.isWhitespace(c2)) {
        editor.document.insertString(offset + 1, " ")
      }
    }
    else {
      val r = file.findElementAt(offset - 1).findParent(CForm::class)?.textRange
      if (r != null && r.startOffset == offset - 1) {
        editor.document.insertString(r.endOffset + 1, "$pair")
      }
    }
    return Result.CONTINUE
  }
}

private fun slurp(form: CPForm, forward: Boolean): Unit {
  val children = form.iterateForms()
  val target = (if (forward) form.findNextSibling(CForm::class) else form.findPrevSibling(CForm::class)) ?: return
  val copy = target.copy()
  target.delete()
  if (forward) form.addAfter(copy, children.last()) else form.addBefore(copy, children.first())

  if (children.isEmpty) {
    form.delete()
  }
}

private fun barf(form: CPForm, forward: Boolean): Unit {
  val children = form.iterateForms()
  val target = (if (forward) children.last() else children.first()) ?: return
  val copy = target.copy()
  target.delete()
  if (forward) form.parent.addAfter(copy, form) else form.parent.addBefore(copy, form)

  if (children.isEmpty) {
    form.delete()
  }
}

private fun splice(form: CPForm, editor: Editor) {
  val r = form.textRange
  val paren1 = form.iterate().find(Condition<PsiElement> { ClojureTokens.PARENS.contains(it.elementType) })!!.textRange
  val replacement = editor.document.immutableCharSequence.substring(paren1.startOffset + 1, r.endOffset - 1)
  editor.document.replaceString(r.startOffset, r.endOffset, replacement)
}

private fun rise(form: CPForm, editor: Editor) {
  val text = editor.document.immutableCharSequence
  var diff = 0
  editor.caretModel.runForEachCaret { caret ->
    if (caret.hasSelection()) {
      editor.document.replaceString(caret.selectionStart, caret.selectionEnd,
          "(${text.subSequence(caret.selectionStart - diff, caret.selectionEnd - diff)})")
    }
    else {
      val r = form.findElementAt(editor.caretModel.offset - form.textRange.startOffset).parentForm()!!.textRange
      editor.document.replaceString(r.startOffset + diff, r.endOffset + diff, "(${r.subSequence(text)})")
    }
    diff += 2
  }
}

private fun kill(editor: Editor, caret: Caret?, dataContext: DataContext, forward: Boolean): Boolean {
  val offset = caret?.offset ?: editor.caretModel.offset
  val project = LangDataKeys.PROJECT.getData(dataContext) ?: return false
  val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) as? ClojureFile ?: return false
  val elementAt = psiFile.findElementAt(if (forward) offset else offset - 1).let { if (ClojureTokens.PARENS.contains(it.elementType)) it else null }
  val form = elementAt.findParent(CPForm::class) ?: return false
  val r = form.textRange
  val replacement = if (forward && r.startOffset == offset || !forward && r.endOffset == offset) ""
  else {
    val paren1 = form.iterate().find(Condition<PsiElement> { ClojureTokens.PARENS.contains(it.elementType) })!!.textRange
    editor.document.immutableCharSequence.substring(paren1.startOffset + 1, r.endOffset - 1)
  }
  editor.document.replaceString(r.startOffset, r.endOffset, replacement)
  if (!forward) (caret ?: editor.caretModel.currentCaret).apply { moveToOffset(getOffset() - 1) }
  return true
}
