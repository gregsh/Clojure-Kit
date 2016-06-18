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
import org.intellij.clojure.psi.stubs.CListStub;
import org.intellij.clojure.psi.stubs.CListElementType;

public class CListImpl extends CListBase implements CList {

  public CListImpl(CListStub stub, CListElementType nodeType, ASTNode node) {
    super(stub, nodeType, node);
  }

  public CListImpl(CListStub stub) {
    super(stub);
  }

  public CListImpl(ASTNode node) {
    super(node);
  }

  public void accept(@NotNull ClojureVisitor visitor) {
    visitor.visitList(this);
  }

  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof ClojureVisitor) accept((ClojureVisitor)visitor);
    else super.accept(visitor);
  }

}
