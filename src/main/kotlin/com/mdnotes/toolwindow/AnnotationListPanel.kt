package com.mdnotes.toolwindow

import com.mdnotes.messaging.AnnotationSyncNotifier
import com.mdnotes.model.ANNOTATION_COLORS
import com.mdnotes.model.Annotation
import com.mdnotes.storage.AnnotationStorageService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.UIManager
import org.intellij.plugins.markdown.lang.MarkdownFileType

class AnnotationListPanel(
    private val project: Project
) : Disposable {

    private enum class SortMode(val label: String) {
        POSITION("Position"),
        DATE_CREATED("Date created"),
        DATE_MODIFIED("Date modified"),
        TAG("Tag"),
        COLOR("Color");

        override fun toString() = label
    }

    private var currentSort = SortMode.POSITION
    private var reverseOrder = false

    private val listModel = DefaultListModel<Annotation>()
    private val annotationList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = AnnotationCellRenderer()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = locationToIndex(e.point)
                if (index < 0) return
                val cellBounds = getCellBounds(index, index)
                if (cellBounds == null || !cellBounds.contains(e.point)) return
                val annotation = listModel.getElementAt(index)
                if (e.clickCount == 1) {
                    scrollPreviewTo(annotation)
                } else if (e.clickCount == 2) {
                    showPopoverFor(annotation)
                }
            }
        })
    }

    private val sortCombo = JComboBox(SortMode.entries.toTypedArray()).apply {
        selectedItem = currentSort
        addActionListener {
            currentSort = selectedItem as SortMode
            refreshList()
        }
    }

    private val sortOrderAction = object : ToggleAction(
        "Reverse Sort Order", "Toggle ascending/descending", AllIcons.ObjectBrowser.Sorted
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = reverseOrder

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            reverseOrder = state
            refreshList()
        }

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.icon = if (reverseOrder)
                AllIcons.General.ArrowDown
            else
                AllIcons.General.ArrowUp
            e.presentation.text = if (reverseOrder) "Descending" else "Ascending"
        }
    }

    private val toolbar: JPanel = run {
        val actionGroup = DefaultActionGroup().apply { add(sortOrderAction) }
        val actionToolbar = ActionManager.getInstance()
            .createActionToolbar("AnnotatorSort", actionGroup, true)
        actionToolbar.targetComponent = annotationList

        JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(sortCombo)
            add(actionToolbar.component)
        }
    }

    val component: JPanel = JPanel(BorderLayout()).apply {
        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(annotationList), BorderLayout.CENTER)
    }

    private var currentFile: VirtualFile? = null

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(AnnotationSyncNotifier.TOPIC, object : AnnotationSyncNotifier {
            override fun annotationsChanged(markdownFile: VirtualFile) {
                ApplicationManager.getApplication().invokeLater {
                    if (markdownFile == currentFile) {
                        refreshList()
                    }
                }
            }
        })

        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val file = event.newFile
                    if (file != null && file.fileType == MarkdownFileType.INSTANCE) {
                        currentFile = file
                        refreshList()
                    }
                }
            })

        FileEditorManager.getInstance(project).selectedFiles.firstOrNull {
            it.fileType == MarkdownFileType.INSTANCE
        }?.let {
            currentFile = it
            refreshList()
        }
    }

    private fun refreshList() {
        val file = currentFile ?: return
        val storage = AnnotationStorageService.getInstance(project)

        ApplicationManager.getApplication().executeOnPooledThread {
            val annotations = storage.loadAnnotations(file).annotations
            val sorted = sortAnnotations(annotations)

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                listModel.clear()
                sorted.forEach { listModel.addElement(it) }
            }
        }
    }

    private fun sortAnnotations(annotations: List<Annotation>): List<Annotation> {
        val comparator: Comparator<Annotation> = when (currentSort) {
            SortMode.POSITION -> compareBy { it.selector.position.start }
            SortMode.DATE_CREATED -> compareBy { it.createdAt }
            SortMode.DATE_MODIFIED -> compareBy { it.updatedAt }
            SortMode.TAG -> compareBy<Annotation> { it.tag.isEmpty() }.thenBy { it.tag.lowercase() }
            SortMode.COLOR -> compareBy { ANNOTATION_COLORS.indexOf(it.color).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        }

        val effective = if (reverseOrder) comparator.reversed() else comparator
        return annotations.sortedWith(effective)
    }

    private fun scrollPreviewTo(annotation: Annotation) {
        project.messageBus
            .syncPublisher(AnnotationSyncNotifier.TOPIC)
            .scrollToAnnotation(annotation.id)
    }

    private fun showPopoverFor(annotation: Annotation) {
        project.messageBus
            .syncPublisher(AnnotationSyncNotifier.TOPIC)
            .showPopoverForAnnotation(annotation.id)
    }

    override fun dispose() = Unit

    private class AnnotationCellRenderer : ListCellRenderer<Annotation> {
        override fun getListCellRendererComponent(
            list: JList<out Annotation>,
            value: Annotation,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val panel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(4, 0, 4, 8)
                isOpaque = true
                background = if (isSelected) list.selectionBackground else list.background
            }

            // Color stripe on the left
            val stripe = JPanel().apply {
                preferredSize = Dimension(4, 0)
                isOpaque = true
                background = parseColor(value.color)
            }
            panel.add(stripe, BorderLayout.WEST)

            // Text content
            val textPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyLeft(8)
            }

            // Note (primary text)
            if (value.note.isNotEmpty()) {
                val noteText = if (value.note.length > 60) value.note.take(60) + "\u2026" else value.note
                val noteLabel = JLabel(noteText).apply {
                    foreground = if (isSelected) list.selectionForeground else list.foreground
                    font = list.font
                }
                textPanel.add(noteLabel, BorderLayout.NORTH)
            }

            // Tag + excerpt (secondary line)
            val secondaryParts = mutableListOf<String>()
            if (value.tag.isNotEmpty()) {
                secondaryParts.add("[${value.tag}]")
            }
            val quoteExcerpt = value.selector.quote.exact.let {
                if (it.length > 40) it.take(40) + "\u2026" else it
            }
            secondaryParts.add("\"$quoteExcerpt\"")

            val secondaryLabel = JLabel(secondaryParts.joinToString(" ")).apply {
                foreground = if (isSelected) list.selectionForeground
                    else (UIManager.getColor("Label.disabledForeground") ?: Color.GRAY)
                font = list.font.deriveFont(list.font.size2D - 1f)
            }
            textPanel.add(secondaryLabel, if (value.note.isNotEmpty()) BorderLayout.SOUTH else BorderLayout.CENTER)

            panel.add(textPanel, BorderLayout.CENTER)
            return panel
        }

        private fun parseColor(hex: String): Color {
            return try {
                Color.decode(hex)
            } catch (_: Exception) {
                Color.YELLOW
            }
        }
    }
}
