package com.livevip.wallpaper.project

import com.livevip.wallpaper.TestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

/** Tests for the Import Project report: findings, exact paths, copyable text, and crash safety. */
class ProjectCheckerTest {

    private fun archive(entries: Map<String, ByteArray>, name: String = "project.mwproj"): File {
        val f = File(TestFiles.tempDir("check"), name)
        f.writeBytes(TestFiles.zipBytes(entries))
        return f
    }

    private fun inspect(entries: Map<String, ByteArray>, limits: CheckLimits = CheckLimits.DEFAULT): ValidationReport =
        ProjectChecker.inspectArchive(archive(entries), "project.mwproj", limits).report

    /** Returns a copy of [entries] with manifest.json replaced by [transform] applied to its current text. */
    private fun editManifest(entries: Map<String, ByteArray>, transform: (String) -> String): Map<String, ByteArray> {
        val copy = LinkedHashMap(entries)
        val current = String(entries.getValue("manifest.json"), Charsets.UTF_8)
        copy["manifest.json"] = transform(current).toByteArray(Charsets.UTF_8)
        return copy
    }

    private fun hasError(r: ValidationReport, code: String, path: String?): Boolean =
        r.errors.any { it.code == code && (path == null || it.path == path) }

    // ---- valid projects ------------------------------------------------------------------------

    @Test
    fun validProjectIsImportableAndShowsPassedChecks() {
        val r = inspect(TestFiles.validProjectEntries())
        assertTrue(r.errors.joinToString { it.message }, r.isImportable)
        assertTrue(r.passed.any { it.path == "background.png" })
        assertTrue(r.passed.any { it.path == "formatVersion" })
        assertTrue(r.passed.any { it.path == "layers/hair.png" })
        assertEquals(720, r.stats!!.canvasWidth)
        assertEquals(1, r.stats!!.layerCount)
    }

    @Test
    fun bundledSampleProducesARealReport() {
        val outcome = ProjectChecker.inspectArchive(File("src/main/assets/samples/neon_warrior.mwproj"), "neon_warrior.mwproj")
        val r = outcome.report
        assertTrue(r.errors.joinToString { it.message }, r.isImportable)
        assertTrue("the sample should show many passed checks, found ${r.passed.size}", r.passed.size >= 8)
        assertEquals(4, outcome.layers.size)
        assertTrue(ReportText.fullReport(r).contains("layers/body.png"))
    }

    @Test
    fun checklistTemplateIsAValidProject() {
        val entries = linkedMapOf(
            "manifest.json" to FormatRequirements.TEMPLATE.toByteArray(Charsets.UTF_8),
            "background.png" to TestFiles.pngBytes(720, 1280),
            "layers/body.png" to TestFiles.pngBytes(200, 300),
        )
        val r = inspect(entries)
        assertTrue(r.errors.joinToString { it.message }, r.isImportable)
        assertTrue("template should have no warnings, found ${r.warnings}", r.warnings.isEmpty())
    }

    @Test
    fun checklistTextListsEveryRequirement() {
        val text = FormatRequirements.checklistText()
        assertTrue(FormatRequirements.CHECKLIST.size >= 10)
        FormatRequirements.CHECKLIST.forEach { assertTrue(text.contains(it)) }
        assertTrue(text.contains("\"formatVersion\": 1"))
    }

    // ---- required problems ---------------------------------------------------------------------

    @Test
    fun missingFormatVersionIsReportedExplicitly() {
        val entries = editManifest(TestFiles.validProjectEntries()) { it.replace("\"formatVersion\": 1,", "") }
        val r = inspect(entries)
        assertFalse(r.isImportable)
        val f = r.errors.single { it.code == Code.MISSING_FIELD }
        assertEquals("formatVersion", f.path)
        assertTrue(f.message, f.message.contains("formatVersion is missing"))
        assertTrue(ReportText.missingItems(r).contains("formatVersion"))
    }

    @Test
    fun missingManifestIsReported() {
        val entries = TestFiles.validProjectEntries().filterKeys { it != "manifest.json" }
        val r = inspect(entries)
        assertTrue(hasError(r, Code.MANIFEST_MISSING, "manifest.json"))
        assertTrue(r.requiredMissing().any { it.path == "manifest.json" })
    }

    @Test
    fun invalidJsonIsReported() {
        val entries = editManifest(TestFiles.validProjectEntries()) { "{not json" }
        val r = inspect(entries)
        val f = r.errors.single { it.code == Code.INVALID_JSON }
        assertEquals("manifest.json", f.path)
        assertTrue(f.message.contains("not valid JSON"))
    }

    @Test
    fun missingReferencedImagesAreListedByExactPath() {
        val entries = TestFiles.validProjectEntries().toMutableMap()
        entries.remove("background.png")
        entries.remove("layers/hair.png")
        val r = inspect(entries)
        assertFalse(r.isImportable)
        assertEquals(setOf("background.png", "layers/hair.png"), r.requiredMissing().map { it.path }.toSet())
        val missing = ReportText.missingItems(r)
        assertTrue(missing.contains("layers/hair.png"))
        assertTrue(missing.contains("background.png"))
    }

    @Test
    fun missingLayerWithCapitalizationDifferenceExplainsCaseSensitivity() {
        val entries = TestFiles.validProjectEntries().toMutableMap()
        entries["layers/Hair.png"] = entries.remove("layers/hair.png")!!
        val r = inspect(entries)
        val f = r.errors.single { it.code == Code.MISSING_ASSET }
        assertEquals("layers/hair.png", f.path)
        assertTrue(f.message, f.message.contains("case-sensitive"))
        assertTrue(f.message.contains("layers/Hair.png"))
    }

    @Test
    fun backgroundOfWrongSizeIsReported() {
        val entries = TestFiles.validProjectEntries().toMutableMap()
        entries["background.png"] = TestFiles.pngBytes(100, 100)
        val r = inspect(entries)
        val f = r.errors.single { it.code == Code.SIZE_MISMATCH }
        assertEquals("background.png", f.path)
        assertTrue(f.message.contains("background.png must be exactly 720x1280"))
    }

    @Test
    fun incompatibleFormatVersionIsReported() {
        val entries = editManifest(TestFiles.validProjectEntries()) { it.replace("\"formatVersion\": 1", "\"formatVersion\": 2") }
        val r = inspect(entries)
        val f = r.errors.single { it.code == Code.UNSUPPORTED_VERSION }
        assertEquals("formatVersion", f.path)
        assertTrue(f.message, f.message.contains("Unsupported project version 2"))
    }

    @Test
    fun wrongFormatIdentifierIsReported() {
        val entries = editManifest(TestFiles.validProjectEntries()) { it.replace("\"mwproj\"", "\"psd\"") }
        val r = inspect(entries)
        assertTrue(r.errors.any { it.code == Code.UNSUPPORTED_FORMAT && it.message.contains("'psd'") })
    }

    @Test
    fun emptyManifestAndNonObjectManifestAreReported() {
        val empty = inspect(editManifest(TestFiles.validProjectEntries()) { "" })
        assertTrue(hasError(empty, Code.MANIFEST_EMPTY, "manifest.json"))
        val list = inspect(editManifest(TestFiles.validProjectEntries()) { "[1, 2]" })
        assertTrue(hasError(list, Code.NOT_AN_OBJECT, "manifest.json"))
    }

    @Test
    fun deeplyNestedManifestIsRejectedWithoutCrashing() {
        val deep = "[".repeat(300) + "]".repeat(300)
        val r = inspect(editManifest(TestFiles.validProjectEntries()) { deep })
        assertTrue(hasError(r, Code.JSON_TOO_DEEP, "manifest.json"))
    }

    // ---- optional files ------------------------------------------------------------------------

    @Test
    fun missingOptionalFilesAreWarningsNotFailures() {
        val entries = TestFiles.validProjectEntries().toMutableMap()
        listOf("depth.png", "preview.png", "masks/hair.png").forEach { entries.remove(it) }
        val outcome = ProjectChecker.inspectArchive(archive(entries), "project.mwproj")
        val r = outcome.report
        assertTrue(r.errors.joinToString { it.message }, r.isImportable)
        assertEquals(setOf("depth.png", "preview.png", "masks/hair.png"), r.optionalMissing().map { it.path }.toSet())
        // Missing optional references are cleared, so the renderer never tries to load them.
        val manifest = outcome.manifest!!
        assertNull(manifest.depth)
        assertNull(manifest.preview)
        assertNull(outcome.layers.single().mask)
    }

    @Test
    fun optionalFileCheckOnlyAffectsTheReferencedNames() {
        val entries = TestFiles.validProjectEntries().toMutableMap()
        entries.remove("preview.png")
        val r = inspect(entries)
        assertTrue(r.isImportable)
        assertEquals(0, r.requiredMissing().size)
        assertTrue(r.optionalMissing().any { it.path == "preview.png" })
    }

    @Test
    fun validateDirectoryStillFailsOnRequiredProblemsOnly() {
        val dir = TestFiles.validProjectDir(TestFiles.tempDir("dirval"))
        File(dir, "depth.png").delete()
        val validated = ProjectValidator.validate(dir)
        assertNull(validated.manifest.depth)

        File(dir, "layers/hair.png").delete()
        try {
            ProjectValidator.validate(dir)
            throw AssertionError("a missing required layer must still fail validation")
        } catch (e: ProjectException) {
            assertTrue(e.message!!.contains("layers/hair.png"))
        }
    }

    // ---- unsupported files ---------------------------------------------------------------------

    @Test
    fun unsupportedFilesAreListedWithTheirNames() {
        val entries = TestFiles.validProjectEntries().toMutableMap()
        entries["layers/hair.psd"] = byteArrayOf(1, 2, 3)
        entries["notes/readme.txt"] = "hello".toByteArray()
        val r = inspect(entries)
        assertFalse(r.isImportable)
        assertEquals(setOf("layers/hair.psd", "notes/readme.txt"), r.unsupportedFiles().map { it.path }.toSet())
        val missing = ReportText.missingItems(r)
        assertTrue(missing.contains("hair.psd"))
        assertTrue(missing.contains("readme.txt"))
    }

    @Test
    fun manifestReferencingAnUnsupportedExtensionIsReported() {
        val entries = editManifest(TestFiles.validProjectEntries()) { it.replace("layers/hair.png", "layers/hair.jpg") }
        val r = inspect(entries)
        val f = r.errors.single { it.code == Code.UNSUPPORTED_EXTENSION }
        assertEquals("layers[0].file", f.path)
        assertTrue(f.message.contains("layers/hair.jpg"))
    }

    // ---- duplicates and limits -----------------------------------------------------------------

    @Test
    fun duplicatePathsAreReported() {
        val entries = LinkedHashMap(TestFiles.validProjectEntries())
        entries["layers\\hair.png"] = TestFiles.pngBytes(300, 400)   // same path after normalization
        entries["./manifest.json"] = entries.getValue("manifest.json")
        val r = inspect(entries)
        val dups = r.errors.filter { it.code == Code.DUPLICATE_PATH }.map { it.path }.toSet()
        assertEquals(setOf("layers/hair.png", "manifest.json"), dups)
    }

    @Test
    fun oversizedArchiveIsRejectedBeforeReading() {
        val f = File(TestFiles.tempDir("big"), "huge.mwproj")
        RandomAccessFile(f, "rw").use { it.setLength(ProjectLimits.MAX_ARCHIVE_BYTES + 1) }
        val r = ProjectChecker.inspectArchive(f, "huge.mwproj").report
        assertTrue(hasError(r, Code.FILE_TOO_LARGE, null))
    }

    @Test
    fun archiveLargerThanTheLimitIsRejectedWithItsLimit() {
        val r = inspect(TestFiles.validProjectEntries(), CheckLimits(maxArchiveBytes = 100))
        assertTrue(hasError(r, Code.FILE_TOO_LARGE, null))
    }

    @Test
    fun tooManyFilesIsReported() {
        val r = inspect(TestFiles.validProjectEntries(), CheckLimits(maxEntries = 2))
        assertTrue(hasError(r, Code.TOO_MANY_FILES, null))
    }

    @Test
    fun unpackedSizeLimitIsReported() {
        val r = inspect(TestFiles.validProjectEntries(), CheckLimits(maxUnpackedBytes = 100))
        assertTrue(hasError(r, Code.UNPACKED_TOO_LARGE, null))
    }

    @Test
    fun oversizedEntryIsReportedOnceWithoutMissingFileErrors() {
        val r = inspect(TestFiles.validProjectEntries(), CheckLimits(maxEntryBytes = 10))
        assertTrue(r.errors.any { it.code == Code.ENTRY_TOO_LARGE })
        // The refused files are reported as too large, not as missing.
        assertTrue(r.errors.none { it.code in Code.MISSING })
    }

    @Test
    fun imageSidesAboveTheLimitAreRejected() {
        val entries = TestFiles.validProjectEntries().toMutableMap()
        entries["layers/hair.png"] = TestFiles.pngBytes(5000, 10)
        val r = inspect(entries)
        assertTrue(hasError(r, Code.IMAGE_TOO_LARGE, "layers/hair.png"))
    }

    @Test
    fun gpuMemoryBudgetIsReported() {
        val r = inspect(TestFiles.validProjectEntries(), CheckLimits(maxTextureBytes = 1000))
        assertTrue(hasError(r, Code.GPU_BUDGET, null))
    }

    // ---- reusable text and recheck -------------------------------------------------------------

    @Test
    fun copiedTextsContainEveryProblem() {
        val entries = TestFiles.validProjectEntries().toMutableMap()
        entries.remove("layers/hair.png")
        entries["layers/hair.psd"] = ByteArray(4)
        val r = inspect(entries)
        val full = ReportText.fullReport(r)
        r.findings.forEach { assertTrue("report is missing: ${it.message}", full.contains(it.message)) }
        val missing = ReportText.missingItems(r)
        assertTrue(missing.contains("layers/hair.png"))
        assertTrue(missing.contains("layers/hair.psd"))
    }

    @Test
    fun recheckingACorrectedProjectUpdatesTheResults() {
        val broken = TestFiles.validProjectEntries().toMutableMap()
        broken.remove("layers/hair.png")
        broken.remove("background.png")
        val first = inspect(broken)
        assertFalse(first.isImportable)
        assertEquals(2, first.requiredMissing().size)

        val second = inspect(TestFiles.validProjectEntries())
        assertTrue(second.isImportable)
        assertTrue(second.requiredMissing().isEmpty())
        assertTrue(ReportText.missingItems(second).contains("  none"))
    }

    @Test
    fun headlineSaysWhetherTheFileCanBeImported() {
        assertTrue(ReportText.headline(inspect(TestFiles.validProjectEntries())).startsWith("VALID"))
        val broken = TestFiles.validProjectEntries().toMutableMap().apply { remove("background.png") }
        assertTrue(ReportText.headline(inspect(broken)).startsWith("NOT IMPORTED"))
    }

    // ---- robustness ----------------------------------------------------------------------------

    @Test
    fun malformedManifestVariantsAreReportedNotThrown() {
        val variants = listOf(
            "null",
            "[]",
            "\"text\"",
            "{\"canvas\": []}",
            "{\"layers\": {}}",
            "{\"background\": 5, \"format\": \"mwproj\", \"formatVersion\": 1}",
            "{\"format\":\"mwproj\",\"formatVersion\":1,\"name\":\"x\",\"canvas\":{\"width\":720,\"height\":1280}," +
                "\"background\":{\"file\":\"background.png\"},\"layers\":[1,\"a\",{\"id\":5}]}",
            "{\"particles\": [{\"type\": \"fire\", \"region\": [0, 0]}]}",
            "{\"format\":\"mwproj\",\"formatVersion\":1,\"name\":\"x\",\"canvas\":{\"width\":720,\"height\":1280}," +
                "\"background\":{\"file\":\"background.png\"},\"mesh\":5}",
        )
        for (v in variants) {
            val r = inspect(editManifest(TestFiles.validProjectEntries()) { v })
            assertFalse("variant should not be importable: $v", r.isImportable)
            assertTrue("variant should have at least one error: $v", r.errors.isNotEmpty())
        }
    }

    @Test
    fun invalidInputsNeverCrashTheChecker() {
        val rnd = Random(42)
        val valid = TestFiles.zipBytes(TestFiles.validProjectEntries())
        repeat(40) { i ->
            val bytes: ByteArray = when (i % 4) {
                0 -> ByteArray(rnd.nextInt(1, 600)) { rnd.nextInt(256).toByte() }
                1 -> valid.copyOf(rnd.nextInt(1, valid.size))
                2 -> valid.copyOf().also { b -> repeat(8) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() } }
                else -> TestFiles.zipBytes(mapOf("manifest.json" to ByteArray(rnd.nextInt(0, 300)) { rnd.nextInt(256).toByte() }))
            }
            val f = File(TestFiles.tempDir("fuzz"), "f$i.mwproj")
            f.writeBytes(bytes)
            val outcome = ProjectChecker.inspectArchive(f, null)
            assertNotNull(outcome.report)
        }
    }

    @Test
    fun missingDisplayNameStillProducesAReport() {
        val outcome = ProjectChecker.inspectArchive(archive(TestFiles.validProjectEntries()), null)
        assertTrue(outcome.report.isImportable)
        assertEquals("selected file", outcome.report.fileName)
    }
}
