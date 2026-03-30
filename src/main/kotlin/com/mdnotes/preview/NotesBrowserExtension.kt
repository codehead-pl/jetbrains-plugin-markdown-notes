package com.mdnotes.preview

import com.mdnotes.messaging.AnnotationSyncNotifier
import com.mdnotes.model.Annotation
import com.mdnotes.storage.AnnotationStorageService
import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import org.intellij.plugins.markdown.extensions.MarkdownBrowserPreviewExtension
import org.intellij.plugins.markdown.ui.preview.BrowserPipe
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanel
import org.intellij.plugins.markdown.ui.preview.ResourceProvider
import java.awt.Color
import javax.swing.UIManager

@Suppress("UnstableApiUsage")
internal class NotesBrowserExtension(
    private val panel: MarkdownHtmlPanel
) : MarkdownBrowserPreviewExtension, ResourceProvider {

    companion object {
        private val LOG = Logger.getInstance(NotesBrowserExtension::class.java)
        private val gson = Gson()
        const val EVENT_CREATE_ANNOTATION = "annotator.create"
        const val EVENT_UPDATE_ANNOTATION = "annotator.update"
        const val EVENT_DELETE_ANNOTATION = "annotator.delete"
        const val EVENT_SCROLL_TO = "annotator.scrollTo"
        const val EVENT_SHOW_POPOVER = "annotator.showPopover"
        const val EVENT_PUSH_ANNOTATIONS = "annotator.pushAnnotations"
        const val EVENT_PUSH_THEME = "annotator.pushTheme"
        const val EVENT_REQUEST_ANNOTATIONS = "annotator.requestAnnotations"
        const val EVENT_LOG = "annotator.log"

        private fun colorToRgba(c: Color, alpha: Float = 1f): String {
            return "rgba(${c.red},${c.green},${c.blue},${alpha})"
        }

        fun collectThemeColors(): String {
            fun resolve(key: String, fallback: Color): Color =
                UIManager.getColor(key) ?: fallback

            val bg = resolve("Panel.background", JBColor.PanelBackground)
            val fg = resolve("Label.foreground", JBColor.foreground())
            val border = resolve("Component.borderColor", JBColor.border())
            val focusColor = resolve("Component.focusColor", Color(0x21, 0x96, 0xF3))
            val errorColor = resolve("Component.errorFocusColor", Color(0xF4, 0x43, 0x36))
            val btnDefaultBg = resolve("Button.default.startBackground", Color(0x21, 0x96, 0xF3))

            val font = UIManager.getFont("Label.font")
            val fontSize = font?.size ?: 13
            val fontFamily = font?.family ?: "sans-serif"
            val buttonFontSize = UIManager.getFont("Button.font")?.size ?: fontSize
            val basePad = (fontSize * 0.35).toInt().coerceAtLeast(4)
            val basePadH = (fontSize * 0.75).toInt().coerceAtLeast(8)

            val colorEntries = mapOf(
                "bg" to (bg to 1f),
                "fg" to (fg to 1f),
                "fgSecondary" to (resolve("Label.disabledForeground", JBColor.GRAY) to 1f),
                "border" to (border to 1f),
                "inputBg" to (resolve("TextField.background", JBColor.background()) to 1f),
                "inputBorder" to (resolve("Component.borderColor", JBColor.border()) to 1f),
                "focus" to (focusColor to 1f),
                "focusRing" to (focusColor to 0.3f),
                "error" to (errorColor to 1f),
                "errorRing" to (errorColor to 0.3f),
                "hoverBg" to (resolve("ActionButton.hoverBackground", bg.brighter()) to 1f),
                "btnBg" to (resolve("Button.startBackground", bg) to 1f),
                "btnFg" to (resolve("Button.foreground", fg) to 1f),
                "btnBorder" to (resolve("Button.borderColor", border) to 1f),
                "btnDefaultBg" to (btnDefaultBg to 1f),
                "btnDefaultFg" to (resolve("Button.default.foreground", Color.WHITE) to 1f),
                "btnDefaultBorder" to (resolve("Button.default.borderColor", btnDefaultBg) to 1f),
                "shadow" to (resolve("Component.borderColor", JBColor.border()) to 0.25f)
            )

            val themeMap = mutableMapOf<String, Any>(
                "fontSize" to fontSize,
                "fontFamily" to fontFamily,
                "buttonFontSize" to buttonFontSize,
                "btnPadV" to basePad,
                "btnPadH" to basePadH,
                "inputPadV" to basePad,
                "inputPadH" to (fontSize * 0.5).toInt().coerceAtLeast(6)
            )
            colorEntries.forEach { (key, pair) -> themeMap[key] = colorToRgba(pair.first, pair.second) }

            return gson.toJson(themeMap)
        }
    }

    private val storageService: AnnotationStorageService?
        get() = panel.project?.let { AnnotationStorageService.getInstance(it) }

    private fun pipeHandler(action: (String) -> Unit, label: String) = object : BrowserPipe.Handler {
        override fun processMessageReceived(data: String): Boolean {
            try {
                action(data)
            } catch (e: Exception) {
                LOG.error("Failed to $label annotation", e)
            }
            return false
        }
    }

    private val createHandler = pipeHandler(::handleCreateAnnotation, "create")
    private val updateHandler = pipeHandler(::handleUpdateAnnotation, "update")
    private val deleteHandler = pipeHandler(::handleDeleteAnnotation, "delete")

    private val requestHandler = object : BrowserPipe.Handler {
        override fun processMessageReceived(data: String): Boolean {
            pushThemeToPreview()
            pushAnnotationsToPreview()
            return false
        }
    }

    @Suppress("UnstableApiUsage")
    private val syncListener = object : AnnotationSyncNotifier {
        override fun annotationsChanged(markdownFile: VirtualFile) {
            if (markdownFile == panel.virtualFile) {
                pushAnnotationsToPreview()
            }
        }

        override fun scrollToAnnotation(annotationId: String) {
            panel.browserPipe?.send(EVENT_SCROLL_TO, annotationId)
        }

        override fun showPopoverForAnnotation(annotationId: String) {
            panel.browserPipe?.send(EVENT_SHOW_POPOVER, annotationId)
        }
    }

    init {
        val pipe = panel.browserPipe
        pipe?.subscribe(EVENT_LOG, object : BrowserPipe.Handler {
            override fun processMessageReceived(data: String): Boolean {
                LOG.debug("JS: $data")
                return true
            }
        })
        pipe?.subscribe(EVENT_CREATE_ANNOTATION, createHandler)
        pipe?.subscribe(EVENT_UPDATE_ANNOTATION, updateHandler)
        pipe?.subscribe(EVENT_DELETE_ANNOTATION, deleteHandler)
        pipe?.subscribe(EVENT_REQUEST_ANNOTATIONS, requestHandler)

        panel.project?.messageBus
            ?.connect(this)
            ?.subscribe(AnnotationSyncNotifier.TOPIC, syncListener)

        Disposer.register(this) {
            pipe?.removeSubscription(EVENT_CREATE_ANNOTATION, createHandler)
            pipe?.removeSubscription(EVENT_UPDATE_ANNOTATION, updateHandler)
            pipe?.removeSubscription(EVENT_DELETE_ANNOTATION, deleteHandler)
            pipe?.removeSubscription(EVENT_REQUEST_ANNOTATIONS, requestHandler)
        }
    }

    override val scripts: List<String> = listOf(
        "notes/notes-anchoring.js",
        "notes/notes-ui.js",
        "notes/notes.js"
    )

    override val styles: List<String> = listOf(
        "notes/notes.css"
    )

    override val resourceProvider: ResourceProvider = this

    override val priority: MarkdownBrowserPreviewExtension.Priority =
        MarkdownBrowserPreviewExtension.Priority.AFTER_ALL

    override fun canProvide(resourceName: String): Boolean {
        return resourceName in scripts || resourceName in styles
    }

    override fun loadResource(resourceName: String): ResourceProvider.Resource? {
        return ResourceProvider.loadInternalResource(this::class, resourceName)
    }

    @Suppress("UnstableApiUsage")
    private fun handleCreateAnnotation(json: String) {
        val service = storageService ?: return
        val file = panel.virtualFile ?: return
        val annotation = gson.fromJson(json, Annotation::class.java)

        ApplicationManager.getApplication().executeOnPooledThread {
            service.addAnnotation(file, annotation)
        }
    }

    @Suppress("UnstableApiUsage")
    private fun handleUpdateAnnotation(json: String) {
        val service = storageService ?: return
        val file = panel.virtualFile ?: return
        val element = gson.fromJson(json, com.google.gson.JsonObject::class.java) ?: return
        val id = element.get("id")?.asString ?: return
        val tag = element.get("tag")?.asString
        val note = element.get("note")?.asString
        val color = element.get("color")?.asString

        ApplicationManager.getApplication().executeOnPooledThread {
            service.updateAnnotation(file, id, tag, note, color)
        }
    }

    @Suppress("UnstableApiUsage")
    private fun handleDeleteAnnotation(annotationId: String) {
        val service = storageService ?: return
        val file = panel.virtualFile ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            service.removeAnnotation(file, annotationId)
        }
    }

    private fun pushThemeToPreview() {
        val json = collectThemeColors()
        panel.browserPipe?.send(EVENT_PUSH_THEME, json)
    }

    private fun pushAnnotationsToPreview() {
        val service = storageService ?: return
        val file = panel.virtualFile ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val json = service.serializeAnnotations(file)
            panel.browserPipe?.send(EVENT_PUSH_ANNOTATIONS, json)
        }
    }

    override fun dispose() = Unit

    class Provider : MarkdownBrowserPreviewExtension.Provider {
        override fun createBrowserExtension(
            panel: MarkdownHtmlPanel
        ): MarkdownBrowserPreviewExtension {
            return NotesBrowserExtension(panel)
        }
    }
}
