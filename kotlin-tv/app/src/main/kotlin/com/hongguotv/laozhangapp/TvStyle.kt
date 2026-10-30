// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.laozhangapp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Shared native colors and shapes; flat surfaces keep the TV UI readable at a distance. */
object TvStyle {
    // Palette follows the supplied prototype: neutral slate surfaces keep
    // posters and the red brand accent in focus without heavy gradients.
    val background=Color.rgb(15,17,21)
    val surface=Color.rgb(29,32,39)
    val surfaceAlt=Color.rgb(39,43,52)
    val raised=Color.rgb(39,43,52)
    val accent=Color.rgb(229,72,61)
    val accentSoft=Color.rgb(39,43,52)
    val text=Color.rgb(242,243,245)
    val muted=Color.rgb(166,172,182)
    val outline=Color.rgb(59,63,73)
    const val POSTER_ASPECT=.7f
    fun shape(context: Context,color: Int,border: Int=Color.TRANSPARENT,radius: Int=6)=GradientDrawable().apply {
        setColor(color); cornerRadius=radius*context.resources.displayMetrics.density
        setStroke((2*context.resources.displayMetrics.density).toInt(),border)
    }
    fun card(context: Context,focused: Boolean)=shape(context,Color.TRANSPARENT,if(focused) accent else Color.TRANSPARENT,6)
    fun input(view: android.widget.EditText) {
        val density=view.resources.displayMetrics.density
        fun dp(value: Int)=(value*density).toInt()
        view.setTextColor(text); view.setHintTextColor(muted); view.textSize=16f
        view.minHeight=dp(48); view.setPadding(dp(14),dp(10),dp(14),dp(10))
        fun paint(focused: Boolean) { TvMotion.focus(view,focused,background,if(focused) accent else outline,8,focusedScale=1f) }
        paint(view.hasFocus()); view.setOnFocusChangeListener { _,focused -> paint(focused) }
    }
    fun checkbox(view: android.widget.CheckBox) {
        view.setTextColor(text); view.textSize=15f
        view.buttonTintList=android.content.res.ColorStateList.valueOf(accent)
        val density=view.resources.displayMetrics.density
        view.setPadding((8*density).toInt(),(8*density).toInt(),(8*density).toInt(),(8*density).toInt())
        TvMotion.focus(view,view.hasFocus(),if(view.hasFocus()) raised else Color.TRANSPARENT,if(view.hasFocus()) accent else Color.TRANSPARENT)
        view.setOnFocusChangeListener { _,focused -> TvMotion.focus(view,focused,if(focused) raised else Color.TRANSPARENT,if(focused) accent else Color.TRANSPARENT) }
    }
}
