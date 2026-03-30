package com.mdnotes.storage

import com.mdnotes.messaging.AnnotationSyncNotifier
import com.mdnotes.model.ANNOTATION_COLORS
import com.mdnotes.model.Annotation
import com.mdnotes.model.AnnotationFile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service(Service.Level.PROJECT)
class AnnotationStorageService(private val project: Project) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val lock = ReentrantLock()
    private val recentlyWrittenPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()

    companion object {
        private val LOG = Logger.getInstance(AnnotationStorageService::class.java)
        const val ANNOTATIONS_DIR = ".annotations"

        fun getInstance(project: Project): AnnotationStorageService {
            return project.getService(AnnotationStorageService::class.java)
        }

        private val HEX_COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}$")

        /**
         * Derive the sidecar JSON path for a given source file.
         * e.g., "docs/README.md" -> ".annotations/docs/README.md.json"
         */
        fun sidecarPath(projectBasePath: String, sourceRelativePath: String): File {
            return File(projectBasePath, "$ANNOTATIONS_DIR/$sourceRelativePath.json")
        }
    }

    /**
     * Load all annotations for a given markdown VirtualFile.
     * Returns empty list if no sidecar file exists.
     */
    fun loadAnnotations(markdownFile: VirtualFile): AnnotationFile {
        val relativePath = relativePathFor(markdownFile) ?: return emptyFile(markdownFile)
        val basePath = project.basePath ?: return emptyFile(markdownFile)
        val ioFile = sidecarPath(basePath, relativePath)

        if (!ioFile.isFile) return emptyFile(markdownFile)

        return try {
            val content = ioFile.readText(Charsets.UTF_8)
            gson.fromJson(content, AnnotationFile::class.java) ?: emptyFile(markdownFile)
        } catch (e: Exception) {
            LOG.warn("Failed to parse annotation sidecar for ${markdownFile.path}", e)
            emptyFile(markdownFile)
        }
    }

    /**
     * Save a new annotation for the given markdown file.
     * Creates .annotations directory and sidecar file if needed.
     */
    fun addAnnotation(markdownFile: VirtualFile, annotation: Annotation) {
        lock.withLock {
            val current = loadAnnotations(markdownFile)
            val sanitized = annotation.copy(color = validatedColor(annotation.color))
            val updated = current.copy(
                annotations = current.annotations + sanitized
            )
            writeSidecar(markdownFile, updated)
        }
        notifyChanged(markdownFile)
    }

    /**
     * Update an existing annotation by ID.
     */
    fun updateAnnotation(markdownFile: VirtualFile, annotationId: String,
                                  tag: String?, note: String?, color: String?) {
        lock.withLock {
            val current = loadAnnotations(markdownFile)
            val updated = current.copy(
                annotations = current.annotations.map { ann ->
                    if (ann.id == annotationId) {
                        ann.copy(
                            tag = tag ?: ann.tag,
                            note = note ?: ann.note,
                            color = color?.let { validatedColor(it) } ?: ann.color,
                            updatedAt = Instant.now().toString()
                        )
                    } else ann
                }
            )
            writeSidecar(markdownFile, updated)
        }
        notifyChanged(markdownFile)
    }

    /**
     * Remove an annotation by ID.
     */
    fun removeAnnotation(markdownFile: VirtualFile, annotationId: String) {
        lock.withLock {
            val current = loadAnnotations(markdownFile)
            val updated = current.copy(
                annotations = current.annotations.filter { it.id != annotationId }
            )
            writeSidecar(markdownFile, updated)
        }
        notifyChanged(markdownFile)
    }

    /**
     * Serialize all annotations for a file as JSON, for sending to the JS layer.
     */
    fun serializeAnnotations(markdownFile: VirtualFile): String {
        return gson.toJson(loadAnnotations(markdownFile))
    }

    /**
     * Returns true (and removes the entry) if the given path was recently
     * written by this service, allowing callers to skip redundant notifications.
     */
    fun isRecentWrite(path: String): Boolean = recentlyWrittenPaths.remove(path)

    // --- Private helpers ---

    private fun relativePathFor(file: VirtualFile): String? {
        val basePath = project.basePath ?: return null
        return file.path.removePrefix(basePath).removePrefix("/")
    }

    private fun writeSidecar(markdownFile: VirtualFile, data: AnnotationFile) {
        val basePath = project.basePath
        if (basePath == null) {
            LOG.warn("Cannot write sidecar: project base path is null")
            return
        }
        val relativePath = relativePathFor(markdownFile)
        if (relativePath == null) {
            LOG.warn("Cannot write sidecar: unable to compute relative path for ${markdownFile.path}")
            return
        }
        val ioFile = sidecarPath(basePath, relativePath)

        ioFile.parentFile.mkdirs()
        recentlyWrittenPaths.add(ioFile.absolutePath)
        ioFile.writeText(gson.toJson(data), Charsets.UTF_8)
    }

    private fun emptyFile(markdownFile: VirtualFile): AnnotationFile {
        return AnnotationFile(
            sourceFile = relativePathFor(markdownFile) ?: markdownFile.name
        )
    }

    private fun validatedColor(color: String): String {
        if (color in ANNOTATION_COLORS) return color
        return if (color.matches(HEX_COLOR_REGEX)) color else ANNOTATION_COLORS[0]
    }

    private fun notifyChanged(markdownFile: VirtualFile) {
        project.messageBus
            .syncPublisher(AnnotationSyncNotifier.TOPIC)
            .annotationsChanged(markdownFile)
    }
}
