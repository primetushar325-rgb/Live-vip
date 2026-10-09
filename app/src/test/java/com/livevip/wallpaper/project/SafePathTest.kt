package com.livevip.wallpaper.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafePathTest {
    @Test
    fun acceptsPlainRelativePaths() {
        assertEquals("layers/hair.png", SafePath.normalize("layers/hair.png"))
        assertEquals("background.png", SafePath.normalize("background.png"))
    }

    @Test
    fun rejectsTraversalAndAbsolutePaths() {
        assertNull(SafePath.normalize("../evil.json"))
        assertNull(SafePath.normalize("layers/../../evil.png"))
        assertNull(SafePath.normalize("/etc/passwd"))
        assertNull(SafePath.normalize("C:/windows/x.png"))
        assertNull(SafePath.normalize("layers\\hair.png"))
        assertNull(SafePath.normalize("layers//hair.png"))
        assertNull(SafePath.normalize("./hair.png"))
        assertNull(SafePath.normalize(".hidden/x.png"))
        assertNull(SafePath.normalize("a/b/c/d/e.png"))
        assertNull(SafePath.normalize("nul\u0000.png"))
        assertNull(SafePath.normalize(""))
    }
}
