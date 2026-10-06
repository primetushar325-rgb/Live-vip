package com.livevip.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionStateTest {
    @Test fun fitResetsTransformWithoutDistortingState() {
        val state = CompositionState(1920, 1080, 720, 1280, scale = 1.8f, translationX = 22f).fit()
        assertEquals(FitMode.FIT, state.fitMode)
        assertEquals(1f, state.scale, 0.0001f)
        assertEquals(0f, state.translationX, 0.0001f)
        assertEquals(16f / 9f, state.sourceWidth.toFloat() / state.sourceHeight, 0.0001f)
    }

    @Test fun zoomNeverChangesXAndYIndependently() {
        val state = CompositionState(1920, 1080).zoomBy(2f)
        assertEquals(2f, state.scale, 0.0001f)
        assertTrue(state.sourceWidth.toFloat() / state.sourceHeight > 1f)
    }
}
