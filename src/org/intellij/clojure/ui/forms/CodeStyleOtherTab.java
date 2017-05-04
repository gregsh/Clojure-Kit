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

package org.intellij.clojure.ui.forms;

import com.intellij.application.options.CodeStyleAbstractPanel;
import com.intellij.lang.Language;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.ui.components.JBCheckBox;
import org.intellij.clojure.lang.ClojureFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

import static org.intellij.clojure.formatter.Clojure_formatterKt.getClojureSettings;

/**
 * @author gregsh
 */
public class CodeStyleOtherTab extends CodeStyleAbstractPanel {

  private JPanel myPanel;
  private JBCheckBox myUseDoubleSemiCb;

  public CodeStyleOtherTab(@Nullable Language defaultLanguage,
                              @Nullable CodeStyleSettings currentSettings,
                              @NotNull CodeStyleSettings settings) {
    super(defaultLanguage, currentSettings, settings);
  }

  @Override
  protected int getRightMargin() {
    return 0;
  }

  @Nullable
  @Override
  protected EditorHighlighter createHighlighter(EditorColorsScheme scheme) {
    return null;
  }

  @NotNull
  @Override
  protected FileType getFileType() {
    return ClojureFileType.INSTANCE;
  }

  @Nullable
  @Override
  protected String getPreviewText() {
    return null;
  }

  @Nullable
  @Override
  public JComponent getPanel() {
    return myPanel;
  }

  @Override
  public void apply(CodeStyleSettings settings) throws ConfigurationException {
    getClojureSettings(settings).USE_2SEMI_COMMENT = myUseDoubleSemiCb.isSelected();
  }

  @Override
  public boolean isModified(CodeStyleSettings settings) {
    boolean modified = false;
    modified |= myUseDoubleSemiCb.isSelected() != getClojureSettings(settings).USE_2SEMI_COMMENT;
    return modified;
  }

  @Override
  protected void resetImpl(CodeStyleSettings settings) {
    myUseDoubleSemiCb.setSelected(getClojureSettings(settings).USE_2SEMI_COMMENT);
  }
}
