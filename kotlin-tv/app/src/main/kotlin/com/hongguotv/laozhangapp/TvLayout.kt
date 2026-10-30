// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.laozhangapp

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/** Layout limits use the current window, including a smaller multi-window viewport. */
internal object TvLayout {
    fun dp(context: Context,value: Number)=(value.toFloat()*context.resources.displayMetrics.density).toInt()
    fun windowHeight(activity: Activity): Int {
        val decor=activity.window.decorView
        val visible=Rect().also(decor::getWindowVisibleDisplayFrame)
        return listOf(activity.resources.displayMetrics.heightPixels,decor.height,visible.height())
            .filter { it>0 }.minOrNull() ?: 1
    }
    fun dialogWidth(activity: Activity,maxDp: Int=720)=minOf(dp(activity,maxDp),(activity.resources.displayMetrics.widthPixels*.88f).toInt())
    fun columns(context: Context,width: Int,minWidthDp: Int,max: Int,gapDp: Int=14): Int {
        val gap=dp(context,gapDp)
        val minimum=dp(context,minWidthDp)*context.resources.configuration.fontScale.coerceAtLeast(1f)
        return ((width+gap)/(minimum+gap)).toInt().coerceIn(1,max)
    }
}

/** A weighted body can shrink while the title and footer keep their measured space. */
@SuppressLint("ViewConstructor") // Activity-owned modal, never inflated from XML.
internal class TvDialogLayout(private val activity: Activity): LinearLayout(activity) {
    init { orientation=VERTICAL }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val limit=(TvLayout.windowHeight(activity)-TvLayout.dp(activity,32)).coerceAtLeast(1)
        val parent=MeasureSpec.getSize(heightMeasureSpec)
        val mode=MeasureSpec.getMode(heightMeasureSpec)
        val height=if(mode==MeasureSpec.UNSPECIFIED) limit else minOf(parent,limit)
        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(height,if(mode==MeasureSpec.EXACTLY) mode else MeasureSpec.AT_MOST))
    }
}

@SuppressLint("ViewConstructor") // Requires its host window to calculate available space.
internal class TvDialogScroll(private val activity: Activity,private val maxDp: Int=410): ScrollView(activity) {
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val limit=minOf(TvLayout.dp(activity,maxDp),(TvLayout.windowHeight(activity)*.65f).toInt()).coerceAtLeast(1)
        val mode=MeasureSpec.getMode(heightMeasureSpec)
        val height=if(mode==MeasureSpec.UNSPECIFIED) limit else minOf(limit,MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(height,if(mode==MeasureSpec.EXACTLY) mode else MeasureSpec.AT_MOST))
    }
}

/** Buttons retain their text size and wrap into another row when space runs out. */
internal class TvFlowLayout @JvmOverloads constructor(context: Context,var gravity: Int=Gravity.START): ViewGroup(context) {
    private data class Line(val views: MutableList<View> = mutableListOf(),var width: Int=0,var height: Int=0)
    private val lines=mutableListOf<Line>()
    private var lineCount=0
    private val rowGap=TvLayout.dp(context,8)
    private fun lineAt(index: Int): Line {
        while(lines.size<=index) lines+=Line()
        return lines[index]
    }
    override fun generateDefaultLayoutParams()=MarginLayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(params: LayoutParams)=MarginLayoutParams(params)
    override fun checkLayoutParams(params: LayoutParams)=params is MarginLayoutParams
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        lines.forEach { it.views.clear(); it.width=0; it.height=0 }; lineCount=0
        val available=if(MeasureSpec.getMode(widthMeasureSpec)==MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else (MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight).coerceAtLeast(0)
        var line=lineAt(0)
        var state=0
        for(index in 0 until childCount) {
            val child=getChildAt(index)
            if(child.visibility==GONE) continue
            measureChildWithMargins(child,widthMeasureSpec,0,heightMeasureSpec,0)
            val params=child.layoutParams as MarginLayoutParams
            val width=child.measuredWidth+params.leftMargin+params.rightMargin
            val height=child.measuredHeight+params.topMargin+params.bottomMargin
            if(line.views.isNotEmpty() && line.width+width>available) { lineCount++; line=lineAt(lineCount) }
            line.views+=child; line.width+=width; line.height=maxOf(line.height,height)
            state=combineMeasuredStates(state,child.measuredState)
        }
        if(line.views.isNotEmpty()) lineCount++
        var contentWidth=0
        var contentHeight=rowGap*(lineCount-1).coerceAtLeast(0)
        for(index in 0 until lineCount) { contentWidth=maxOf(contentWidth,lines[index].width); contentHeight+=lines[index].height }
        setMeasuredDimension(resolveSizeAndState(contentWidth+paddingLeft+paddingRight,widthMeasureSpec,state),
            resolveSizeAndState(contentHeight+paddingTop+paddingBottom,heightMeasureSpec,state shl MEASURED_HEIGHT_STATE_SHIFT))
    }
    @SuppressLint("RtlHardcoded") // START/END are resolved to an absolute edge before laying out each row.
    override fun onLayout(changed: Boolean,left: Int,top: Int,right: Int,bottom: Int) {
        val available=width-paddingLeft-paddingRight
        val align=Gravity.getAbsoluteGravity(gravity,layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK
        val rtl=layoutDirection==LAYOUT_DIRECTION_RTL
        var y=paddingTop
        for(index in 0 until lineCount) {
            val line=lines[index]
            val offset=when(align) { Gravity.RIGHT -> available-line.width; Gravity.CENTER_HORIZONTAL -> (available-line.width)/2; else -> 0 }
            val start=paddingLeft+offset
            var x=if(rtl) start+line.width else start
            line.views.forEach { child ->
                val params=child.layoutParams as MarginLayoutParams
                val childTop=y+params.topMargin
                if(rtl) {
                    x-=params.rightMargin
                    child.layout(x-child.measuredWidth,childTop,x,childTop+child.measuredHeight)
                    x-=child.measuredWidth+params.leftMargin
                } else {
                    x+=params.leftMargin
                    child.layout(x,childTop,x+child.measuredWidth,childTop+child.measuredHeight)
                    x+=child.measuredWidth+params.rightMargin
                }
            }
            y+=line.height+rowGap
        }
    }
}
