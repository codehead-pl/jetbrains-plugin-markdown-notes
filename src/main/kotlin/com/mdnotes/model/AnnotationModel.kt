package com.mdnotes.model

import java.time.Instant
import java.util.UUID

/**
 * A single annotation attached to a text range in a Markdown document.
 */
data class Annotation(
    val id: String = UUID.randomUUID().toString(),
    val selector: TextSelector,
    val tag: String = "",
    val note: String = "",
    val color: String = "#FFEB3B",       // default highlight yellow
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String = Instant.now().toString()
)

/**
 * W3C Web Annotation Data Model selectors for robust text anchoring.
 */
data class TextSelector(
    val quote: TextQuoteSelector,
    val position: TextPositionSelector
)

/**
 * TextQuoteSelector: stores the exact matched text plus surrounding context
 * for fuzzy re-anchoring when the document changes.
 */
data class TextQuoteSelector(
    val exact: String,
    val prefix: String,    // 32 chars before selection
    val suffix: String     // 32 chars after selection
)

/**
 * TextPositionSelector: character offsets as a performance hint.
 * Used for fast first-pass anchoring; falls back to quote if positions shift.
 */
data class TextPositionSelector(
    val start: Int,
    val end: Int
)

/**
 * Canonical color palette for annotations.
 * Keep in sync with COLORS in notes-ui.js and highlight pseudo-elements in notes.css.
 */
val ANNOTATION_COLORS = listOf(
    "#FFEB3B", "#FF9800", "#4CAF50", "#2196F3",
    "#9C27B0", "#F44336", "#00BCD4", "#E91E63"
)

/**
 * Top-level container for the sidecar JSON file.
 */
data class AnnotationFile(
    val version: Int = 1,
    val sourceFile: String,          // relative path from project root
    val annotations: List<Annotation> = emptyList()
)
