// This is a generated file. Not intended for manual editing.
package org.intellij.clojure.psi.impl;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import static org.intellij.clojure.psi.ClojureTypes.*;
import org.intellij.clojure.psi.*;
import com.intellij.psi.PsiQualifiedReference;
import com.intellij.psi.tree.IElementType;

public class CSymbolImpl extends CSymbolBase implements CSymbol {

  public CSymbolImpl(@NotNull IElementType type) {
    super(type);
  }

  public void accept(@NotNull ClojureVisitor visitor) {
    visitor.visitSymbol(this);
  }

  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof ClojureVisitor) accept((ClojureVisitor)visitor);
    else super.accept(visitor);
  }

  @NotNull
  public String getName() {
    return ClojurePsiImplUtil.getName(this);
  }

  @NotNull
  public String getQualifiedName() {
    return ClojurePsiImplUtil.getQualifiedName(this);
  }

  @Nullable
  public CSymbol getQualifier() {
    return ClojurePsiImplUtil.getQualifier(this);
  }

  public int getTextOffset() {
    return ClojurePsiImplUtil.getTextOffset(this);
  }

  @NotNull
  public PsiQualifiedReference getReference() {
    return ClojurePsiImplUtil.getReference(this);
  }

}
