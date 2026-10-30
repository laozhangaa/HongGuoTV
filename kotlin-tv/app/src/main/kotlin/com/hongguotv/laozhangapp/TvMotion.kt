// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.laozhangapp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/** Short, interruptible feedback for TV remotes. No animation owns navigation or playback. */
internal object TvMotion {
    private const val FOCUS_MS = 120L
    private const val ENTER_MS = 180L
    private const val EXIT_MS = 110L
    private val easing = PathInterpolator(.16f, 1f, .3f, 1f)
    private val colors = ArgbEvaluator()
    private fun enabled() = ValueAnimator.areAnimatorsEnabled()

    private class FocusState(val drawable: GradientDrawable, var fill: Int, var stroke: Int, var scale: Float) {
        var targetFill = fill
        var targetStroke = stroke
        var targetScale = scale
        var animator: ValueAnimator? = null
    }

    fun focus(view: View, focused: Boolean, fill: Int, stroke: Int, radius: Int = 6, focusedScale: Float = 1.02f) {
        val scale = if (focused && enabled()) focusedScale else 1f
        var state = view.getTag(R.id.tv_motion_focus) as? FocusState
        if (state == null || view.background !== state.drawable) {
            state?.animator?.cancel()
            val drawable = TvStyle.shape(view.context, fill, stroke, radius)
            state = FocusState(drawable, fill, stroke, scale)
            view.background = drawable
            view.scaleX = scale; view.scaleY = scale
            view.setTag(R.id.tv_motion_focus, state)
            if (view.getTag(R.id.tv_motion_cleanup) == null) {
                view.setTag(R.id.tv_motion_cleanup, true)
                view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = Unit
                    override fun onViewDetachedFromWindow(v: View) {
                        (v.getTag(R.id.tv_motion_focus) as? FocusState)?.let { value ->
                            value.animator?.cancel(); value.animator = null
                            paint(v, value, value.targetFill, value.targetStroke, value.targetScale)
                        }
                    }
                })
            }
            return
        }
        val current = state
        if (current.targetFill == fill && current.targetStroke == stroke && current.targetScale == scale) return
        current.animator?.cancel()
        current.targetFill = fill; current.targetStroke = stroke; current.targetScale = scale
        if (!enabled() || !view.isAttachedToWindow) {
            paint(view, current, fill, stroke, scale)
            current.animator = null
            return
        }
        val startFill = current.fill; val startStroke = current.stroke; val startScale = current.scale
        current.animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FOCUS_MS; interpolator = easing
            addUpdateListener {
                val fraction = it.animatedValue as Float
                paint(view, current, colors.evaluate(fraction, startFill, fill) as Int,
                    colors.evaluate(fraction, startStroke, stroke) as Int, startScale + (scale - startScale) * fraction)
            }
            start()
        }
    }

    private fun paint(view: View, state: FocusState, fill: Int, stroke: Int, scale: Float) {
        state.fill = fill; state.stroke = stroke; state.scale = scale
        state.drawable.setColor(fill)
        state.drawable.setStroke((2 * view.resources.displayMetrics.density).toInt(), stroke)
        view.scaleX = scale; view.scaleY = scale
    }

    /** Keep navigation readable while the destination content follows the tab's direction. */
    fun enterPage(view: View, stableHeader: Boolean = false, direction: Int = 0) {
        view.post {
            if (!view.isAttachedToWindow || !enabled()) return@post
            val targets = if (stableHeader && view is ViewGroup)
                (2 until view.childCount).map(view::getChildAt) else listOf(view)
            targets.forEach { content ->
                arrive(content, ENTER_MS, direction * TvLayout.dp(view.context, 16).toFloat())
            }
        }
    }

    /** Loaded results replace their placeholder; headers and existing shelves stay still. */
    fun contentReady(view: View) {
        if (enabled()) arrive(view, 150L, 0f)
    }

    private fun arrive(view: View, durationMs: Long, offset: Float) {
        view.animate().cancel()
        if (!enabled()) { view.alpha = 1f; view.translationX = 0f; return }
        if (view.getTag(R.id.tv_motion_arrival) == null) {
            view.setTag(R.id.tv_motion_arrival, true)
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    v.animate().cancel(); v.alpha = 1f; v.translationX = 0f
                }
            })
        }
        view.alpha = .68f; view.translationX = offset
        view.animate().alpha(1f).translationX(0f).setDuration(durationMs).setInterpolator(easing).start()
    }

    private class VisibilityState(var shown: Boolean) {
        var revision = 0
        var animator: ValueAnimator? = null
    }

    /** Logical visibility changes immediately, so repeated Back/Up presses never wait for a fade. */
    fun isVisible(view: View) = (view.getTag(R.id.tv_motion_visibility) as? VisibilityState)?.shown
        ?: (view.visibility == View.VISIBLE)

    fun visible(view: View, shown: Boolean, animate: Boolean = true) {
        var state = view.getTag(R.id.tv_motion_visibility) as? VisibilityState
        if (state == null) {
            state = VisibilityState(view.visibility == View.VISIBLE)
            view.setTag(R.id.tv_motion_visibility, state)
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    (v.getTag(R.id.tv_motion_visibility) as? VisibilityState)?.let { value ->
                        value.revision++; value.animator?.cancel(); value.animator = null
                        v.alpha = 1f; v.visibility = if (value.shown) View.VISIBLE else View.GONE
                    }
                }
            })
        }
        val current = state
        if (animate && current.shown == shown) return
        val wasVisible = view.visibility == View.VISIBLE
        current.shown = shown
        val revision = ++current.revision
        current.animator?.cancel(); current.animator = null
        if (!animate || !enabled() || !view.isAttachedToWindow) {
            view.alpha = 1f; view.visibility = if (shown) View.VISIBLE else View.GONE
            return
        }
        if (shown) { view.visibility = View.VISIBLE; if (!wasVisible) view.alpha = 0f }
        current.animator = ValueAnimator.ofFloat(view.alpha, if (shown) 1f else 0f).apply {
            duration = if (shown) ENTER_MS else EXIT_MS; interpolator = easing
            addUpdateListener { view.alpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (revision != current.revision) return
                    view.visibility = if (shown) View.VISIBLE else View.GONE
                    view.alpha = 1f; current.animator = null
                }
            })
            start()
        }
    }

    fun dialog(dialog: Dialog, sidePanel: Boolean = false) {
        dialog.window?.setWindowAnimations(if (!enabled()) 0 else if (sidePanel) R.style.TvSidePanelMotion else R.style.TvDialogMotion)
    }
}
