// This is a generated file. Not intended for manual editing.
package org.intellij.clojure.psi;

import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElementVisitor;

public class ClojureVisitor extends PsiElementVisitor {

  public void visitAccess(@NotNull CAccess o) {
    visitSForm(o);
  }

  public void visitCommented(@NotNull CCommented o) {
    visitElement(o);
  }

  public void visitConstructor(@NotNull CConstructor o) {
    visitPForm(o);
  }

  public void visitForm(@NotNull CForm o) {
    visitElement(o);
  }

  public void visitFun(@NotNull CFun o) {
    visitList(o);
  }

  public void visitKeyword(@NotNull CKeyword o) {
    visitSForm(o);
  }

  public void visitLVForm(@NotNull CLVForm o) {
    visitPForm(o);
  }

  public void visitList(@NotNull CList o) {
    visitLVForm(o);
  }

  public void visitLiteral(@NotNull CLiteral o) {
    visitSForm(o);
  }

  public void visitMap(@NotNull CMap o) {
    visitPForm(o);
  }

  public void visitMetadata(@NotNull CMetadata o) {
    visitElement(o);
  }

  public void visitPForm(@NotNull CPForm o) {
    visitForm(o);
  }

  public void visitReaderMacro(@NotNull CReaderMacro o) {
    visitElement(o);
  }

  public void visitRegexp(@NotNull CRegexp o) {
    visitLiteral(o);
  }

  public void visitSForm(@NotNull CSForm o) {
    visitForm(o);
  }

  public void visitSet(@NotNull CSet o) {
    visitPForm(o);
  }

  public void visitSymbol(@NotNull CSymbol o) {
    visitSForm(o);
  }

  public void visitVec(@NotNull CVec o) {
    visitLVForm(o);
  }

  public void visitElement(@NotNull CElement o) {
    super.visitElement(o);
  }

}
