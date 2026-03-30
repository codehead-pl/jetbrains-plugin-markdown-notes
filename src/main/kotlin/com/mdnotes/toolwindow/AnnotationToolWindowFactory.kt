package com.mdnotes.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class AnnotationToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AnnotationListPanel(project)
        val content = ContentFactory.getInstance()
            .createContent(panel.component, "", false)
        content.isCloseable = false
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }
}
