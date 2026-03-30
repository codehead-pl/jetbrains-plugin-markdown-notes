package com.mdnotes.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SidecarPathTest {

    @Test
    fun `sidecar path for simple file`() {
        val result = AnnotationStorageService.sidecarPath("/project", "README.md")
        assertEquals(File("/project/.annotations/README.md.json"), result)
    }

    @Test
    fun `sidecar path for nested file`() {
        val result = AnnotationStorageService.sidecarPath("/project", "docs/design/notes.md")
        assertEquals(File("/project/.annotations/docs/design/notes.md.json"), result)
    }

    @Test
    fun `sidecar path preserves markdown extension before json`() {
        val result = AnnotationStorageService.sidecarPath("/home/user/repo", "file.markdown")
        assertEquals(File("/home/user/repo/.annotations/file.markdown.json"), result)
    }

    @Test
    fun `sidecar path with spaces in filename`() {
        val result = AnnotationStorageService.sidecarPath("/project", "my notes/design doc.md")
        assertEquals(File("/project/.annotations/my notes/design doc.md.json"), result)
    }

    @Test
    fun `annotations dir constant is correct`() {
        assertEquals(".annotations", AnnotationStorageService.ANNOTATIONS_DIR)
    }
}
