package com.fina.wheelclass.action

import com.fina.wheelclass.ui.EntitySearchDialog
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil

class EntitySearchAction : AnAction() {
    val actionManager: ActionManager = ActionManager.getInstance()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        // 获取光标位置的类
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset)
        val psiClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java) ?: return

        val dialog = EntitySearchDialog(project, psiClass)
        dialog.show()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null
    }
}
