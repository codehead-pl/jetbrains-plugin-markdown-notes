// annotator-anchoring.js
// Text anchoring using W3C Web Annotation selectors
// Includes embedded Bitap fuzzy matching (from diff-match-patch, Apache 2.0)

window.__MdNotesAnchoring = (function() {
    'use strict';

    const CONTEXT_LENGTH = 32;  // chars of prefix/suffix to capture

    /**
     * Create a TextSelector from a DOM Range.
     * Returns { quote: { exact, prefix, suffix }, position: { start, end } }
     */
    function createSelector(range) {
        const root = document.body;
        const textContent = root.textContent || '';

        const position = rangeToTextPosition(range, root);
        const exact = textContent.substring(position.start, position.end);
        const prefix = textContent.substring(
            Math.max(0, position.start - CONTEXT_LENGTH), position.start
        );
        const suffix = textContent.substring(
            position.end, Math.min(textContent.length, position.end + CONTEXT_LENGTH)
        );

        return {
            quote: { exact: exact, prefix: prefix, suffix: suffix },
            position: { start: position.start, end: position.end }
        };
    }

    /**
     * Convert a DOM Range to character offsets within a root element's text content.
     */
    function rangeToTextPosition(range, root) {
        const startOffset = getTextOffset(root, range.startContainer, range.startOffset);
        const endOffset = getTextOffset(root, range.endContainer, range.endOffset);
        return { start: startOffset, end: endOffset };
    }

    /**
     * Calculate the character offset of a point within root's text content.
     * When node is a text node, offset is a character index within that node.
     * When node is an element, offset is a child-node index (DOM Range spec).
     */
    function getTextOffset(root, node, offset) {
        if (node.nodeType === Node.TEXT_NODE) {
            const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
            let charCount = 0;
            let current;
            while ((current = walker.nextNode())) {
                if (current === node) {
                    return charCount + offset;
                }
                charCount += current.textContent.length;
            }
            return charCount;
        }

        // Element node: offset is a child index. Use a temporary Range to measure
        // the text length from root start to (node, offset).
        const tempRange = document.createRange();
        tempRange.setStart(root, 0);
        tempRange.setEnd(node, offset);
        return tempRange.toString().length;
    }

    /**
     * Anchor a selector back to a DOM Range.
     * Strategy: try position first, validate with exact text, fall back to fuzzy search.
     */
    function anchorSelector(selector) {
        const root = document.body;
        const text = root.textContent || '';

        // Fast path: try position selector
        if (selector.position) {
            const candidate = text.substring(selector.position.start, selector.position.end);
            if (candidate === selector.quote.exact) {
                return textPositionToRange(root, selector.position.start, selector.position.end);
            }
        }

        // Slow path: fuzzy search using quote selector
        const searchText = selector.quote.exact;
        const expectedPosition = selector.position ? selector.position.start : 0;
        const matchIndex = fuzzySearch(text, searchText, expectedPosition);

        if (matchIndex === -1) return null;

        return textPositionToRange(root, matchIndex, matchIndex + searchText.length);
    }

    /**
     * Convert character offsets back to a DOM Range.
     */
    function textPositionToRange(root, start, end) {
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
        let charCount = 0;
        let startNode = null, startOff = 0;
        let endNode = null, endOff = 0;
        let current;

        while ((current = walker.nextNode())) {
            const len = current.textContent.length;
            if (!startNode && charCount + len > start) {
                startNode = current;
                startOff = start - charCount;
            }
            if (!endNode && charCount + len >= end) {
                endNode = current;
                endOff = end - charCount;
                break;
            }
            charCount += len;
        }

        if (!startNode || !endNode) return null;

        const range = document.createRange();
        range.setStart(startNode, startOff);
        range.setEnd(endNode, endOff);
        return range;
    }

    // --- Bitap fuzzy search (from diff-match-patch, simplified) ---

    function fuzzySearch(text, pattern, expectedLoc) {
        const MATCH_THRESHOLD = 0.4;
        const MATCH_DISTANCE = 1000;

        if (pattern.length === 0) return -1;
        if (pattern.length > 32) {
            // Bitap limited to 32 chars; for longer patterns, try indexOf first
            const idx = text.indexOf(pattern);
            if (idx !== -1) return idx;
            return fuzzySearch(text, pattern.substring(0, 32), expectedLoc);
        }

        expectedLoc = Math.max(0, Math.min(expectedLoc, text.length));

        // Exact match check
        if (text.substring(expectedLoc, expectedLoc + pattern.length) === pattern) {
            return expectedLoc;
        }

        // Full indexOf as second fast path
        const exactIdx = text.indexOf(pattern);
        if (exactIdx !== -1) return exactIdx;

        // Bitap algorithm
        const s = {};
        for (let i = 0; i < pattern.length; i++) {
            s[pattern.charAt(i)] = (s[pattern.charAt(i)] || 0) | (1 << (pattern.length - i - 1));
        }

        let scoreThreshold = MATCH_THRESHOLD;
        let bestLoc = -1;
        const binMax = pattern.length + text.length;
        let lastRd = null;

        for (let d = 0; d < pattern.length; d++) {
            let start = 0, finish = binMax;
            let mid;
            while (start < finish) {
                mid = Math.floor((finish - start) / 2 + start);
                if (computeScore(d, mid, expectedLoc, pattern.length, MATCH_DISTANCE) <= scoreThreshold) {
                    start = mid + 1;
                } else {
                    finish = mid;
                }
            }
            finish = start;

            const range_start = Math.max(1, expectedLoc - finish + 1);
            const range_end = Math.min(text.length - 1, expectedLoc + finish) + pattern.length;

            const rd = new Array(range_end + 2);
            rd[range_end + 1] = (1 << d) - 1;

            for (let j = range_end; j >= range_start; j--) {
                const charMatch = s[text.charAt(j - 1)] || 0;
                if (d === 0) {
                    rd[j] = ((rd[j + 1] << 1) | 1) & charMatch;
                } else {
                    rd[j] = (((rd[j + 1] << 1) | 1) & charMatch) |
                            (((lastRd[j + 1] | lastRd[j]) << 1) | 1) |
                            lastRd[j + 1];
                }
                if (rd[j] & (1 << (pattern.length - 1))) {
                    const score = computeScore(d, j - 1, expectedLoc, pattern.length, MATCH_DISTANCE);
                    if (score <= scoreThreshold) {
                        scoreThreshold = score;
                        bestLoc = j - 1;
                    }
                }
            }

            if (computeScore(d + 1, expectedLoc, expectedLoc, pattern.length, MATCH_DISTANCE) > scoreThreshold) {
                break;
            }
            lastRd = rd;
        }
        return bestLoc;
    }

    function computeScore(errors, currentLoc, expectedLoc, patternLen, matchDistance) {
        const accuracy = errors / patternLen;
        const proximity = Math.abs(expectedLoc - currentLoc);
        if (matchDistance === 0) return proximity ? 1.0 : accuracy;
        return accuracy + (proximity / matchDistance);
    }

    return {
        createSelector: createSelector,
        anchorSelector: anchorSelector
    };
})();
