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

import com.intellij.codeInsight.editorActions.TypedHandler
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.hint.HintManager
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorAction
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ProperTextRange
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiUtilBase
import org.intellij.clojure.editor.ClojureSmartKeysOptions
import org.intellij.clojure.lang.ClojureTokens
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

class ClojureBackspaceHandler(original: EditorWriteActionHandler) : ClojureSmartKillHandlerBase(original, false)
class ClojureDeleteHandler(original: EditorWriteActionHandler) : ClojureSmartKillHandlerBase(original, true)

abstract class EditActionBase(private val handler: (CFile, Document, Caret) -> (() -> Unit)?)
  : EditorAction(object : EditorActionHandler(false) {

  override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean {
    if (editor.caretModel.caretCount != 1) return false
    val project = dataContext?.getData(CommonDataKeys.PROJECT) ?: return false
    return PsiUtilBase.getPsiFileInEditor(editor, project) is CFile
  }

  override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
    val project = dataContext.getData(CommonDataKeys.PROJECT) ?: return
    val currentCaret = editor.caretModel.currentCaret
    val file = PsiUtilBase.getPsiFileInEditor(editor, project) as? CFile ?: return
    PsiDocumentManager.getInstance(project).commitDocument(editor.document)
    handler(file, editor.document, currentCaret)?.let {
      WriteCommandAction.runWriteCommandAction(file.project) { it() }
    }
  }
}) {
  @Suppress("UNUSED_PARAMETER")
  constructor(handler: (CFile, Document, Caret) -> Unit, unit: Unit)
      : this({ file, document, caret -> { handler(file, document, caret) } })

  init {
    @Suppress("LeakingThis")
    setInjectedContext(true)
  }
}

abstract class CPFormActionBase(private val handler: (CPForm, Document, caret: Caret) -> Unit)
  : EditActionBase({ file, document, caret ->
  file.findElementAt(caret.offset).findParent(CPForm::class).let { form ->
    if (form != null) {
      { handler(form, document, caret) }
    }
    else {
      HintManager.getInstance().showErrorHint(caret.editor, "Place caret inside a list-like form")
      null
    }
  }
}) {
  constructor(handler: (CPForm, Document, caret: Caret, Boolean) -> Unit, forward: Boolean)
      : this({ form, document, caret -> handler(form, document, caret, forward) })
}

abstract class ClojureSmartKillHandlerBase(private val original: EditorWriteActionHandler,
                                           private val forward: Boolean)
  : EditorWriteActionHandler(true) {

  override fun executeWriteAction(editor: Editor, caret: Caret?, dataContext: DataContext) {
    if (!service<ClojureSmartKeysOptions>().SMART_KILL || !perform(editor, caret, dataContext)) {
      original.executeWriteAction(editor, caret, dataContext)
    }
  }

  private fun perform(originalEditor: Editor, caret: Caret?, dataContext: DataContext): Boolean {
    if (caret == null || originalEditor.caretModel.caretCount != 1) return false
    val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return false
    val originalFile = PsiUtilBase.getPsiFileInEditor(originalEditor, project) ?: return false
    val editor = TypedHandler.injectedEditorIfCharTypedIsSignificant('x', originalEditor, originalFile)
    val file = (if (editor === originalEditor) originalFile
    else PsiDocumentManager.getInstance(project).getPsiFile(editor.document))
        as? CFile ?: return false
    return kill(file, editor as EditorEx, editor.caretModel.currentCaret, forward)
  }
}


class ClojureTypedHandler : TypedHandlerDelegate() {

  override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
    if (file !is CFile || editor !is EditorEx) return Result.CONTINUE
    if (!service<ClojureSmartKeysOptions>().SMART_PARENS) return Result.CONTINUE
    val p1 = if (c == ')') '(' else if (c == ']') '[' else if (c == '}') '{' else null
    val p2 = if (c == '(') ')' else if (c == '[') ']' else if (c == '{') '}' else null
    if (p1 == null && p2 == null) return Result.CONTINUE
    val offset = editor.caretModel.offset
    editor.highlighter.createIterator(offset - 1).tokenType.let { tokenType ->
      if (ClojureTokens.STRINGS.contains(tokenType) || ClojureTokens.COMMENTS.contains(tokenType)) {
        return Result.CONTINUE
      }
    }
    if (isNotBalanced(editor, offset)) return Result.CONTINUE
    val text = editor.document.charsSequence
    fun needSpaceAt(offset: Int, skip: String) = offset >= 0 && offset < text.length &&
        text[offset].let { ch -> skip.indexOf(ch) == -1 && !Character.isWhitespace(ch) }
    if (p1 != null) {
      val s1 = if (needSpaceAt(offset - 2, "([{")) " " else ""
      val s2 = if (needSpaceAt(offset, ")]}")) " " else ""
      editor.document.replaceString(offset - 1, offset, "$s1$p1${text[offset-1]}$s2")
      val careOffset = if (s1 == "") offset else offset + 1
      editor.caretModel.moveToOffset(careOffset)
    }
    else if (offset < text.length && text[offset] == p2) {
      if (needSpaceAt(offset + 1, ")]}")) editor.document.insertString(offset + 1, " ")
    }
    else {
      val r = nextFormRangeNoCommit(editor, offset, file.language)
      if (r != null && r.startOffset == offset) {
        editor.document.insertString(r.endOffset, "$p2")
      }
    }
    return Result.CONTINUE
  }
}

private fun slurp(form: CPForm, document: Document, @Suppress("UNUSED_PARAMETER") caret: Caret, forward: Boolean) {
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

private fun barf(form: CPForm, document: Document, caret: Caret, forward: Boolean) {
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

  var off = range.startOffset
  if (forward) {
    document.replaceString(range.startOffset, range.endOffset, StringBuilder()
        .append(s.subSequence(range.startOffset, nonWsIdx))
        .apply { off += length }
        .append(s.subSequence(parens[1].startOffset, parens[1].endOffset))
        .append(if (addSpace) " " else "")
        .append(s.subSequence(nonWsIdx, target.endOffset)))
    caret.moveToOffset(Math.min(caret.offset, off))
  }
  else {
    document.replaceString(range.startOffset, range.endOffset, StringBuilder()
        .append(s.subSequence(target.startOffset, nonWsIdx))
        .append(if (addSpace) " " else "")
        .append(s.subSequence(parens[0].startOffset, parens[0].endOffset))
        .apply { off += length }
        .append(s.subSequence(nonWsIdx, range.endOffset)))
    caret.moveToOffset(Math.max(caret.offset, off))
  }
}

private fun splice(form: CPForm, document: Document, caret: Caret) {
  splice(0, form, document, caret, true)
}

private fun splice(base: Int = 0, form: CPForm, document: Document, caret: Caret, fromCaret: Boolean) {
  val offset = caret.offset
  val parens = form.iterate().filter { ClojureTokens.PAREN_ALIKE.contains(it.elementType) }
      .map { it.textRange.shiftRight(base) }.toList()
  if (parens[0].endOffset > offset) {
    return splice(base, form.parentForm as? CPForm ?: return, document, caret, fromCaret)
  }
  val range = (form.parent as? CMetadata ?: form).textRange.shiftRight(base)
  val start = if (fromCaret) offset else parens[0].endOffset
  val replacement = when (parens.size) {
    2 -> ProperTextRange(start, parens[1].startOffset)
    1 -> ProperTextRange(start, range.endOffset)
    else -> return
  }.subSequence(document.charsSequence).trim { it != '\n' && it.isWhitespace() }
  document.replaceString(range.startOffset, range.endOffset, replacement)
  caret.moveToOffset(offset + range.startOffset - start)
}

private fun rise(file: CFile, document: Document, caret: Caret) {
  val range = if (!caret.hasSelection()) file.formAt(caret.offset)?.textRange ?: return
  else ProperTextRange(
      file.findElementAt(caret.selectionStart).thisForm?.textRange?.startOffset ?: caret.selectionStart,
      file.findElementAt(caret.selectionEnd - 1).thisForm?.textRange?.endOffset ?: caret.selectionEnd)
  val s = document.charsSequence
  document.replaceString(range.startOffset, range.endOffset, "(${range.subSequence(s)})")
  caret.moveToOffset(caret.offset + 1)
}

private fun kill(file: CFile, document: Document, caret: Caret) {
  val range = if (!caret.hasSelection()) file.formAt(caret.offset)?.
      let { it as? CPForm ?: it.findParent(CPForm::class) ?: it }?.
      let { it.parent as? CMetadata ?: it }?.textRange ?: return
  else ProperTextRange(
      file.findElementAt(caret.selectionStart).thisForm?.textRange?.startOffset ?: caret.selectionStart,
      file.findElementAt(caret.selectionEnd - 1).thisForm?.textRange?.endOffset ?: caret.selectionEnd)
  kill(range, document)
}

private fun kill(range: TextRange, document: Document) {
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
}

private fun kill(file: CFile, editor: EditorEx, caret: Caret, forward: Boolean): Boolean {
  if (caret.hasSelection()) return false
  val offset = if (forward) caret.offset else Math.max(caret.offset - 1, 0)
  val iterator = editor.highlighter.createIterator(offset)
  if (iterator.atEnd()) return false
  if (forward && iterator.start != offset || !forward && iterator.end != offset + 1) return false
  val tokenType = iterator.tokenType
  val paren1 = ClojureTokens.PAREN1_ALIKE.contains(tokenType)
  val paren2 = ClojureTokens.PAREN2_ALIKE.contains(tokenType)
  val macros = ClojureTokens.MACROS.contains(tokenType) || ClojureTokens.SHARPS.contains(tokenType)
  if (!paren1 && !paren2 && (!forward || !macros)) return false

  val (base, elementAt) = leafElementAtNoCommit(file.project, editor, offset, file.language) ?: return false
  val form = elementAt.findParent(CPForm::class) ?: return false

  if (isNotBalanced(editor)) return false
  if (!paren1 && !paren2) {
    if (elementAt.textRange.startOffset != form.textRange.startOffset) return false
    kill(elementAt.let { it.parent as? CMetadata ?: form }.textRange.shiftRight(base), editor.document)
  }
  else if (forward && paren1 || !forward && paren2) {
    kill(form.let { it.parent as? CMetadata ?: it }.textRange.shiftRight(base), editor.document)
  }
  else {
    splice(base, form, editor.document, caret, false)
  }
  return true
}

private fun CFile.formAt(offset: Int): CForm? {
  return findElementAt(offset)?.let { e ->
    if (offset > 0 && e is PsiWhiteSpace) findElementAt(offset - 1) else e
  }.thisForm
}

private fun leafElementAtNoCommit(project: Project, editor: EditorEx, offset: Int, language: Language): Pair<Int, PsiElement>? {
  if (offset < 0 || editor.document.textLength == 0) return null
  val range = formContextRangeApprox(editor, offset, Mode.CONTEXT)
  val text = range.subSequence(editor.document.immutableCharSequence)
  val file = PsiFileFactory.getInstance(project).createFileFromText("a", language, text, false, false)
  val adjusted = offset - range.startOffset
  val leafAt = file.findElementAt(adjusted) ?: return null
  return range.startOffset to leafAt
}

private fun nextFormRangeNoCommit(editor: EditorEx, offset: Int, language: Language): TextRange? {
  val approx = findRangeApprox(editor, offset, true, true)
  val tailText = editor.document.immutableCharSequence.subSequence(approx.startOffset, approx.endOffset)

  val r = cljLightTraverser(tailText, language).let { s ->
    s.traverse().filter { o -> ClojureTokens.FORMS.contains(s.api.typeOf(o)) }.first()?.let { s.api.rangeOf(it) }
  }
  return r?.shiftRight(approx.startOffset)
}

enum class Mode { LEFT, RIGHT, CONTEXT, FULL }

fun formContextRangeApprox(editor: EditorEx, offset: Int, mode: Mode) = when (mode) {
  Mode.LEFT -> findRangeApprox(editor, offset, true, false)
  Mode.RIGHT -> findRangeApprox(editor, offset, true, true)
  Mode.CONTEXT -> findRangeApprox(editor, offset, true, false).union(findRangeApprox(editor, offset, true, true))
  Mode.FULL -> findRangeApprox(editor, offset, false, false).union(findRangeApprox(editor, offset, false, true))
}

private fun findRangeApprox(editor: EditorEx, offset: Int, siblings: Boolean, forward: Boolean): TextRange {
  val iterator = editor.highlighter.createIterator(offset)
  if (iterator.atEnd()) return TextRange(offset, offset)
  val pos = if (forward) iterator.start else iterator.end
  val counter = ParenCounter()
  if (forward && iterator.start < offset) {
    while (!iterator.atEnd() && iterator.start < offset) {
      iterator.advance()
    }
  }
  else if (!forward && ClojureTokens.PAREN_ALIKE.contains(iterator.tokenType)) {
    if (ClojureTokens.PAREN1_ALIKE.contains(iterator.tokenType)) {
      iterator.retreat()
    }
    else {
      while (!iterator.atEnd()) {
        counter.visit(iterator.tokenType)
        iterator.retreat()
        if (counter.isBalanced) break
      }
    }
  }

  var wasOpen = false
  while (!iterator.atEnd()) {
    val tokenType = iterator.tokenType
    counter.visit(tokenType)
    val pp = counter.parens
    if (pp < 0 || !forward && pp > 0) break
    if (counter.braces == 0 && counter.brackets == 0) {
      if (siblings) {
        if (wasOpen && (forward && pp <= 0 || !forward && pp >= 0)) break
        if (!wasOpen && (forward && pp > 0 || !forward && pp < 0)) wasOpen = true
      }
    }
    if (forward) iterator.advance() else iterator.retreat()
  }
  if (forward) {
    val end = if (!iterator.atEnd()) iterator.end else editor.document.textLength
    return TextRange(pos, end)
  }
  else {
    val start = if (!iterator.atEnd()) iterator.end else 0
    return TextRange(start, pos)
  }
}

private fun isNotBalanced(editor: EditorEx, skipAtOffset: Int = -1): Boolean {
  val counter = ParenCounter()
  val iterator = editor.highlighter.createIterator(0)
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

  fun visit(t: IElementType) {
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
