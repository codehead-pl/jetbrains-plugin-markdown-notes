# Markdown Notes

A JetBrains plugin that lets you add notes directly to text in the Markdown preview.

Select text, write a note, pick a highlight color, and see it rendered right in the preview — without leaving the preview pane or modifying the source file.

## Features

- **Select and annotate** — select text in the Markdown preview, add a note with an optional tag and highlight color
- **Persistent highlights** — annotations are rendered as colored highlights using the CSS Custom Highlight API
- **Click to view** — click any highlight to see the full note, tag, and a delete option
- **Sidebar panel** — browse all annotations for the current file, sorted by position, date, tag, or color
- **Navigate from sidebar** — single-click scrolls the preview to the annotation, double-click opens the popover
- **IDE theme integration** — colors, fonts, and sizes adapt to your current IDE theme
- **Sidecar storage** — annotations are stored as JSON files in `.annotations/` alongside your documents, making them easy to version-control
- **Fuzzy re-anchoring** — when a document changes, annotations find their text using fuzzy matching (Bitap algorithm), so small edits don't break existing notes
- **Duplicate prevention** — can't annotate the exact same text twice

## How it works

1. Open a `.md` file in **Preview** or **Split** mode
2. Select text in the preview pane
3. Click **Add Note** in the floating toolbar
4. Write your note, optionally add a tag and choose a color
5. Click **Save**

Your note appears as a colored highlight in the preview. Click it to view or delete. All notes for the current file appear in the **Markdown Notes** tool window (right sidebar).

## Storage

Annotations are stored in `.annotations/` at your project root as sidecar JSON files mirroring the source path:

```
project/
├── docs/
│   └── design.md
└── .annotations/
    └── docs/
        └── design.md.json
```

Add `.annotations/` to version control to share notes with your team, or add it to `.gitignore` to keep them local.

## Requirements

- JetBrains IDE 2026.1+ (WebStorm, IntelliJ IDEA, PhpStorm, etc.)
- Markdown plugin (bundled with all JetBrains IDEs)

## Building from source

```bash
./gradlew buildPlugin
```

The plugin ZIP will be in `build/distributions/`.

## License

[Apache License 2.0](LICENSE)
