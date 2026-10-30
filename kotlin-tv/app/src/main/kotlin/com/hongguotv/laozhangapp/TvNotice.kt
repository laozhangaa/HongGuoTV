// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.laozhangapp

import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView

/** Activity-owned feedback. Never takes TV remote focus or survives leaving the app. */
internal class TvNotice(
    private val activity: Activity,
    private val anchorView: () -> View = { activity.window.decorView },
) {
    private val main=Handler(Looper.getMainLooper())
    private var popup: PopupWindow?=null
    private var pendingShow: Runnable?=null
    private var pendingDismiss: Runnable?=null
    private var revision=0L
    private var active=false

    fun resume() { active=true }
    fun pause() { active=false; dismiss() }

    fun show(message: String,loading: Boolean=false): Long {
        dismiss()
        val ticket=revision
        if(!active || activity.isFinishing || activity.isDestroyed) return ticket
        val show=Runnable {
            pendingShow=null
            // Attach above the visible account dialog so its dim layer cannot obscure feedback.
            val anchor=anchorView()
            if(ticket!=revision || !active || activity.isFinishing || activity.isDestroyed || !anchor.isAttachedToWindow) return@Runnable
            fun dp(value: Int)=TvLayout.dp(activity,value)
            val width=activity.window.decorView.width.takeIf { it>0 } ?: activity.resources.displayMetrics.widthPixels
            val inset=(width*.045f).toInt().coerceAtLeast(dp(24))
            val maxWidth=minOf(dp(400),width-2*inset).coerceAtLeast(1)
            val body=LinearLayout(activity).apply {
                orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
                setPadding(dp(18),dp(14),dp(18),dp(14)); minimumHeight=dp(54)
                background=TvStyle.shape(activity,TvStyle.raised,TvStyle.outline,10).apply { setStroke(dp(1),TvStyle.outline) }
                isFocusable=false; isClickable=false
            }
            val mark=if(loading) ProgressBar(activity).apply {
                isIndeterminate=true; indeterminateTintList=ColorStateList.valueOf(TvStyle.accent)
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            } else ImageView(activity).apply {
                setImageResource(R.drawable.app_icon)
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            body.addView(mark,LinearLayout.LayoutParams(dp(20),dp(20)).apply { marginEnd=dp(12) })
            body.addView(TextView(activity).apply {
                text=message; textSize=16f; setTextColor(TvStyle.text)
                typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                includeFontPadding=false; maxLines=3; ellipsize=TextUtils.TruncateAt.END
                setMaxWidth((maxWidth-dp(68)).coerceAtLeast(1))
                setLineSpacing(dp(3).toFloat(),1f)
                accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
                contentDescription=message
            },LinearLayout.LayoutParams(-2,-2))
            val next=PopupWindow(body,ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,false).apply {
                isTouchable=false; isOutsideTouchable=false; isClippingEnabled=true
                inputMethodMode=PopupWindow.INPUT_METHOD_NOT_NEEDED
                elevation=0f
                animationStyle=if(ValueAnimator.areAnimatorsEnabled()) R.style.TvDialogMotion else 0
            }
            popup=next
            // Below the navigation, inside the same safe gutter as the content shelves.
            next.showAtLocation(anchor,Gravity.TOP or Gravity.END,inset,dp(72))
            if(!loading) {
                val base=if(message.length>30) 5000 else 3500
                val timeout=if(Build.VERSION.SDK_INT>=29) activity.getSystemService(AccessibilityManager::class.java)
                    .getRecommendedTimeoutMillis(base,AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_ICONS) else base
                pendingDismiss=Runnable { dismiss(ticket) }.also { main.postDelayed(it,timeout.toLong()) }
            }
        }
        pendingShow=show
        // A quick request finishes without flashing a loading notice.
        main.postDelayed(show,if(loading) 240 else 0)
        return ticket
    }

    fun dismiss(ticket: Long?=null) {
        if(ticket!=null && ticket!=revision) return
        revision++
        pendingShow?.let(main::removeCallbacks); pendingShow=null
        pendingDismiss?.let(main::removeCallbacks); pendingDismiss=null
        popup?.dismiss(); popup=null
    }
}
