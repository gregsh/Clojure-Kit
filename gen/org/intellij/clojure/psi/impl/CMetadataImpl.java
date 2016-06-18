// This is a generated file. Not intended for manual editing.
package org.intellij.clojure.psi.impl;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import static org.intellij.clojure.psi.ClojureTypes.*;
import com.intellij.extapi.psi.ASTWrapperPsiElement;
import org.intellij.clojure.psi.*;

public class CMetadataImpl extends ASTWrapperPsiElement implements CMetadata {

  public CMetadataImpl(ASTNode node) {
    super(node);
  }

  public void accept(@NotNull ClojureVisitor visitor) {
    visitor.visitMetadata(this);
  }

  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof ClojureVisitor) accept((ClojureVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @Nullable
  public CForm getForm() {
    return PsiTreeUtil.getChildOfType(this, CForm.class);
  }

}
