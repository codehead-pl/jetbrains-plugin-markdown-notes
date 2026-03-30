package com.mdnotes.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.junit.Assert.*
import org.junit.Test

class AnnotationModelTest {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    @Test
    fun `annotation has default values`() {
        val selector = TextSelector(
            quote = TextQuoteSelector("hello", "prefix", "suffix"),
            position = TextPositionSelector(0, 5)
        )
        val annotation = Annotation(selector = selector, tag = "TODO")

        assertNotNull(annotation.id)
        assertEquals("TODO", annotation.tag)
        assertEquals("", annotation.note)
        assertEquals("#FFEB3B", annotation.color)
        assertNotNull(annotation.createdAt)
        assertNotNull(annotation.updatedAt)
    }

    @Test
    fun `annotation file has default values`() {
        val file = AnnotationFile(sourceFile = "docs/README.md")

        assertEquals(1, file.version)
        assertEquals("docs/README.md", file.sourceFile)
        assertTrue(file.annotations.isEmpty())
    }

    @Test
    fun `annotation roundtrips through JSON`() {
        val selector = TextSelector(
            quote = TextQuoteSelector("selected text", "some prefix context ", " some suffix context"),
            position = TextPositionSelector(20, 33)
        )
        val annotation = Annotation(
            id = "test-id-123",
            selector = selector,
            tag = "Question",
            note = "Why is this here?",
            color = "#FF9800",
            createdAt = "2026-01-15T10:30:00Z",
            updatedAt = "2026-01-15T10:30:00Z"
        )

        val json = gson.toJson(annotation)
        val deserialized = gson.fromJson(json, Annotation::class.java)

        assertEquals(annotation.id, deserialized.id)
        assertEquals(annotation.tag, deserialized.tag)
        assertEquals(annotation.note, deserialized.note)
        assertEquals(annotation.color, deserialized.color)
        assertEquals(annotation.createdAt, deserialized.createdAt)
        assertEquals(annotation.selector.quote.exact, deserialized.selector.quote.exact)
        assertEquals(annotation.selector.quote.prefix, deserialized.selector.quote.prefix)
        assertEquals(annotation.selector.quote.suffix, deserialized.selector.quote.suffix)
        assertEquals(annotation.selector.position.start, deserialized.selector.position.start)
        assertEquals(annotation.selector.position.end, deserialized.selector.position.end)
    }

    @Test
    fun `annotation file roundtrips through JSON`() {
        val selector = TextSelector(
            quote = TextQuoteSelector("test", "pre", "suf"),
            position = TextPositionSelector(10, 14)
        )
        val annotationFile = AnnotationFile(
            version = 1,
            sourceFile = "notes/design.md",
            annotations = listOf(
                Annotation(id = "a1", selector = selector, tag = "TODO", note = "Fix this"),
                Annotation(id = "a2", selector = selector, tag = "", note = "Another note", color = "#4CAF50")
            )
        )

        val json = gson.toJson(annotationFile)
        val deserialized = gson.fromJson(json, AnnotationFile::class.java)

        assertEquals(1, deserialized.version)
        assertEquals("notes/design.md", deserialized.sourceFile)
        assertEquals(2, deserialized.annotations.size)
        assertEquals("a1", deserialized.annotations[0].id)
        assertEquals("TODO", deserialized.annotations[0].tag)
        assertEquals("a2", deserialized.annotations[1].id)
        assertEquals("#4CAF50", deserialized.annotations[1].color)
    }

    @Test
    fun `annotation colors constant has 8 entries`() {
        assertEquals(8, ANNOTATION_COLORS.size)
        ANNOTATION_COLORS.forEach { color ->
            assertTrue("Color $color should start with #", color.startsWith("#"))
            assertEquals("Color $color should be 7 chars", 7, color.length)
        }
    }

    @Test
    fun `default annotation color is first in palette`() {
        val selector = TextSelector(
            quote = TextQuoteSelector("x", "", ""),
            position = TextPositionSelector(0, 1)
        )
        val annotation = Annotation(selector = selector, tag = "")
        assertEquals(ANNOTATION_COLORS[0], annotation.color)
    }
}
