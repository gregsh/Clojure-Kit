// This is a generated file. Not intended for manual editing.
package org.intellij.clojure.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.StubBasedPsiElement;
import org.intellij.clojure.psi.stubs.CKeywordStub;
import kotlin.jvm.JvmStatic;

public interface CKeyword extends CSForm, StubBasedPsiElement<CKeywordStub> {

  @NotNull
  CSymbol getSymbol();

  @JvmStatic
  @NotNull
  String getName();

  @JvmStatic
  @NotNull
  String getNamespace();

  @JvmStatic
  int getTextOffset();

}
