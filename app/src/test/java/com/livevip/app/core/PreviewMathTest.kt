package com.livevip.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure preview-surface sizing math (never binds, only sizes). */
class PreviewMathTest {

    @Test
    fun `fits 16by9 output into a wider container`() {
        val (w, h) = PreviewMath.fit(1000f, 500f, 1920f, 1080f)
        assertEquals(1000f, w, 0.01f)
        assertEquals(562.5f, h, 0.01f)
    }

    @Test
    fun `fits 9by16 output into a short container`() {
        val (w, h) = PreviewMath.fit(1000f, 500f, 1080f, 1920f)
        assertEquals(281.25f, w, 0.01f)
        assertEquals(500f, h, 0.01f)
    }

    @Test
    fun `same aspect returns the container`() {
        val (w, h) = PreviewMath.fit(800f, 450f, 1280f, 720f)
        assertEquals(800f, w, 0.01f)
        assertEquals(450f, h, 0.01f)
    }

    @Test
    fun `degenerate container returns zeros`() {
        val (w, h) = PreviewMath.fit(0f, 0f, 1280f, 720f)
        assertEquals(0f, w, 0.01f)
        assertEquals(0f, h, 0.01f)
        assertTrue(w >= 0f && h >= 0f)
    }
}
