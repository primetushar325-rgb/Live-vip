package com.livevip.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionStateTest {
    @Test fun fitPreservesAspectRatio() {
        val state = CompositionState(1920, 1080, 720, 1280).fit()
        val values = FloatArray(9)
        state.outputMatrix().getValues(values)
        assertTrue(values[MatrixIndex.SCALE_X] > 0f)
        assertEquals(values[MatrixIndex.SCALE_X], values[MatrixIndex.SCALE_Y], 0.0001f)
    }

    @Test fun zoomNeverChangesXAndYIndependently() {
        val state = CompositionState(1920, 1080).zoomBy(2f)
        val values = FloatArray(9)
        state.outputMatrix().getValues(values)
        assertEquals(values[MatrixIndex.SCALE_X], values[MatrixIndex.SCALE_Y], 0.0001f)
    }

    private object MatrixIndex { const val SCALE_X = 0; const val SCALE_Y = 4 }
}
