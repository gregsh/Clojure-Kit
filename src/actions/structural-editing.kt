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
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ProperTextRange
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.IElementType
import org.intellij.clojure.parser.ClojureTokens
import org.intellij.clojure.psi.*
import org.intellij.clojure.util.*
import java.util.*

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
      PsiDocumentManager.getInstance(file.project).commitDocument(editor.document)
      actionFun(file, editor)?.let {
        WriteCommandAction.runWriteCommandAction(file.project) { it() }
      }
    }
  }

  abstract fun actionFun(file: ClojureFile, editor: Editor): (() -> Unit)?
}

abstract class CPFormActionBase : EditActionBase() {
  override fun actionFun(file: ClojureFile, editor: Editor): (() -> Unit)? {
    val form = file.findElementAt(editor.caretModel.offset).findParent(CPForm::class) ?: run {
      HintManager.getInstance().showErrorHint(editor, "Place caret inside a list-like form")
      return null
    }
    return { actionPerformed(form, file, editor) }
  }

  abstract fun actionPerformed(form: CPForm, file: ClojureFile, editor: Editor)
}

class SlurpBwdAction : CPFormActionBase() {
  override fun actionPerformed(form: CPForm, file: ClojureFile, editor: Editor) = slurp(form, editor, false)
}

class SlurpFwdAction : CPFormActionBase() {
  override fun actionPerformed(form: CPForm, file: ClojureFile, editor: Editor) = slurp(form, editor, true)
}

class BarfBwdAction : CPFormActionBase() {
  override fun actionPerformed(form: CPForm, file: ClojureFile, editor: Editor) = barf(form, editor, false)
}

class BarfFwdAction : CPFormActionBase() {
  override fun actionPerformed(form: CPForm, file: ClojureFile, editor: Editor) = barf(form, editor, true)
}

class SpliceAction : CPFormActionBase() {
  override fun actionPerformed(form: CPForm, file: ClojureFile, editor: Editor) = splice(form, editor)
}

class RiseAction : EditActionBase() {
  override fun actionFun(file: ClojureFile, editor: Editor) = { rise(file, editor) }
}

class KillAction : EditActionBase() {
  override fun actionFun(file: ClojureFile, editor: Editor) = { kill(file, editor) }
}

class ClojureBackspaceHandler(val original: EditorWriteActionHandler) : EditorWriteActionHandler(true) {
  override fun executeWriteAction(editor: Editor, caret: Caret?, dataContext: DataContext) =
      if (!kill(editor, caret, dataContext, false)) original.executeWriteAction(editor, caret, dataContext) else Unit
}

class ClojureDeleteHandler(val original: EditorWriteActionHandler) : EditorWriteActionHandler(true) {
  override fun executeWriteAction(editor: Editor, caret: Caret?, dataContext: DataContext) =
      if (!kill(editor, caret, dataContext, true)) original.executeWriteAction(editor, caret, dataContext) else Unit
}

class ClojureTypedHandler : TypedHandlerDelegate() {

  override fun charTyped(c: Char, project: Project?, editor: Editor, file: PsiFile): Result {
    if (file !is ClojureFile) return Result.CONTINUE
    val pair = if (c == '(') ')' else if (c == '[') ']' else if (c == '{') '}' else return Result.CONTINUE
    val offset = editor.caretModel.offset
    if (isNotBalanced(editor, offset)) return Result.CONTINUE
    val text = editor.document.charsSequence
    if (offset < text.length && text[offset] == pair) {
      val c2 = if (offset + 1 < text.length) text[offset + 1] else null
      if (c2 != null && c2 != ')' && c2 != ']' && c2 != '}' && !Character.isWhitespace(c2)) {
        editor.document.insertString(offset + 1, " ")
      }
    }
    else {
      val r = file.findElementAt(offset - 1).parentForm?.textRange
      if (r != null && r.startOffset == offset - 1) {
        editor.document.insertString(r.endOffset + 1, "$pair")
      }
    }
    return Result.CONTINUE
  }
}

private fun slurp(form: CPForm, editor: Editor, forward: Boolean): Unit {
  val target = (if (forward) form.nextForm else form.prevForm)?.textRange ?: return
  val parens = form.iterate().filter { ClojureTokens.PAREN_ALIKE.contains(it.elementType) }.map { it.textRange }.toList()
  if (parens.size != 2) return
  val range = form.textRange
  val s = editor.document.charsSequence
  val isEmpty = parens[0].endOffset == parens[1].startOffset
  if (forward) {
    editor.document.replaceString(range.startOffset, target.endOffset, StringBuilder()
        .append(s.subSequence(range.startOffset, parens[1].startOffset))
        .append(s.subSequence(if (isEmpty) target.startOffset else parens[1].endOffset, target.endOffset))
        .append(s.subSequence(parens[1].startOffset, parens[1].endOffset)))
  }
  else {
    editor.document.replaceString(target.startOffset, range.endOffset, StringBuilder()
        .append(s.subSequence(parens[0].startOffset, parens[0].endOffset))
        .append(s.subSequence(target.startOffset, if (isEmpty) target.endOffset else parens[0].startOffset))
        .append(s.subSequence(parens[0].endOffset, range.endOffset)))
  }
}

private fun barf(form: CPForm, editor: Editor, forward: Boolean): Unit {
  val target = form.childForms.run { if (forward) last() else first() }?.textRange ?: return
  val parens = form.iterate().filter { ClojureTokens.PAREN_ALIKE.contains(it.elementType) }.map { it.textRange }.toList()
  if (parens.size != 2) return

  val range = form.textRange
  val s = editor.document.charsSequence

  val nonWsIdx0 = if (forward) target.startOffset else target.endOffset
  val delta = if (forward) -1 else 1
  var nonWsIdx = nonWsIdx0
  while (range.contains(nonWsIdx + delta) && s[nonWsIdx + (if (forward) delta else 0)].isWhitespace()) nonWsIdx += delta
  val addSpace = nonWsIdx0 == nonWsIdx

  if (forward) {
    editor.document.replaceString(range.startOffset, range.endOffset, StringBuilder()
        .append(s.subSequence(range.startOffset, nonWsIdx))
        .append(s.subSequence(parens[1].startOffset, parens[1].endOffset))
        .append(if (addSpace) " " else "")
        .append(s.subSequence(nonWsIdx, target.endOffset)))
  }
  else {
    editor.document.replaceString(range.startOffset, range.endOffset, StringBuilder()
        .append(s.subSequence(target.startOffset, nonWsIdx))
        .append(if (addSpace) " " else "")
        .append(s.subSequence(parens[0].startOffset, parens[0].endOffset))
        .append(s.subSequence(nonWsIdx, range.endOffset)))
  }
}

private fun splice(form: CPForm, editor: Editor) {
  val parens = form.iterate().filter { ClojureTokens.PAREN_ALIKE.contains(it.elementType) }.map { it.textRange }.toList()
  val range = (form.parent as? CMetadata ?: form).textRange
  val replacement = when (parens.size) {
    2 -> ProperTextRange(parens[0].endOffset, parens[1].startOffset)
    1 -> ProperTextRange(parens[0].endOffset, range.endOffset)
    else -> return
  }.subSequence(editor.document.charsSequence).trim { it != '\n' && it.isWhitespace() }
  editor.document.replaceString(range.startOffset, range.endOffset, replacement)
}

private fun rise(file: ClojureFile, editor: Editor) {
  val ranges = ArrayList<TextRange>()
  editor.caretModel.runForEachCaret { caret ->
    if (!caret.hasSelection()) {
      file.formAt(caret.offset)?.let { ranges.add(it.textRange) }
    }
    else {
      ranges.add(ProperTextRange(
          file.findElementAt(caret.selectionStart).parentForm?.textRange?.startOffset ?: caret.selectionStart,
          file.findElementAt(caret.selectionEnd - 1).parentForm?.textRange?.endOffset ?: caret.selectionEnd))
    }
  }
  val s = editor.document.immutableCharSequence
  var diff = 0
  ranges.forEach { r ->
    editor.document.replaceString(r.startOffset + diff, r.endOffset + diff, "(${r.subSequence(s)})")
    diff += 2
  }
}

private fun kill(file: ClojureFile, editor: Editor) {
  val ranges = ArrayList<TextRange>()
  editor.caretModel.runForEachCaret { caret ->
    if (!caret.hasSelection()) {
      file.formAt(caret.offset)?.
          let { it.findParent(CPForm::class) ?: it }?.
          let { it.parent as? CMetadata ?: it }?.
          let { ranges.add(it.textRange) }
    }
    else {
      ranges.add(ProperTextRange(
          file.findElementAt(caret.selectionStart).parentForm?.textRange?.startOffset ?: caret.selectionStart,
          file.findElementAt(caret.selectionEnd - 1).parentForm?.textRange?.endOffset ?: caret.selectionEnd))
    }
  }
  var diff = 0
  ranges.forEach { r ->
    diff += kill(r.shiftRight(-diff), editor.document)
  }
}

private fun kill(range: TextRange, document: Document): Int {
  val s = document.immutableCharSequence
  var o1 = range.startOffset
  var o2 = range.endOffset
  var nl1 = false
  var nl2 = false
  while (o1 > 0 && s[o1 - 1].run { nl1 = this == '\n'; !nl1 && isWhitespace() }) o1--
  while (o2 < s.length && s[o2].run { nl2 = this == '\n'; !nl2 && isWhitespace() }) o2++
  if (nl1 && nl2) o2++
  else if (nl2) o2 = range.endOffset
  else o1 = range.startOffset
  document.replaceString(o1, o2, "")
  return o2 - o1
}

private fun kill(editor: Editor, caret: Caret?, dataContext: DataContext, forward: Boolean): Boolean {
  if (caret?.hasSelection() ?: editor.selectionModel.hasSelection()) return false
  val offset = caret?.offset ?: editor.caretModel.offset
  val project = LangDataKeys.PROJECT.getData(dataContext) ?: return false
  val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) as? ClojureFile ?: return false
  val elementAt = psiFile.findElementAt(if (forward) offset else offset - 1)
  val form = elementAt.findParent(CPForm::class) ?: return false
  if (isNotBalanced(editor)) return false
  val paren1 = ClojureTokens.PAREN1_ALIKE.contains(elementAt.elementType)
  val paren2 = ClojureTokens.PAREN2_ALIKE.contains(elementAt.elementType)
  if (!paren1 && !paren2) {
    if (!forward || elementAt == null || elementAt.textRange.startOffset != form.textRange.startOffset) return false
    kill(elementAt.let { it.parent as? CMetadata ?: form }.textRange, editor.document)
    return true
  }
  if (forward && paren1 || !forward && paren2) {
    kill(form.let { it.parent as? CMetadata ?: it }.textRange, editor.document)
  }
  else {
    splice(form, editor)
  }
  return true
}

private fun ClojureFile.formAt(offset: Int): CForm? {
  return findElementAt(offset)?.let { e ->
    if (offset > 0 && e is PsiWhiteSpace) findElementAt(offset - 1) else e
  }.parentForm
}

private fun isNotBalanced(editor: Editor, skipAtOffset: Int = -1): Boolean {
  val counter = ParenCounter()
  val iterator = (editor as EditorEx).highlighter.createIterator(0)
  while (!iterator.atEnd()) {
    if (skipAtOffset == -1 || skipAtOffset != iterator.end) {
      counter.visit(iterator.tokenType)
    }
    iterator.advance()
  }
  return !counter.isBalanced
}

private class ParenCounter(var parens: Int = 0, var braces: Int = 0, var brackets: Int = 0) {
  val isBalanced: Boolean get() = parens == 0 && braces == 0 && brackets == 0

  fun visit(t: IElementType): Unit {
    when (t) {
      ClojureTypes.C_PAREN1 -> parens++
      ClojureTypes.C_PAREN2 -> parens--
      ClojureTypes.C_BRACE1 -> braces++
      ClojureTypes.C_BRACE2 -> braces--
      ClojureTypes.C_BRACKET1 -> brackets++
      ClojureTypes.C_BRACKET2 -> brackets--
    }
  }
}
