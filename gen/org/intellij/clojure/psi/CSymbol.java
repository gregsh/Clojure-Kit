// This is a generated file. Not intended for manual editing.
package org.intellij.clojure.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiQualifiedReference;
import kotlin.jvm.JvmStatic;

public interface CSymbol extends CSForm {

  @JvmStatic
  @NotNull
  String getName();

  @JvmStatic
  @NotNull
  String getQualifiedName();

  @JvmStatic
  @Nullable
  CSymbol getQualifier();

  @JvmStatic
  int getTextOffset();

  @JvmStatic
  @NotNull
  PsiQualifiedReference getReference();

}
