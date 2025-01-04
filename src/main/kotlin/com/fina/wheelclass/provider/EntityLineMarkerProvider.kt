package com.fina.wheelclass.provider

import com.fina.wheelclass.action.EntitySearchAction
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.util.IconLoader

class EntityLineMarkerProvider : LineMarkerProvider {
    private val searchAction = EntitySearchAction()
    
    // 添加图标加载
    private val icon = IconLoader.getIcon("/icons/similar-class.svg", EntityLineMarkerProvider::class.java)

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // 只处理标识符
        if (element !is PsiIdentifier) return null

        // 获取父级类
        val psiClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java) ?: return null
        
        // 检查是否是类名标识符
        if (element != psiClass.nameIdentifier) return null

        // 检查是否有@Data注解
        if (!hasDataAnnotation(psiClass)) return null

        // 创建LineMarkerInfo
        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { "Search Similar Classes" },
            { _, elt ->
                val project = elt.project
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                val dataContext = SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, project)
                    .add(CommonDataKeys.PSI_FILE, elt.containingFile)
                    .add(CommonDataKeys.EDITOR, editor)
                    .build()
                searchAction.actionPerformed(
                    AnActionEvent(
                        null,
                        dataContext,
                        "SearchSimilarClass",
                        Presentation(),
                        ActionManager.getInstance(),
                        0
                    )
                )
            },
            GutterIconRenderer.Alignment.LEFT,
            { "Search Similar Classes" }
        )
    }

    private fun hasDataAnnotation(psiClass: PsiClass): Boolean {
        return psiClass.modifierList?.annotations?.any {
            it.qualifiedName == "lombok.Data"
        } ?: false
    }
}
