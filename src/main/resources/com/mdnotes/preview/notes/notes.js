// annotator.js
// Main entry point - wires anchoring + UI + BrowserPipe communication

(function() {
    'use strict';

    // Guard against double-initialization
    if (window.__MdNotesInitialized) return;
    window.__MdNotesInitialized = true;

    // Logging helper — sends to Kotlin via messagePipe if available, else console
    let DEBUG = false;
    function log(msg) {
        if (!DEBUG) return;
        if (window.__IntelliJTools && window.__IntelliJTools.messagePipe) {
            window.__IntelliJTools.messagePipe.post('annotator.log', msg);
        }
        console.log('[MdNotes] ' + msg);
    }

    // Diagnostic: report script load state
    log('annotator.js loaded. readyState=' + document.readyState
        + ' body=' + !!document.body
        + ' __IntelliJTools=' + !!window.__IntelliJTools
        + ' messagePipe=' + !!(window.__IntelliJTools && window.__IntelliJTools.messagePipe)
        + ' __MdNotesUI=' + !!window.__MdNotesUI
        + ' __MdNotesAnchoring=' + !!window.__MdNotesAnchoring);

    let bootstrapped = false;

    function bootstrap() {
        if (bootstrapped) {
            log('bootstrap() skipped — already ran');
            return;
        }
        bootstrapped = true;
        log('bootstrap() called');

        if (!window.__MdNotesUI) {
            log('ERROR: __MdNotesUI is undefined');
            bootstrapped = false;
            return;
        }

        try {
            window.__MdNotesUI.init();
            log('UI.init() succeeded');
        } catch (e) {
            log('ERROR in UI.init(): ' + e.message + ' | ' + e.stack);
            bootstrapped = false;
            return;
        }

        if (window.__IntelliJTools && window.__IntelliJTools.messagePipe) {
            wireMessagePipe();
        } else {
            log('WARN: messagePipe not available at bootstrap time');
        }
    }

    function wireMessagePipe() {
        const pipe = window.__IntelliJTools.messagePipe;
        log('wireMessagePipe() — subscribing to events');

        pipe.subscribe('annotator.pushAnnotations', function(data) {
            try {
                const annotationFile = JSON.parse(data);
                window.__MdNotesUI.renderHighlights(annotationFile);
            } catch (e) {
                log('ERROR parsing annotations: ' + e.message);
            }
        });

        pipe.subscribe('annotator.scrollTo', function(annotationId) {
            window.__MdNotesUI.scrollToAnnotation(annotationId);
        });

        pipe.subscribe('annotator.showPopover', function(annotationId) {
            window.__MdNotesUI.showPopoverForId(annotationId);
        });

        pipe.subscribe('annotator.pushTheme', function(data) {
            try {
                const theme = JSON.parse(data);
                const root = document.documentElement;

                // Compound properties that combine multiple theme values
                const compound = {
                    'font-family': theme.fontFamily + ', -apple-system, BlinkMacSystemFont, sans-serif',
                    'btn-pad': theme.btnPadV + 'px ' + theme.btnPadH + 'px',
                    'input-pad': theme.inputPadV + 'px ' + theme.inputPadH + 'px'
                };
                Object.keys(compound).forEach(function(key) {
                    root.style.setProperty('--ann-' + key, compound[key]);
                });

                // Simple properties: camelCase key -> kebab-case CSS variable
                const simple = [
                    'fontSize', 'buttonFontSize', 'bg', 'fg', 'fgSecondary', 'border',
                    'inputBg', 'inputBorder', 'focus', 'focusRing', 'error', 'errorRing',
                    'hoverBg', 'btnBg', 'btnFg', 'btnBorder', 'btnDefaultBg',
                    'btnDefaultFg', 'btnDefaultBorder', 'shadow'
                ];
                simple.forEach(function(key) {
                    const cssKey = '--ann-' + key.replace(/([A-Z])/g, '-$1').toLowerCase();
                    const value = theme[key];
                    root.style.setProperty(cssKey, typeof value === 'number' ? value + 'px' : value);
                });

                log('Theme applied');
            } catch (e) {
                log('ERROR applying theme: ' + e.message);
            }
        });

        log('Requesting annotations from Kotlin...');
        pipe.post('annotator.requestAnnotations', '');
    }

    // Strategy 1: Listen for IdeReady event
    window.addEventListener('IdeReady', function() {
        log('IdeReady event received');
        bootstrap();
    });

    // Strategy 2: DOMContentLoaded fallback (in case IdeReady fires before our listener)
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function() {
            log('DOMContentLoaded fired');
            setTimeout(function() {
                if (!bootstrapped) {
                    log('Bootstrapping from DOMContentLoaded fallback');
                    bootstrap();
                }
            }, 500);
        });
    }

    // Strategy 3: Already loaded — try immediately if pipe is ready
    if (document.readyState !== 'loading') {
        log('Document already loaded (readyState=' + document.readyState + ')');
        if (window.__IntelliJTools && window.__IntelliJTools.messagePipe) {
            log('messagePipe available — bootstrapping immediately');
            bootstrap();
        } else {
            log('messagePipe not yet available — waiting for IdeReady');
        }
    }
})();
