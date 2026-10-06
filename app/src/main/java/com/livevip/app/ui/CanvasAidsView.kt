package com.livevip.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * EDITING AIDS ONLY — grid, center guide, safe area and the selected layer
 * box drawn ABOVE the preview inside the editor. These are Android UI
 * chrome, NOT GL filters: they are never part of the encoded stream.
 */
class CanvasAidsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var showGrid = false
    var showSafeArea = false
    var showCenterGuide = false

    /** Selection rect in FRACTIONS of this view (0..1), or null. */
    var selectionRect: RectF? = null

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        strokeWidth = 1f
    }
    private val safePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 0, 194, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val safeShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(28, 0, 194, 255)
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 124, 77, 255) // primary purple
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 124, 77, 255)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (showGrid) {
            // Rule of thirds.
            for (i in 1..2) {
                val x = w * i / 3f
                canvas.drawLine(x, 0f, x, h, gridPaint)
                val y = h * i / 3f
                canvas.drawLine(0f, y, w, y, gridPaint)
            }
            canvas.drawLine(w / 2f, 0f, w / 2f, h, gridPaint)
            canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)
        }

        if (showCenterGuide) {
            canvas.drawLine(w / 2f, 0f, w / 2f, h, gridPaint)
            canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)
        }

        if (showSafeArea) {
            val inset = 0.05f
            val shade = RectF(w * inset, h * inset, w * (1 - inset), h * (1 - inset))
            canvas.drawRect(shade, safeShadePaint)
            canvas.drawRect(shade, safePaint)
        }

        selectionRect?.let { r ->
            val px = RectF(r.left * w, r.top * h, r.right * w, r.bottom * h)
            canvas.drawRect(px, selectionPaint)
            // Corner handles.
            val hs = 9f
            canvas.drawCircle(px.left, px.top, hs, handlePaint)
            canvas.drawCircle(px.right, px.top, hs, handlePaint)
            canvas.drawCircle(px.left, px.bottom, hs, handlePaint)
            canvas.drawCircle(px.right, px.bottom, hs, handlePaint)
        }
    }
}
