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
import com.intellij.psi.tree.IElementType;

public class CListImpl extends CListBase implements CList {

  public CListImpl(IElementType type) {
    super(type);
  }

  public void accept(@NotNull ClojureVisitor visitor) {
    visitor.visitList(this);
  }

  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof ClojureVisitor) accept((ClojureVisitor)visitor);
    else super.accept(visitor);
  }

  public int getTextOffset() {
    return ClojurePsiImplUtil.getTextOffset(this);
  }

  @Nullable
  public IDef getDef() {
    return ClojurePsiImplUtil.getDef(this);
  }

  @Nullable
  public CSymbol getFirst() {
    return ClojurePsiImplUtil.getFirst(this);
  }

}
