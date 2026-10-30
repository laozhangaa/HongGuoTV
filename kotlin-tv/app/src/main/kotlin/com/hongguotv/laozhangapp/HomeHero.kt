// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.laozhangapp

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.ui.PlayerView
import com.hongguotv.core.Series
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * A wide, focusable home recommendation. The poster is deliberately absent:
 * the PlayerView is the background and the copy/actions sit above a dark
 * gradient so the recommendation remains readable while the video is muted.
 */
@androidx.media3.common.util.UnstableApi
class HomeHero(
    context: Context
): FrameLayout(context) {
    private fun dp(value: Number)= (value.toFloat()*resources.displayMetrics.density).toInt()
    private fun label(value: String,size: Float,color: Int=TvStyle.text)=TextView(context).apply {
        text=value; textSize=size; setTextColor(color); includeFontPadding=false
    }
    private fun focusStyle(view: View) {
        view.id=View.generateViewId()
        view.isFocusable=true; view.isFocusableInTouchMode=true
        fun paint(focused: Boolean) {
            TvMotion.focus(view,focused,if(focused) TvStyle.raised else Color.argb(185,24,29,38),if(focused) TvStyle.accent else TvStyle.outline)
            if(view is TextView) view.setTextColor(TvStyle.text)
        }
        paint(false); view.setOnFocusChangeListener { _,focused -> paint(focused) }
    }

    val playerView=PlayerView(context).apply {
        useController=false
        isFocusable=false
        resizeMode=androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        setShutterBackgroundColor(Color.TRANSPARENT)
        setKeepContentOnPlayerReset(false)
        setBackgroundColor(Color.rgb(7,10,15))
    }
    private val title=label("今日热映",27f).apply {
        maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END
        typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    private val meta=label("最高热度短剧 · 静音预览",13f,TvStyle.muted).apply {
        maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END
        // Keep one compact, visible focus target in the hero after removing
        // the action buttons. D-pad navigation still has an entry point, but
        // the preview itself remains a passive surface.
        id=View.generateViewId()
        tag="home:hero"
        isFocusable=true
        isFocusableInTouchMode=true
        setPadding(dp(5),dp(4),dp(5),dp(4))
        TvMotion.focus(this,false,Color.TRANSPARENT,Color.TRANSPARENT,8)
        setOnFocusChangeListener { view, focused ->
            TvMotion.focus(view,focused,
                if(focused) TvStyle.raised else Color.TRANSPARENT,
                if(focused) TvStyle.accent else Color.TRANSPARENT,8)
            setTextColor(if(focused) TvStyle.text else TvStyle.muted)
        }
    }
    private val copyPanel=LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL
        gravity=Gravity.BOTTOM
        setPadding(dp(28),dp(18),dp(28),dp(20))
    }

    /**
     * The recommendation rail is deliberately kept inside the hero. This lets
     * Android TV's normal focus search handle left/right D-pad navigation while
     * the video remains full-bleed behind the rail. `onRecommendationSelected`
     * is invoked for both focus and click, so a host can switch the preview as
     * soon as a user moves to another card.
     */
    private val recommendationScroll=HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled=false
        isFocusable=false
        clipToPadding=false
        setPadding(dp(2),dp(2),dp(8),dp(2))
        overScrollMode=View.OVER_SCROLL_NEVER
    }
    private val recommendationRow=LinearLayout(context).apply {
        orientation=LinearLayout.HORIZONTAL
        gravity=Gravity.CENTER_VERTICAL
    }
    private var recommendations: List<Series> = emptyList()
    private var recommendationCards: List<View> = emptyList()
    private var recommendationSelectedIndex: Int = -1
    private var onRecommendationSelected: ((Int) -> Unit)? = null

    private fun kindLabel(series: Series): String = when {
        series.badge.contains("漫剧") || series.tags.contains("漫剧") -> "漫剧"
        series.badge.contains("短剧") || series.tags.contains("短剧") -> "短剧"
        else -> "推荐"
    }
    private fun railCardWidthDp()=(resources.displayMetrics.widthPixels/resources.displayMetrics.density*.065f).coerceIn(72f,92f)
    private fun railCardHeightDp()=railCardWidthDp()*1.326f

    /** The first card is useful to hosts when wiring custom focus order. */
    val recommendationFirstFocus: View?
        get() = recommendationCards.firstOrNull()

    fun focusTarget(key: String): View? = recommendationCards.firstOrNull { it.tag == key }

    /**
     * The hero no longer exposes watch/favorite buttons. `meta` remains a
     * compact fallback focus target when the recommendation rail is empty.
     */
    val firstFocus: View get() = recommendationFirstFocus ?: meta
    val secondFocus: View get() = recommendationFirstFocus ?: meta

    init {
        id=View.generateViewId()
        clipToPadding=false
        addView(playerView,LayoutParams(-1,-1))
        addView(View(context).apply {
            background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(Color.argb(245,7,10,15),Color.argb(135,7,10,15),Color.argb(30,7,10,15)))
        },LayoutParams(-1,-1))
        addView(View(context).apply {
            background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.argb(10,7,10,15),Color.argb(235,7,10,15)))
        },LayoutParams(-1,-1))
        val copy=copyPanel
        copy.addView(label("今日热映  ·  热度榜",12f,TvStyle.accent).apply { typeface=Typeface.DEFAULT_BOLD })
        copy.addView(title,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
        copy.addView(meta,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
        // Playback and follow actions belong to the detail page. Keeping
        // them out of this cinematic rail removes two unnecessary D-pad
        // stops while browsing recommendations.
        // Leave a quiet lower band for the shelf overlay. The copy remains in
        // the image area, like a streaming home screen, instead of being
        // covered by the poster rails below it.
        addView(copy,LayoutParams(-1,-2,Gravity.BOTTOM).apply { bottomMargin=dp(154) })
        recommendationScroll.addView(recommendationRow,LayoutParams(-2,-1))
        // Keep the rail on the lower-right so the title and actions remain
        // readable. It is allowed to extend beyond the viewport for long
        // recommendation lists and scrolls automatically with focus.
        val railWidth=dp((resources.displayMetrics.widthPixels/resources.displayMetrics.density*.46f).coerceIn(320f,680f))
        val railInset=dp((resources.displayMetrics.widthPixels/resources.displayMetrics.density*.015f).coerceIn(10f,22f))
        addView(recommendationScroll,LayoutParams(railWidth,dp(136),Gravity.END or Gravity.BOTTOM).apply {
            rightMargin=railInset
            bottomMargin=dp(172)
        })
    }

    fun bindVideo(series: Series) {
        if(recommendationCards.isNotEmpty()) recommendationScroll.visibility=View.VISIBLE
        title.text=series.title
        meta.text=listOf("${kindLabel(series)}最高热度","静音预览",series.badge,series.tags)
            .filter { it.isNotBlank() }.joinToString("  ·  ")
        syncRecommendationSelection(series)
    }

    fun showSelection(series: Series,status: String) {
        if(recommendationCards.isNotEmpty()) recommendationScroll.visibility=View.VISIBLE
        title.text=series.title
        meta.text=listOf(status,kindLabel(series),series.badge,series.tags).flatMap { it.split('·') }.map(String::trim)
            .filter { it.isNotBlank() && !it.all(Char::isDigit) }.distinct().joinToString("  ·  ").ifBlank { "热度榜" }
        syncRecommendationSelection(series)
    }

    fun setLoading() {
        title.text="正在准备今日热映"
        meta.text="加载首页热度榜 · 静音预览"
        recommendationScroll.visibility=View.INVISIBLE
    }

    fun setVideoUnavailable() {
        meta.text="静音预览暂不可用 · 可直接观看"
    }

    /**
     * Rebuild the horizontal recommendation rail. Items are deduplicated by id
     * and cards are focusable so D-pad left/right works without a custom key
     * listener. `selectedIndex` is clamped for an empty/short response.
     */
    fun setRecommendations(items: List<Series>, selectedIndex: Int = 0, onSelected: (Int) -> Unit = {}) {
        recommendations=items.distinctBy { it.id }
        onRecommendationSelected=onSelected
        recommendationRow.removeAllViews()
        recommendationCards=if(recommendations.isEmpty()) emptyList() else recommendations.mapIndexed { index, series ->
            makeRecommendationCard(index,series)
        }
        recommendationCards.forEach { recommendationRow.addView(it) }
        recommendationScroll.visibility=if(recommendationCards.isEmpty()) View.INVISIBLE else View.VISIBLE
        recommendationSelectedIndex=selectedIndex.coerceIn(0,(recommendations.size-1).coerceAtLeast(0)).takeIf { recommendations.isNotEmpty() } ?: -1
        paintRecommendationCards()
        recommendationCards.getOrNull(recommendationSelectedIndex)?.let { card ->
            card.post { recommendationScroll.smoothScrollTo(card.left.coerceAtLeast(0),0) }
        }
    }

    private fun makeRecommendationCard(index: Int, series: Series): View {
        val cardWidth=railCardWidthDp(); val cardHeight=railCardHeightDp()
        val card=FrameLayout(context).apply {
            id=View.generateViewId()
            tag="home:hero:${series.id}"
            isFocusable=true
            isFocusableInTouchMode=true
            clipChildren=true
            clipToPadding=true
            setPadding(dp(1),dp(1),dp(1),dp(1))
            contentDescription="推荐 ${series.title}"
        }
        val poster=ImageView(context).apply {
            scaleType=ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(38,45,57))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            tag=series.cover
        }
        card.addView(poster,LayoutParams(dp(cardWidth),dp(cardHeight)))
        val shade=View(context).apply {
            background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT,Color.argb(235,7,10,15)))
            isClickable=false
        }
        card.addView(shade,LayoutParams(dp(cardWidth),dp(cardHeight)))
        val name=label(series.title,12f).apply {
            maxLines=2
            ellipsize=android.text.TextUtils.TruncateAt.END
            setShadowLayer(dp(2).toFloat(),0f,dp(1).toFloat(),Color.BLACK)
            setPadding(dp(7),0,dp(7),dp(6))
            gravity=Gravity.BOTTOM
        }
        card.addView(name,LayoutParams(dp(cardWidth),dp(cardHeight)))
        loadRecommendationCover(poster,series.cover,series.title)
        card.setOnFocusChangeListener { _,focused ->
            if(focused) {
                recommendationSelectedIndex=index
                recommendationScroll.post { ensureCardVisible(card) }
                onRecommendationSelected?.invoke(index)
            }
            paintRecommendationCards()
        }
        card.setOnClickListener {
            recommendationSelectedIndex=index
            paintRecommendationCards()
            onRecommendationSelected?.invoke(index)
        }
        card.layoutParams=LinearLayout.LayoutParams(dp(cardWidth),dp(cardHeight)).apply {
            rightMargin=dp(9)
        }
        return card
    }

    private fun ensureCardVisible(card: View) {
        val left=card.left
        val right=card.right
        val viewportLeft=recommendationScroll.scrollX
        val viewportRight=viewportLeft+recommendationScroll.width
        when {
            left<viewportLeft -> recommendationScroll.smoothScrollTo(left-dp(8),0)
            right>viewportRight -> recommendationScroll.smoothScrollTo(right-recommendationScroll.width+dp(8),0)
        }
    }

    private fun paintRecommendationCards() {
        recommendationCards.forEachIndexed { index, card ->
            val focused=card.hasFocus() || index==recommendationSelectedIndex
            TvMotion.focus(card,card.hasFocus(),Color.TRANSPARENT,if(focused) TvStyle.accent else Color.TRANSPARENT,focusedScale=1.04f)
        }
    }

    private fun syncRecommendationSelection(series: Series) {
        val index=recommendations.indexOfFirst { it.id==series.id }
        if(index<0) return
        recommendationSelectedIndex=index
        paintRecommendationCards()
    }

    private fun loadRecommendationCover(image: ImageView, cover: String, title: String) {
        image.setBackgroundColor(Color.rgb(38,45,57))
        if(cover.isBlank()) return
        recommendationImageExecutor.execute {
            val bitmap=runCatching {
                val connection=(URL(Uri.decode(cover)).openConnection() as? HttpURLConnection)?.apply {
                    connectTimeout=8_000
                    readTimeout=12_000
                    instanceFollowRedirects=true
                    useCaches=true
                } ?: return@runCatching null
                try {
                    connection.connect()
                    if(connection.responseCode !in 200..299) return@runCatching null
                    BitmapFactory.decodeStream(connection.inputStream)
                } finally { connection.disconnect() }
            }.getOrNull()
            image.post {
                if(image.tag==cover && bitmap!=null) image.setImageBitmap(bitmap)
            }
        }
    }

    companion object {
        // Shared to avoid retaining a thread pool for every short-lived hero
        // view when the user changes tabs or returns to the home screen.
        private val recommendationImageExecutor=Executors.newFixedThreadPool(2)
    }
}
