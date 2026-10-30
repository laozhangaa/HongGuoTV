// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.laozhangapp

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Bounded, scrollable TV modal with shared focus, selection and footer styling. */
class TvDialog private constructor(private val host: Activity) : Dialog(host) {
    companion object {
        const val BUTTON_POSITIVE=DialogInterface.BUTTON_POSITIVE
        const val BUTTON_NEGATIVE=DialogInterface.BUTTON_NEGATIVE
        const val BUTTON_NEUTRAL=DialogInterface.BUTTON_NEUTRAL

        fun button(activity: Activity,label: String,selected: Boolean=false,action: ()->Unit): TextView = TextView(activity).apply {
            val density=resources.displayMetrics.density
            fun dp(n: Int)=(n*density).toInt()
            id=View.generateViewId(); text=label; textSize=15f; gravity=Gravity.CENTER
            minHeight=dp(44); setPadding(dp(14),dp(10),dp(14),dp(10))
            isFocusable=true; isFocusableInTouchMode=true; isClickable=true; isSelected=selected
            fun paint(focused: Boolean) {
                setTextColor(if(focused) TvStyle.background else if(selected) TvStyle.accent else TvStyle.text)
                TvMotion.focus(this,focused,if(focused) TvStyle.accent else if(selected) TvStyle.raised else TvStyle.background,
                    if(focused || selected) TvStyle.accent else TvStyle.outline,9)
            }
            paint(false); setOnFocusChangeListener { _,focused -> paint(focused) }
            setOnClickListener { action() }
        }
    }
    private val actions=mutableMapOf<Int,TextView>()
    private val choices=mutableListOf<TextView>()
    private val anchor=host.currentFocus
    private var bodyView: View?=null
    private var initialChoice=-1
    private fun dp(n: Int)=(n*host.resources.displayMetrics.density).toInt()
    fun getButton(which: Int): TextView=actions.getValue(which)
    fun focusChoice(index: Int=initialChoice) { (choices.getOrNull(index) ?: choices.firstOrNull())?.requestFocus() }
    override fun show() {
        TvMotion.dialog(this)
        super.show()
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(.64f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setLayout(TvLayout.dialogWidth(host),WindowManager.LayoutParams.WRAP_CONTENT)
        }
        if(choices.isNotEmpty()) focusChoice()
        else (bodyView?.getFocusables(View.FOCUS_FORWARD)?.firstOrNull() ?: actions[BUTTON_NEGATIVE] ?: actions.values.firstOrNull())?.requestFocus()
    }
    override fun dismiss() {
        val restore=isShowing
        super.dismiss()
        if(restore) anchor?.post {
            if(!host.isFinishing && !host.isDestroyed && host.hasWindowFocus() && host.currentFocus==null && anchor.isAttachedToWindow) anchor.requestFocus()
        }
    }

    class Builder(private val activity: Activity) {
        private var title=""
        private var message: String?=null
        private var body: View?=null
        private var labels: List<String>?=null
        private var selected=-1
        private var onChoice: ((DialogInterface,Int)->Unit)?=null
        private val buttons=linkedMapOf<Int,Pair<String,((DialogInterface,Int)->Unit)?>>()
        fun setTitle(value: String)=apply { title=value }
        fun setMessage(value: String)=apply { message=value }
        fun setView(value: View)=apply { body=value }
        fun setItems(values: Array<String>,action: (DialogInterface,Int)->Unit)=apply { labels=values.toList(); onChoice=action }
        fun setSingleChoiceItems(values: Array<String>,checked: Int,action: (DialogInterface,Int)->Unit)=apply { labels=values.toList(); selected=checked; onChoice=action }
        fun setPositiveButton(label: String,action: ((DialogInterface,Int)->Unit)?)=apply { buttons[BUTTON_POSITIVE]=label to action }
        fun setNegativeButton(label: String,action: ((DialogInterface,Int)->Unit)?)=apply { buttons[BUTTON_NEGATIVE]=label to action }
        fun setNeutralButton(label: String,action: ((DialogInterface,Int)->Unit)?)=apply { buttons[BUTTON_NEUTRAL]=label to action }
        fun show()=create().also { it.show() }
        fun create(): TvDialog {
            val dialog=TvDialog(activity)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setTitle(title)
            val root=TvDialogLayout(activity).apply {
                setPadding(dialog.dp(26),dialog.dp(22),dialog.dp(26),dialog.dp(18))
                background=TvStyle.shape(activity,TvStyle.surface,TvStyle.outline,18)
            }
            root.addView(TextView(activity).apply {
                text=title; textSize=23f; setTextColor(TvStyle.text); setTypeface(null,Typeface.BOLD)
                setPadding(0,0,0,dialog.dp(16))
            },LinearLayout.LayoutParams(-1,-2))
            val content=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
            message?.let { content.addView(TextView(activity).apply {
                text=it; textSize=16f; setTextColor(TvStyle.muted); setLineSpacing(dialog.dp(5).toFloat(),1f)
                setPadding(0,0,0,dialog.dp(10))
            }) }
            body?.let { content.addView(it); dialog.bodyView=it }
            labels?.let { items ->
                val maxColumns=if(items.size>8 && items.all { it.length<=14 }) 3 else if(items.size>4 && items.all { it.length<=22 }) 2 else 1
                val columns=TvLayout.columns(activity,TvLayout.dialogWidth(activity)-dialog.dp(52),170,maxColumns,6)
                items.withIndex().toList().chunked(columns).forEach { row ->
                    val line=LinearLayout(activity)
                    row.forEach { (index,label) ->
                        val cell=button(activity,(if(index==selected) "✓  " else "")+label,index==selected) {
                            // Dismiss before invoking: the callback may open another modal.
                            dialog.dismiss(); onChoice?.invoke(dialog,index)
                        }
                        dialog.choices+=cell
                        line.addView(cell,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dialog.dp(3),dialog.dp(3),dialog.dp(3),dialog.dp(3)) })
                    }
                    repeat(columns-row.size) { line.addView(View(activity),LinearLayout.LayoutParams(0,1,1f)) }
                    content.addView(line)
                }
            }
            val scroll=TvDialogScroll(activity).apply { isFillViewport=false; addView(content); clipToPadding=false }
            root.addView(scroll,LinearLayout.LayoutParams(-1,-2,1f))
            if(buttons.isNotEmpty()) {
                root.addView(View(activity).apply { setBackgroundColor(TvStyle.outline) },LinearLayout.LayoutParams(-1,dialog.dp(1)).apply { topMargin=dialog.dp(18); bottomMargin=dialog.dp(14) })
                val footer=TvFlowLayout(activity,Gravity.END)
                listOf(BUTTON_NEUTRAL,BUTTON_NEGATIVE,BUTTON_POSITIVE).forEach { which -> buttons[which]?.let { (label,action) ->
                    val cell=button(activity,label,which==BUTTON_POSITIVE) { dialog.dismiss(); action?.invoke(dialog,which) }
                    dialog.actions[which]=cell
                    footer.addView(cell,LinearLayout.LayoutParams(-2,-2).apply { leftMargin=dialog.dp(8) })
                } }
                root.addView(footer,LinearLayout.LayoutParams(-1,-2))
            }
            dialog.initialChoice=selected
            dialog.setContentView(root)
            return dialog
        }
    }
}
