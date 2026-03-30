package com.mdnotes.messaging

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic

interface AnnotationSyncNotifier {
    companion object {
        @JvmField
        val TOPIC = Topic.create(
            "Markdown Annotation Changes",
            AnnotationSyncNotifier::class.java
        )
    }

    fun annotationsChanged(markdownFile: VirtualFile)
    fun scrollToAnnotation(annotationId: String) {}
    fun showPopoverForAnnotation(annotationId: String) {}
}
