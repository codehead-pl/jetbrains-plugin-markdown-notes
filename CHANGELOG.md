# Changelog

## 1.0.0

### Added
- Select text in Markdown preview and add notes with optional tags
- 8 highlight colors with CSS Custom Highlight API (no DOM modification)
- Notes sidebar tool window with sorting (position, date, tag, color) and reverse toggle
- Click highlights in preview to view note details and delete
- Single-click sidebar item to scroll preview, double-click to show popover
- Sidecar JSON storage in `.annotations/` directory (version-control friendly)
- Fuzzy text re-anchoring using Bitap algorithm when documents change
- Duplicate detection prevents annotating the exact same text twice
- Full IDE theme integration (colors, fonts, sizes from current theme)
- Dark theme support
- VFS file listener for external annotation file changes
