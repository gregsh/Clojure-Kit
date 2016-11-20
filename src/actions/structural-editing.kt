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

/**
 * @author gregsh
 */
class SlurpBwdAction : CPFormActionBase(::slurp, false)
class SlurpFwdAction : CPFormActionBase(::slurp, true)
class BarfBwdAction  : CPFormActionBase(::barf, false)
class BarfFwdAction  : CPFormActionBase(::barf, true)
class SpliceAction   : CPFormActionBase(::splice)
class RiseAction     : EditActionBase(::rise, Unit)
class KillAction     : EditActionBase(::kill, Unit)

class ClojureBackspaceHandler(original: EditorWriteActionHandler) : ClojureEditorHandlerBase(original, ::kill, false)
class ClojureDeleteHandler(original: EditorWriteActionHandler) : ClojureEditorHandlerBase(original, ::kill, true)

abstract class EditActionBase(private val handler: (ClojureFile, Document, Caret) -> (() -> Unit)?)
  : DumbAwareAction() {
  @Suppress("UNUSED_PARAMETER")
  constructor(handler: (ClojureFile, Document, Caret) -> Unit, unit: Unit)
      : this({ file, document, caret -> { handler(file, document, caret) } })

  override fun update(e: AnActionEvent) {
    val editor = e.getData(LangDataKeys.EDITOR)
    val file = e.getData(LangDataKeys.PSI_FILE) as? ClojureFile
    e.presentation.isEnabled = editor != null && file != null && editor.caretModel.caretCount == 1
  }

  override fun actionPerformed(e: AnActionEvent) {
    val editor = e.getData(LangDataKeys.EDITOR) ?: return
    val file = e.getData(LangDataKeys.PSI_FILE) as? ClojureFile ?: return
    val caret = editor.caretModel.currentCaret
    PsiDocumentManager.getInstance(file.project).commitDocument(editor.document)
    handler(file, editor.document, caret)?.let {
      WriteCommandAction.runWriteCommandAction(file.project) { it() }
    }
  }
}

abstract class CPFormActionBase(private val handler: (CPForm, Document) -> Unit)
  : EditActionBase({ file, document, caret ->
  file.findElementAt(caret.offset).findParent(CPForm::class).let { form ->
    if (form != null) {
      { handler(form, document) }
    }
    else {
      HintManager.getInstance().showErrorHint(caret.editor, "Place caret inside a list-like form")
      null
    }
  }
}) {
  constructor(handler: (CPForm, Document, Boolean) -> Unit, forward: Boolean)
      : this({ form, document -> handler(form, document, forward) })
}

abstract class ClojureEditorHandlerBase(val original: EditorWriteActionHandler,
                                        val handler: (ClojureFile, Document, Caret) -> Boolean)
  : EditorWriteActionHandler(true) {
  constructor(original: EditorWriteActionHandler,
              handler: (ClojureFile, Document, Caret, Boolean) -> Boolean,
              forward: Boolean)
      : this(original, { file, document, caret -> handler(file, document, caret, forward) })
  override fun executeWriteAction(editor: Editor, caret: Caret?, dataContext: DataContext) {
    val file = dataContext.getData(LangDataKeys.PSI_FILE) as? ClojureFile
    if (file != null && editor.caretModel.caretCount == 1) {
      if (handler(file, editor.document, caret!!)) return
    }
    original.executeWriteAction(editor, caret, dataContext)
  }
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

private fun slurp(form: CPForm, document: Document, forward: Boolean): Unit {
  val target = (if (forward) form.nextForm else form.prevForm)?.textRange ?: return
  val parens = form.iterate().filter { ClojureTokens.PAREN_ALIKE.contains(it.elementType) }.map { it.textRange }.toList()
  if (parens.size != 2) return
  val range = form.textRange
  val s = document.charsSequence
  val isEmpty = parens[0].endOffset == parens[1].startOffset
  if (forward) {
    document.replaceString(range.startOffset, target.endOffset, StringBuilder()
        .append(s.subSequence(range.startOffset, parens[1].startOffset))
        .append(s.subSequence(if (isEmpty) target.startOffset else parens[1].endOffset, target.endOffset))
        .append(s.subSequence(parens[1].startOffset, parens[1].endOffset)))
  }
  else {
    document.replaceString(target.startOffset, range.endOffset, StringBuilder()
        .append(s.subSequence(parens[0].startOffset, parens[0].endOffset))
        .append(s.subSequence(target.startOffset, if (isEmpty) target.endOffset else parens[0].startOffset))
        .append(s.subSequence(parens[0].endOffset, range.endOffset)))
  }
}

private fun barf(form: CPForm, document: Document, forward: Boolean): Unit {
  val target = form.childForms.run { if (forward) last() else first() }?.textRange ?: return
  val parens = form.iterate().filter { ClojureTokens.PAREN_ALIKE.contains(it.elementType) }.map { it.textRange }.toList()
  if (parens.size != 2) return

  val range = form.textRange
  val s = document.charsSequence

  val nonWsIdx0 = if (forward) target.startOffset else target.endOffset
  val delta = if (forward) -1 else 1
  var nonWsIdx = nonWsIdx0
  while (range.contains(nonWsIdx + delta) && s[nonWsIdx + (if (forward) delta else 0)].isWhitespace()) nonWsIdx += delta
  val addSpace = nonWsIdx0 == nonWsIdx

  if (forward) {
    document.replaceString(range.startOffset, range.endOffset, StringBuilder()
        .append(s.subSequence(range.startOffset, nonWsIdx))
        .append(s.subSequence(parens[1].startOffset, parens[1].endOffset))
        .append(if (addSpace) " " else "")
        .append(s.subSequence(nonWsIdx, target.endOffset)))
  }
  else {
    document.replaceString(range.startOffset, range.endOffset, StringBuilder()
        .append(s.subSequence(target.startOffset, nonWsIdx))
        .append(if (addSpace) " " else "")
        .append(s.subSequence(parens[0].startOffset, parens[0].endOffset))
        .append(s.subSequence(nonWsIdx, range.endOffset)))
  }
}

private fun splice(form: CPForm, document: Document) {
  val parens = form.iterate().filter { ClojureTokens.PAREN_ALIKE.contains(it.elementType) }.map { it.textRange }.toList()
  val range = (form.parent as? CMetadata ?: form).textRange
  val replacement = when (parens.size) {
    2 -> ProperTextRange(parens[0].endOffset, parens[1].startOffset)
    1 -> ProperTextRange(parens[0].endOffset, range.endOffset)
    else -> return
  }.subSequence(document.charsSequence).trim { it != '\n' && it.isWhitespace() }
  document.replaceString(range.startOffset, range.endOffset, replacement)
}

private fun rise(file: ClojureFile, document: Document, caret: Caret) {
  val range = if (!caret.hasSelection()) file.formAt(caret.offset)?.textRange ?: return
  else ProperTextRange(
      file.findElementAt(caret.selectionStart).parentForm?.textRange?.startOffset ?: caret.selectionStart,
      file.findElementAt(caret.selectionEnd - 1).parentForm?.textRange?.endOffset ?: caret.selectionEnd)
  val s = document.charsSequence
  document.replaceString(range.startOffset, range.endOffset, "(${range.subSequence(s)})")
}

private fun kill(file: ClojureFile, document: Document, caret: Caret) {
  val range = if (!caret.hasSelection()) file.formAt(caret.offset)?.
      let { it as? CPForm ?: it.findParent(CPForm::class) ?: it }?.
      let { it.parent as? CMetadata ?: it }?.textRange ?: return
  else ProperTextRange(
      file.findElementAt(caret.selectionStart).parentForm?.textRange?.startOffset ?: caret.selectionStart,
      file.findElementAt(caret.selectionEnd - 1).parentForm?.textRange?.endOffset ?: caret.selectionEnd)
  kill(range, document)
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

private fun kill(file: ClojureFile, document: Document, caret: Caret, forward: Boolean): Boolean {
  if (caret.hasSelection()) return false
  val offset = caret.offset
  val elementAt = file.findElementAt(if (forward) offset else offset - 1)
  val form = elementAt.findParent(CPForm::class) ?: return false
  if (isNotBalanced(caret.editor)) return false
  val paren1 = ClojureTokens.PAREN1_ALIKE.contains(elementAt.elementType)
  val paren2 = ClojureTokens.PAREN2_ALIKE.contains(elementAt.elementType)
  if (!paren1 && !paren2) {
    if (!forward || elementAt == null || elementAt.textRange.startOffset != form.textRange.startOffset) return false
    kill(elementAt.let { it.parent as? CMetadata ?: form }.textRange, document)
    return true
  }
  if (forward && paren1 || !forward && paren2) {
    kill(form.let { it.parent as? CMetadata ?: it }.textRange, document)
  }
  else {
    splice(form, document)
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
