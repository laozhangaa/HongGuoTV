// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.laozhangapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** The prototype's home banner, backed by the same LAN service as the remote dialog. */
class HomeRemoteBanner(
    context: Context,
    private val tools: TvTools,
    private val dismiss: () -> Unit
): LinearLayout(context) {
    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
    private fun label(value: String,size: Float,color: Int=TvStyle.text)=TextView(context).apply {
        text=value; textSize=size; setTextColor(color); includeFontPadding=false
    }
    private val info=LinearLayout(context).apply { orientation=VERTICAL; gravity=Gravity.CENTER_VERTICAL }
    private val qrPaper=Color.rgb(220,224,231)
    private val qrInk=Color.rgb(20,23,29)
    private val qrCard=LinearLayout(context).apply {
        orientation=VERTICAL; gravity=Gravity.CENTER
        setPadding(dp(14),dp(14),dp(14),dp(14))
        background=TvStyle.shape(context,TvStyle.surfaceAlt,TvStyle.outline)
    }
    private val heading=label("手机扫码，变身遥控器",36f,Color.rgb(244,239,231)).apply {
        typeface=resources.getFont(R.font.home_remote_display)
        letterSpacing=.015f
    }
    private val qrImage=ImageView(context).apply {
        scaleType=ImageView.ScaleType.FIT_CENTER
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val qrPlaceholder=label("正在生成二维码…",13f,qrInk).apply { gravity=Gravity.CENTER }
    private val qrAddress=label("正在连接电视…",10f,TvStyle.muted).apply {
        gravity=Gravity.CENTER; maxLines=2
    }
    private var qrUrl: String?=null
    private var lastNetworkCheck=0L
    private var stacked=false
    private var headingSp=36f
    var onActionFocus: (String)->Unit = {}
    private fun action(value: String,key: String,primary: Boolean,click: ()->Unit)=label(value,15f).apply {
        id=View.generateViewId(); tag=key
        isFocusable=true; isFocusableInTouchMode=true
        gravity=Gravity.CENTER; minHeight=dp(44)
        setPadding(dp(22),dp(10),dp(22),dp(10))
        typeface=Typeface.DEFAULT_BOLD
        fun paint(focused: Boolean) {
            TvMotion.focus(this,focused,if(primary) TvStyle.accent else if(focused) TvStyle.raised else TvStyle.surface,
                if(focused) TvStyle.text else if(primary) TvStyle.accent else TvStyle.outline)
            setTextColor(if(primary) Color.WHITE else TvStyle.text)
        }
        paint(false)
        setOnFocusChangeListener { _,focused -> paint(focused); if(focused) onActionFocus(key) }
        setOnClickListener { click() }
    }
    private val refreshButton=action("刷新二维码","home:remote:refresh",true) {
        tools.ensurePhoneSearchServer()
        qrUrl=null
        updateConnection()
        if(tools.phoneRemoteState().url!=null) Toast.makeText(context,"二维码已刷新",Toast.LENGTH_SHORT).show()
    }
    private val dismissButton=action("稍后再说","home:remote:later",false) { dismiss() }
    val firstFocus: View get()=refreshButton
    val focusTargets: List<View> get()=listOf(refreshButton,dismissButton)
    fun focusTarget(key: String)=focusTargets.firstOrNull { it.tag==key }

    private val update=object: Runnable {
        override fun run() {
            if(!isAttachedToWindow || visibility!=VISIBLE) return
            val now=SystemClock.elapsedRealtime()
            if(now-lastNetworkCheck>=5_000) {
                lastNetworkCheck=now
                tools.ensurePhoneSearchServer()
            }
            updateConnection()
            postDelayed(this,500)
        }
    }

    init {
        id=View.generateViewId()
        orientation=HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
        setPadding(dp(28),dp(22),dp(28),dp(22))
        background=TvStyle.shape(context,TvStyle.surface)
        val tagRow=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        tagRow.addView(label("手机遥控",12f,Color.WHITE).apply {
            setPadding(dp(9),dp(3),dp(9),dp(3)); typeface=Typeface.DEFAULT_BOLD
            background=TvStyle.shape(context,TvStyle.accent,radius=4)
        })
        tagRow.addView(label("无需安装 · 浏览器即用",12f,TvStyle.muted),LayoutParams(-2,-2).apply { leftMargin=dp(8) })
        info.addView(tagRow)
        info.addView(heading,LayoutParams(-1,-2).apply { topMargin=dp(14) })
        info.addView(label("手机与电视连接同一局域网，扫码后在浏览器打开遥控页面。\n搜索剧名、浏览剧库，点选想看的内容即可在电视播放。",13f,TvStyle.muted).apply {
            setLineSpacing(dp(4).toFloat(),1f)
        },LayoutParams(-1,-2).apply { topMargin=dp(10) })
        info.addView(label("选集  ·  暂停 / 继续  ·  快进 / 快退  ·  倍速  ·  收藏与续看",12f,TvStyle.text),LayoutParams(-1,-2).apply { topMargin=dp(10) })
        val actions=LinearLayout(context)
        actions.addView(refreshButton)
        actions.addView(dismissButton,LayoutParams(-2,-2).apply { leftMargin=dp(12) })
        info.addView(actions,LayoutParams(-1,-2).apply { topMargin=dp(18) })
        addView(info,LayoutParams(0,-2,1f).apply { rightMargin=dp(32) })
        val qrArea=FrameLayout(context).apply {
            background=TvStyle.shape(context,qrPaper,radius=4)
            clipToOutline=true
        }
        qrArea.addView(qrImage,FrameLayout.LayoutParams(-1,-1))
        qrArea.addView(qrPlaceholder,FrameLayout.LayoutParams(-1,-1))
        qrCard.addView(qrArea,LayoutParams(dp(144),dp(144)))
        qrCard.addView(label("扫一扫，连接电视",12f).apply {
            typeface=Typeface.DEFAULT_BOLD; gravity=Gravity.CENTER
        },LayoutParams(-1,-2).apply { topMargin=dp(8) })
        qrCard.addView(qrAddress,LayoutParams(-1,-2).apply { topMargin=dp(5) })
        addView(qrCard,LayoutParams(dp(180),-2))
        refreshButton.nextFocusLeftId=refreshButton.id
        refreshButton.nextFocusRightId=dismissButton.id
        dismissButton.nextFocusLeftId=refreshButton.id
        dismissButton.nextFocusRightId=dismissButton.id
    }

    private fun updateConnection() {
        val state=tools.phoneRemoteState()
        val url=state.url
        if(url!=null) {
            if(qrUrl!=url) {
                val bitmap=runCatching { tools.phoneQrBitmap(url,qrInk,qrPaper) }.getOrNull()
                if(bitmap==null) {
                    qrImage.visibility=GONE; qrPlaceholder.visibility=VISIBLE
                    qrPlaceholder.text="二维码生成失败\n请刷新重试"
                    qrAddress.text=url
                    return
                }
                qrImage.setImageDrawable(BitmapDrawable(resources,bitmap).apply { isFilterBitmap=false })
                qrUrl=url
            }
            qrImage.visibility=VISIBLE; qrPlaceholder.visibility=GONE
            qrAddress.text=url
        } else {
            qrUrl=null; qrImage.setImageDrawable(null); qrImage.visibility=GONE
            qrPlaceholder.visibility=VISIBLE
            qrPlaceholder.text=if(state.loading) "正在生成二维码…" else "暂时无法连接\n请刷新重试"
            qrAddress.text=state.error ?: "正在打开手机遥控…"
        }
    }

    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)/resources.displayMetrics.density
        val nextStacked=width<560 || resources.configuration.fontScale>1.4f && width<760
        if(nextStacked!=stacked) {
            stacked=nextStacked; orientation=if(stacked) VERTICAL else HORIZONTAL
            info.layoutParams=if(stacked) LayoutParams(-1,-2) else LayoutParams(0,-2,1f).apply { rightMargin=dp(32) }
            qrCard.layoutParams=LayoutParams(dp(180),-2).apply { if(stacked) { gravity=Gravity.CENTER_HORIZONTAL; topMargin=dp(20) } }
        }
        val headingSize=if(stacked || width<850) 28f else 36f
        if(headingSp!=headingSize) { headingSp=headingSize; heading.textSize=headingSize }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if(visibility==VISIBLE) { tools.ensurePhoneSearchServer(); post(update) }
    }
    override fun onDetachedFromWindow() { removeCallbacks(update); super.onDetachedFromWindow() }
}
