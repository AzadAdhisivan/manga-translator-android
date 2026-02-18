package com.example.mangatranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class SelectionOverlayView(
    context: Context,
    private val onSelectionDone: (RectF) -> Unit
) : View(context) {

    private val dimPaint = Paint().apply {
        style = Paint.Style.FILL
        alpha = 60 // slight dim, not heavy
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        alpha = 40
    }

    private var startX = 0f
    private var startY = 0f
    private var curX = 0f
    private var curY = 0f
    private var dragging = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // dim the whole screen slightly
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        if (dragging) {
            val left = min(startX, curX)
            val top = min(startY, curY)
            val right = max(startX, curX)
            val bottom = max(startY, curY)

            val rect = RectF(left, top, right, bottom)

            // draw selection box
            canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, borderPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                startX = event.x
                startY = event.y
                curX = startX
                curY = startY
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                curX = event.x
                curY = event.y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                dragging = false
                curX = event.x
                curY = event.y

                val left = min(startX, curX)
                val top = min(startY, curY)
                val right = max(startX, curX)
                val bottom = max(startY, curY)

                // ignore tiny selections
                if ((right - left) > 30 && (bottom - top) > 30) {
                    onSelectionDone(RectF(left, top, right, bottom))
                }

                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
