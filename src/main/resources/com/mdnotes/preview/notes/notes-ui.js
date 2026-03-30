// annotator-ui.js
// Floating toolbar and highlight rendering for Markdown Notes

window.__MdNotesUI = (function() {
    'use strict';

    // Keep in sync with colorOrder in AnnotationListPanel.kt and highlight pseudo-elements in notes.css
    const COLORS = [
        '#FFEB3B', '#FF9800', '#4CAF50', '#2196F3',
        '#9C27B0', '#F44336', '#00BCD4', '#E91E63'
    ];

    let toolbar = null;
    let currentRange = null;
    let DEBUG = false;

    function log(msg) {
        if (!DEBUG) return;
        if (window.__IntelliJTools && window.__IntelliJTools.messagePipe) {
            window.__IntelliJTools.messagePipe.post('annotator.log', '[UI] ' + msg);
        }
        console.log('[MdNotes-UI] ' + msg);
    }

    function init() {
        log('init() called, document.body=' + !!document.body);

        // Remove orphaned toolbar from a previous initialization cycle
        const existing = document.getElementById('mdnotes-toolbar');
        if (existing) existing.remove();

        createToolbar();
        log('toolbar created, appended to body=' + !!toolbar.parentNode);
        document.addEventListener('mouseup', onMouseUp, true);
        document.addEventListener('mousedown', onMouseDown, true);
        document.addEventListener('click', onAnnotationClick);
        log('event listeners registered (capture phase)');
    }

    function createToolbar() {
        toolbar = document.createElement('div');
        toolbar.id = 'mdnotes-toolbar';
        toolbar.className = 'mdnotes-toolbar';
        toolbar.innerHTML = `
            <div class="mdnotes-toolbar-content">
                <button class="mdnotes-btn mdnotes-btn-highlight" title="Add a note to selection">
                    <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor">
                        <path d="M11.5 1.5l3 3-9 9H2.5v-3l9-9z"/>
                    </svg>
                    Add Note
                </button>
            </div>
            <div class="mdnotes-form" style="display:none;">
                <textarea class="mdnotes-note-input"
                          placeholder="Note" rows="2" maxlength="500"></textarea>
                <input type="text" class="mdnotes-tag-input"
                       placeholder="Tag (optional, e.g., TODO, Question)" maxlength="50"/>
                <div class="mdnotes-color-picker"></div>
                <div class="mdnotes-form-actions">
                    <button class="mdnotes-btn mdnotes-btn-cancel">Cancel</button>
                    <button class="mdnotes-btn mdnotes-btn-save">Save</button>
                </div>
            </div>
        `;
        // Append to <html> instead of <body> because Incremental DOM
        // patches document.body and removes nodes it didn't create.
        document.documentElement.appendChild(toolbar);

        // Build color picker
        const picker = toolbar.querySelector('.mdnotes-color-picker');
        COLORS.forEach((color, i) => {
            const swatch = document.createElement('span');
            swatch.className = 'mdnotes-color-swatch' + (i === 0 ? ' selected' : '');
            swatch.style.backgroundColor = color;
            swatch.dataset.color = color;
            swatch.addEventListener('click', () => {
                picker.querySelectorAll('.mdnotes-color-swatch').forEach(
                    s => s.classList.remove('selected')
                );
                swatch.classList.add('selected');
            });
            picker.appendChild(swatch);
        });

        // Wire buttons
        toolbar.querySelector('.mdnotes-btn-highlight').addEventListener('click', showForm);
        toolbar.querySelector('.mdnotes-btn-cancel').addEventListener('click', hideToolbar);
        toolbar.querySelector('.mdnotes-btn-save').addEventListener('click', saveAnnotation);

        // Ctrl+Enter to save from toolbar form
        toolbar.addEventListener('keydown', function(e) {
            if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
                e.preventDefault();
                saveAnnotation();
            } else if (e.key === 'Escape') {
                e.preventDefault();
                hideToolbar();
            }
        });
    }

    function onMouseDown(e) {
        log('mousedown: target=' + e.target.tagName + '.' + e.target.className + ' inToolbar=' + (toolbar && toolbar.contains(e.target)));
        if (toolbar && !toolbar.contains(e.target)) {
            hideToolbar();
        }
    }

    function onMouseUp(e) {
        log('mouseup: target=' + e.target.tagName + '.' + e.target.className + ' x=' + e.clientX + ' y=' + e.clientY);
        if (toolbar && toolbar.contains(e.target)) {
            log('mouseup: inside toolbar, ignoring');
            return;
        }

        const selection = window.getSelection();
        log('mouseup: selection=' + (selection ? 'exists' : 'null')
            + ' collapsed=' + (selection ? selection.isCollapsed : 'n/a')
            + ' text="' + (selection ? selection.toString().substring(0, 50) : '') + '"');

        if (!selection || selection.isCollapsed || selection.toString().trim().length === 0) {
            log('mouseup: no valid selection, skipping');
            return;
        }

        currentRange = selection.getRangeAt(0).cloneRange();

        // Don't show toolbar if this exact range is already annotated
        const selector = window.__MdNotesAnchoring.createSelector(currentRange);
        for (let i = 0; i < activeAnnotations.length; i++) {
            const existing = activeAnnotations[i].annotation.selector;
            if (existing.position.start === selector.position.start &&
                existing.position.end === selector.position.end) {
                log('mouseup: exact range already annotated, skipping toolbar');
                currentRange = null;
                return;
            }
        }

        log('mouseup: valid selection, showing toolbar');
        showToolbar(e.clientX, e.clientY);
    }

    /**
     * Position a fixed element so it stays within the viewport.
     * anchorX/anchorY: desired position (e.g. cursor or highlight rect).
     * prefAbove: if true, try to place above the anchor first.
     * margin: minimum distance from viewport edges.
     */
    function clampToViewport(el, anchorX, anchorY, prefAbove, margin) {
        margin = margin || 8;
        // Make visible off-screen to measure
        el.style.left = '-9999px';
        el.style.top = '-9999px';
        const w = el.offsetWidth;
        const h = el.offsetHeight;
        const vw = window.innerWidth;
        const vh = window.innerHeight;

        // Horizontal: center on anchorX, clamp to edges
        let left = anchorX - w / 2;
        left = Math.max(margin, Math.min(left, vw - w - margin));

        // Vertical: prefer above or below anchor, flip if needed
        let top;
        if (prefAbove) {
            top = anchorY - h - margin;
            if (top < margin) top = anchorY + margin; // flip below
        } else {
            top = anchorY + margin;
            if (top + h > vh - margin) top = anchorY - h - margin; // flip above
        }
        // Final clamp
        top = Math.max(margin, Math.min(top, vh - h - margin));

        el.style.left = left + 'px';
        el.style.top = top + 'px';
    }

    function showToolbar(x, y) {
        if (!toolbar) return;
        toolbar.querySelector('.mdnotes-toolbar-content').style.display = '';
        toolbar.querySelector('.mdnotes-form').style.display = 'none';
        toolbar.style.display = 'block';
        clampToViewport(toolbar, x, y, true);
    }

    function showForm() {
        toolbar.querySelector('.mdnotes-toolbar-content').style.display = 'none';
        toolbar.querySelector('.mdnotes-form').style.display = 'block';
        toolbar.querySelector('.mdnotes-note-input').value = '';
        toolbar.querySelector('.mdnotes-tag-input').value = '';
        toolbar.querySelector('.mdnotes-note-input').focus();

        // Re-clamp after form expanded (larger than the button)
        const rect = toolbar.getBoundingClientRect();
        clampToViewport(toolbar, rect.left + rect.width / 2, rect.top, true);
    }

    function hideToolbar() {
        if (toolbar) {
            toolbar.style.display = 'none';
        }
        currentRange = null;
    }

    function saveAnnotation() {
        log('saveAnnotation() called, currentRange=' + !!currentRange);
        if (!currentRange) {
            log('saveAnnotation: no currentRange, aborting');
            return;
        }

        const note = toolbar.querySelector('.mdnotes-note-input').value.trim();
        log('saveAnnotation: note="' + note.substring(0, 30) + '"');
        if (!note) {
            toolbar.querySelector('.mdnotes-note-input').classList.add('mdnotes-input-error');
            setTimeout(() => toolbar.querySelector('.mdnotes-note-input')
                .classList.remove('mdnotes-input-error'), 1000);
            return;
        }

        const tag = toolbar.querySelector('.mdnotes-tag-input').value.trim();
        const selectedSwatch = toolbar.querySelector('.mdnotes-color-swatch.selected');
        const color = selectedSwatch ? selectedSwatch.dataset.color : COLORS[0];

        try {
            const selector = window.__MdNotesAnchoring.createSelector(currentRange);
            log('saveAnnotation: selector created, exact="' + selector.quote.exact.substring(0, 30) + '"');

            const annotation = {
                id: generateId(),
                selector: selector,
                tag: tag,
                note: note,
                color: color,
                createdAt: new Date().toISOString(),
                updatedAt: new Date().toISOString()
            };

            // Send to Kotlin via BrowserPipe
            log('saveAnnotation: posting to Kotlin...');
            window.__IntelliJTools.messagePipe.post(
                'annotator.create',
                JSON.stringify(annotation)
            );
            log('saveAnnotation: posted successfully');
        } catch (e) {
            log('saveAnnotation ERROR: ' + e.message + ' | ' + e.stack);
        }

        hideToolbar();
    }

    function generateId() {
        return crypto.randomUUID();
    }

    // --- Highlight rendering ---

    // --- CSS Custom Highlight API ---
    // We store annotation ranges so we can show popovers on click
    // and scroll to annotations without modifying the DOM.
    let activeAnnotations = []; // [{annotation, range}, ...]

    // Map each of our 8 colors to a named highlight group
    const COLOR_HIGHLIGHT_MAP = {};
    COLORS.forEach(function(color, i) {
        COLOR_HIGHLIGHT_MAP[color] = 'mdnotes-hl-' + i;
    });

    function renderHighlights(annotations) {
        log('renderHighlights: ' + (annotations && annotations.annotations ? annotations.annotations.length : 0) + ' annotations');
        clearHighlights();

        if (!annotations || !annotations.annotations) return;

        // Group ranges by color for CSS Highlight API
        const colorGroups = {}; // highlightName -> [Range]

        annotations.annotations.forEach(function(ann) {
            const range = window.__MdNotesAnchoring.anchorSelector(ann.selector);
            if (!range) {
                log('renderHighlights: failed to anchor annotation ' + ann.id);
                return;
            }

            activeAnnotations.push({ annotation: ann, range: range });

            const hlName = COLOR_HIGHLIGHT_MAP[ann.color] || 'mdnotes-hl-0';
            if (!colorGroups[hlName]) colorGroups[hlName] = [];
            colorGroups[hlName].push(range);
        });

        // Register highlights with CSS Custom Highlight API
        if (CSS.highlights) {
            Object.keys(colorGroups).forEach(function(hlName) {
                const highlight = new Highlight();
                colorGroups[hlName].forEach(function(r) { highlight.add(r); });
                CSS.highlights.set(hlName, highlight);
            });
            log('renderHighlights: registered ' + Object.keys(colorGroups).length + ' highlight groups');
        } else {
            log('renderHighlights: CSS.highlights API not available');
        }
    }

    function clearHighlights() {
        activeAnnotations = [];
        if (CSS.highlights) {
            COLORS.forEach(function(color, i) {
                CSS.highlights.delete('mdnotes-hl-' + i);
            });
        }
    }

    // Click handler: check if click lands on an annotated range
    function onAnnotationClick(e) {
        // Don't interfere with toolbar or popover
        if (toolbar && toolbar.contains(e.target)) return;
        const existing = document.getElementById('mdnotes-popover');
        if (existing && existing.contains(e.target)) return;

        // Check if the click position falls within any annotation range
        for (let i = 0; i < activeAnnotations.length; i++) {
            const entry = activeAnnotations[i];
            const rects = entry.range.getClientRects();
            for (let j = 0; j < rects.length; j++) {
                const r = rects[j];
                if (e.clientX >= r.left && e.clientX <= r.right &&
                    e.clientY >= r.top && e.clientY <= r.bottom) {
                    showAnnotationPopover(entry.annotation, r);
                    return;
                }
            }
        }
    }

    function showAnnotationPopover(annotation, rect) {
        const existing = document.getElementById('mdnotes-popover');
        if (existing) existing.remove();

        const popover = document.createElement('div');
        popover.id = 'mdnotes-popover';
        popover.className = 'mdnotes-popover';

        buildPopoverViewMode(popover, annotation, rect);

        document.documentElement.appendChild(popover);

        // Position below the highlight, clamped to viewport
        clampToViewport(popover, rect.left + (rect.right - rect.left) / 2, rect.bottom, false);

        registerPopoverOutsideClickHandler(popover);
    }

    function sanitizeColor(color) {
        return /^#[0-9A-Fa-f]{6}$/.test(color) ? color : COLORS[0];
    }

    function buildPopoverViewMode(popover, annotation, rect) {
        const safeColor = sanitizeColor(annotation.color);
        const noteHtml = annotation.note
            ? '<div class="mdnotes-popover-note">' + escapeHtml(annotation.note) + '</div>'
            : '';
        const tagHtml = annotation.tag
            ? '<div class="mdnotes-popover-tag">' + escapeHtml(annotation.tag) + '</div>'
            : '';

        popover.innerHTML =
            '<div class="mdnotes-popover-stripe" style="background-color: ' + safeColor + '"></div>' +
            '<div class="mdnotes-popover-body">' +
                noteHtml + tagHtml +
                '<div class="mdnotes-popover-actions">' +
                    '<button class="mdnotes-btn mdnotes-btn-edit">Edit</button>' +
                    '<button class="mdnotes-btn mdnotes-btn-delete">Delete</button>' +
                '</div>' +
            '</div>';

        popover.querySelector('.mdnotes-btn-edit').addEventListener('click', function() {
            buildPopoverEditMode(popover, annotation, rect);
            clampToViewport(popover, rect.left + (rect.right - rect.left) / 2, rect.bottom, false);
        });

        popover.querySelector('.mdnotes-btn-delete').addEventListener('click', function() {
            window.__IntelliJTools.messagePipe.post('annotator.delete', annotation.id);
            popover.remove();
        });
    }

    function buildPopoverEditMode(popover, annotation, rect) {
        var safeColor = sanitizeColor(annotation.color);
        var colorSwatchesHtml = '';
        COLORS.forEach(function(color) {
            var selected = color === annotation.color ? ' selected' : '';
            colorSwatchesHtml += '<span class="mdnotes-color-swatch' + selected +
                '" style="background-color: ' + color + '" data-color="' + color + '"></span>';
        });

        popover.innerHTML =
            '<div class="mdnotes-popover-stripe mdnotes-popover-edit-stripe" style="background-color: ' + safeColor + '"></div>' +
            '<div class="mdnotes-popover-body">' +
                '<div class="mdnotes-form" style="display:block;">' +
                    '<textarea class="mdnotes-note-input" placeholder="Note" rows="2" maxlength="500"></textarea>' +
                    '<input type="text" class="mdnotes-tag-input" placeholder="Tag (optional)" maxlength="50"/>' +
                    '<div class="mdnotes-color-picker">' + colorSwatchesHtml + '</div>' +
                    '<div class="mdnotes-form-actions">' +
                        '<button class="mdnotes-btn mdnotes-btn-cancel">Cancel</button>' +
                        '<button class="mdnotes-btn mdnotes-btn-save">Save</button>' +
                    '</div>' +
                '</div>' +
            '</div>';

        // Set values via DOM properties to avoid HTML parsing of user content
        popover.querySelector('.mdnotes-note-input').value = annotation.note || '';
        popover.querySelector('.mdnotes-tag-input').value = annotation.tag || '';

        // Wire color swatch selection
        var picker = popover.querySelector('.mdnotes-color-picker');
        picker.querySelectorAll('.mdnotes-color-swatch').forEach(function(swatch) {
            swatch.addEventListener('click', function() {
                picker.querySelectorAll('.mdnotes-color-swatch').forEach(function(s) {
                    s.classList.remove('selected');
                });
                swatch.classList.add('selected');
                popover.querySelector('.mdnotes-popover-edit-stripe').style.backgroundColor = swatch.dataset.color;
            });
        });

        popover.querySelector('.mdnotes-note-input').focus();

        popover.querySelector('.mdnotes-btn-cancel').addEventListener('click', function() {
            buildPopoverViewMode(popover, annotation, rect);
            clampToViewport(popover, rect.left + (rect.right - rect.left) / 2, rect.bottom, false);
        });

        function saveEdit() {
            var note = popover.querySelector('.mdnotes-note-input').value.trim();
            if (!note) {
                popover.querySelector('.mdnotes-note-input').classList.add('mdnotes-input-error');
                setTimeout(function() {
                    var input = popover.querySelector('.mdnotes-note-input');
                    if (input) input.classList.remove('mdnotes-input-error');
                }, 1000);
                return;
            }
            var tag = popover.querySelector('.mdnotes-tag-input').value.trim();
            var selectedSwatch = popover.querySelector('.mdnotes-color-swatch.selected');
            var color = selectedSwatch ? selectedSwatch.dataset.color : annotation.color;

            window.__IntelliJTools.messagePipe.post('annotator.update', JSON.stringify({
                id: annotation.id,
                note: note,
                tag: tag,
                color: color
            }));
            popover.remove();
        }

        popover.querySelector('.mdnotes-btn-save').addEventListener('click', saveEdit);

        // Ctrl+Enter to save, Escape to cancel
        popover.addEventListener('keydown', function(e) {
            if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
                e.preventDefault();
                saveEdit();
            } else if (e.key === 'Escape') {
                e.preventDefault();
                buildPopoverViewMode(popover, annotation, rect);
                clampToViewport(popover, rect.left + (rect.right - rect.left) / 2, rect.bottom, false);
            }
        });
    }

    function registerPopoverOutsideClickHandler(popover) {
        // Use mousedown on next tick to avoid the opening click from closing immediately
        function closePopover(ev) {
            if (!popover.contains(ev.target)) {
                popover.remove();
                document.removeEventListener('mousedown', closePopover, true);
            }
        }
        // requestAnimationFrame ensures the current event cycle completes first
        requestAnimationFrame(function() {
            document.addEventListener('mousedown', closePopover, true);
        });
    }

    function findAnnotationEntry(annotationId) {
        for (let i = 0; i < activeAnnotations.length; i++) {
            if (activeAnnotations[i].annotation.id === annotationId) {
                return activeAnnotations[i];
            }
        }
        return null;
    }

    function scrollToAnnotation(annotationId) {
        const entry = findAnnotationEntry(annotationId);
        if (!entry) return;

        let el = entry.range.startContainer;
        if (el.nodeType === Node.TEXT_NODE) el = el.parentElement;
        if (!el) return;

        el.scrollIntoView({ behavior: 'smooth', block: 'center' });

        // Flash the highlight by temporarily boosting its opacity
        const hlName = COLOR_HIGHLIGHT_MAP[entry.annotation.color] || 'mdnotes-hl-0';
        if (CSS.highlights) {
            const flashHighlight = new Highlight(entry.range);
            CSS.highlights.set('mdnotes-flash', flashHighlight);
            setTimeout(function() { CSS.highlights.delete('mdnotes-flash'); }, 1500);
        }
    }

    function showPopoverForId(annotationId) {
        const entry = findAnnotationEntry(annotationId);
        if (!entry) return;

        // Scroll into view first
        let el = entry.range.startContainer;
        if (el.nodeType === Node.TEXT_NODE) el = el.parentElement;
        if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });

        // Show popover after scroll settles
        setTimeout(function() {
            const rects = entry.range.getClientRects();
            if (rects.length > 0) {
                showAnnotationPopover(entry.annotation, rects[0]);
            }
        }, 400);
    }

    function escapeHtml(text) {
        return text
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    return {
        init: init,
        renderHighlights: renderHighlights,
        clearHighlights: clearHighlights,
        scrollToAnnotation: scrollToAnnotation,
        showPopoverForId: showPopoverForId,
        setDebug: function(val) { DEBUG = !!val; }
    };
})();
