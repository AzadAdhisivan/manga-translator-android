package com.example.mangatranslator

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.max

class TranslationOverlayView(
    context: Context,
    private val rect: RectF,
    private val text: String
) : View(context) {

    private val dimPaint = Paint().apply {
        color = 0x22000000 // translucent dark
        style = Paint.Style.FILL
    }

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F1E9.toInt() // soft off-white
        style = Paint.Style.FILL
    }

    private val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22000000
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }

    private val corner = 24f
    private val pad = 24f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Dim background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        // Card bounds (use rect)
        val card = RectF(rect)

        // Draw card
        canvas.drawRoundRect(card, corner, corner, cardPaint)
        canvas.drawRoundRect(card, corner, corner, cardStroke)

        // Inner text area
        val innerLeft = card.left + pad
        val innerTop = card.top + pad
        val innerRight = card.right - pad
        val innerBottom = card.bottom - pad

        val innerW = max(1f, innerRight - innerLeft)
        val innerH = max(1f, innerBottom - innerTop)

        // 1) Pick a starting size
        var hi = 34f
        var lo = 10f

        // 2) Binary search best text size that fits inside innerH
        var best = lo
        repeat(10) {
            val mid = (lo + hi) / 2f
            textPaint.textSize = mid

            val layout = makeLayout(textPaint, text, innerW.toInt())
            if (layout.height <= innerH) {
                best = mid
                lo = mid
            } else {
                hi = mid
            }
        }

        textPaint.textSize = best
        val finalLayout = makeLayout(textPaint, text, innerW.toInt())

        // Draw wrapped text inside box (no overflow)
        canvas.save()
        canvas.translate(innerLeft, innerTop)
        finalLayout.draw(canvas)
        canvas.restore()
    }

    private fun makeLayout(paint: TextPaint, text: String, widthPx: Int): StaticLayout {
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(6f, 1.0f)
            .setIncludePad(false)
            .build()
    }
}
