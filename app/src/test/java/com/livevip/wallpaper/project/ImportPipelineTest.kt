package com.livevip.wallpaper.project

import com.livevip.wallpaper.TestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Drives the same entry point the file picker uses ([ProjectStore.importFromUri]) with in-memory
 * streams, so these tests cover the real import pipeline without an Android device.
 */
class ImportPipelineTest {

    private lateinit var base: File
    private lateinit var store: ProjectStore

    @Before
    fun setUp() {
        base = TestFiles.tempDir("import")
        store = newStore()
    }

    private fun newStore(): ProjectStore = ProjectStore(
        root = File(base, "projects"),
        staging = File(base, "staging"),
        cache = File(base, "cache"),
        openAsset = { path -> File("src/main/assets/$path").inputStream() },
    )

    private fun importBytes(displayName: String?, bytes: ByteArray): ImportResult =
        store.importFromUri(displayName) { ByteArrayInputStream(bytes) }

    private fun validArchive(): ByteArray = TestFiles.zipBytes(TestFiles.validProjectEntries())

    private fun successOf(result: ImportResult): ProjectSummary {
        val success = result as? ImportResult.Success ?: throw AssertionError("expected a successful import but got $result")
        return success.project
    }

    /** Asserts the import was rejected with a message containing every fragment, and changed nothing. */
    private fun assertRejected(result: ImportResult, vararg fragments: String) {
        val failure = result as? ImportResult.Failure ?: throw AssertionError("expected a rejection but got $result")
        for (f in fragments) {
            assertTrue("message should mention '$f' but was: ${failure.message}", failure.message.contains(f, ignoreCase = true))
        }
        assertTrue("a rejected import must not add a project", store.list().isEmpty())
        assertEquals("staging must be empty after a rejection", 0, File(base, "staging").listFiles()?.size ?: 0)
        assertEquals("cache must be empty after a rejection", 0, File(base, "cache").listFiles()?.size ?: 0)
    }

    // ---- valid imports -------------------------------------------------------------------------

    @Test
    fun importsValidProjectFromPickedFile() {
        val summary = successOf(importBytes("neon.mwproj", validArchive()))
        assertEquals("Test Project", summary.name)
        assertEquals(720, summary.canvasWidth)
        assertEquals(1280, summary.canvasHeight)
        assertEquals(1, summary.layerCount)
        assertTrue(File(summary.dir, "manifest.json").isFile)
        assertTrue(File(summary.dir, "meta.json").isFile)
        assertEquals(1, store.list().size)

        // The imported project must load for rendering, not just exist on disk.
        val loaded = store.load(summary.id)
        assertEquals("hair", loaded.manifest.layers[0].id)
        assertEquals(300, loaded.manifest.layers[0].width)
    }

    @Test
    fun acceptsAnyDisplayNameWhenTheContentIsAValidArchive() {
        // Providers may report no name, an upper-case extension, no extension, or a double extension.
        val names = listOf(null, "", "neon", "NEON.MWPROJ", "neon.zip", "neon.mwproj.zip", "neon (1).mwproj", "neon.bin")
        for (name in names) {
            val result = importBytes(name, validArchive())
            assertTrue("import with name '$name' should succeed but was $result", result is ImportResult.Success)
        }
        assertEquals(names.size, store.list().size)
    }

    @Test
    fun importsArchiveWrappedInOneFolder() {
        val entries = TestFiles.validProjectEntries(prefix = "Neon Warrior/")
        successOf(importBytes("Neon Warrior.mwproj", TestFiles.zipBytes(entries)))
    }

    @Test
    fun skipsMacOsAndDesktopMetadata() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["__MACOSX/"] = ByteArray(0)
        entries["__MACOSX/._manifest.json"] = ByteArray(26) { 7 }
        entries[".DS_Store"] = ByteArray(16)
        entries["Thumbs.db"] = ByteArray(16)
        entries.putAll(TestFiles.validProjectEntries())
        successOf(importBytes("mac.mwproj", TestFiles.zipBytes(entries)))
    }

    @Test
    fun skipsMacOsMetadataInsideAWrapperFolder() {
        // Finder adds __MACOSX next to the wrapper folder. That must not stop the wrapper being detected.
        val entries = LinkedHashMap<String, ByteArray>()
        entries["__MACOSX/Neon Warrior/._manifest.json"] = ByteArray(26) { 7 }
        entries.putAll(TestFiles.validProjectEntries(prefix = "Neon Warrior/"))
        entries["Neon Warrior/.DS_Store"] = ByteArray(16)
        successOf(importBytes("Neon Warrior.mwproj", TestFiles.zipBytes(entries)))
    }

    @Test
    fun acceptsWindowsSeparatorsAndLeadingDotSlash() {
        val entries = LinkedHashMap<String, ByteArray>()
        for ((name, bytes) in TestFiles.validProjectEntries()) {
            entries["./" + name.replace("/", "\\")] = bytes
        }
        successOf(importBytes("windows.mwproj", TestFiles.zipBytes(entries)))
    }

    @Test
    fun acceptsManifestWithUtf8ByteOrderMark() {
        val entries = TestFiles.validProjectEntries()
        val withBom = "\uFEFF" + String(entries.getValue("manifest.json"), Charsets.UTF_8)
        entries["manifest.json"] = withBom.toByteArray(Charsets.UTF_8)
        successOf(importBytes("bom.mwproj", TestFiles.zipBytes(entries)))
    }

    @Test
    fun acceptsDotSlashPathsInsideTheManifest() {
        val entries = TestFiles.validProjectEntries()
        entries["manifest.json"] = String(entries.getValue("manifest.json"), Charsets.UTF_8)
            .replace("\"background.png\"", "\"./background.png\"")
            .toByteArray(Charsets.UTF_8)
        successOf(importBytes("dot.mwproj", TestFiles.zipBytes(entries)))
    }

    @Test
    fun importsBundledSampleFromItsBytesThroughThePicker() {
        // The shipped sample is the reference that is known to work, so it must pass the picker path too.
        val bytes = File("src/main/assets/samples/neon_warrior.mwproj").readBytes()
        val summary = successOf(importBytes("neon_warrior.mwproj", bytes))
        assertEquals("Neon Warrior", summary.name)
        assertEquals(4, store.load(summary.id).manifest.layers.size)
    }

    @Test
    fun importsBundledSampleByAssetName() {
        val summary = store.importBundledSample("neon_warrior.mwproj")
        assertEquals("Neon Warrior", summary.name)
    }

    // ---- wrong file types ----------------------------------------------------------------------

    @Test
    fun rejectsPlainTextNamedAsProject() {
        val result = importBytes("notes.txt", "Just a note, not a project.".toByteArray())
        assertRejected(result, "notes.txt", "text file", "ZIP")
    }

    @Test
    fun rejectsPngImageNamedAsProject() {
        val result = importBytes("photo.mwproj", TestFiles.pngBytes(64, 64))
        assertRejected(result, "photo.mwproj", "image")
    }

    @Test
    fun rejectsJpegImageNamedAsProject() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64)
        assertRejected(importBytes("shot.mwproj", jpeg), "image")
    }

    @Test
    fun rejectsPdfDocumentNamedAsProject() {
        assertRejected(importBytes("doc.mwproj", "%PDF-1.4\n%fake".toByteArray()), "PDF")
    }

    @Test
    fun rejectsEmptyFile() {
        assertRejected(importBytes("empty.mwproj", ByteArray(0)), "empty")
    }

    @Test
    fun rejectsUnknownBinaryData() {
        val binary = ByteArray(128) { (it * 37 % 256).toByte() }.also { it[0] = 0 }
        assertRejected(importBytes("data.mwproj", binary), "not a ZIP-based .mwproj")
    }

    // ---- malformed archives --------------------------------------------------------------------

    @Test
    fun rejectsTruncatedZip() {
        val full = validArchive()
        assertRejected(importBytes("cut.mwproj", full.copyOf(full.size / 2)), "not a valid .mwproj")
    }

    @Test
    fun rejectsZipHeaderFollowedByGarbage() {
        val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(300) { 0x41 }
        assertRejected(importBytes("garbage.mwproj", bytes), "not a valid .mwproj")
    }

    @Test
    fun rejectsStreamThatFailsWhileReading() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("provider disconnected")
        }
        val result = store.importFromUri("neon.mwproj") { broken }
        assertRejected(result, "Could not read the selected file")
    }

    @Test
    fun reportsDeniedAccessWithoutCrashing() {
        val result = store.importFromUri("neon.mwproj") { throw SecurityException("no grant") }
        assertRejected(result, "did not allow reading")
    }

    @Test
    fun reportsMissingStreamWithoutCrashing() {
        assertRejected(store.importFromUri("neon.mwproj") { null }, "Could not open")
    }

    @Test
    fun rejectsArchiveWithoutManifest() {
        val bytes = TestFiles.zipBytes(mapOf("background.png" to TestFiles.pngBytes(720, 1280)))
        assertRejected(importBytes("nomanifest.mwproj", bytes), "manifest.json is missing")
    }

    @Test
    fun rejectsArchiveWhoseManifestIsOnlyInsideMetadataFolder() {
        val bytes = TestFiles.zipBytes(mapOf("__MACOSX/manifest.json" to "{}".toByteArray()))
        assertRejected(importBytes("meta.mwproj", bytes), "no project files")
    }

    @Test
    fun rejectsZipSlipPath() {
        val entries = TestFiles.validProjectEntries().toMutableMap()
        entries["../escape.png"] = TestFiles.pngBytes(4, 4)
        assertRejected(importBytes("slip.mwproj", TestFiles.zipBytes(entries)), "Unsafe path")
    }

    @Test
    fun rejectsAbsolutePath() {
        val entries = TestFiles.validProjectEntries().toMutableMap()
        entries["/etc/evil.png"] = TestFiles.pngBytes(4, 4)
        assertRejected(importBytes("abs.mwproj", TestFiles.zipBytes(entries)), "Unsafe path")
    }

    @Test
    fun rejectsExecutableInsideArchive() {
        val entries = TestFiles.validProjectEntries().toMutableMap()
        entries["payload.exe"] = ByteArray(16)
        assertRejected(importBytes("exe.mwproj", TestFiles.zipBytes(entries)), "payload.exe", "Only .png and .json")
    }

    // ---- invalid manifest and project schema ---------------------------------------------------

    private fun withManifest(json: String): ByteArray {
        val entries = TestFiles.validProjectEntries()
        entries["manifest.json"] = json.toByteArray(Charsets.UTF_8)
        return TestFiles.zipBytes(entries)
    }

    private fun manifestOf(transform: (String) -> String): ByteArray {
        val original = String(TestFiles.validProjectEntries().getValue("manifest.json"), Charsets.UTF_8)
        return withManifest(transform(original))
    }

    @Test
    fun rejectsInvalidManifestJson() {
        assertRejected(importBytes("bad.mwproj", withManifest("{not json")), "manifest.json is not valid JSON")
    }

    @Test
    fun rejectsEmptyManifest() {
        assertRejected(importBytes("empty.mwproj", withManifest("   ")), "manifest.json is empty")
    }

    @Test
    fun rejectsWrongFormatIdentifier() {
        val result = importBytes("wrong.mwproj", manifestOf { it.replace("\"mwproj\"", "\"psd\"") })
        assertRejected(result, "Unsupported format 'psd'")
    }

    @Test
    fun rejectsMissingFormatVersion() {
        val result = importBytes("noversion.mwproj", manifestOf { it.replace("\"formatVersion\": 1,", "") })
        assertRejected(result, "formatVersion is missing")
    }

    @Test
    fun rejectsUnsupportedFormatVersion() {
        val result = importBytes("v9.mwproj", manifestOf { it.replace("\"formatVersion\": 1", "\"formatVersion\": 9") })
        assertRejected(result, "Unsupported project version 9")
    }

    @Test
    fun rejectsMissingCanvas() {
        val result = importBytes("nocanvas.mwproj", manifestOf { it.replace(Regex("\"canvas\": \\{[^}]*\\},"), "") })
        assertRejected(result, "canvas is missing")
    }

    @Test
    fun rejectsCanvasOutOfRange() {
        val result = importBytes("tiny.mwproj", withManifest(TestFiles.manifestJson(canvasW = 50, canvasH = 1280)))
        assertRejected(result, "canvas size must be between 128 and 4096")
    }

    @Test
    fun rejectsMissingBackgroundFile() {
        val entries = TestFiles.validProjectEntries().toMutableMap()
        entries.remove("background.png")
        val result = importBytes("nobg.mwproj", TestFiles.zipBytes(entries))
        assertRejected(result, "background", "not in the archive")
    }

    @Test
    fun rejectsBackgroundOfWrongSize() {
        val entries = TestFiles.validProjectEntries().toMutableMap()
        entries["background.png"] = TestFiles.pngBytes(100, 100)
        assertRejected(importBytes("small.mwproj", TestFiles.zipBytes(entries)), "background.png must be exactly 720x1280")
    }

    @Test
    fun rejectsLayerPathWithDifferentCapitalization() {
        val entries = TestFiles.validProjectEntries().toMutableMap()
        val hair = entries.remove("layers/hair.png")!!
        entries["layers/Hair.png"] = hair
        val result = importBytes("case.mwproj", TestFiles.zipBytes(entries))
        assertRejected(result, "case-sensitive", "layers/Hair.png")
    }

    @Test
    fun rejectsUnknownLayerRole() {
        val result = importBytes("role.mwproj", withManifest(
            TestFiles.manifestJson(layers = """{"id":"hair","role":"wings","file":"layers/hair.png"}"""),
        ))
        assertRejected(result, "unknown role 'wings'")
    }

    @Test
    fun rejectsManifestPathThatEscapesTheArchive() {
        val result = importBytes("escape.mwproj", withManifest(
            TestFiles.manifestJson(layers = """{"id":"hair","file":"../hair.png"}"""),
        ))
        assertRejected(result, "Unsafe or invalid path")
    }

    @Test
    fun rejectsNonNumericValueInEffects() {
        val result = importBytes("badfx.mwproj", manifestOf {
            it.replace(
                "\"layers\": [",
                "\"effects\": {\"outerGlowIntensity\": \"very bright\"}, \"layers\": [",
            )
        })
        assertRejected(result, "manifest.effects has a value of the wrong type")
    }

    @Test
    fun acceptsValidEffectsAndMotionDefaults() {
        val result = importBytes("fx.mwproj", manifestOf {
            it.replace(
                "\"layers\": [",
                "\"effects\": {\"outerGlowColor\": \"#00E5FF\", \"outerGlowIntensity\": 0.8}, " +
                    "\"motion\": {\"strength\": 1.2, \"smoothing\": 6}, \"layers\": [",
            )
        })
        val summary = successOf(result)
        val loaded = store.load(summary.id)
        assertEquals(0.8f, loaded.tuning.effects.outerGlowIntensity, 0.0001f)
        assertEquals(1.2f, loaded.tuning.motion.strength, 0.0001f)
    }

    @Test
    fun failedImportsNeverLeaveAProjectBehind() {
        importBytes("bad1.mwproj", withManifest("{}"))
        importBytes("bad2.mwproj", ByteArray(0))
        importBytes("bad3.mwproj", TestFiles.zipBytes(emptyMap()))
        assertTrue(store.list().isEmpty())
        assertEquals(0, File(base, "projects").listFiles()?.size ?: 0)
    }
}
