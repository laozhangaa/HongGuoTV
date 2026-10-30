// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.laozhangapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.hongguotv.core.Series

/** Fixed text slots keep D-pad movement from resizing the content below the preview. */
class SeriesPreview(context: Context): LinearLayout(context) {
    private fun label(size: Float,color: Int)=TextView(context).apply { textSize=size; setTextColor(color); includeFontPadding=false }
    private val title=label(24f,TvStyle.text).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) }
    private val meta=label(12f,TvStyle.muted).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END }
    init {
        orientation=VERTICAL
        val dp=resources.displayMetrics.density
        setPadding(0,(10*dp).toInt(),0,(14*dp).toInt())
        addView(title)
        addView(meta,LayoutParams(-1,-2).apply { topMargin=(6*dp).toInt() })
        show(null,"")
    }
    fun show(series: Series?,status: String) {
        val nextTitle=series?.title ?: "下一段好故事，从这里开始"
        val nextMeta=if(series==null) "浏览片库，选择你想看的剧" else listOf(status,series.badge,series.tags).flatMap { it.split('·') }.map(String::trim).filter { it.isNotBlank() && !it.all(Char::isDigit) }.distinct().joinToString("  /  ")
        if(title.text.toString()!=nextTitle) title.text=nextTitle
        if(meta.text.toString()!=nextMeta) meta.text=nextMeta
    }
}

/** Bounded horizontal shelves with stable D-pad selection and per-row scroll positions. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HomeScreen(
    context: Context,
    private val top: View,
    private val loadCover: (ImageView,String)->Unit,
    private val selected: (Series,String)->Unit,
    private val open: (Series,Boolean)->Unit,
    private val actions: (Series,View)->Unit,
    private val remember: (String)->Unit
): LinearLayout(context) {
    data class Entry(val series: Series,val label: String,val resume: Boolean=false,val progress: Int?=null)
    data class Shelf(val key: String,val title: String,val entries: List<Entry>,val more: (() -> Unit)?=null)
    data class Position(val focus: String,val shelf: String?,val column: Int,val row: Int,val vertical: Int,val horizontal: Map<String,Int>)
    private data class Card(val key: String,val view: LinearLayout,val image: ImageView?,val title: TextView,val label: TextView,val progress: ProgressBar?,var entry: Entry?,var pending: Boolean=false)
    private data class Row(val key: String,val block: LinearLayout,val horizontal: HorizontalScrollView,val cards: List<Card>)
    private val accent=TvStyle.accent
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun sideInsetPx(): Int = dp(((resources.displayMetrics.widthPixels / resources.displayMetrics.density) * .045f).coerceIn(36f,84f).toInt())
    private val shelfInset get()=dp(2)
    private val posterInset get()=dp(3)
    private val shelfGap get()=dp(14)
    private fun shelfWidth()=(scroll.width.takeIf { it>0 } ?: ((width.takeIf { it>0 } ?: resources.displayMetrics.widthPixels)-paddingLeft-paddingRight)).coerceAtLeast(1)
    private fun cardWidth(viewport: Int,index: Int): Int {
        val count=TvLayout.columns(context,viewport-shelfInset*2,120,6)
        val available=(viewport-shelfInset*2-shelfGap*(count-1)).coerceAtLeast(count)
        return available/count + if(index%count<available%count) 1 else 0
    }
    private fun fitShelfWidths() {
        rows.forEach { row -> row.cards.forEachIndexed { index,card ->
            val params=card.view.layoutParams as LayoutParams
            val target=cardWidth(row.horizontal.width.takeIf { it>0 } ?: shelfWidth(),index)
            if(params.width!=target) card.view.layoutParams=params.apply { width=target }
        } }
    }
    // The shelf stays visually flat even when focused: the outline and a
    // small scale change give TV users a clear target without turning the
    // whole poster into a heavy raised tile.
    private fun shape(focus: Boolean)=TvStyle.shape(
        context,
        Color.TRANSPARENT,
        if(focus) accent else Color.TRANSPARENT,
        6
    )
    private fun actionFocus(view: View,focused: Boolean)=TvMotion.focus(view,focused,if(focused) TvStyle.raised else TvStyle.surfaceAlt,if(focused) TvStyle.accent else TvStyle.outline)
    private fun label(value: String,size: Float,color: Int=TvStyle.text)=TextView(context).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
    private val scroll=ScrollView(context).apply { isVerticalScrollBarEnabled=false; clipToPadding=false }
    private fun topFocus(): View? = if(top.visibility!=VISIBLE) null else when(top) {
        is HomeRemoteBanner -> top.firstFocus
        is HomeHero -> top.firstFocus
        else -> null
    }
    private fun topFocusTarget(key: String): View? = if(top.visibility!=VISIBLE) null else when(top) {
        is HomeRemoteBanner -> top.focusTarget(key)
        is HomeHero -> top.focusTarget(key)
        else -> null
    }
    private fun updateHeroHeight() {
        if(top !is HomeHero) return
        val viewport=scroll.height.takeIf { it>0 }
            ?: height.takeIf { it>0 }
            ?: (resources.displayMetrics.heightPixels*resources.displayMetrics.density).toInt()
        val params=top.layoutParams as? LayoutParams ?: return
        val targetHeight=(viewport*.62f).toInt().coerceAtLeast(dp(300))
        if(params.height!=targetHeight) {
            params.height=targetHeight
            top.layoutParams=params
        }
    }
    private val contents=LinearLayout(context).apply { orientation=VERTICAL }
    private val rows=mutableListOf<Row>()
    private val cards=linkedMapOf<String,Card>()
    private var shelves=emptyList<Shelf>()
    private var footerMore: (() -> Unit)?=null
    private var moreButton: View?=null
    private var lastFocus=""
    private var lastRow: String?=null
    private var restoring=false
    private var revision=0
    private var showingLoading=false
    val refresh=label("正在更新热门…",13f).apply { id=View.generateViewId(); tag="home:refresh"; isFocusable=true; isFocusableInTouchMode=true; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(14),dp(9),dp(14),dp(9)); actionFocus(this,false); setOnFocusChangeListener { _,f -> actionFocus(this,f); if(f) lastFocus=tag.toString() } }
    init {
        orientation=VERTICAL
        val inset=sideInsetPx()
        setPadding(inset,0,inset,0)
        scroll.addView(contents)
        addView(scroll,LayoutParams(-1,0,1f))
        (top as? HomeRemoteBanner)?.onActionFocus={ key ->
            lastFocus=key; remember(key)
            if(!restoring) scroll.post { scroll.smoothScrollTo(0,0) }
        }
    }

    fun capturePosition(): Position {
        val key=findFocus()?.tag?.toString()?.takeIf {
            it in cards || it=="home:refresh" || it=="home:more" || it=="home:hero" || it.startsWith("home:hero:") || it.startsWith("home:remote:")
        } ?: lastFocus
        val row=rows.indexOfFirst { item -> item.cards.any { it.key==key } }
        val column=rows.getOrNull(row)?.cards?.indexOfFirst { it.key==key } ?: 0
        return Position(key,rows.getOrNull(row)?.key,column.coerceAtLeast(0),row.coerceAtLeast(0),scroll.scrollY,rows.associate { it.key to it.horizontal.scrollX })
    }

    /** Rebuild only this bounded shelf area, preserving the old card or its nearest neighbour. */
    fun updateShelves(next: List<Shelf>,requestFocus: Boolean=hasFocus(),animate: Boolean=false) {
        if(!showingLoading && shelves.map { Triple(it.key,it.title,it.entries) }==next.map { Triple(it.key,it.title,it.entries) }) return
        val position=capturePosition()
        render(next,position.focus,requestFocus,footerMore,position)
        if(animate) rows.firstOrNull { it.key=="hot" }?.block?.let(TvMotion::contentReady)
    }

    /** Update an existing card without replacing views or moving focus. Structural changes use updateShelves. */
    fun updateEntry(shelfKey: String,entry: Entry): Boolean {
        val card=cards["$shelfKey:${entry.series.id}"] ?: return false
        if(card.entry?.series?.cover!=entry.series.cover) { card.image?.setImageDrawable(null) }
        card.entry=entry; card.title.text=entry.series.title; card.label.text=entry.label
        card.progress?.apply { progress=entry.progress ?: 0; visibility=if(entry.progress==null) INVISIBLE else VISIBLE }
        card.view.contentDescription="${shelves.firstOrNull { it.key==shelfKey }?.title.orEmpty()}，${entry.series.title}，${entry.label}"
        shelves=shelves.map { shelf -> if(shelf.key==shelfKey) shelf.copy(entries=shelf.entries.map { if(it.series.id==entry.series.id) entry else it }) else shelf }
        if(lastFocus==card.key) refreshSelection()
        loadNearbySelection()
        return true
    }

    fun refreshSelection() { cards[lastFocus]?.entry?.let { selected(it.series,it.label) } }

    /** Retry visible cover requests after the owner cancels work for a page or lifecycle change. */
    fun reloadArtwork() {
        loadNearbySelection()
    }

    fun render(shelves: List<Shelf>,focus: String,requestFocus: Boolean,more: (() -> Unit)?,position: Position?=null,loading: Boolean=false) {
        showingLoading=loading
        val previous=position ?: if(rows.isNotEmpty()) capturePosition() else null
        this.shelves=shelves; footerMore=more; revision++; val ticket=revision; restoring=true
        contents.removeAllViews(); rows.clear(); cards.clear(); moreButton=null; lastRow=null
        val topParams=if(top is HomeHero) {
            val heroHeight=(((scroll.height.takeIf { it>0 } ?: height.takeIf { it>0 }
                ?: (resources.displayMetrics.heightPixels*resources.displayMetrics.density).toInt())*.62f).toInt()).coerceAtLeast(dp(300))
            LayoutParams(-1,heroHeight)
        } else LayoutParams(-1,-2)
        contents.addView(top,topParams)
        if(top is HomeRemoteBanner) top.layoutParams=(top.layoutParams as LayoutParams).apply {
            topMargin=dp(16); bottomMargin=dp(20)
            leftMargin=shelfInset+posterInset; rightMargin=shelfInset+posterInset
        }
        shelves.filter { it.entries.isNotEmpty() || it.more!=null }.forEachIndexed { shelfIndex,shelf ->
            val block=LinearLayout(context).apply { orientation=VERTICAL }
            val heading=LinearLayout(context).apply { orientation=HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(shelfInset+posterInset,dp(8),shelfInset+posterInset,dp(10)) }
            heading.addView(View(context).apply { background=TvStyle.shape(context,accent,radius=2) },LayoutParams(dp(5),dp(24)).apply { rightMargin=dp(11) })
            heading.addView(label(shelf.title,24f).apply { setTypeface(null,Typeface.BOLD) },LayoutParams(0,-2,1f))
            if(shelfIndex==0) heading.addView(label("左右浏览  ·  上下切换",10f,TvStyle.muted))
            block.addView(heading)
            // Six cards fill the same visual edges as the banner. The next
            // card starts beyond the gap; symmetric padding protects focus rings.
            val horizontal=HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled=false
                clipToPadding=false
                setPadding(shelfInset,dp(3),shelfInset,dp(3))
                overScrollMode=View.OVER_SCROLL_NEVER
            }
            val line=LinearLayout(context).apply { orientation=HORIZONTAL; gravity=Gravity.TOP }
            horizontal.addView(line); block.addView(horizontal)
            val rowCards=mutableListOf<Card>()
            val viewportWidth=shelfWidth()
            val totalCards=shelf.entries.size+if(shelf.more!=null) 1 else 0
            fun addCard(entry: Entry?,moreAction: (() -> Unit)?=null) {
                val width=cardWidth(viewportWidth,rowCards.size)
                val key=if(entry!=null) "${shelf.key}:${entry.series.id}" else "${shelf.key}:more"
                val view=LinearLayout(context).apply { orientation=VERTICAL; id=View.generateViewId(); tag=key; isFocusable=true; isFocusableInTouchMode=true; setPadding(posterInset,dp(3),posterInset,dp(7)); background=shape(false) }
                TvMotion.focus(view,false,Color.TRANSPARENT,Color.TRANSPARENT,focusedScale=1.015f)
                val image=if(entry!=null) ImageView(context).apply {
                    scaleType=ImageView.ScaleType.CENTER_CROP
                    background=TvStyle.shape(context,TvStyle.surface,radius=6)
                    clipToOutline=true
                    importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
                } else null
                view.addView(image ?: label("›",36f).apply { gravity=Gravity.CENTER },LayoutParams(-1,((width-posterInset*2)/TvStyle.POSTER_ASPECT).toInt()))
                val title=label(entry?.series?.title ?: "查看全部",15f).apply { minLines=2; maxLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(2),dp(7),dp(2),0) }
                val badge=label(entry?.label ?: shelf.title,12f,TvStyle.muted).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(2),dp(3),0,0) }
                val progress=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; progress=entry?.progress ?: 0; visibility=if(entry?.progress==null) INVISIBLE else VISIBLE; progressTintList=android.content.res.ColorStateList.valueOf(accent); progressBackgroundTintList=android.content.res.ColorStateList.valueOf(TvStyle.outline) }
                view.addView(title); view.addView(badge); view.addView(progress,LayoutParams(-1,dp(3)).apply { topMargin=dp(4) })
                val card=Card(key,view,image,title,badge,progress,entry)
                view.contentDescription=entry?.let { "${shelf.title}，${it.series.title}，${it.label}" } ?: "查看全部${shelf.title}"
                view.setOnClickListener { lastFocus=key; remember(key); card.entry?.let { open(it.series,it.resume) } ?: moreAction?.invoke() }
                if(entry!=null) {
                    view.setOnLongClickListener { lastFocus=key; remember(key); card.entry?.let { actions(it.series,view) }; true }
                    view.setOnKeyListener { _,code,event -> if(code==KeyEvent.KEYCODE_MENU) { if(event.action==KeyEvent.ACTION_UP) { lastFocus=key; remember(key); card.entry?.let { actions(it.series,view) } }; true } else false }
                }
                view.setOnFocusChangeListener { _,focused ->
                    TvMotion.focus(view,focused,Color.TRANSPARENT,if(focused) accent else Color.TRANSPARENT,focusedScale=1.015f)
                    if(focused) {
                        lastFocus=key; remember(key); card.entry?.let { selected(it.series,it.label) }
                        if(!restoring) {
                            loadNearbySelection()
                            if(lastRow!=shelf.key) scroll.post { if(revision==ticket && view.hasFocus()) reveal(block) }
                        }
                        lastRow=shelf.key
                    }
                }
                line.addView(view,LayoutParams(width,-1).apply { rightMargin=if(rowCards.size<totalCards-1) shelfGap else 0 }); rowCards+=card; cards[key]=card
            }
            shelf.entries.forEach { addCard(it) }
            shelf.more?.let { action -> addCard(null,action) }
            contents.addView(block,LayoutParams(-1,-2).apply { bottomMargin=dp(16) }); rows+=Row(shelf.key,block,horizontal,rowCards)
        }
        if(loading && shelves.none { it.key=="hot" && it.entries.isNotEmpty() })
            contents.addView(TvLoading(context,"正在加载热门剧集…"),LayoutParams(-1,dp(310)))
        else if(cards.isEmpty()) contents.addView(label("还没有本机记录，热门剧加载后可选择观看。",15f).apply { setPadding(0,dp(16),0,dp(16)) })
        // Keep the refresh action compact so it reads as a utility control
        // instead of another full-width content card.
        contents.addView(refresh,LayoutParams(dp(190),-2).apply { topMargin=dp(10) })
        val footer=mutableListOf<View>(refresh)
        if(more!=null) {
            val next=label("更多热门  ›",14f).apply { id=View.generateViewId(); tag="home:more"; isFocusable=true; isFocusableInTouchMode=true; setPadding(dp(14),dp(10),dp(14),dp(10)); actionFocus(this,false); setOnFocusChangeListener { _,f -> actionFocus(this,f); if(f) lastFocus=tag.toString() }; setOnClickListener { more() } }
            contents.addView(next,LayoutParams(-1,-2).apply { topMargin=dp(8) }); footer+=next; moreButton=next
        }
        rows.forEachIndexed { i,row -> row.cards.forEachIndexed { j,card ->
            card.view.nextFocusLeftId=row.cards[(j-1).coerceAtLeast(0)].view.id; card.view.nextFocusRightId=row.cards[(j+1).coerceAtMost(row.cards.lastIndex)].view.id
            card.view.nextFocusUpId=if(i==0) topFocus()?.id ?: nextFocusUpId else rows[i-1].cards[j.coerceAtMost(rows[i-1].cards.lastIndex)].view.id
            card.view.nextFocusDownId=if(i==rows.lastIndex) refresh.id else rows[i+1].cards[j.coerceAtMost(rows[i+1].cards.lastIndex)].view.id
        } }
        footer.forEachIndexed { i,v -> v.nextFocusUpId=if(i==0) rows.lastOrNull()?.cards?.firstOrNull()?.view?.id ?: topFocus()?.id ?: nextFocusUpId else footer[i-1].id; v.nextFocusDownId=footer.getOrNull(i+1)?.id ?: v.id }
        top.nextFocusDownId=firstShelfId()
        (top as? HomeHero)?.recommendationFirstFocus?.nextFocusDownId=firstShelfId()
        (top as? HomeRemoteBanner)?.focusTargets?.forEach { control ->
            control.nextFocusUpId=nextFocusUpId
            control.nextFocusDownId=firstShelfId()
        }
        val preferred=focus.ifBlank { previous?.focus.orEmpty() }
        val target=if(preferred.isBlank() && previous==null) topFocus() ?: target(preferred,previous)
            else target(preferred,previous)
        if(requestFocus) target.requestFocus()
        post layout@{
            if(revision!=ticket) return@layout
            fitShelfWidths(); fitArtwork()
            post restore@{
                if(revision!=ticket) return@restore
                if(previous!=null && previous.focus.isNotBlank() && !preferred.startsWith("home:hero") && !preferred.startsWith("home:remote:")) restoreOffsets(previous)
                else scroll.scrollTo(0,0)
                restoring=false
                updateHeroHeight()
                if(requestFocus && !target.hasFocus()) target.requestFocus()
                if(requestFocus && previous==null) rows.firstOrNull { row -> row.cards.any { it.view===target } }?.let { revealSelection(it) }
                loadNearbySelection()
            }
        }
    }

    fun restorePosition(position: Position,requestFocus: Boolean=true) {
        restoring=true
        if(requestFocus) target(position.focus,position).requestFocus()
        post { restoreOffsets(position); restoring=false; loadNearbySelection() }
    }

    private fun target(key: String,position: Position?): View {
        if(key=="home:refresh") return refresh
        if(key=="home:more") moreButton?.let { return it }
        if(key.startsWith("home:remote:")) return topFocusTarget(key) ?: topFocus() ?: cards.values.firstOrNull()?.view ?: refresh
        if(key=="home:hero") return (top as? HomeHero)?.firstFocus ?: refresh
        if(key.startsWith("home:hero:")) return (top as? HomeHero)?.focusTarget(key) ?: (top as? HomeHero)?.firstFocus ?: refresh
        if(key.isBlank()) topFocus()?.let { return it }
        cards[key]?.let { return it.view }
        if(position!=null) {
            val row=rows.firstOrNull { it.key==position.shelf } ?: rows.getOrNull(position.row.coerceAtMost(rows.lastIndex))
            row?.cards?.getOrNull(position.column.coerceAtMost(row.cards.lastIndex))?.let { return it.view }
        }
        return cards.values.firstOrNull()?.view ?: refresh
    }
    private fun restoreOffsets(position: Position) {
        rows.forEach { row -> row.horizontal.scrollTo(position.horizontal[row.key] ?: 0,0) }
        scroll.scrollTo(0,position.vertical)
        if(position.focus.startsWith("home:remote:")) { scroll.scrollTo(0,0); return }
        // A removed row can leave the nearest surviving card outside the old viewport.
        rows.firstOrNull { row -> row.cards.any { it.view.hasFocus() } }?.let(::revealSelection)
    }
    private fun revealSelection(row: Row) {
        row.cards.firstOrNull { it.view.hasFocus() }?.view?.let { card ->
            val horizontal=row.horizontal
            val left=horizontal.scrollX
            val right=left+horizontal.width-horizontal.paddingRight
            val target=when { card.left<left -> card.left; card.right>right -> card.right-horizontal.width+horizontal.paddingRight; else -> left }
            if(target!=left) horizontal.scrollTo(target.coerceAtLeast(0),0)
        }
        reveal(row.block,false)
    }
    private fun reveal(block: View,smooth: Boolean=true) {
        val target=if(smooth) block.top else when { block.top<scroll.scrollY -> block.top; block.bottom>scroll.scrollY+scroll.height -> if(block.height>=scroll.height) block.top else block.bottom-scroll.height; else -> scroll.scrollY }.coerceAtLeast(0)
        if(target!=scroll.scrollY) { if(smooth) scroll.smoothScrollTo(0,target) else scroll.scrollTo(0,target) }
    }
    private fun loadNearbySelection() {
        if(restoring) return
        val row=rows.indexOfFirst { it.cards.any { card -> card.key==lastFocus } }.coerceAtLeast(0)
        val column=rows.getOrNull(row)?.cards?.indexOfFirst { it.key==lastFocus }?.coerceAtLeast(0) ?: 0
        rows.getOrNull(row)?.cards?.let { items -> for(index in (column-1).coerceAtLeast(0)..(column+6).coerceAtMost(items.lastIndex)) requestCover(items[index]) }
        rows.getOrNull(row+1)?.cards?.take(6)?.forEach(::requestCover)
    }
    private fun requestCover(card: Card) {
        val image=card.image ?: return
        if(card.pending || card.entry?.series?.cover.isNullOrBlank()) return
        val ticket=revision
        fun start() {
            card.pending=false
            if(ticket!=revision || !image.isAttachedToWindow || image.width<=0 || image.height<=0) return
            loadCover(image,card.entry!!.series.cover)
        }
        if(image.isLaidOut && !image.isLayoutRequested) start()
        else {
            card.pending=true
            image.addOnLayoutChangeListener(object: View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View,left: Int,top: Int,right: Int,bottom: Int,oldLeft: Int,oldTop: Int,oldRight: Int,oldBottom: Int) {
                    image.removeOnLayoutChangeListener(this); start()
                }
            })
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post { reloadArtwork() } }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if(w!=oldw || h!=oldh) post {
            val inset=sideInsetPx()
            if(paddingLeft!=inset || paddingRight!=inset) setPadding(inset,paddingTop,inset,paddingBottom)
            updateHeroHeight(); fitShelfWidths(); fitArtwork(); loadNearbySelection()
        }
    }
    private fun fitArtwork() {
        if(height<=0) return
        rows.forEach { row ->
            val textHeight=row.cards.maxOf { item -> item.view.paddingTop+item.view.paddingBottom+(1 until item.view.childCount).sumOf { child ->
                val view=item.view.getChildAt(child); val params=view.layoutParams as LayoutParams
                view.measuredHeight+params.topMargin+params.bottomMargin
            } }
            val desired=((row.cards.first().view.layoutParams.width-posterInset*2)/TvStyle.POSTER_ASPECT).toInt()
            val imageHeight=minOf(desired,(height-row.block.getChildAt(0).measuredHeight-textHeight-dp(8)).coerceAtLeast(dp(48)))
            row.cards.forEach { card -> card.view.getChildAt(0).let { artwork -> if(artwork.layoutParams.height!=imageHeight) artwork.layoutParams=artwork.layoutParams.apply { height=imageHeight } } }
        }
    }
    fun dismissTop() {
        top.visibility=GONE
        val first=cards.values.firstOrNull()
        lastFocus=first?.key ?: "home:refresh"
        remember(lastFocus)
        rows.firstOrNull()?.cards?.forEach { it.view.nextFocusUpId=nextFocusUpId }
        if(rows.isEmpty()) refresh.nextFocusUpId=nextFocusUpId
        (first?.view ?: refresh).requestFocus()
        scroll.post { scroll.scrollTo(0,0) }
    }
    private fun firstShelfId()=rows.firstOrNull()?.cards?.firstOrNull()?.view?.id ?: refresh.id
    fun firstId()=topFocus()?.id ?: firstShelfId()
}
