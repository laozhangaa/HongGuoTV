// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.laozhangapp

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Bounded loading layout. One canvas draws the poster slots; no looping full-screen effects. */
internal class TvLoading(context: Context, message: String, detail: Boolean = false) : LinearLayout(context) {
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(TvStyle.accent)
        visibility = INVISIBLE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val revealProgress = Runnable {
        if (isAttachedToWindow && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled())
            progress.visibility = VISIBLE
    }

    init {
        orientation = VERTICAL
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        contentDescription = message
        val heading = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(18), dp(4), dp(16))
        }
        heading.addView(TextView(context).apply {
            text = message; textSize = 15f; setTextColor(TvStyle.muted)
            includeFontPadding = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(0, -2, 1f))
        heading.addView(progress, LayoutParams(dp(96), dp(4)).apply { marginStart = dp(20) })
        addView(heading, LayoutParams(-1, -2))
        addView(Slots(context, detail), LayoutParams(-1, 0, 1f))
    }

    private fun dp(value: Int) = TvLayout.dp(context, value)
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(revealProgress, 240)
    }
    override fun onDetachedFromWindow() {
        removeCallbacks(revealProgress)
        progress.visibility = INVISIBLE
        super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // View constructors can dispatch this before our children are initialized.
        if (childCount == 0) return
        removeCallbacks(revealProgress)
        progress.visibility = INVISIBLE
        if (visibility == VISIBLE && isAttachedToWindow) postDelayed(revealProgress, 240)
    }

    private class Slots(context: Context, private val detail: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private fun dp(value: Int) = TvLayout.dp(context, value).toFloat()
        private fun block(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, color: Int) {
            paint.color = color
            canvas.drawRoundRect(x, y, x + width, y + height, dp(5), dp(5), paint)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = dp(4)
            val available = (width - inset * 2).coerceAtLeast(1f)
            if (detail) {
                val poster = minOf(dp(123), available * .22f)
                val posterHeight = minOf(poster / TvStyle.POSTER_ASPECT, height.toFloat())
                block(canvas, inset, 0f, poster, posterHeight, TvStyle.surface)
                val x = inset + poster + dp(24)
                val textWidth = (width - x - inset).coerceAtLeast(1f)
                block(canvas, x, dp(6), textWidth * .7f, dp(25), TvStyle.surfaceAlt)
                repeat(3) { row -> block(canvas, x, dp(56) + row * dp(25), textWidth * (if (row == 2) .6f else .92f), dp(10), TvStyle.surface) }
                block(canvas, x, dp(147), minOf(textWidth, dp(180)), dp(38), TvStyle.surfaceAlt)
            } else {
                val columns = TvLayout.columns(context, available.toInt(), 120, 6)
                val gap = dp(14)
                val card = ((available - gap * (columns - 1)) / columns).coerceAtLeast(1f)
                val poster = card / TvStyle.POSTER_ASPECT
                val stride = poster + dp(62)
                repeat(2) { row ->
                    val y = row * stride
                    if (y < height) repeat(columns) { column ->
                        val x = inset + column * (card + gap)
                        block(canvas, x, y, card, poster, TvStyle.surface)
                        block(canvas, x, y + poster + dp(12), card * .82f, dp(12), TvStyle.surfaceAlt)
                        block(canvas, x, y + poster + dp(34), card * .5f, dp(8), TvStyle.surface)
                    }
                }
            }
        }
    }
}
