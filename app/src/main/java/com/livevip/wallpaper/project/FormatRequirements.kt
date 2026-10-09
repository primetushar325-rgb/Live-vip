package com.livevip.wallpaper.project

/** The .mwproj requirements, shown on the Import screen and copied as a checklist. */
object FormatRequirements {

    val CHECKLIST: List<String> = listOf(
        "The file is a ZIP archive with the .mwproj extension.",
        "manifest.json is at the top level of the archive, or inside one folder that holds all project files.",
        "manifest.json contains \"format\": \"mwproj\".",
        "manifest.json contains \"formatVersion\": 1. This app reads version 1 only.",
        "manifest.json contains \"name\" (1-80 characters).",
        "manifest.json contains \"canvas\": { \"width\", \"height\" } as whole numbers from 128 to 4096.",
        "background.png exists at the path given in background.file, and is exactly the canvas size.",
        "Each layer has a unique \"id\" (letters, digits, _ or -), and a \"file\" PNG that exists at that exact path, including capital letters.",
        "Optional files: preview.png, depth.png, layer masks and glow masks. If one is missing, the app ignores it and reports a warning.",
        "Only .png and .json files are allowed. Remove .psd, .jpg, .exe and any other file types.",
        "Each path appears once. Use / as the separator. Paths must not start with / or contain \"..\".",
        "Images are PNG files, at most 4096 px on each side.",
        "Images together use at most 256 MB of GPU memory (background, depth, layers and masks).",
        "Archive limits: 150 MB archive, 200 files, 300 MB unpacked, 40 MB per file.",
    )

    val TEMPLATE: String = """{
  "format": "mwproj",
  "formatVersion": 1,
  "name": "My Character",
  "canvas": { "width": 720, "height": 1280 },
  "background": { "file": "background.png" },
  "layers": [
    { "id": "body", "role": "body", "file": "layers/body.png", "x": 240, "y": 380, "depth": 0.5 }
  ]
}"""

    fun checklistText(): String = buildString {
        appendLine("Live VIP .mwproj checklist")
        appendLine()
        CHECKLIST.forEachIndexed { i, item -> appendLine("${i + 1}. $item") }
        appendLine()
        appendLine("Minimal manifest.json (with background.png and layers/body.png):")
        appendLine(TEMPLATE)
    }
}
