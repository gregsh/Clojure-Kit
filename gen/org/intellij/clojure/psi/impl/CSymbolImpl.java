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
import kotlin.jvm.JvmStatic;

public class CSymbolImpl extends CSFormImpl implements CSymbol {

  public CSymbolImpl(ASTNode node) {
    super(node);
  }

  public void accept(@NotNull ClojureVisitor visitor) {
    visitor.visitSymbol(this);
  }

  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof ClojureVisitor) accept((ClojureVisitor)visitor);
    else super.accept(visitor);
  }

  @JvmStatic
  @NotNull
  public String getName() {
    return ClojurePsiImplUtil.getName(this);
  }

  @JvmStatic
  @NotNull
  public String getQualifiedName() {
    return ClojurePsiImplUtil.getQualifiedName(this);
  }

  @JvmStatic
  @Nullable
  public CSymbol getQualifier() {
    return ClojurePsiImplUtil.getQualifier(this);
  }

  @JvmStatic
  public int getTextOffset() {
    return ClojurePsiImplUtil.getTextOffset(this);
  }

  @JvmStatic
  @NotNull
  public PsiQualifiedReference getReference() {
    return ClojurePsiImplUtil.getReference(this);
  }

}
