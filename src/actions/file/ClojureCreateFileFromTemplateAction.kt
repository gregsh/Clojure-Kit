package org.intellij.clojure.actions.file

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import org.intellij.clojure.ClojureIcons

class ClojureCreateFileFromTemplateAction : CreateFileFromTemplateAction(ClojureCreateFileFromTemplateAction.CAPTION, "", ClojureIcons.CLOJURE_ICON), DumbAware {

    override fun getActionName(directory: PsiDirectory?, newName: String?, templateName: String?): String = CAPTION

    override fun buildDialog(project: Project?,
                             directory: PsiDirectory?,
                             builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle(CAPTION)
                .addKind("Empty File", ClojureIcons.CLOJURE_ICON, ClojureCreateFileFromTemplateAction.TEMPLATE_FILENAME)
    }

    private companion object {
        private val TEMPLATE_FILENAME = "new-clojure-file"
        private val CAPTION = "New Clojure File"
    }
}