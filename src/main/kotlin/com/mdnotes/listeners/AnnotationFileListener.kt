package com.mdnotes.listeners

import com.mdnotes.messaging.AnnotationSyncNotifier
import com.mdnotes.storage.AnnotationStorageService
import com.intellij.openapi.project.ProjectManager
import java.io.File
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * Watches for external changes to .annotations JSON sidecar files
 * (e.g., from git pull or another process editing them)
 * and notifies the annotation system to refresh.
 */
class AnnotationFileListener : AsyncFileListener {

    override fun prepareChange(events: MutableList<out VFileEvent>): AsyncFileListener.ChangeApplier? {
        val annotationEvents = events.filter { event ->
            val path = event.path
            path.endsWith(".json") && path.split("/").contains(AnnotationStorageService.ANNOTATIONS_DIR)
        }

        if (annotationEvents.isEmpty()) return null

        return object : AsyncFileListener.ChangeApplier {
            override fun afterVfsChange() {
                events@ for (event in annotationEvents) {
                    val sidecarPath = Path.of(event.path)
                    val annotationsDir = sidecarPath.parent ?: continue

                    // Walk up to find the .annotations directory
                    var current = annotationsDir
                    while (current.fileName?.pathString != AnnotationStorageService.ANNOTATIONS_DIR) {
                        current = current.parent ?: continue@events
                    }

                    val projectBasePath = current.parent?.pathString ?: continue
                    val relativeSidecar = current.relativize(sidecarPath).pathString
                    val sourceRelativePath = relativeSidecar.removeSuffix(".json")
                    val sourceAbsPath = "$projectBasePath/$sourceRelativePath"

                    val sourceFile = LocalFileSystem.getInstance()
                        .findFileByPath(sourceAbsPath) ?: continue

                    for (project in ProjectManager.getInstance().openProjects) {
                        if (project.basePath == projectBasePath) {
                            val storage = AnnotationStorageService.getInstance(project)
                            if (storage.isRecentWrite(event.path)) continue
                            project.messageBus
                                .syncPublisher(AnnotationSyncNotifier.TOPIC)
                                .annotationsChanged(sourceFile)
                        }
                    }
                }
            }
        }
    }
}
