// This is a generated file. Not intended for manual editing.
package org.intellij.clojure.psi;

import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiElement;

public class ClojureVisitor extends PsiElementVisitor {

  public void visitConstructor(@NotNull CConstructor o) {
    visitPForm(o);
  }

  public void visitForm(@NotNull CForm o) {
    visitPsiElement(o);
  }

  public void visitFun(@NotNull CFun o) {
    visitLForm(o);
  }

  public void visitKeyword(@NotNull CKeyword o) {
    visitSForm(o);
  }

  public void visitLForm(@NotNull CLForm o) {
    visitPForm(o);
  }

  public void visitList(@NotNull CList o) {
    visitLForm(o);
  }

  public void visitLiteral(@NotNull CLiteral o) {
    visitSForm(o);
  }

  public void visitMap(@NotNull CMap o) {
    visitPForm(o);
  }

  public void visitMetadata(@NotNull CMetadata o) {
    visitPsiElement(o);
  }

  public void visitPForm(@NotNull CPForm o) {
    visitForm(o);
  }

  public void visitReaderMacro(@NotNull CReaderMacro o) {
    visitPsiElement(o);
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
    visitPForm(o);
  }

  public void visitPsiElement(@NotNull PsiElement o) {
    visitElement(o);
  }

}
