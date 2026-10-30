// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.laozhangapp

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.*
import android.text.TextUtils
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.util.concurrent.Executors

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity: Activity() {
    private val bg=TvStyle.background; private val surface=TvStyle.surface
    private val accent=TvStyle.accent; private val white=TvStyle.text; private val muted=TvStyle.muted
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newFixedThreadPool(3)
    private val repository=ContentRepository()
    private val catalogRepository=ContentRepository()
    private val networkCleanup=Executors.newSingleThreadExecutor()
    private val prefetchWorker=Executors.newSingleThreadExecutor()
    private val phoneCatalogWorker=Executors.newSingleThreadExecutor()
    private var phoneCatalogScope: RequestScope?=null
    private var phoneCatalogJob: java.util.concurrent.Future<*>?=null
    private var prefetchHttp: okhttp3.OkHttpClient?=null
    private var playbackHttp: okhttp3.OkHttpClient?=null
    private val preparedNext=PreparedSlot<RemoteVideo>({ android.os.SystemClock.elapsedRealtime() })
    private var prefetchJob: java.util.concurrent.Future<*>?=null
    private var prefetchScope: RequestScope?=null
    private var prefetchedFor=""
    private var readySince=0L
    private lateinit var artwork: ArtworkLoader
    private data class DeferredUi(val apply: ()->Unit,val discard: ()->Unit={})
    private var deferredWork: DeferredUi?=null
    private var initialCatalogPending=true
    private var restoreStoppedPage=false
    private var pendingDetail: Series?=null
    private var detailAutoplay=true
    private var libraryLoadError: Throwable?=null
    private var workScope: RequestScope?=null
    private var workJob: java.util.concurrent.Future<*>?=null
    private var playerListener: Player.Listener?=null
    private data class QueuedEpisode(
        val remote: RemoteVideo,
        val http: okhttp3.OkHttpClient?,
        val episodeIndex: Int,
        val key: String
    )
    private var queuedEpisode: QueuedEpisode?=null
    private val retiredSources=mutableMapOf<Runnable,Pair<RemoteVideo?,okhttp3.OkHttpClient?>>()
    private lateinit var playbackTitle: TextView
    private var directPlayback=false
    private var collection: String?=null
    private var collectionReturn: CatalogState?=null
    private var collectionFocus=""
    private var collectionBack: View?=null
    private val pageSize=20
    private var catalogScroll: ScrollView?=null
    private var catalogBody: LinearLayout?=null
    private var homePosition: HomeScreen.Position?=null
    private var catalogPageKey=""
    private val scrollPositions=mutableMapOf<String,Int>()
    private val catalogCards=linkedMapOf<String,View>()
    private val cardImages=linkedMapOf<String,Pair<ImageView,String>>()
    private var prefetchAttemptAt=0L
    private var prefetchAttempts=0
    private var homeScreen: HomeScreen?=null
    private var homeRemoteDismissed=false
    private var homeHero: HomeHero?=null
    private var homeContent=HomeContent()
    private val homeHeroWorker=Executors.newSingleThreadExecutor()
    private var homeHeroScope: RequestScope?=null
    private var homeHeroJob: java.util.concurrent.Future<*>?=null
    private var homeHeroPlayer: ExoPlayer?=null
    private var homeHeroVideo: RemoteVideo?=null
    private var homeHeroItems=emptyList<Series>()
    private var homeHeroIndex=0
    private var homeHeroTicket=0L
    private var selectionPreview: SeriesPreview?=null
    private var homeFromCache=false
    private var homeUpdateCount=0
    private var launchStarted=android.os.SystemClock.elapsedRealtime()
    private val activityStarted=launchStarted
    private var homeContentLogged=false
    private lateinit var library: Library
    private lateinit var cloudAccount: AccountController
    private lateinit var librarySources: LibrarySources
    private var reservationOnline=true
    private val accountSourceButtons=mutableListOf<TextView>()
    private val reservationButtons=mutableListOf<TextView>()
    private var accountStatus: TextView?=null
    private var accountSetting: TextView?=null
    private var accountFavoriteButton: TextView?=null
    private lateinit var tvTools: TvTools
    private lateinit var updater: AppUpdater
    private lateinit var mediaSession: TvMediaSession
    private val sleepTimer=SleepTimer { android.os.SystemClock.elapsedRealtime() }
    private var sleepStopped=false
    private var playerView: PlayerView?=null
    private lateinit var favoriteMonitor: FavoriteMonitor
    private val favoriteBadges=mutableMapOf<String,TextView>()
    private var favoriteStatus: TextView?=null
    private var favoriteCheck: TextView?=null
    private var homeUpdates: TextView?=null
    private val resumeCards=mutableListOf<View>()
    private var episodePanel: EpisodePanel?=null
    private val recovery=RecoveryBudget()
    private var retryPending=false
    private var autoRecovery=false
    private var startPosition=0L
    private var retryPosition=0L
    private var requestedAutoplay=true
    private var retryAutoplay=true
    private var foreground=false
    private lateinit var connectivity: ConnectivityManager
    private var networkRegistered=false
    private val networkCallback=object: ConnectivityManager.NetworkCallback() {
        private fun changed() { main.post { tvTools.ensurePhoneSearchServer(); if(!prefetchNetworkAllowed()) cancelPrefetch(); if(foreground && screen=="player" && playError && autoRecovery) scheduleRecovery() } }
        override fun onAvailable(network: Network) { changed() }
        override fun onLost(network: Network) { changed() }
        override fun onCapabilitiesChanged(network: Network,capabilities: NetworkCapabilities) { changed() }
        override fun onBlockedStatusChanged(network: Network,blocked: Boolean) { changed() }
    }
    private val retryRunnable=Runnable {
        retryPending=false
        if(foreground && screen=="player" && playError && autoRecovery) {
            if(!networkAvailable()) scheduleRecovery()
            else if(recovery.consume()) {
                android.util.Log.i("HongguoTV","Automatic playback recovery ${recovery.attempts}/3 at ${retryPosition}ms")
                playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
            }
        }
    }
    private lateinit var root: FrameLayout
    private var generation=0
    private var screen="catalog"
    private var animatedPage=""
    private var tab=0
    private var page=1
    private var query=""
    private var catalog=emptyList<Series>()
    private var catalogResultType=ContentType.COMIC
    private var catalogResultQuery=""
    private var hasMore=false
    private var catalogFocus=""
    private data class CatalogState(val page: Int,val items: List<Series>,val hasMore: Boolean,val ranking: ComicRanking?,val type: ContentType,val query: String="")
    private data class HomeLoad(val page: CatalogPage,val content: HomeContent)
    private var ranking: ComicRanking?=null
    private var rankType=ContentType.COMIC
    private val rankStates=mutableMapOf<ContentType,CatalogState>()
    private val rankFocus=mutableMapOf<ContentType,String>()
    private var rankRefresh: View?=null
    private var rankSubtitle: TextView?=null
    private val tabState=mutableMapOf<Int,CatalogState>()
    private val tabFocus=mutableMapOf<Int,String>()
    private val nav=mutableListOf<View>()
    private val typeButtons=mutableMapOf<ContentType,View>()
    private var searchInput: View?=null
    private var searchButton: View?=null
    private val searchActions=mutableListOf<View>()
    private var historyManage: View?=null
    private var detail: Detail?=null
    private var episodeIndex=0
    @Volatile private var phonePlayback=PhonePlaybackStatus("","",0,0,false)
    @Volatile private var phoneSearchResults=com.hongguotv.core.PhoneSearchResults()
    private var phoneSearchRevision=0L
    private var catalogGenre=CatalogGenre.COMIC
    private var catalogFilters=emptyMap<String,String>()
    private var catalogFilterRows=emptyList<CatalogFilter>()
    private var libraryEntry: View?=null
    private val libraryGenreButtons=mutableMapOf<CatalogGenre,TextView>()
    private var libraryFilterRequested=false
    private var categorySnapshotLoading=false
    private val seasonWorker=Executors.newSingleThreadExecutor()
    private val seasonRepository=ContentRepository()
    private var seasonScope: RequestScope?=null
    private var seasonJob: java.util.concurrent.Future<*>?=null
    @Volatile private var seasonResults=PhoneSearchResults(section="seasons")
    private var seasonRevision=0L
    private var seasonKey=""
    private var seasonLoadedAt=0L
    private var seasonView: LinearLayout?=null
    private var seasonDialog: android.app.Dialog?=null
    private var seasonDialogBody: LinearLayout?=null
    private var seasonDialogActions: ViewGroup?=null
    private var seasonDialogWanted=false
    private var group=0
    private var synopsisExpanded=false
    private var player: ExoPlayer?=null
    private var video: RemoteVideo?=null
    private var playbackReady=false
    private var pausedForLifecycle=false
    private lateinit var hud: LinearLayout
    private lateinit var playbackText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var controls: LinearLayout
    private var panel=false
    private var transportPlay: TextView?=null
    private var sleepButton: TextView?=null
    private var speedDialog: TvDialog?=null
    private var pendingSeek: Long?=null
    private var playError=false
    private var lastSaved=0L
    private var quality=""
    private val tick=object: Runnable {
        override fun run() {
            if(!foreground || isDestroyed) return
            if(screen=="player") {
                if(sleepTimer.poll()) stopForSleep()
                if(TvMotion.isVisible(hud)) updatePlaybackText()
                maybePrefetch()
                syncMediaSession()
                val now=android.os.SystemClock.elapsedRealtime()
                if(player?.isPlaying==true && now-lastSaved>5000) { saveProgress(); lastSaved=now }
            } else if(screen!="detail" || !directPlayback || !detailAutoplay) phonePlayback=PhonePlaybackStatus("","",0,0,false)
            main.postDelayed(this,1000)
        }
    }
    private val hideHud=Runnable { if(screen=="player" && !panel && player?.isPlaying==true && !playError) hidePlaybackOverlay() }
    private val seekRunnable=Runnable { pendingSeek?.let { player?.seekTo(it) }; pendingSeek=null }
    private fun dp(value: Number)=(value.toFloat()*resources.displayMetrics.density).toInt()
    private fun widthDp()=resources.displayMetrics.widthPixels/resources.displayMetrics.density
    /** Shared TV safe-area gutter used by the home shelves and all catalog tabs. */
    private fun pageInsetPx()=dp((widthDp()*.045f).coerceIn(36f,84f))
    private fun lp(w: Int=LinearLayout.LayoutParams.MATCH_PARENT,h: Int=LinearLayout.LayoutParams.WRAP_CONTENT)=LinearLayout.LayoutParams(w,h)
    private fun rounded(color: Int,border: Int=Color.TRANSPARENT)=TvStyle.shape(this,color,border)
    private fun text(value: String,size: Float=16f,color: Int=white)=TextView(this).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun focusStyle(view: View,selected: Boolean=false,flat: Boolean=false) {
        view.id=View.generateViewId(); view.isFocusable=true; view.isFocusableInTouchMode=true
        fun paint(focused: Boolean) {
            val fill=when {
                focused -> TvStyle.raised
                selected -> TvStyle.accent
                flat -> Color.TRANSPARENT
                else -> TvStyle.surfaceAlt
            }
            val stroke=when {
                focused -> accent
                selected -> accent
                else -> Color.TRANSPARENT
            }
            TvMotion.focus(view,focused,fill,stroke)
            if(view is TextView) {
                view.setTextColor(if(selected && !focused) Color.WHITE else TvStyle.text)
                view.setTypeface(null,if(selected) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        paint(false); view.setOnFocusChangeListener { _,focused -> paint(focused) }
    }
    private fun button(label: String,selected: Boolean=false,onClick: ()->Unit): TextView = text(label,15f).apply {
        gravity=Gravity.CENTER; setPadding(dp(14),dp(10),dp(14),dp(10)); minHeight=dp(42); focusStyle(this,selected); setOnClickListener { onClick() }
    }
    private fun addButton(parent: ViewGroup,label: String,selected: Boolean=false,onClick: ()->Unit): TextView {
        val view=button(label,selected,onClick); parent.addView(view,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin=dp(8) }); return view
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        library=Library(this); librarySources=LibrarySources(this); artwork=ArtworkLoader(this)
        cloudAccount=AccountController(this) { remote -> main.post { if(!isDestroyed && foreground && library.isLoaded) openCloudProgress(remote) } }
        cloudAccount.onHistoryChanged = { main.post {
            if(isDestroyed || !library.isLoaded) return@post
            accountStatus?.text=cloudAccount.historyStatus
            accountSetting?.text="红果账号管理  ·  ${cloudAccount.accountLabel}"
            detail?.series?.id?.let { if(accountFavoriteButton?.isAttachedToWindow==true) accountFavoriteButton?.text=cloudAccount.favoriteActionLabel(it) }
            if(screen=="catalog" && tab in listOf(3,4,7)) showCatalog()
        } }
        tvTools=TvTools(this) { value,type -> main.post { if(!isDestroyed) runSearch(value,type ?: library.contentType) } }; root=FrameLayout(this).apply { setBackgroundColor(bg) }; setContentView(root)
        tvTools.setPhonePlaybackCallbacks({ phonePlayback },{ selected -> main.post { if(!isDestroyed) playPhoneEpisode(selected) } })
        tvTools.setPhoneSearchCallbacks({ phoneSearchResults },
            { revision,id -> main.post { if(!isDestroyed) playPhoneSeries(revision,id) } },
            { revision,target -> main.post { if(!isDestroyed) turnPhoneSearchPage(revision,target) } },
            { section,type -> main.post { if(!isDestroyed) browsePhoneCatalog(section,type) } },
            { command -> main.post { if(!isDestroyed) executePhoneControl(command) } },
            { revision,id,value -> main.post { if(!isDestroyed) favoritePhoneSeries(revision,id,value) } })
        tvTools.setPhoneLibraryCallbacks({ genre,filters -> main.post {
            if(!isDestroyed && foreground && library.isLoaded) loadPhoneCatalog("library",genre.type,1,genre,filters)
        } },{ seasonResults },{ revision,id -> main.post { if(!isDestroyed) playRelatedSeason(revision,id) } })
        updater=AppUpdater(this) { foreground && library.isLoaded && screen=="catalog" && !tvTools.showing }
        connectivity=getSystemService(ConnectivityManager::class.java)
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback); networkRegistered=true }
        tvTools.ensurePhoneSearchServer()
        favoriteMonitor=FavoriteMonitor(library,repository) { refreshFavoriteLabels() }
        mediaSession=TvMediaSession(this,{ if(foreground && screen=="player") requestPlayback(true) },{ if(foreground && screen=="player") requestPlayback(false) },{ position -> if(foreground && screen=="player") seekTo(position) },{ direction -> skipEpisode(direction) },{ if(foreground && screen=="player") returnToDetail() })
        message(base(),"正在读取本机记录…")
        loadLibrary()
    }
    private fun showLibraryError(problem: Throwable) {
        val body=base(); error(body,problem) { message(base(),"正在读取本机记录…"); loadLibrary() }
    }
    private fun loadLibrary() {
        libraryLoadError=null
        library.loadAsync(onFailure={ problem ->
            if(!isDestroyed) { libraryLoadError=problem; if(foreground) showLibraryError(problem) }
        }) {
            if(!isDestroyed && foreground) { showCatalog(load=true); favoriteMonitor.check() }
        }
    }
    private fun base(title: String?=null,edgeToEdge: Boolean=false): LinearLayout {
        root.removeAllViews(); root.setBackgroundColor(bg)
        val container=column()
        // Keep every catalog/settings surface on the same adaptive safe-area
        // gutter as the home shelves. The home header is edge-to-edge and
        // applies the gutter to its own content, so it remains the exception.
        val horizontal=if(edgeToEdge) 0 else pageInsetPx()
        val vertical=((resources.displayMetrics.heightPixels*.035f).toInt())
            .coerceIn(dp(12),dp(28))
        container.setPadding(horizontal,vertical,horizontal,vertical); root.addView(container,FrameLayout.LayoutParams(-1,-1))
        val motionKey=if(screen=="detail") "detail:${detail?.series?.id}" else "catalog:$tab:${collection.orEmpty()}"
        if(animatedPage!=motionKey) {
            val order=listOf(0,6,1,2,7,3,4,5)
            val previous=animatedPage.takeIf { it.startsWith("catalog:") }?.substringAfter(':')?.substringBefore(':')?.toIntOrNull()
            val direction=if(screen=="catalog" && previous!=null) order.indexOf(tab).compareTo(order.indexOf(previous)) else 0
            animatedPage=motionKey
            TvMotion.enterPage(container,stableHeader=screen=="catalog",direction=direction)
        }
        if(title!=null) { val heading=row(); addButton(heading,"‹ 返回") { goBack() }; heading.addView(text(title,22f).apply { setTypeface(null,Typeface.BOLD) },lp(0,dp(44)).apply { weight=1f }); container.addView(heading) }
        return container
    }
    private fun loadCover(view: ImageView,url: String) {
        var ancestor: View?=view
        var focused=false
        while(ancestor!=null && ancestor!==root) {
            if(ancestor.isFocused) { focused=true; break }
            ancestor=ancestor.parent as? View
        }
        artwork.load(view,url,if(focused) ArtworkLoader.PRIORITY_FOCUSED else ArtworkLoader.PRIORITY_VISIBLE)
    }
    private fun cancelWork() {
        deferredWork?.discard?.invoke(); deferredWork=null
        workScope?.cancel(); workScope=null
        workJob?.cancel(true); workJob=null
        (io as? java.util.concurrent.ThreadPoolExecutor)?.purge()
    }
    private fun invalidatePage() {
        if(phoneSearchResults.loading && phoneSearchResults.section=="search") phoneSearchResults=phoneSearchResults.copy(loading=false,error="电视已切换页面，请重新搜索")
        generation++; cancelWork(); artwork.cancelPage()
    }
    private fun releaseCatalogViews() {
        stopHomeHero()
        homeScreen?.let { homePosition=it.capturePosition() }
        catalogScroll?.let { if(catalogPageKey.isNotBlank()) scrollPositions[catalogPageKey]=it.scrollY }
        catalogBody=null
        libraryEntry=null; libraryGenreButtons.clear()
        homeScreen=null; selectionPreview=null; catalogScroll=null
        nav.clear(); typeButtons.clear(); searchInput=null; searchButton=null; searchActions.clear()
        historyManage=null; rankRefresh=null; rankSubtitle=null; collectionBack=null; favoriteBadges.clear()
        favoriteStatus=null; favoriteCheck=null; homeUpdates=null; resumeCards.clear(); accountStatus=null; accountSetting=null; accountFavoriteButton=null
        accountSourceButtons.clear(); reservationButtons.clear()
        catalogCards.clear(); cardImages.clear()
    }
    private fun stopHomeHero() {
        homeHeroTicket++
        clearHomeHeroPlayback()
        homeHero=null; homeHeroItems=emptyList(); homeHeroIndex=0
    }
    private fun clearHomeHeroPlayback() {
        homeHeroScope?.cancel(); homeHeroScope=null
        homeHeroJob?.cancel(true); homeHeroJob=null
        val oldPlayer=homeHeroPlayer
        homeHeroPlayer=null
        homeHero?.playerView?.player=null
        oldPlayer?.runCatching { stop() }
        oldPlayer?.release()
        homeHeroVideo?.close(); homeHeroVideo=null
    }
    private fun startHomeHero(view: HomeHero,items: List<Series>) {
        stopHomeHero()
        homeHero=view; homeHeroItems=items.distinctBy { it.id }; homeHeroIndex=0
        if(homeHeroItems.isEmpty()) { view.setLoading(); return }
        view.bindVideo(homeHeroItems.first()); loadHomeHero(0)
    }
    private fun loadHomeHero(index: Int) {
        val view=homeHero ?: return
        if(screen!="catalog" || tab!=0 || !foreground || homeHeroItems.isEmpty()) return
        clearHomeHeroPlayback()
        homeHeroIndex=index.coerceIn(0,homeHeroItems.lastIndex)
        val series=homeHeroItems[homeHeroIndex]; view.bindVideo(series)
        val ticket=++homeHeroTicket
        val scope=RequestScope(); homeHeroScope=scope
        homeHeroJob=homeHeroWorker.submit {
            var remote: RemoteVideo?=null
            try {
                val prepared=scope.run {
                    val detail=repository.detail(series.id)
                    val episode=detail.episodes.firstOrNull() ?: throw IllegalStateException("推荐剧集暂无可播放内容")
                    RemoteVideo(repository.http,repository.stream(episode,library.maxQuality)).also { remote=it; it.prepare() }
                }
                main.post {
                    if(isDestroyed || ticket!=homeHeroTicket || screen!="catalog" || tab!=0 || homeHero!==view) {
                        prepared.close(); return@post
                    }
                    homeHeroVideo=prepared
                    val renderers=PlaybackRuntime.renderers(this)
                    val player=ExoPlayer.Builder(this,renderers).setLoadControl(
                        DefaultLoadControl.Builder().setBufferDurationsMs(10_000,25_000,1_000,1_500).setTargetBufferBytes(8*1024*1024).build()
                    ).build()
                    homeHeroPlayer=player; view.playerView.player=player; player.volume=0f
                    player.addListener(object: Player.Listener {
                        private fun active()=homeHeroPlayer===player && ticket==homeHeroTicket && homeHero===view
                        override fun onPlaybackStateChanged(state: Int) {
                            if(active() && state==Player.STATE_ENDED) loadHomeHero((homeHeroIndex+1)%homeHeroItems.size)
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            if(active() && homeHeroItems.size>1) loadHomeHero((homeHeroIndex+1)%homeHeroItems.size)
                            else if(active()) view.setVideoUnavailable()
                        }
                    })
                    val source=ProgressiveMediaSource.Factory { VideoDataSource(prepared) }.createMediaSource(
                        MediaItem.Builder().setMediaId("home-hero-${series.id}").setUri("hongguotv://home/${series.id}").setMimeType(MimeTypes.VIDEO_MP4).build()
                    )
                    player.setMediaSource(source); player.prepare(); player.playWhenReady=true
                }
            } catch(_: Exception) { remote?.close() }
        }
    }
    private fun <T> work(action: ()->T,done: (T)->Unit,failed: (Throwable)->Unit) {
        cancelWork()
        val ticket=generation; val scope=RequestScope(); workScope=scope
        workJob=io.submit {
            try {
                val result=scope.run(action)
                main.post {
                    val discard={ if(result is RemoteVideo) result.close(); Unit }
                    if(isDestroyed || generation!=ticket) discard()
                    else if(foreground) done(result)
                    else { deferredWork?.discard?.invoke(); deferredWork=DeferredUi({ done(result) },discard) }
                }
            } catch(e: Exception) {
                main.post {
                    if(!isDestroyed && generation==ticket) {
                        if(foreground) failed(e) else deferredWork=DeferredUi({ failed(e) })
                    }
                }
            }
        }
    }
    private fun message(parent: LinearLayout,label: String) { parent.addView(text(label,17f,muted).apply { setPadding(0,dp(28),0,dp(18)) }) }
    private fun error(parent: LinearLayout,problem: Throwable,retry: ()->Unit) {
        android.util.Log.w("HongguoTV","Content request failed",problem)
        message(parent,"暂时无法加载，请检查网络后重试。")
        parent.addView(text(networkHint(problem),13f,muted)); addButton(parent,"重试",onClick=retry).requestFocus()
    }
    private fun networkHint(problem: Throwable): String {
        val root=generateSequence(problem) { it.cause }.lastOrNull() ?: problem
        return when(root) {
            is UnknownHostException -> "电视无法解析内容域名，请检查电视网络和 DNS；手机能访问不代表电视使用相同 DNS。"
            is SSLException -> "电视与内容服务的 TLS/系统证书协商失败，请更新电视系统或切换网络后重试。"
            is ConnectException -> "电视无法连接内容服务，可能是网络路由或服务暂时不可用。"
            is SocketTimeoutException -> "内容服务响应超时，已自动重试；请检查电视网络或稍后重试。"
            else -> problem.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(180).ifBlank { "内容服务暂时不可用" }
        }
    }
    private fun switchTab(next: Int) {
        if(screen=="catalog" && next==tab && collection==null) { nav.getOrNull(tab)?.requestFocus(); return }
        leaveCollection()
        tabState[tab]=CatalogState(page,catalog,hasMore,ranking,catalogResultType,catalogResultQuery); tabFocus[tab]=catalogFocus
        tab=next
        // A library selection or phone playback can change the shared type
        // without visiting the search type buttons. Never relabel cached rows.
        val saved=tabState[next]?.takeIf { state ->
            (next !in listOf(0,1) || state.type==library.contentType) && (next!=1 || state.query==query)
        }
        if(next==0 && saved==null) homePosition=null
        page=saved?.page ?: 1; setCatalog(saved?.items ?: emptyList(),saved?.type ?: catalogType(),saved?.query.orEmpty()); hasMore=saved?.hasMore ?: false; ranking=saved?.ranking; catalogFocus=if(saved!=null) tabFocus[next].orEmpty() else ""
        showCatalog(load=((next<=2 || next==6 || next in listOf(3,4,7)) && catalog.isEmpty() && (next!=1 || query.isNotBlank())))
    }
    private fun switchContentType(type: ContentType) {
        if(library.contentType==type) return
        leaveCollection()
        (searchInput as? EditText)?.let { query=it.text.toString().trim() }
        library.contentType=type
        for(index in 0..1) { tabState.remove(index); tabFocus.remove(index) }
        releaseCatalogViews()
        page=1; setCatalog(emptyList(),type,if(tab==1) query else ""); hasMore=false; ranking=null; catalogFocus=""; homePosition=null
        showCatalog(load=(tab==0 || tab==6 || query.isNotBlank()),focusType=true)
    }
    private fun catalogType()=if(tab==2) rankType else library.contentType
    private fun setCatalog(items: List<Series>,type: ContentType,searchQuery: String="") {
        catalog=items; catalogResultType=type; catalogResultQuery=searchQuery
    }
    private fun rankingSource()="来源：红果${rankType.label}热播榜"
    private fun switchRankingType(type: ContentType) {
        if(rankType==type) return
        rankStates[rankType]=CatalogState(page,catalog,hasMore,ranking,rankType)
        rankFocus[rankType]=catalogFocus
        rankType=type
        val saved=rankStates[type]
        page=saved?.page ?: 1; setCatalog(saved?.items ?: emptyList(),type)
        hasMore=saved?.hasMore ?: false; ranking=saved?.ranking; catalogFocus=rankFocus[type].orEmpty()
        showCatalog(load=catalog.isEmpty(),focusType=true)
    }
    private fun leaveCollection() {
        if(collection==null) return
        collection=null
        collectionReturn?.let { page=it.page; setCatalog(it.items,it.type,it.query); hasMore=it.hasMore; ranking=it.ranking }
        catalogFocus=collectionFocus; collectionReturn=null
    }
    private fun showCollection(kind: String) {
        collectionReturn=CatalogState(page,catalog,hasMore,ranking,catalogResultType,catalogResultQuery); collectionFocus=catalogFocus
        collection=kind; page=1; catalogFocus=""; showCatalog()
    }
    private fun inlineHomeFilters()=tab==0 && resources.configuration.fontScale<=1.2f && widthDp()>=900
    private fun localCatalog()=tab in listOf(3,4,7) || collection!=null
    private fun localItems(): List<Series> = when(collection) {
        "hot" -> collectionReturn?.items.orEmpty().filterNot { it.id in library.hidden() }
        "later" -> library.later()
        "updates" -> library.favorites().filter { (library.favoriteUpdate(it.id)?.added ?: 0)>0 }
        "resume" -> library.history().filterNot { library.watched(it.series.id) }.map { it.series }
        else -> when {
            tab==3 && librarySources.accountFavorites -> cloudAccount.favoriteState().rows.map { it.series }
            tab==4 && librarySources.accountHistory -> cloudAccount.historyRows().map { it.series }
            tab==7 -> cloudAccount.reservationState(reservationOnline).rows.map { it.series }
            tab==3 -> library.favorites()
            else -> library.history().map { it.series }
        }
    }
    private fun showCatalog(load: Boolean=false,focusNav: Boolean=false,focusType: Boolean=false) {
        // Playback can change the shared type while the previous rows remain
        // in memory. Validate their recorded source before showing them again.
        if(!load && collection==null && (tab==0 || tab==1 && query.isNotBlank()) &&
            (catalogResultType!=library.contentType || tab==1 && catalogResultQuery!=query)) {
            page=1; catalogFocus=""; if(tab==0) homePosition=null
            showCatalog(load=true,focusNav=focusNav,focusType=focusType)
            return
        }
        // A same-source home refresh keeps the banner, artwork and focused shelf mounted.
        if(load && screen=="catalog" && tab==0 && page==1 && collection==null &&
            catalogResultType==library.contentType && homeScreen!=null && catalogBody!=null) {
            generation++; cancelWork()
            homeScreen?.refresh?.text="正在更新热门…"
            requestCatalog(catalogBody!!,focusNav,focusType,placeholder=false)
            return
        }
        if(tab!=6) libraryFilterRequested=false
        initialCatalogPending=false; restoreStoppedPage=false; pendingDetail=null
        phonePlayback=PhonePlaybackStatus("","",0,0,false)
        // Never cache a new page number with the previous page's results while a request is pending.
        if(load && !localCatalog()) {
            val cached=if(tab==0 && page==1) library.cachedHome(library.contentType) else null
            setCatalog(cached?.items ?: emptyList(),catalogType(),if(tab==1) query else ""); hasMore=cached?.hasMore ?: false; ranking=null; homeFromCache=cached!=null
        }
        invalidatePage(); screen="catalog"; releaseCatalogViews(); val container=base(edgeToEdge=tab==0)
        if(foreground && library.isLoaded && !favoriteMonitor.running) main.post { if(foreground && screen=="catalog") favoriteMonitor.check() }
        val homeInset=pageInsetPx()
        val top=row().apply { setPadding(if(tab==0) homeInset else 0,0,if(tab==0) homeInset else 0,dp(9)) }
        val brand=row()
        brand.addView(ImageView(this).apply { setImageResource(com.hongguotv.laozhangapp.R.drawable.app_icon) },lp(dp(28),dp(28)).apply { rightMargin=dp(9) })
        brand.addView(text("红果",22f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) })
        brand.minimumWidth=dp(if(widthDp()<800) 96 else 120)
        top.addView(brand,lp(-2,-2))
        val navigationRow=row()
        val navigationScroll=HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=false; clipToPadding=false; isFocusable=false
            overScrollMode=View.OVER_SCROLL_NEVER; addView(navigationRow)
        }
        top.addView(navigationScroll,lp(0,-2).apply { weight=1f })
        val navigation=listOf("首页","搜索","排行榜","收藏","最近观看","设置","剧库","短剧预约")
        val navigationViews=mutableMapOf<Int,View>()
        // Keep this order aligned with the complete navigation list. Omitting
        // the last item makes getValue(7) throw during startup on some devices.
        // Keep short-drama reservations directly after the ranking entry.
        listOf(0,6,1,2,7,3,4,5).forEach { index ->
            val label=navigation[index]
            val item=addButton(navigationRow,label,index==tab) { switchTab(index) }
            fun paint(focused: Boolean) {
                item.setTextColor(if(focused || index==tab) TvStyle.text else TvStyle.muted)
                TvMotion.focus(item,focused,if(focused) TvStyle.raised else if(index==tab) TvStyle.surfaceAlt else Color.TRANSPARENT,if(focused) TvStyle.accent else Color.TRANSPARENT)
                item.setTypeface(null,if(focused || index==tab) Typeface.BOLD else Typeface.NORMAL)
            }
            item.apply { textSize=14f; minHeight=dp(38); setPadding(dp(13),dp(7),dp(13),dp(7)) }
            paint(false); item.setOnFocusChangeListener { _,focused -> paint(focused) }; navigationViews[index]=item
        }
        navigation.indices.forEach { nav+=navigationViews.getValue(it) }
        navigationRow.post {
            navigationViews[tab]?.let { selected ->
                selected.requestRectangleOnScreen(android.graphics.Rect(0,0,selected.width,selected.height),true)
            }
        }
        container.addView(top)
        container.addView(View(this).apply { setBackgroundColor(TvStyle.outline) },lp(-1,dp(1)).apply { bottomMargin=dp(6) })
        if(tab==5) {
            val scroll=ScrollView(this); val body=column(); scroll.addView(body); container.addView(scroll,lp(-1,0).apply { weight=1f })
            settings(body); if(focusNav) nav[tab].requestFocus(); return
        }
        if(tab in listOf(3,4)) {
            val source=row().apply { setPadding(0,dp(8),0,dp(4)) }
            accountSourceButtons.clear()
            val accountSource=if(tab==3) librarySources.accountFavorites else librarySources.accountHistory
            val localLabel=if(tab==3) "本机收藏" else "本机历史"
            val accountLabel=if(tab==3) "账户收藏" else "账户历史"
            accountSourceButtons += addButton(source,localLabel,!accountSource) {
                if((if(tab==3) librarySources.accountFavorites else librarySources.accountHistory)) {
                    if(tab==3) librarySources.accountFavorites=false else librarySources.accountHistory=false
                    page=1; catalogFocus=""; showCatalog()
                }
            }
            accountSourceButtons += addButton(source,accountLabel,accountSource) {
                if(tab==3) librarySources.accountFavorites=true else librarySources.accountHistory=true
                page=1; catalogFocus=""; showCatalog(true)
            }
            val hint=if(accountSource) "登录红果后与手机同步" else "仅保存在这台电视"
            source.addView(text(hint,12f,muted).apply { setPadding(dp(10),0,0,0) })
            container.addView(source)
            if(accountSource) {
                accountStatus=text(if(cloudAccount.signedIn) if(tab==3) cloudAccount.favoriteState().message else cloudAccount.historyStatus else "未登录红果账号",13f,muted)
                accountStatus?.setPadding(0,0,0,dp(8)); container.addView(accountStatus)
            }
            nav.forEach { it.nextFocusDownId=accountSourceButtons.first().id }
        }
        if(tab==7) {
            val heading=row().apply { setPadding(0,dp(10),0,dp(5)) }
            heading.addView(text("短剧预约",25f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            addButton(heading,"账号管理") { cloudAccount.show() }.apply { nextFocusUpId=nav[tab].id }
            addButton(heading,"刷新预约") { cloudAccount.refreshReservations() }.apply { nextFocusUpId=nav[tab].id }
            container.addView(heading)
            val filters=row().apply { setPadding(0,dp(4),0,dp(4)) }
            reservationButtons.clear()
            reservationButtons += addButton(filters,"已上线",reservationOnline) { if(!reservationOnline) { reservationOnline=true; page=1; catalogFocus=""; showCatalog(true) } }
            reservationButtons += addButton(filters,"待上线",!reservationOnline) { if(reservationOnline) { reservationOnline=false; page=1; catalogFocus=""; showCatalog(true) } }
            container.addView(filters)
            accountStatus=text(if(cloudAccount.signedIn) cloudAccount.reservationState(reservationOnline).message else "账号未登录，请先登录红果账号后同步短剧预约",13f,muted)
            accountStatus?.setPadding(0,0,0,dp(8)); container.addView(accountStatus)
            nav.forEach { it.nextFocusDownId=reservationButtons.first().id }
        }
        if(tab<=1) {
            val types=row().apply { setPadding(if(tab==0) homeInset else 0,dp(8),if(tab==0) homeInset else 0,dp(6)) }
            if(!inlineHomeFilters()) types.addView(text("片库",12f,muted),lp(dp(42),-2))
            ContentType.entries.forEach { type ->
                typeButtons[type]=addButton(types,type.label,library.contentType==type) { switchContentType(type) }
                    .apply { isSelected=library.contentType==type; nextFocusUpId=nav[tab].id }
            }
            if(inlineHomeFilters()) {
                typeButtons.values.forEach { view -> (view as TextView).apply { textSize=12f; minHeight=dp(34); setPadding(dp(12),dp(7),dp(12),dp(7)) } }
                types.setPadding(0,0,0,0); top.addView(types)
            } else {
                if(tab==0) types.addView(text("长按确认 · 管理片单",12f,muted).apply { setPadding(dp(14),0,0,0) })
                container.addView(types)
            }
            nav.forEach { it.nextFocusDownId=typeButtons.getValue(library.contentType).id }
        }
        if(tab==6) {
            val types=row().apply { setPadding(0,dp(8),0,dp(5)) }
            CatalogGenre.entries.forEach { genre -> libraryGenreButtons[genre]=addButton(types,genre.label+" ▾",catalogGenre==genre) {
                if(catalogGenre!=genre) {
                    libraryFilterRequested=true
                    catalogGenre=genre; library.contentType=genre.type; catalogFilters=emptyMap(); catalogFilterRows=emptyList(); page=1; catalogFocus=""; showCatalog(true)
                } else if(catalogFilterRows.isNotEmpty()) showLibraryFilters()
                else { libraryFilterRequested=true; showCatalog(true) }
            }.apply { nextFocusUpId=nav[tab].id } }
            libraryEntry=libraryGenreButtons[catalogGenre]
            addButton(types,"手机遥控") { tvTools.phoneInput(types) }
            container.addView(types)
            renderLibraryFilters()
            nav.forEach { it.nextFocusDownId=libraryEntry!!.id }
        }
        if(tab==1) {
            val searchRow=row(); val input=EditText(this).apply { id=View.generateViewId(); hint="输入${library.contentType.label}名称或关键词"; setText(query); isSingleLine=true; maxLines=1; filters=arrayOf(android.text.InputFilter.LengthFilter(80)); imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH; TvStyle.input(this) }
            searchRow.addView(input,lp(0,-2).apply { weight=1f; rightMargin=dp(10) })
            fun search() { val next=input.text.toString().trim(); if(next.isBlank()) { input.requestFocus(); return }; runSearch(next) }
            searchInput=input; searchButton=addButton(searchRow,"搜索") { search() }; input.nextFocusUpId=typeButtons.getValue(library.contentType).id; searchButton?.nextFocusUpId=input.nextFocusUpId
            lateinit var recent: TextView
            recent=addButton(searchRow,"历史") { showSearchHistory(recent) }; recent.nextFocusUpId=input.nextFocusUpId
            lateinit var phone: TextView
            phone=addButton(searchRow,"手机遥控") { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0); tvTools.phoneInput(phone) }; phone.nextFocusUpId=input.nextFocusUpId
            searchActions.addAll(listOf(recent,phone))
            typeButtons.values.forEach { it.nextFocusDownId=input.id }
            input.setOnEditorActionListener { _,_,_-> search(); true }; container.addView(searchRow)
        } else if(tab==2) {
            val heading=row().apply { setPadding(0,dp(8),0,dp(6)) }
            heading.addView(text("${rankType.label}热播榜",25f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            listOf(ContentType.COMIC,ContentType.SHORT).forEach { type ->
                typeButtons[type]=addButton(heading,type.label,rankType==type) { switchRankingType(type) }
                    .apply { isSelected=rankType==type; nextFocusUpId=nav[tab].id }
            }
            rankRefresh=addButton(heading,"刷新榜单") { page=1; catalogFocus=""; showCatalog(true) }.apply { nextFocusUpId=nav[tab].id }
            val actions=typeButtons.values.toList()+rankRefresh!!
            actions.forEachIndexed { index,view ->
                view.nextFocusLeftId=actions[(index-1).coerceAtLeast(0)].id
                view.nextFocusRightId=actions[(index+1).coerceAtMost(actions.lastIndex)].id
                view.nextFocusDownId=rankRefresh!!.id
            }
            container.addView(heading)
            rankSubtitle=text(if(load) "正在读取最新${rankType.label}榜单…" else ranking?.updatedText?.ifBlank { rankingSource() } ?: rankingSource(),13f,muted)
                .apply { setPadding(0,0,0,dp(8)) }
            container.addView(rankSubtitle)
            nav.forEach { it.nextFocusDownId=typeButtons.getValue(rankType).id }
        } else if(tab in listOf(3,4)) {
            val heading=row().apply { setPadding(0,dp(14),0,dp(6)) }
            val remote=if(tab==3) librarySources.accountFavorites else librarySources.accountHistory
            heading.addView(text(if(tab==3) if(remote) "账户收藏" else "我的收藏" else if(remote) "账户历史" else "接着上次看",25f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            if(tab==3 && !remote) {
                favoriteCheck=addButton(heading,"检查更新") { favoriteMonitor.check(force=true) }.apply { nextFocusUpId=nav[tab].id }
            }
            if(tab==4 && !remote && library.history().isNotEmpty()) {
                historyManage=addButton(heading,"管理记录") {
                    val rows=library.history()
                    tvTools.choose("选择要管理的观看记录",rows.map { it.series.title },historyManage,{ index -> manageHistory(rows[index].series,historyManage) },"清空记录") {
                        tvTools.confirm("清空观看记录","将删除本机所有观看进度，收藏会保留。",historyManage) { library.clearHistory(); catalogFocus=""; showCatalog() }
                    }
                }.apply { nextFocusUpId=nav[tab].id }
            }
            container.addView(heading)
            if(tab==3 && !remote) {
                favoriteStatus=text(favoriteMonitor.status,13f,muted).apply { setPadding(0,0,0,dp(6)) }; container.addView(favoriteStatus)
            }
        }
        val body=column(); catalogBody=body; container.addView(body,lp(-1,0).apply { weight=1f })
        if(localCatalog()) {
            setCatalog(localItems(),catalogType()); ranking=null; page=page.coerceIn(1,maxOf(1,(catalog.size+pageSize-1)/pageSize)); hasMore=page*pageSize<catalog.size
        }
        if(load) {
            if(tab==3 && librarySources.accountFavorites) cloudAccount.refreshFavorites()
            if(tab==4 && librarySources.accountHistory) cloudAccount.refreshHistory()
            if(tab==7) cloudAccount.refreshReservations()
        }
        if(load && !localCatalog()) {
            requestCatalog(body,focusNav,focusType)
        } else {
            if(tab in listOf(0,1,3,4,6)) phoneSearchResults=phoneCatalogSnapshot(++phoneSearchRevision,tab,page,catalog,hasMore)
            catalogGrid(body,focusNav,focusType)
        }
    }
    private fun requestCatalog(body: LinearLayout,focusNav: Boolean,focusType: Boolean,placeholder: Boolean=true) {
        if(placeholder) {
            if(tab==0 || tab==6) catalogGrid(body,focusNav,focusType,loading=true)
            else {
                body.addView(TvLoading(this,if(tab==1) "正在搜索剧集…" else "正在更新榜单…"),lp(-1,0).apply { weight=1f })
                if(focusType) typeButtons[catalogType()]?.requestFocus() else nav[tab].requestFocus()
            }
        }
        val requestTab=tab; val requestPage=page; val requestQuery=query; val requestType=if(tab==6) catalogGenre.type else catalogType()
        val requestGenre=catalogGenre; val requestFilters=catalogFilters.toMap()
        val searchRevision=if(requestTab in listOf(0,1,3,4,6)) (++phoneSearchRevision).also {
            phoneSearchResults=com.hongguotv.core.PhoneSearchResults(it,if(requestTab==1) requestQuery else "",requestType,requestPage,loading=true,
                section=when(requestTab) { 6 -> "library"; 1 -> "search"; 3 -> "favorites"; 4 -> "history"; else -> "home" },genre=requestGenre,filterRows=if(requestTab==6) emptyList() else catalogFilterRows,filters=requestFilters)
        } else null
        if(requestTab in listOf(3,4)) {
            val rows=if(requestTab==3) library.favorites() else library.history().map { it.series }
            phoneSearchResults=phoneCatalogSnapshot(searchRevision!!,requestTab,requestPage,rows,requestPage*pageSize<rows.size,requestType)
        }
        work({
            val result=when {
                requestTab in listOf(3,4) -> HomeLoad(CatalogPage(emptyList(),false),HomeContent())
                requestTab==0 -> { val content=repository.homeContent(); HomeLoad(CatalogPage(if(requestType==ContentType.COMIC) content.comicHot else content.shortHot,false),content) }
                requestTab==6 -> HomeLoad(catalogRepository.catalog(requestGenre,requestPage,requestFilters),HomeContent())
                else -> HomeLoad(when(requestTab) { 2 -> repository.ranking(requestPage,requestType); else -> repository.search(requestQuery,requestPage,requestType) },HomeContent())
            }
            val rows: List<CatalogFilter> = if(requestTab==6) catalogRepository.catalogFilters(requestGenre) else emptyList()
            result to rows
        }, { loaded ->
            val (result,rows)=loaded
            if(requestTab==0) homeContent=result.content
            if(requestTab==6) { catalogFilterRows=rows; renderLibraryFilters() }
            if(searchRevision!=null && phoneSearchResults.revision==searchRevision && requestTab !in listOf(3,4))
                phoneSearchResults=phoneCatalogSnapshot(searchRevision,requestTab,requestPage,result.page.items,result.page.hasMore,requestType)
            if(requestTab==0 && requestPage==1) library.cacheHome(requestType,result.page.items,result.page.hasMore)
            if(requestTab==0 && requestPage==1 && (tvTools.showing || updater.showing)) {
                homeScreen?.refresh?.text="热门已更新 · 按确认查看"
                homeScreen?.refresh?.setOnClickListener { setCatalog(result.page.items,requestType); hasMore=result.page.hasMore; ranking=result.page.ranking; homeContent=result.content; showCatalog() }
            } else {
                setCatalog(result.page.items,requestType,if(requestTab==1) requestQuery else ""); hasMore=result.page.hasMore; ranking=result.page.ranking
                val keepNav=nav.any { it.hasFocus() }; val keepType=typeButtons.values.any { it.hasFocus() }
                rankSubtitle?.text=ranking?.updatedText?.ifBlank { rankingSource() } ?: rankingSource()
                val home=homeScreen?.takeIf { requestTab==0 && requestPage==1 && collection==null }
                if(home!=null) {
                    home.updateShelves(homeShelves(),requestFocus=home.hasFocus(),animate=true)
                    home.refresh.text="刷新热门"
                } else {
                    body.removeAllViews(); catalogGrid(body,keepNav || focusNav,keepType || focusType)
                    TvMotion.contentReady(body)
                }
            }
            if(requestTab==6 && libraryFilterRequested) { libraryFilterRequested=false; showLibraryFilters() }
        }, { problem ->
            if(requestTab==6) libraryFilterRequested=false
            if(searchRevision!=null && phoneSearchResults.revision==searchRevision && requestTab !in listOf(3,4)) phoneSearchResults=com.hongguotv.core.PhoneSearchResults(searchRevision,requestQuery,requestType,requestPage,
                error="内容暂时无法加载，请检查电视网络后重试",section=if(requestTab==6) "library" else if(requestTab==1) "search" else "home",genre=requestGenre,filters=requestFilters,filterRows=catalogFilterRows)
            if(requestTab==0 && requestPage==1) {
                homeScreen?.refresh?.text=if(catalog.isNotEmpty()) "当前显示上次内容 · 联网后按确认刷新" else "热门暂时无法加载 · 按确认重试"
                homeScreen?.refresh?.setOnClickListener { showCatalog(true) }
                return@work
            }
            setCatalog(emptyList(),requestType,if(requestTab==1) requestQuery else ""); hasMore=false; ranking=null; rankSubtitle?.text="榜单加载失败"
            if(problem is SearchSessionExpiredException) {
                page=1; catalogFocus=""
                Toast.makeText(this,"搜索结果已过期，已回到第 1 页刷新",Toast.LENGTH_LONG).show()
                showCatalog(true)
            } else {
                body.removeAllViews()
                error(body,problem) { showCatalog(true) }
                if(resumeCards.isNotEmpty()) resumeCards.first().requestFocus()
            }
        })
    }
    private fun phoneCatalogSnapshot(revision: Long,sourceTab: Int,sourcePage: Int,rows: List<Series>,more: Boolean,
        contentType: ContentType=library.contentType): com.hongguotv.core.PhoneSearchResults {
        val displayed=if(sourceTab in listOf(3,4)) rows.drop((sourcePage-1)*pageSize).take(pageSize)
            else if(sourceTab==0) rows.filterNot { it.id in library.hidden() }.take(30) else rows.take(30)
        return com.hongguotv.core.PhoneSearchResults(revision,if(sourceTab==1) query else "",contentType,sourcePage,items=displayed.toList(),hasMore=more,
            section=when(sourceTab) { 0 -> "home"; 3 -> "favorites"; 4 -> "history"; 6 -> "library"; else -> "search" },
            genre=catalogGenre,filterRows=if(sourceTab==6) catalogFilterRows else emptyList(),filters=if(sourceTab==6) catalogFilters else emptyMap(),
            favorites=displayed.filter { library.favorite(it.id) }.map { it.id }.toSet(),
            progress=displayed.mapNotNull { item -> library.progress(item.id)?.let { p -> item.id to
                if(p.completed) "已看完第 ${p.episodeIndex+1} 集" else "上次第 ${p.episodeIndex+1} 集 · ${formatTime(p.position)}" } }.toMap())
    }
    private fun refreshPhoneFavorites() {
        val snapshot=phoneSearchResults
        phoneSearchResults=snapshot.copy(favorites=snapshot.items.filter { library.favorite(it.id) }.map { it.id }.toSet())
    }
    private fun renderLibraryFilters() {
        libraryGenreButtons.forEach { (genre,button) ->
            val count=if(genre==catalogGenre) catalogFilters.size else 0
            button.text=genre.label+(if(count>0) " · $count" else "")+" ▾"
        }
    }
    private fun showLibraryFilters() {
        if(tab!=6 || catalogFilterRows.isEmpty()) return
        tvTools.catalogFilters(catalogGenre.label,catalogFilterRows,catalogFilters,libraryGenreButtons[catalogGenre]) { selected ->
            catalogFilters=selected; page=1; catalogFocus=""; showCatalog(true)
        }
    }
    private fun catalogGrid(body: LinearLayout,focusNav: Boolean,focusType: Boolean=false,loading: Boolean=false) {
        if(tab==0 && page==1 && collection==null) { renderHome(body,focusNav,focusType,loading); return }
        val waiting=when {
            tab==3 && librarySources.accountFavorites -> cloudAccount.favoriteState().loading
            tab==4 && librarySources.accountHistory -> cloudAccount.historyLoading
            tab==7 -> cloudAccount.reservationState(reservationOnline).loading
            tab==6 -> categorySnapshotLoading
            else -> false
        }
        if((loading || waiting) && catalog.isEmpty()) {
            body.addView(TvLoading(this,when(tab) { 3 -> "正在读取账户收藏…"; 4 -> "正在读取账户历史…"; 7 -> "正在读取短剧预约…"; else -> "正在加载剧库…" }),lp(-1,0).apply { weight=1f })
            if(focusType) libraryEntry?.requestFocus() else nav[tab].requestFocus()
            return
        }
        resumeCards.clear(); catalogCards.clear(); cardImages.clear(); collectionBack=null
        if(collection!=null) {
            val heading=row()
            collectionBack=addButton(heading,"‹ 返回首页") { leaveCollection(); showCatalog() }.apply {
                nextFocusUpId=typeButtons[library.contentType]?.id ?: nav[tab].id
                nextFocusDownId=id
            }
            heading.addView(text(when(collection) { "later" -> "稍后看"; "updates" -> "收藏有更新"; "hot" -> "热门发现"; else -> "接着看" },22f))
            body.addView(heading)
            typeButtons.values.forEach { it.nextFocusDownId=collectionBack!!.id }
        }
        catalogPageKey="$tab:${catalogType()}:${collection.orEmpty()}:$page:${if(tab==1) query else ""}:"+
            if(tab==6) "${catalogGenre.value}:${catalogFilters.toSortedMap()}" else ""
        val preview=SeriesPreview(this); selectionPreview=preview; body.addView(preview)
        val scroll=ScrollView(this).apply { isFillViewport=false; isVerticalScrollBarEnabled=false; clipToPadding=false }; catalogScroll=scroll
        scroll.setOnScrollChangeListener { _,_,_,_,_ -> artwork.refreshVisible() }
        val list=column(); scroll.addView(list); body.addView(scroll,lp(-1,0).apply { weight=1f })
        var restoringScroll=true
        val displayed=if(localCatalog()) catalog.drop((page-1)*pageSize).take(pageSize) else if(tab==0) catalog.filterNot { it.id in library.hidden() } else catalog.take(30)
        val restorePosition=catalogFocus.isNotBlank() && displayed.any { it.id==catalogFocus }
        preview.visibility=if(displayed.isEmpty()) View.GONE else View.VISIBLE
        if(displayed.isEmpty()) {
            accountStatus?.visibility=View.GONE
            message(list,if(loading || tab==6 && categorySnapshotLoading) "正在加载剧库…" else when(tab) {
                1 -> if(query.isBlank()) "输入关键词，用遥控器确认搜索" else "没有找到相关${library.contentType.label}，换个关键词试试"
                3 -> if(librarySources.accountFavorites) (if(!cloudAccount.signedIn) "请先在设置中登录红果账号" else cloudAccount.favoriteState().message) else "在剧集详情中选择收藏，喜欢的剧就会出现在这里"
                4 -> if(librarySources.accountHistory) (if(!cloudAccount.signedIn) "请先在设置中登录红果账号" else cloudAccount.historyStatus) else "播放过的剧集会自动保存在这里"
                6 -> "当前分类暂无剧集，请更换筛选条件。"
                7 -> if(!cloudAccount.signedIn) "账号未登录，请先登录红果账号后同步短剧预约" else cloudAccount.reservationState(reservationOnline).message
                else -> "本页没有更多内容"
            })
            if(!cloudAccount.signedIn && (tab==7 || tab==3 && librarySources.accountFavorites || tab==4 && librarySources.accountHistory)) {
                addButton(list,"登录红果账号") { cloudAccount.show() }
            }
            if((tab<=2 || tab==6 || localCatalog()) && page>1) addButton(list,"上一页") { page--; showCatalog(!localCatalog()) }
            if((tab<=2 || tab==6 || localCatalog()) && hasMore) addButton(list,"下一页") { page++; showCatalog(!localCatalog()) }
            if(resumeCards.isNotEmpty()) {
                typeButtons.values.forEach { it.nextFocusDownId=resumeCards.first().id }
                resumeCards.forEach { it.nextFocusDownId=it.id }
            }
            if(focusType) typeButtons[catalogType()]?.requestFocus() else if(collectionBack!=null && !focusNav) collectionBack?.requestFocus() else if(focusNav || resumeCards.isEmpty()) nav[tab].requestFocus() else resumeCards.first().requestFocus()
            return
        }
        val cards=mutableListOf<View>(); val gap=dp(14)
        // The body already sits inside base()'s shared safe-area gutter. Use
        // that measured width directly so non-home cards line up with home.
        val availableWidth=(root.width.takeIf { it>0 } ?: resources.displayMetrics.widthPixels)-pageInsetPx()*2
        val count=TvLayout.columns(this,availableWidth-dp(8),120,6)
        val width=((availableWidth-gap*(count-1)-dp(8))/count).coerceAtLeast(1)
        displayed.chunked(count).forEach { items ->
            val line=row(); line.gravity=Gravity.TOP
            items.forEachIndexed { columnIndex,item ->
                val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(9)); focusStyle(card,flat=true); card.contentDescription=item.title; card.tag=item.id; catalogCards[item.id]=card
                val position=if(tab==2) ranking?.positions?.get(item.id) else null
                val artwork=FrameLayout(this); card.addView(artwork,lp(-1,((width-dp(8))/TvStyle.POSTER_ASPECT).toInt()))
                val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }; artwork.addView(image,FrameLayout.LayoutParams(-1,-1)); loadCover(image,item.cover); cardImages[item.id]=image to item.cover
                if(tab==2) {
                    val rankLabel=position?.rank?.let { "第 $it 名" } ?: "名次暂无"
                    artwork.addView(text(rankLabel,15f).apply { setTypeface(null,Typeface.BOLD); setPadding(dp(8),dp(5),dp(8),dp(5)); background=rounded(if((position?.rank ?: Int.MAX_VALUE)<=3) accent else Color.rgb(28,31,39)) },FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START))
                    card.contentDescription="$rankLabel，${item.title}，${position?.heat?.ifBlank { "热度暂无" } ?: "热度暂无"}"
                }
                card.addView(text(item.title,14f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(7),dp(3),0) },lp(-1,-2))
                val accountHistory=tab==4 && librarySources.accountHistory
                val progress=if(tab==4 && !accountHistory || collection=="resume") library.progress(item.id) else null
                val cloudProgress=if(accountHistory) cloudAccount.progress(item.id) else null
                val badgeText=when {
                    tab==3 && librarySources.accountFavorites -> cloudProgress?.let { "账户第 ${it.episodeIndex+1} 集" } ?: item.badge
                    tab==3 -> library.favoriteLabel(item.id)
                    tab==7 -> item.badge.ifBlank { if(reservationOnline) "已上线" else "待上线" }
                    tab==2 -> position?.heat?.ifBlank { "热度暂无" } ?: "热度暂无"
                    progress!=null -> if(library.watched(item.id)) "整剧已看完" else "第 ${progress.episodeIndex+1} 集 · ${formatTime(progress.position)}"
                    else -> item.badge
                }
                val badge=text(badgeText,12f,if(tab==2 || tab==3 || tab==7) accent else muted).apply { maxLines=1; minLines=1; minHeight=dp(19); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) }
                card.addView(badge,lp(-1,-2)); if(tab==3) favoriteBadges[item.id]=badge
                card.setOnClickListener {
                    if(tab==7 && !reservationOnline) { tvTools.info("待上线预约", "《${item.title}》尚未上线，暂不可播放。上线后可在“已上线”中打开。", card); return@setOnClickListener }
                    if(tab==6) library.contentType=catalogGenre.type
                    catalogFocus=item.id
                    val resume=tab==4 || collection=="resume" || collection=="updates"
                    openDetail(item,resume,cloudProgress=cloudProgress)
                }
                card.setOnLongClickListener { catalogFocus=item.id; quickActions(item,card); true }
                card.setOnKeyListener { _,key,event -> if(key==KeyEvent.KEYCODE_MENU) { if(event.action==KeyEvent.ACTION_UP) { catalogFocus=item.id; quickActions(item,card) }; true } else false }
                TvMotion.focus(card,false,Color.TRANSPARENT,Color.TRANSPARENT,focusedScale=1.015f)
                card.setOnFocusChangeListener { _,focused -> TvMotion.focus(card,focused,Color.TRANSPARENT,if(focused) accent else Color.TRANSPARENT,focusedScale=1.015f); if(focused) { catalogFocus=item.id; loadCover(image,item.cover); preview.show(item,selectionStatus(item)); if(!restoringScroll) scroll.post { scroll.smoothScrollTo(0,when { line.top<scroll.scrollY -> line.top; line.bottom>scroll.scrollY+scroll.height -> (line.bottom-scroll.height).coerceAtLeast(0); else -> scroll.scrollY }) } } }
                // Fixed text slots align badges without stretching a card during the row's second measure.
                line.addView(card,lp(width,-2).apply { if(columnIndex<count-1) rightMargin=gap }); cards+=card
            }
            list.addView(line,lp(-1,-2).apply { bottomMargin=dp(10) })
        }
        val paging=row(); paging.gravity=Gravity.CENTER
        var prev: View?=null; var next: View?=null
        if(tab<=2 || tab==6 || localCatalog()) {
            prev=addButton(paging,"上一页") { if(page>1) { page--; catalogFocus=""; showCatalog(!localCatalog()) } }.apply { isEnabled=page>1; isFocusable=page>1; alpha=if(page>1) 1f else .4f }
            paging.addView(text(if(localCatalog()) "第 $page / ${maxOf(1,(catalog.size+pageSize-1)/pageSize)} 页" else if(tab==2 && ranking!=null) "第 $page / ${ranking!!.totalPages} 页" else "第 $page 页",14f,muted).apply { gravity=Gravity.CENTER },lp(dp(if(tab==2) 135 else 95),dp(44)))
            next=addButton(paging,"下一页") { if(hasMore) { page++; catalogFocus=""; showCatalog(!localCatalog()) } }.apply { isEnabled=hasMore; isFocusable=hasMore; alpha=if(hasMore) 1f else .4f }
            if(collection=="hot" && !hasMore && collectionReturn?.hasMore==true) {
                next=addButton(paging,"下一批热门") { collection=null; collectionReturn=null; page=2; catalogFocus=""; showCatalog(true) }
            }
            list.addView(paging,lp(-1,-2).apply { topMargin=dp(6); bottomMargin=dp(6) })
        }
        cards.forEachIndexed { index,card ->
            card.nextFocusLeftId=if(index%count==0) card.id else cards[index-1].id
            card.nextFocusRightId=if(index%count==count-1 || index==cards.lastIndex) card.id else cards[index+1].id
            card.nextFocusUpId=if(index<count) (libraryEntry?.id ?: searchInput?.id ?: collectionBack?.id ?: typeButtons[catalogType()]?.id ?: rankRefresh?.id ?: favoriteCheck?.id ?: historyManage?.id ?: accountSourceButtons.firstOrNull()?.id ?: reservationButtons.firstOrNull()?.id ?: resumeCards.getOrNull(minOf(index,2))?.id ?: nav[tab].id) else cards[index-count].id
            card.nextFocusDownId=if(index+count<cards.size) cards[index+count].id else if(index/count<cards.lastIndex/count) cards.last().id else next?.takeIf { it.isFocusable }?.id ?: prev?.takeIf { it.isFocusable }?.id ?: card.id
        }
        nav.forEach { it.nextFocusDownId=libraryEntry?.id ?: typeButtons[catalogType()]?.id ?: rankRefresh?.id ?: favoriteCheck?.id ?: historyManage?.id ?: accountSourceButtons.firstOrNull()?.id ?: reservationButtons.firstOrNull()?.id ?: cards.first().id }
        rankRefresh?.nextFocusDownId=cards.first().id
        historyManage?.nextFocusDownId=cards.first().id
        favoriteCheck?.nextFocusDownId=cards.first().id
        collectionBack?.nextFocusDownId=cards.first().id
        resumeCards.forEachIndexed { index,v -> v.nextFocusDownId=cards[minOf(index,cards.lastIndex)].id }
        if(resumeCards.isNotEmpty()) cards.take(count).forEachIndexed { index,v -> v.nextFocusUpId=resumeCards[minOf(index,resumeCards.lastIndex)].id }
        else homeUpdates?.let { updates -> updates.nextFocusUpId=typeButtons[library.contentType]?.id ?: nav[0].id; updates.nextFocusDownId=cards.first().id; cards.take(count).forEach { it.nextFocusUpId=updates.id } }
        typeButtons.values.forEach { it.nextFocusDownId=searchInput?.id ?: collectionBack?.id ?: resumeCards.firstOrNull()?.id ?: homeUpdates?.id ?: cards.first().id }
        searchInput?.nextFocusDownId=cards.first().id
        searchButton?.nextFocusDownId=cards[minOf(4,cards.lastIndex)].id
        searchActions.forEach { it.nextFocusDownId=cards[minOf(4,cards.lastIndex)].id }
        if(focusType) typeButtons[catalogType()]?.requestFocus() else if(focusNav) nav[tab].requestFocus()
        else if(resumeCards.isNotEmpty() && (catalogFocus.isEmpty() || catalogFocus.startsWith("resume:"))) (resumeCards.firstOrNull { it.tag==catalogFocus } ?: resumeCards.first()).requestFocus()
        else cards[displayed.indexOfFirst { it.id==catalogFocus }.coerceAtLeast(0)].requestFocus()
        val savedScroll=if(restorePosition) scrollPositions[catalogPageKey] ?: 0 else 0
        scroll.post {
            if(catalogScroll!==scroll) return@post
            cards.forEach { view ->
                val card=view as LinearLayout
                val image=card.getChildAt(0)
                val textHeight=card.paddingTop+card.paddingBottom+(1 until card.childCount).sumOf { card.getChildAt(it).measuredHeight }
                val target=minOf(((width-dp(8))/TvStyle.POSTER_ASPECT).toInt(),(scroll.height-textHeight-dp(4)).coerceAtLeast(dp(48)))
                if(image.layoutParams.height!=target) image.layoutParams=image.layoutParams.apply { height=target }
            }
            // Wait for the resized posters to finish layout before restoring/aligning the row.
            val observer=object: android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                if(list.isLayoutRequested) return
                list.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if(catalogScroll!==scroll) return
                scroll.scrollTo(0,savedScroll)
                (cards.firstOrNull { it.hasFocus() }?.parent as? View)?.let { line ->
                    val y=when {
                        line.top<scroll.scrollY -> line.top
                        line.bottom>scroll.scrollY+scroll.height -> if(line.height>=scroll.height) line.top else line.bottom-scroll.height
                        else -> scroll.scrollY
                    }
                    scroll.scrollTo(0,y.coerceAtLeast(0))
                }
                restoringScroll=false
                artwork.refreshVisible()
                }
            }
            list.viewTreeObserver.addOnGlobalLayoutListener(observer)
            list.requestLayout()
        }
    }
    private fun selectionStatus(series: Series): String {
        val progress=library.progress(series.id)
        return listOf(if(library.favorite(series.id)) "已收藏" else "",if(library.queued(series.id)) "稍后看" else "",
            if(library.watched(series.id)) "整剧已看完" else progress?.let { "上次第 ${it.episodeIndex+1} 集 · ${formatTime(it.position)}" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
    }
    fun isFavorite(series: Series): Boolean = library.favorite(series.id)
    private fun renderHome(body: LinearLayout,focusNav: Boolean,focusType: Boolean,loading: Boolean) {
        stopHomeHero()
        homeUpdateCount=library.updatedFavorites()
        selectionPreview=null
        lateinit var home: HomeScreen
        val banner=HomeRemoteBanner(this,tvTools) {
            homeRemoteDismissed=true; home.dismissTop()
            typeButtons.values.forEach { it.nextFocusDownId=home.firstId() }
            if(inlineHomeFilters()) nav.forEach { it.nextFocusDownId=home.firstId() }
        }
            .apply { visibility=if(homeRemoteDismissed) View.GONE else View.VISIBLE }
        home=HomeScreen(this,banner,::loadCover,{ _,_ -> },
            { item,resume -> openDetail(item,resume) },::quickActions,{ catalogFocus=it }).apply {
            nextFocusUpId=if(inlineHomeFilters()) nav[0].id else typeButtons[library.contentType]?.id ?: nav[0].id
        }
        homeScreen=home
        body.addView(home,lp(-1,0).apply { weight=1f })
        val hot=catalog.filterNot { it.id in library.hidden() }
        val shelves=homeShelves()
        home.render(shelves,catalogFocus,!focusNav && !focusType,
            if(hasMore) { { page=2; catalogFocus=""; showCatalog(true) } } else null,homePosition,loading=loading)
        home.refresh.text=if(loading) if(homeFromCache) "已显示上次内容 · 正在更新热门…" else "正在更新热门…" else "刷新热门"
        home.refresh.setOnClickListener { showCatalog(true) }
        val homeFocus=findViewById<View>(home.firstId())
        typeButtons.values.forEach { it.nextFocusDownId=homeFocus.id }
        if(inlineHomeFilters()) nav.forEach { it.nextFocusDownId=homeFocus.id }
        if(focusNav) nav[0].requestFocus() else if(focusType) typeButtons.getValue(library.contentType).requestFocus()
        if(hot.isNotEmpty() && !homeContentLogged) {
            homeContentLogged=true
            body.post { android.util.Log.i("HongguoTV","Home content ready cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-activityStarted}") }
        }
        if(launchStarted>0) {
            val started=launchStarted; launchStarted=0
            body.post { android.util.Log.i("HongguoTV","Home displayed cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}") }
        }
    }
    private fun homeShelves(): List<HomeScreen.Shelf> {
        val recent=library.history().filterNot { library.watched(it.series.id) }
        val updates=library.favorites().filter { (library.favoriteUpdate(it.id)?.added ?: 0)>0 }
        val later=library.later(); val hidden=library.hidden()
        fun shelf(key: String,title: String,items: List<Series>) = HomeScreen.Shelf(key,title,
            items.filterNot { it.id in hidden }.take(12).map { HomeScreen.Entry(it,it.badge.ifBlank { "查看剧集" }) })
        val shelves=mutableListOf<HomeScreen.Shelf>()
        if(recent.isNotEmpty()) shelves+=HomeScreen.Shelf("resume","继续观看",recent.take(8).map { p -> HomeScreen.Entry(p.series,
            "第 ${p.episodeIndex+1} 集 · ${if(p.completed) "接着看下一集" else formatTime(p.position)}",true,
            if(p.duration>0) (p.position*100/p.duration).toInt().coerceIn(0,100) else null) },
            if(recent.size>8) { { showCollection("resume") } } else null)
        val hot=if(library.contentType==ContentType.COMIC) homeContent.comicHot else homeContent.shortHot
        shelves+=shelf("hot","热门发现 · ${library.contentType.label}",hot.ifEmpty { catalog })
        if(updates.isNotEmpty()) shelves+=HomeScreen.Shelf("updates","收藏有更新",updates.take(8).map { HomeScreen.Entry(it,library.favoriteLabel(it.id),true) },
            if(updates.size>8) { { showCollection("updates") } } else null)
        if(later.isNotEmpty()) shelves+=HomeScreen.Shelf("later","稍后看",later.take(10).map { HomeScreen.Entry(it,"稍后看 · 长按管理") },
            if(later.size>10) { { showCollection("later") } } else null)
        return shelves
    }
    private fun refreshLibraryCards(changedId: String,removed: Boolean=false) {
        if(screen!="catalog") return
        val home=homeScreen
        if(home!=null) { home.updateShelves(homeShelves(),requestFocus=true); home.refreshSelection(); return }
        if(removed && (localCatalog() || tab==0)) {
            val oldIds=catalogCards.keys.toList(); val index=oldIds.indexOf(changedId).coerceAtLeast(0)
            if(localCatalog()) {
                setCatalog(localItems(),catalogType()); page=page.coerceIn(1,maxOf(1,(catalog.size+pageSize-1)/pageSize)); hasMore=page*pageSize<catalog.size
            }
            val remaining=if(localCatalog()) catalog.drop((page-1)*pageSize).take(pageSize) else catalog.filterNot { it.id in library.hidden() }
            catalogFocus=remaining.getOrNull(index.coerceAtMost((remaining.size-1).coerceAtLeast(0)))?.id.orEmpty()
            val y=catalogScroll?.scrollY ?: 0; scrollPositions[catalogPageKey]=y
            artwork.cancelPage(); favoriteBadges.clear()
            catalogBody?.let { body -> body.removeAllViews(); catalogGrid(body,false) }
        } else {
            catalog.firstOrNull { it.id==changedId }?.let { selectionPreview?.show(it,selectionStatus(it)) }
            favoriteBadges[changedId]?.text=library.favoriteLabel(changedId)
        }
    }
    private fun quickActions(series: Series,anchor: View) {
        val labels=mutableListOf(if(library.progress(series.id)!=null) "继续观看" else "直接播放",
            if(library.favorite(series.id)) "取消收藏" else "收藏这部剧",
            if(library.queued(series.id)) "移出稍后看" else "加入稍后看","查看剧集详情")
        if(tab==0) labels+="不在热门推荐中显示"
        if(tab==4) labels+="管理观看记录"
        tvTools.choose(series.title,labels,anchor,{ choice ->
            when(choice) {
                0 -> openDetail(series,true)
                1 -> { val added=library.toggle(series); Toast.makeText(this,if(added) "已收藏" else "已取消收藏",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && (tab==3 || collection=="updates")); if(added) favoriteMonitor.check() }
                2 -> { val added=library.toggleLater(series); Toast.makeText(this,if(added) "已加入稍后看" else "已移出稍后看",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && collection=="later") }
                3 -> openDetail(series)
                4 -> if(tab==4) manageHistory(series,anchor) else { library.hide(series.id); Toast.makeText(this,"已隐藏热门推荐，可在设置中恢复",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,true) }
            }
        })
    }
    private fun refreshFavoriteLabels() {
        if(screen!="catalog" || !library.isLoaded) return
        favoriteBadges.forEach { (id,label) -> val value=library.favoriteLabel(id); if(label.text.toString()!=value) label.text=value }
        favoriteStatus?.text=favoriteMonitor.status
        favoriteCheck?.text=if(favoriteMonitor.running) "正在检查…" else "检查更新"
        homeUpdates?.text="收藏更新 ${library.updatedFavorites()} 部"
        val home=homeScreen
        if(screen=="catalog" && tab==0 && page==1 && collection==null && home!=null && library.updatedFavorites()!=homeUpdateCount) {
            // A stable resume key can be restored without moving the user's selection.
            if(!tvTools.showing && !updater.showing && currentFocus?.tag?.toString()?.startsWith("resume:")==true) {
                homeUpdateCount=library.updatedFavorites(); home.updateShelves(homeShelves())
            } else {
                home.refresh.text="收藏更新 ${library.updatedFavorites()} 部 · 按确认查看"
                home.refresh.setOnClickListener { showCatalog() }
            }
        }
    }
    private fun settings(parent: LinearLayout) {
        parent.addView(text("按你的习惯播放",25f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); setPadding(0,dp(15),0,dp(7)) })
        parent.addView(text("偏好自动保存，下一部剧继续使用。",13f,muted).apply { setPadding(0,0,0,dp(18)) })
        val stacked=widthDp()/resources.configuration.fontScale.coerceAtLeast(1f)<740
        val groups=if(stacked) column() else row().apply { gravity=Gravity.TOP }
        val playback=column(); val device=column()
        listOf(playback,device).forEachIndexed { i,group ->
            group.setPadding(dp(16),dp(16),dp(16),dp(16)); group.background=rounded(surface)
            groups.addView(group,if(stacked) lp(-1,-2).apply { if(i==0) bottomMargin=dp(16) }
                else lp(0,-2).apply { weight=1f; if(i==0) rightMargin=dp(16) })
        }
        parent.addView(groups)
        fun heading(group: LinearLayout,title: String,subtitle: String) {
            group.addView(text(title,18f).apply { setTypeface(null,Typeface.BOLD) })
            group.addView(text(subtitle,12f,muted).apply { setPadding(0,dp(5),0,dp(15)) })
        }
        fun setting(group: LinearLayout,label: String,action: ()->Unit): TextView {
            val view=button(label,onClick=action).apply {
                gravity=Gravity.CENTER_VERTICAL; setPadding(dp(13),dp(11),dp(13),dp(11))
                TvMotion.focus(this,false,bg,Color.TRANSPARENT,focusedScale=1f)
                setOnFocusChangeListener { _,focused -> TvMotion.focus(this,focused,if(focused) TvStyle.raised else bg,if(focused) accent else Color.TRANSPARENT,focusedScale=1f) }
            }
            group.addView(view,lp(-1,-2).apply { bottomMargin=dp(8) })
            return view
        }
        heading(playback,"播放偏好","所有剧集共用，播放中也可调整")
        heading(device,"片单与设备","保管记录，保持应用更新")
        lateinit var qualityButton: TextView
        qualityButton=setting(playback,"默认清晰度  ·  ${library.maxQuality}P") { showQualityPicker(qualityButton,false) }
        lateinit var defaultSpeed: TextView
        defaultSpeed=setting(playback,"默认倍速  ·  ${PlaybackSpeed.label(library.playbackSpeed)}") { showSpeedPicker(defaultSpeed,false) }
        lateinit var frame: TextView
        frame=setting(playback,"画面模式  ·  ${library.frameMode.label}") { showFramePicker(frame,false) }
        lateinit var autoNextButton: TextView
        autoNextButton=setting(playback,"自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}") {
            library.autoNext=!library.autoNext
            autoNextButton.text="自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}"
        }
        lateinit var backup: TextView
        backup=setting(device,"手机备份与恢复") {
            runCatching { LibraryBackup.encode(library.snapshot()) }.onSuccess { encoded ->
                tvTools.libraryTransfer(encoded,backup) { data,restoreSettings ->
                    favoriteMonitor.stop(); library.restore(data,restoreSettings)
                    tabState.clear(); tabFocus.clear(); catalogFocus=""; page=1
                    showCatalog(); favoriteMonitor.check()
                    Toast.makeText(this,"记录已合并恢复",Toast.LENGTH_LONG).show()
                }
            }.onFailure { tvTools.info("无法生成备份","本机记录暂时无法导出，请重试。",backup) }
        }
        lateinit var hidden: TextView
        hidden=setting(device,"恢复隐藏推荐  ·  ${library.hidden().size} 部") {
            library.unhideAll(); hidden.text="恢复隐藏推荐  ·  0 部"; Toast.makeText(this,"热门推荐已恢复",Toast.LENGTH_SHORT).show()
        }
        lateinit var updates: TextView
        updates=setting(device,"版本与更新  ·  ${BuildConfig.VERSION_NAME}") { updater.show(updates) }
        accountSetting=setting(device,"红果账号管理  ·  ${cloudAccount.accountLabel}") { cloudAccount.show() }
        val license=setting(device,"开源许可") {
            TvDialog.Builder(this).setTitle("开源许可").setMessage("本原生版以 GPL-3.0 发布。\n内容协议与加密处理移植自 drpys（22261ad）。\nAndroidX Media3 / OkHttp：Apache-2.0\nKotlin：Apache-2.0\nBouncy Castle：MIT\n完整源码和许可证见 GitHub：N3urda/hongguoTV-updates。").setPositiveButton("关闭",null).show()
        }
        val left=listOf(qualityButton,defaultSpeed,frame,autoNextButton); val right=listOf(backup,hidden,updates,accountSetting!!,license)
        if(stacked) {
            val items=left+right
            items.forEachIndexed { index,view ->
                view.nextFocusUpId=items.getOrNull(index-1)?.id ?: nav[tab].id
                view.nextFocusDownId=items.getOrNull(index+1)?.id ?: view.id
                view.nextFocusLeftId=view.id; view.nextFocusRightId=view.id
            }
        } else {
            listOf(left,right).forEach { items -> items.forEachIndexed { index,view ->
                view.nextFocusUpId=items.getOrNull(index-1)?.id ?: nav[tab].id
                view.nextFocusDownId=items.getOrNull(index+1)?.id ?: view.id
            } }
            left.forEachIndexed { i,v -> v.nextFocusLeftId=v.id; v.nextFocusRightId=right.getOrNull(i)?.id ?: v.id }
            right.forEachIndexed { i,v -> v.nextFocusLeftId=left.getOrNull(i)?.id ?: v.id; v.nextFocusRightId=v.id }
        }
        nav.forEach { it.nextFocusDownId=qualityButton.id }
        parent.addView(text("红果 TV  /  ${BuildConfig.VERSION_NAME}    ·    收藏与进度保存在本机",12f,muted).apply { setPadding(dp(2),dp(18),0,dp(10)) })
        nav[tab].requestFocus()
    }
    private fun runSearch(value: String,type: ContentType=library.contentType) {
        val clean=value.trim(); if(clean.isEmpty() || clean.length>80) return
        if(!foreground || !library.isLoaded) return
        cancelPhoneCatalog()
        if(screen=="player") { saveProgress(); library.flush(); releasePlayer() } else tvTools.close()
        leaveCollection()
        (searchInput as? EditText)?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken,0) }
        if(library.contentType!=type) { library.contentType=type; for(index in 0..1) { tabState.remove(index); tabFocus.remove(index) } }
        library.rememberSearch(clean,type); query=clean; tab=1; page=1; catalogFocus=""; showCatalog(true)
    }
    private fun playPhoneSeries(revision: Long,id: String) {
        val snapshot=phoneSearchResults
        if(!foreground || !library.isLoaded || snapshot.revision!=revision || !snapshot.selectable(id)) return
        val series=snapshot.items.firstOrNull { it.id==id } ?: return
        saveProgress(); library.flush(); releasePlayer()
        library.contentType=snapshot.type
        if(snapshot.section=="library") { catalogGenre=snapshot.genre; catalogFilters=snapshot.filters; catalogFilterRows=snapshot.filterRows }
        leaveCollection()
        query=snapshot.query; page=snapshot.page; tab=phoneCatalogTab(snapshot.section); setCatalog(snapshot.items,snapshot.type,snapshot.query); hasMore=snapshot.hasMore; catalogFocus=id
        openDetail(series,resume=true)
    }
    private fun turnPhoneSearchPage(revision: Long,target: Int) {
        val snapshot=phoneSearchResults
        if(!foreground || !library.isLoaded || snapshot.revision!=revision || !snapshot.canTurnTo(target)) return
        if(snapshot.section!="search") { loadPhoneCatalog(snapshot.section,snapshot.type,target,snapshot.genre,snapshot.filters); return }
        if(screen=="player") { saveProgress(); library.flush(); releasePlayer() } else tvTools.close()
        leaveCollection(); library.contentType=snapshot.type
        query=snapshot.query; page=target; tab=phoneCatalogTab(snapshot.section); catalogFocus=""; showCatalog(load=tab in listOf(0,1))
    }
    private fun phoneCatalogTab(section: String)=when(section) { "home" -> 0; "favorites" -> 3; "history" -> 4; "library" -> 6; else -> 1 }
    private fun browsePhoneCatalog(section: String,type: ContentType) {
        if(!foreground || !library.isLoaded) return
        loadPhoneCatalog(section,type,1)
    }
    private fun cancelPhoneCatalog() {
        phoneCatalogScope?.cancel(); phoneCatalogScope=null; phoneCatalogJob?.cancel(true); phoneCatalogJob=null
    }
    private fun loadPhoneCatalog(section: String,type: ContentType,target: Int,genre: CatalogGenre=CatalogGenre.COMIC,filters: Map<String,String> = emptyMap()) {
        cancelPhoneCatalog()
        val revision=++phoneSearchRevision; val sourceTab=phoneCatalogTab(section)
        if(sourceTab in listOf(3,4)) {
            val rows=if(sourceTab==3) library.favorites() else library.history().map { it.series }
            val selected=target.coerceIn(1,maxOf(1,(rows.size+pageSize-1)/pageSize))
            phoneSearchResults=phoneCatalogSnapshot(revision,sourceTab,selected,rows,selected*pageSize<rows.size,type)
            return
        }
        categorySnapshotLoading=section=="library"
        val currentRows=phoneSearchResults.filterRows.takeIf { phoneSearchResults.genre==genre }.orEmpty()
        phoneSearchResults=PhoneSearchResults(revision=revision,type=type,page=target,loading=true,section=section,genre=genre,filters=filters,filterRows=currentRows)
        val scope=RequestScope(); phoneCatalogScope=scope
        phoneCatalogJob=phoneCatalogWorker.submit {
            try {
                val (result,rows)=scope.run { if(section=="library") catalogRepository.catalog(genre,target,filters) to catalogRepository.catalogFilters(genre)
                    else repository.home(target,type) to emptyList() }
                main.post { if(!isDestroyed && foreground && phoneSearchResults.revision==revision) {
                    categorySnapshotLoading=false
                    phoneSearchResults=phoneCatalogSnapshot(revision,sourceTab,target,result.items,result.hasMore,type)
                        .copy(genre=genre,filters=filters,filterRows=rows)
                } }
            } catch(_: Exception) {
                main.post { if(!isDestroyed && foreground && phoneSearchResults.revision==revision) { categorySnapshotLoading=false
                    phoneSearchResults=phoneSearchResults.copy(loading=false,error="剧库加载失败，请检查电视网络后重试") }
                }
            }
        }
    }
    private fun favoritePhoneSeries(revision: Long,id: String,value: Boolean) {
        val snapshot=phoneSearchResults
        if(!foreground || !library.isLoaded || snapshot.revision!=revision || !snapshot.selectable(id)) return
        val series=snapshot.items.firstOrNull { it.id==id } ?: return
        if(library.favorite(id)!=value) library.toggle(series)
        refreshPhoneFavorites()
        if(snapshot.section=="favorites" && !value) loadPhoneCatalog("favorites",snapshot.type,snapshot.page)
        if(screen=="player") syncMediaSession()
    }
    private fun executePhoneControl(command: com.hongguotv.core.PhoneControl) {
        if(!foreground || !library.isLoaded || !command.allowed(phonePlayback)) return
        when(command.action) {
            "home" -> { saveProgress(); library.flush(); releasePlayer(); leaveCollection(); tab=0; page=1; catalogFocus=""; showCatalog(true) }
            "stop" -> { saveProgress(); library.flush(); releasePlayer(); invalidatePage(); directPlayback=false
                if(detail!=null) showDetail(true) else showCatalog() }
            "play","pause" -> { episodePanel?.dismiss(); speedDialog?.dismiss(); tvTools.close(); requestPlayback(command.action=="play") }
            "previous" -> skipEpisode(-1)
            "next" -> skipEpisode(1)
            "seek" -> seekTo(currentPosition()+command.value.toLong()*1000)
            "speed" -> { library.playbackSpeed=command.value.toFloat(); player?.setPlaybackSpeed(library.playbackSpeed)
                if(screen=="player") syncMediaSession() else phonePlayback=phonePlayback.copy(speed=library.playbackSpeed) }
            "favorite" -> { val series=detail?.series ?: pendingDetail ?: return
                if(library.favorite(series.id)!=(command.value=="1")) library.toggle(series)
                refreshPhoneFavorites(); if(screen=="player") syncMediaSession() else phonePlayback=phonePlayback.copy(favorite=library.favorite(series.id)) }
            "retry" -> if(screen=="player") refreshPlayback(autoplay=true)
                else pendingDetail?.let { openDetail(it,resume=true) }
        }
    }
    private fun showSearchHistory(anchor: View) {
        val history=library.searches()
        if(history.isEmpty()) { tvTools.info("搜索历史","搜索过的剧名会保存在这里，最多保留 20 条。",anchor); return }
        tvTools.choose("搜索历史",history.map { "${it.type.label} · ${it.query}" },anchor,{ index -> runSearch(history[index].query,history[index].type) },"清空历史") {
            tvTools.confirm("清空搜索历史","清除本机保存的搜索词？",anchor) { library.clearSearches() }
        }
    }
    private fun manageHistory(series: Series,anchor: View?) {
        tvTools.choose(series.title,listOf("继续观看",if(library.watched(series.id)) "恢复未看状态" else "标记整剧已看","删除这条记录"),anchor,{ index ->
            when(index) {
                0 -> openDetail(series,true)
                1 -> { library.setWatched(series.id,!library.watched(series.id)); catalogFocus=series.id; showCatalog() }
                2 -> tvTools.confirm("删除观看记录","删除《${series.title}》的本机观看进度？收藏会保留。",anchor) { library.removeHistory(series.id); showCatalog() }
            }
        })
    }
    private fun openCloudProgress(remote: CloudProgress) {
        if(!foreground || !library.isLoaded) return
        saveProgress(); library.flush(); releasePlayer(); tvTools.close()
        library.contentType=ContentType.SHORT
        leaveCollection(); tab=4; librarySources.accountHistory=true; page=1; catalogFocus=remote.series.id
        openDetail(remote.series,resume=true,cloudProgress=remote)
    }
    private fun openDetail(series: Series,resume: Boolean=false,autoplay: Boolean=true,cloudProgress: CloudProgress?=null) {
        if(screen=="catalog" && tab==2) library.contentType=rankType
        closeSeasonDialog(); seasonView=null
        pendingDetail=series; detailAutoplay=autoplay
        invalidatePage(); releaseCatalogViews(); screen="detail"; directPlayback=resume; detail=null; synopsisExpanded=false
        phonePlayback=if(resume) PhonePlaybackStatus(series.title,series.id,0,0,false,state="loading",speed=library.playbackSpeed,favorite=library.favorite(series.id),session=generation.toLong())
            else PhonePlaybackStatus("","",0,0,false)
        val body=base(series.title)
        val loading=TvLoading(this,"正在加载剧集…",detail=true)
        body.addView(loading,lp(-1,0).apply { weight=1f })
        work({ repository.detail(series.id) }, { result ->
            detail=result; library.observeFavorite(series.id,result.episodes.size,System.currentTimeMillis(),acknowledge=true)
            loadSeasons(result.series)
            val target=if(cloudProgress!=null) {
                val index=result.episodes.indexOf(cloudProgress.episodeId).takeIf { it>=0 } ?: cloudProgress.episodeIndex.coerceIn(0,result.episodes.lastIndex)
                ResumeTarget(index,cloudProgress.position.coerceAtLeast(0))
            } else if(tab==4 && librarySources.accountHistory) {
                val accountProgress=cloudAccount.progress(series.id)
                ResumePlayback.target(result.episodes,accountProgress?.episodeId,accountProgress?.position ?: 0,false)
            } else {
                val local=library.progress(series.id)
                ResumePlayback.target(result.episodes,local?.episodeId,local?.position ?: 0,local?.completed==true)
            }
            episodeIndex=target.index; group=episodeIndex/20
            if(resume) playEpisode(target.index,target.position,autoplay=detailAutoplay) else showDetail(false,reveal=true)
        }, { problem ->
            if(resume) phonePlayback=PhonePlaybackStatus(series.title,series.id,0,0,false,state="error",speed=library.playbackSpeed,
                favorite=library.favorite(series.id),error="剧集加载失败，请重试",session=generation.toLong())
            body.removeView(loading)
            error(body,problem) { openDetail(series,resume,detailAutoplay,cloudProgress) }
        })
    }
    private fun showDetail(focusEpisode: Boolean,reveal: Boolean=false) {
        closeSeasonDialog()
        invalidatePage(); releaseCatalogViews(); screen="detail"; val data=detail ?: return showCatalog()
        val body=base("剧集详情")
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false }; val content=column(); scroll.addView(content); body.addView(scroll,lp(-1,0).apply { weight=1f })
        val hero=row(); hero.gravity=Gravity.TOP; hero.setPadding(0,dp(12),0,dp(12))
        val posterWidth=dp((widthDp()*.085f).coerceIn(88f,123f)); val posterHeight=(posterWidth*174f/123f).toInt()
        val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER }; hero.addView(image,lp(posterWidth,posterHeight).apply { rightMargin=dp((widthDp()*.015f).coerceIn(14f,24f)) }); loadCover(image,data.series.cover)
        val info=column(); hero.addView(info,lp(0,-2).apply { weight=1f })
        info.addView(text(data.series.title,26f).apply { setTypeface(null,Typeface.BOLD); maxLines=2; ellipsize=TextUtils.TruncateAt.END })
        info.addView(text("${data.episodes.size} 集  ·  ${data.series.tags}",13f,muted).apply { maxLines=1; setPadding(0,dp(8),0,dp(8)) })
        val description=data.series.description.ifBlank { "选择剧集开始观看" }
        val synopsis=text(description,15f,muted).apply { maxLines=if(synopsisExpanded) 20 else 2; ellipsize=TextUtils.TruncateAt.END }
        info.addView(synopsis)
        val actions=TvFlowLayout(this).apply { setPadding(0,dp(12),0,0) }; info.addView(actions,lp(-1,-2))
        val progress=library.progress(data.series.id)
        val resumeIndex=progress?.let { data.episodes.indexOf(it.episodeId).takeIf { n -> n>=0 } } ?: 0
        val resumePosition=progress?.takeIf { !it.completed && resumeIndex==it.episodeIndex }?.position ?: 0
        val watched=library.watched(data.series.id)
        val play=addButton(actions,if(watched) "重新观看" else if(progress!=null && !progress.completed) "继续第 ${resumeIndex+1} 集" else "开始观看") { playEpisode(if(watched) 0 else if(progress?.completed==true) (resumeIndex+1).coerceAtMost(data.episodes.lastIndex) else resumeIndex,if(watched) 0 else resumePosition) }
        lateinit var favorite: TextView
        favorite=addButton(actions,if(library.favorite(data.series.id)) "已收藏" else "收藏") {
            favoriteMonitor.stop()
            val saved=library.toggle(data.series)
            if(saved) library.observeFavorite(data.series.id,data.episodes.size,System.currentTimeMillis(),acknowledge=true)
            favorite.text=if(saved) "已收藏" else "收藏"
        }
        accountFavoriteButton=addButton(actions,cloudAccount.favoriteActionLabel(data.series.id)) { accountFavoriteAction(data.series,accountFavoriteButton) }
        lateinit var synopsisButton: TextView
        synopsisButton=addButton(actions,if(synopsisExpanded) "收起简介" else "完整简介") {
            synopsisExpanded=!synopsisExpanded
            synopsis.maxLines=if(synopsisExpanded) 20 else 2
            synopsisButton.text=if(synopsisExpanded) "收起简介" else "完整简介"
            synopsisButton.post { synopsisButton.requestRectangleOnScreen(android.graphics.Rect(0,0,synopsisButton.width,synopsisButton.height),false) }
        }
        content.addView(hero)
        val groupRow=row(); groupRow.setPadding(0,dp(3),0,dp(5))
        val maxGroup=data.episodes.lastIndex/20; group=group.coerceIn(0,maxGroup)
        val previous=addButton(groupRow,"‹ 上一组") { if(group>0) { group--; showDetail(true) } }.apply { isEnabled=group>0; isFocusable=group>0; alpha=if(group>0) 1f else .4f }
        groupRow.addView(text("第 ${group*20+1}—${minOf((group+1)*20,data.episodes.size)} 集",16f).apply { gravity=Gravity.CENTER },lp(0,dp(42)).apply { weight=1f })
        addButton(groupRow,"下一组 ›") { if(group<maxGroup) { group++; showDetail(true) } }.apply { isEnabled=group<maxGroup; isFocusable=group<maxGroup; alpha=if(group<maxGroup) 1f else .4f }
        lateinit var jump: TextView
        jump=addButton(groupRow,"跳转集数") { tvTools.episodePicker(data.episodes.size,episodeIndex,jump) { target -> episodeIndex=target; group=target/20; showDetail(true) } }
        content.addView(groupRow)
        val episodes=mutableListOf<View>()
        val episodeColumns=TvLayout.columns(this,resources.displayMetrics.widthPixels-pageInsetPx()*2,48,10,6)
        (group*20 until minOf((group+1)*20,data.episodes.size)).toList().chunked(episodeColumns).forEach { numbers ->
            val line=row()
            numbers.forEach { index -> val item=button("${index+1}",index==episodeIndex) { playEpisode(index,if(progress?.episodeId==data.episodes[index] && !progress.completed) progress.position else 0) }; item.contentDescription="第 ${index+1} 集"; line.addView(item,lp(0,-2).apply { weight=1f; rightMargin=dp(6) }); episodes+=item }
            repeat(episodeColumns-numbers.size) { line.addView(Space(this),lp(0,1).apply { weight=1f; rightMargin=dp(6) }) }
            content.addView(line,lp(-1,-2).apply { bottomMargin=dp(7) })
        }
        episodes.forEachIndexed { i,v -> v.nextFocusLeftId=if(i%episodeColumns==0) v.id else episodes[i-1].id; v.nextFocusRightId=if(i%episodeColumns==episodeColumns-1 || i==episodes.lastIndex) v.id else episodes[i+1].id; if(i>=episodeColumns) v.nextFocusUpId=episodes[i-episodeColumns].id; v.nextFocusDownId=episodes.getOrNull(i+episodeColumns)?.id ?: episodes.last().id }
        val related=column().apply { setPadding(0,dp(18),0,dp(16)) }; content.addView(related); seasonView=related
        loadSeasons(data.series); renderSeasonViews()
        // Let the D-pad move from the last episode row into the following seasons.
        episodes.takeLast(minOf(episodeColumns,episodes.size)).forEach { it.nextFocusDownId=View.NO_ID }
        if(focusEpisode) episodes.getOrNull((episodeIndex-group*20).coerceIn(0,episodes.lastIndex))?.requestFocus() else play.requestFocus()
        if(reveal) TvMotion.contentReady(scroll)
    }
    private fun accountFavoriteAction(series: Series,anchor: View?) {
        if(!cloudAccount.signedIn) { cloudAccount.show(); return }
        val add=!cloudAccount.accountFavorite(series.id)
        cloudAccount.changeFavorite(series,add) { message ->
            if(!isDestroyed) {
                accountFavoriteButton?.text=cloudAccount.favoriteActionLabel(series.id)
                tvTools.info("账户收藏",message,anchor)
            }
        }
    }
    private fun cancelSeasons() {
        seasonScope?.cancel(); seasonScope=null; seasonJob?.cancel(true); seasonJob=null
    }
    private fun closeSeasonDialog() {
        seasonDialogWanted=false; seasonDialog?.dismiss(); seasonDialog=null; seasonDialogBody=null; seasonDialogActions=null
    }
    private fun loadSeasons(series: Series,force: Boolean=false) {
        val type=library.contentType; val key="${type.storedValue}:${series.id}"
        if(!force && seasonKey==key && (seasonResults.loading || seasonResults.error.isEmpty() && System.currentTimeMillis()-seasonLoadedAt<300000)) return
        cancelSeasons(); seasonKey=key
        val revision=++seasonRevision
        seasonResults=PhoneSearchResults(revision=revision,query=series.title,type=type,loading=true,section="seasons")
        renderSeasonViews()
        val scope=RequestScope(); seasonScope=scope
        seasonJob=seasonWorker.submit {
            try {
                val rows=scope.run {
                    val keyword=RelatedSeasons.query(series.title)
                    if(keyword.length>=2) RelatedSeasons.search(series) { pageNumber -> seasonRepository.search(keyword,pageNumber,type) }
                    else emptyList()
                }
                main.post { if(!isDestroyed && foreground && seasonResults.revision==revision) {
                    seasonLoadedAt=System.currentTimeMillis(); seasonResults=seasonResults.copy(loading=false,items=rows); renderSeasonViews()
                } }
            } catch(_: Exception) {
                main.post { if(!isDestroyed && foreground && seasonResults.revision==revision) {
                    seasonResults=seasonResults.copy(loading=false,error="后续季搜索失败，请重试"); renderSeasonViews()
                } }
            }
        }
    }
    private fun renderSeasonViews() {
        val current=detail?.series ?: return
        if(seasonResults.query!=current.title) return
        fun fill(parent: LinearLayout) {
            parent.removeAllViews()
            parent.addView(text("后续季 · 智能搜索",19f).apply { setTypeface(null,Typeface.BOLD) })
            parent.addView(text(when { seasonResults.loading -> "正在搜索第二季、第三季等后续内容…";
                seasonResults.error.isNotEmpty() -> seasonResults.error; seasonResults.items.isEmpty() -> "暂未找到同名后续季，可稍后重试或手动搜索。";
                else -> "以下为同名后续季候选，请选择想看的内容。" },13f,muted).apply { setPadding(0,dp(9),0,dp(10)) })
            seasonResults.items.forEach { series -> addButton(parent,series.title) { playRelatedSeason(seasonResults.revision,series.id) }.apply {
                maxLines=2; ellipsize=TextUtils.TruncateAt.END
            } }
            val actions=row(); parent.addView(actions)
            addButton(actions,"重新搜索") { loadSeasons(current,force=true) }.apply { isEnabled=!seasonResults.loading }
            addButton(actions,"手动搜索") { closeSeasonDialog(); runSearch(RelatedSeasons.query(current.title)) }
        }
        seasonView?.takeIf { screen=="detail" && it.isAttachedToWindow }?.let(::fill)
        seasonDialogBody?.takeIf { seasonDialogWanted && seasonDialog?.isShowing==true && screen=="player" }?.let {
            renderSeasonDialog(it,current)
        }
    }
    private fun renderSeasonDialog(parent: LinearLayout,current: Series) {
        val focusTag=seasonDialog?.currentFocus?.tag
        val keepFooter=seasonDialogActions?.hasFocus()==true
        parent.removeAllViews()
        parent.addView(text("本季已看完",13f,accent))
        parent.addView(text("接着看下一季",27f).apply { setTypeface(null,Typeface.BOLD); setPadding(0,dp(8),0,dp(8)) })
        parent.addView(text(current.title,14f,muted).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END })
        parent.addView(text(when {
            seasonResults.loading -> "正在寻找同名后续季…"
            seasonResults.error.isNotEmpty() -> seasonResults.error
            seasonResults.items.isEmpty() -> "暂未找到后续季，可以重新搜索或换个关键词。"
            else -> "找到 ${seasonResults.items.size} 部同名后续季 · 请选择想看的内容"
        },13f,muted).apply { setPadding(0,dp(16),0,dp(14)) })
        val cards=mutableListOf<View>()
        val columns=TvLayout.columns(this,TvLayout.dialogWidth(this,760)-dp(56),280,2,12)
        seasonResults.items.chunked(columns).forEach { items ->
            val line=row().apply { gravity=Gravity.TOP }; parent.addView(line,lp(-1,-2).apply { bottomMargin=dp(10) })
            items.forEachIndexed { index,series ->
                val cardInset=dp((widthDp()*.012f).coerceIn(10f,14f))
                val card= row().apply {
                    gravity=Gravity.CENTER_VERTICAL; setPadding(cardInset,cardInset,cardInset,cardInset); tag=series.id
                    focusStyle(this,flat=true); contentDescription="${series.title}，在电视播放"
                    setOnClickListener { playRelatedSeason(seasonResults.revision,series.id) }
                }
                val cover=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; background=rounded(TvStyle.raised) }
                val coverWidth=dp((widthDp()*.045f).coerceIn(52f,70f)); val coverHeight=(coverWidth*82f/58f).toInt()
                card.addView(cover,lp(coverWidth,coverHeight).apply { rightMargin=cardInset }); loadCover(cover,series.cover)
                val info=column(); card.addView(info,lp(0,-2).apply { weight=1f })
                info.addView(text("第 ${RelatedSeasons.parse(series.title).season} 季",12f,accent))
                info.addView(text(series.title,17f).apply { setTypeface(null,Typeface.BOLD); maxLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(0,dp(6),0,dp(6)) })
                info.addView(text(series.badge.ifBlank { "选择后在电视播放" },12f,muted).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END })
                line.addView(card,lp(0,-2).apply { weight=1f; if(index<columns-1) rightMargin=dp(12) }); cards+=card
            }
            repeat(columns-items.size) { line.addView(Space(this),lp(0,1).apply { weight=1f }) }
        }
        seasonDialogActions?.let { actions ->
            val retry=actions.getChildAt(0); retry.isEnabled=!seasonResults.loading; retry.isFocusable=retry.isEnabled; retry.alpha=if(retry.isEnabled) 1f else .45f
            val footerTarget=if(retry.isFocusable) retry else actions.getChildAt(1)
            cards.forEachIndexed { index,card ->
                card.nextFocusUpId=if(index>=columns) cards[index-columns].id else card.id
                card.nextFocusDownId=cards.getOrNull(index+columns)?.id ?: footerTarget.id
            }
            for(index in 0 until actions.childCount) actions.getChildAt(index).nextFocusUpId=cards.firstOrNull()?.id ?: footerTarget.id
        }
        if(!keepFooter) (cards.firstOrNull { it.tag==focusTag } ?: cards.firstOrNull() ?: seasonDialogActions?.getChildAt(1))?.requestFocus()
    }
    private fun showSeasonDialog() {
        val current=detail?.series ?: return
        episodePanel?.dismiss(); speedDialog?.dismiss(); tvTools.close(); closeSeasonDialog()
        seasonDialogWanted=true
        val root=TvDialogLayout(this).apply { setPadding(dp(28),dp(26),dp(28),dp(22)); background=TvStyle.shape(this@MainActivity,TvStyle.surface,TvStyle.outline,18) }
        val content=column()
        val scroll=TvDialogScroll(this,370).apply { isVerticalScrollBarEnabled=false; addView(content) }
        root.addView(scroll,lp(-1,-2).apply { weight=1f })
        root.addView(View(this).apply { setBackgroundColor(TvStyle.outline) },lp(-1,dp(1)).apply { topMargin=dp(8); bottomMargin=dp(16) })
        val actions=TvFlowLayout(this); root.addView(actions,lp(-1,-2))
        addButton(actions,"重新搜索") { loadSeasons(current,force=true) }
        addButton(actions,"手动搜索") { closeSeasonDialog(); runSearch(RelatedSeasons.query(current.title)) }
        addButton(actions,"稍后再看") { closeSeasonDialog() }
        val dialog=android.app.Dialog(this).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE); setContentView(root) }
        TvMotion.dialog(dialog)
        seasonDialog=dialog; seasonDialogBody=content; seasonDialogActions=actions
        dialog.setOnDismissListener { if(seasonDialog===dialog) { seasonDialogWanted=false; seasonDialogBody=null; seasonDialogActions=null; seasonDialog=null } }
        dialog.show(); dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(.68f)
            setLayout(TvLayout.dialogWidth(this@MainActivity,760),WindowManager.LayoutParams.WRAP_CONTENT)
        }
        loadSeasons(current)
        renderSeasonViews()
    }
    private fun playRelatedSeason(revision: Long,id: String) {
        val snapshot=seasonResults
        if(!foreground || !library.isLoaded || snapshot.revision!=revision || !snapshot.selectable(id)) return
        val selected=snapshot.items.firstOrNull { it.id==id } ?: return
        saveProgress(); library.flush(); releasePlayer(); library.contentType=snapshot.type
        openDetail(selected,resume=true)
    }
    private fun refreshPlayback(autoplay: Boolean=requestedAutoplay && !pausedForLifecycle && !sleepStopped) {
        if(screen!="player") return
        val position=currentPosition()
        android.util.Log.i("HongguoTV","Playback refresh episode=${episodeIndex+1} positionMs=$position resetOutput=true")
        playEpisode(episodeIndex,position,autoplay,resetOutput=true)
    }
    private fun playEpisode(index: Int,position: Long=0,autoplay: Boolean=true,recovering: Boolean=false,resetOutput: Boolean=false) {
        closeSeasonDialog(); seasonView=null
        updater.interrupt()
        val data=detail ?: return
        val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
        val queued=queuedEpisode
        val currentPlayer=player
        // A prepared playlist item already has its extractor and sample queues. Seeking
        // to it must not replace the MediaSource and throw those buffers away.
        if(position==0L && !recovering && !resetOutput && !playError && queued?.episodeIndex==selectedIndex &&
            queued.key=="${data.episodes[selectedIndex]}:${library.maxQuality}" && currentPlayer!=null && currentPlayer.hasNextMediaItem()) {
            saveProgress(); library.flush(); episodePanel?.dismiss(); episodePanel=null
            requestedAutoplay=autoplay; pausedForLifecycle=!autoplay; sleepStopped=false
            currentPlayer.seekToNextMediaItem()
            currentPlayer.playWhenReady=foreground && autoplay
            return
        }
        val playbackStarted=android.os.SystemClock.elapsedRealtime()
        // Normal episode changes retain the output; a manual refresh recreates it explicitly.
        val reuse=screen=="player" && player!=null && playerView!=null && !recovering && !playError && !resetOutput
        // A zero-position switch means auto-next or a deliberate episode choice. Keep the
        // last decoded frame while Media3 changes the source so a short drama never flashes
        // a black surface or its buffering spinner between episodes.
        val seamless=reuse && position==0L
        val staleVideo=if(reuse) video else null
        val staleHttp=if(reuse) playbackHttp else null
        library.setWatched(data.series.id,false)
        val prefetched=if(position==0L && !recovering) preparedNext.take("${data.episodes[selectedIndex]}:${library.maxQuality}") else null
        val claimedHttp=if(prefetched!=null) prefetchHttp.also { prefetchHttp=null } else null
        saveProgress(); library.flush(); invalidatePage(); favoriteMonitor.stop(); releaseCatalogViews()
        if(reuse) {
            cancelRecovery(); cancelPrefetch(); tvTools.close()
            episodePanel?.dismiss(); episodePanel=null; speedDialog?.dismiss(); speedDialog=null
            playerListener?.let { player?.removeListener(it) }; playerListener=null
            player?.pause()
            // An unqueued/manual switch retains the surface, but still needs new media.
            // Register cleanup now so cancellation during address lookup cannot leak it.
            retirePlaybackSource(staleVideo,staleHttp)
            video=null; playbackHttp=null
            main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        } else releasePlayer()
        artwork.cancelPage(clearMemory=true)
        playbackHttp=claimedHttp; video=prefetched
        var firstReady=true; var firstFrame=true
        if(!recovering) recovery.reset()
        autoRecovery=false; readySince=0; startPosition=position.coerceAtLeast(0); requestedAutoplay=autoplay
        if(autoplay) sleepStopped=false
        screen="player"; animatedPage="player"; episodeIndex=selectedIndex; group=episodeIndex/20
        panel=false; playError=false; playbackReady=false; quality=""; pausedForLifecycle=!autoplay
        prefetchAttempts=0; prefetchAttemptAt=0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(!reuse) {
            root.removeAllViews(); root.setBackgroundColor(Color.BLACK)
            val view=PlayerView(this).apply {
                useController=false; resizeMode=frameResizeMode(); isFocusable=false
                setKeepContentOnPlayerReset(true); setShutterBackgroundColor(Color.BLACK); setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            }
            playerView=view; root.addView(view,FrameLayout.LayoutParams(-1,-1))
            val hudInset=pageInsetPx(); val hudBottom=dp(((resources.displayMetrics.heightPixels/resources.displayMetrics.density)*.04f).coerceIn(16f,48f))
            hud=column().apply { setPadding(hudInset,dp(20),hudInset,hudBottom); background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,Color.argb(240,11,15,21))) }
            playbackTitle=text("",23f).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END; setTypeface(null,Typeface.BOLD) }; hud.addView(playbackTitle)
            playbackText=text("",15f,muted).apply { setPadding(0,dp(10),0,dp(9)) }; hud.addView(playbackText)
            progressBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=1000; progressTintList=android.content.res.ColorStateList.valueOf(accent); progressBackgroundTintList=android.content.res.ColorStateList.valueOf(surface) }; hud.addView(progressBar,lp(-1,dp(4)))
            hud.addView(text("确认 暂停/播放    左右 快退/快进    ↓ 选集    ↑ 更多    返回 收起 / 退出",13f,muted).apply { setPadding(0,dp(12),0,0) })
            controls=column().apply { setPadding(0,dp(12),0,0); visibility=View.GONE }; hud.addView(controls)
            root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled=false; addView(hud) },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        }
        playerView?.setShowBuffering(if(seamless) PlayerView.SHOW_BUFFERING_NEVER else PlayerView.SHOW_BUFFERING_ALWAYS)
        playbackTitle.text="${data.series.title}  ·  第 ${episodeIndex+1} 集"
        playbackText.text="正在获取播放地址…"; progressBar.progress=0; progressBar.visibility=View.VISIBLE; hidePlaybackOverlay(animate=false)
        controls.removeAllViews(); controls.visibility=View.GONE; transportPlay=null; sleepButton=null
        syncMediaSession()
        val requestedId=data.episodes[episodeIndex]; val maxQuality=library.maxQuality; val ticket=generation
        val acquireQueuedAt=android.os.SystemClock.elapsedRealtime()
        work({
            val acquireStarted=android.os.SystemClock.elapsedRealtime()
            var streamMs=0L; var prepareMs=0L
            val remote=prefetched ?: run {
                val info=repository.stream(requestedId,maxQuality)
                val streamFinished=android.os.SystemClock.elapsedRealtime(); streamMs=streamFinished-acquireStarted
                val source=RemoteVideo(repository.http,info)
                try { source.prepare() } catch(problem: Throwable) { source.close(); throw problem }
                prepareMs=android.os.SystemClock.elapsedRealtime()-streamFinished
                source
            }
            android.util.Log.i("HongguoTV","Playback acquisition episode=${selectedIndex+1} queueMs=${acquireStarted-acquireQueuedAt} streamMs=$streamMs prepareMs=$prepareMs preloaded=${prefetched!=null} connectionPolicy=ipv4-first")
            remote
        }, { remote ->
            video=remote; quality=remote.info.quality
            android.util.Log.i("HongguoTV", "Playback source episode=${episodeIndex+1} requested=${maxQuality}P selected=$quality")
            PlaybackRuntime.logSession(episodeIndex+1,reuse,prefetched!=null)
            val p=player ?: run {
                // Keep normal playback well fed while allowing a seamless source handoff
                // to begin with only 500/1000 ms of media in the new source.
                val load=DefaultLoadControl.Builder().setBufferDurationsMs(15000,30000,500,1000).setTargetBufferBytes(12*1024*1024).build()
                val renderers=PlaybackRuntime.renderers(this)
                ExoPlayer.Builder(this,renderers).setLoadControl(load).build().also {
                    val diagnostics=PlaybackDiagnostics()
                    it.addAnalyticsListener(diagnostics); it.setVideoFrameMetadataListener(diagnostics)
                    it.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(5_000_000L))
                    player=it; playerView?.player=it
                    it.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
                    it.setHandleAudioBecomingNoisy(true)
                }
            }
            p.setPlaybackSpeed(library.playbackSpeed)
            val listener=object: Player.Listener {
                private var completionShown=false
                private fun active()=player===p && generation==ticket && screen=="player"
                override fun onMediaItemTransition(mediaItem: MediaItem?,reason: Int) {
                    if(active()) adoptQueuedEpisode(p,reason==Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if(!active()) return
                    if(state==Player.STATE_READY) {
                        if(p.currentPosition<1000) completionShown=false
                        if(!p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                            playerFailure(PlaybackException("资源没有可解码的视频轨道",null,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
                            return
                        }
                        playbackReady=true
                        applyVideoFrameRateHint(p)
                        if(firstReady) { firstReady=false; readySince=android.os.SystemClock.elapsedRealtime(); android.util.Log.i("HongguoTV","Playback ready episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${readySince-playbackStarted}") }
                        if(TvMotion.isVisible(hud)) updatePlaybackText()
                    }
                    syncMediaSession()
                    if(state==Player.STATE_ENDED) {
                        saveProgress(true)
                        if(sleepTimer.episodeEnded()) stopForSleep()
                        else if(foreground && requestedAutoplay && !pausedForLifecycle && library.autoNext && episodeIndex<data.episodes.lastIndex) playEpisode(episodeIndex+1)
                        else { requestedAutoplay=false; p.pause(); playbackText.text="本集已结束"; showPanel()
                            if(foreground && episodeIndex==data.episodes.lastIndex && !completionShown) { completionShown=true; showSeasonDialog() }
                        }
                    }
                }
                override fun onRenderedFirstFrame() {
                    if(active() && firstFrame) {
                        firstFrame=false
                        android.util.Log.i("HongguoTV","Playback first frame episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${android.os.SystemClock.elapsedRealtime()-playbackStarted}")
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) { if(active()) { if(TvMotion.isVisible(hud)) showHud(); syncMediaSession() } }
                override fun onPlaybackParametersChanged(parameters: PlaybackParameters) { if(active()) syncMediaSession() }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason: Int) {
                    if(active()) {
                        adoptQueuedEpisode(p,reason==Player.DISCONTINUITY_REASON_AUTO_TRANSITION,oldPosition.positionMs)
                        syncMediaSession()
                    }
                }
                override fun onPlayerError(error: PlaybackException) { if(active()) playerFailure(error) }
            }
            playerListener=listener; p.addListener(listener)
            val source=episodeSource(remote,requestedId)
            p.setMediaSource(source); p.seekTo(position.coerceAtLeast(0)); p.prepare(); p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle && episodePanel==null
        }, { problem -> playerFailure(problem) })
    }

    private fun episodeSource(remote: RemoteVideo,id: String)=ProgressiveMediaSource.Factory { VideoDataSource(remote) }
        .createMediaSource(MediaItem.Builder().setMediaId(id).setUri("hongguotv://episode/$id").setMimeType(MimeTypes.VIDEO_MP4).build())

    private fun retirePlaybackSource(remote: RemoteVideo?,http: okhttp3.OkHttpClient?) {
        if(remote==null && http==null) return
        lateinit var release: Runnable
        release=Runnable { retiredSources.remove(release); remote?.close(); closeTransport(http) }
        retiredSources[release]=remote to http; main.postDelayed(release,1500)
    }

    private fun adoptQueuedEpisode(p: ExoPlayer,completed: Boolean,previousPosition: Long=0) {
        val next=queuedEpisode ?: return; val data=detail ?: return
        if(p.currentMediaItem?.mediaId!=data.episodes.getOrNull(next.episodeIndex)) return
        queuedEpisode=null
        if(completed) {
            val duration=previousPosition.takeIf { it>0 } ?: phonePlayback.duration
            persistProgress(episodeIndex,duration,duration,true)
        }
        retirePlaybackSource(video,playbackHttp)
        video=next.remote; playbackHttp=next.http; quality=next.remote.info.quality
        episodeIndex=next.episodeIndex; group=episodeIndex/20; startPosition=0; pendingSeek=null
        playbackReady=p.playbackState==Player.STATE_READY; playError=false; readySince=android.os.SystemClock.elapsedRealtime()
        recovery.reset(); cancelRecovery(); prefetchAttempts=0; prefetchAttemptAt=0; prefetchedFor=""
        playerView?.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        playbackTitle.text="${data.series.title}  ·  第 ${episodeIndex+1} 集"
        if(panel) showPanel() else hidePlaybackOverlay(animate=false)
        android.util.Log.i("HongguoTV","Playback queued transition episode=${episodeIndex+1} automatic=$completed state=${p.playbackState} positionMs=${p.currentPosition} reused=true preloaded=true")
        if(completed && sleepTimer.episodeEnded()) stopForSleep()
        syncMediaSession(); library.flush()
        val ticket=generation
        main.post {
            // Keep the playlist bounded to the current item and one next item. Removing
            // already-played items does not reset the current renderer or sample queues.
            if(player===p && generation==ticket && p.currentMediaItemIndex>0) p.removeMediaItems(0,p.currentMediaItemIndex)
        }
    }

    /** Tell Android TV's display pipeline the actual fixed source rate when supported. */
    private fun applyVideoFrameRateHint(p: Player) {
        val group=p.currentTracks.groups.firstOrNull { it.type==C.TRACK_TYPE_VIDEO && it.isSelected }
        val fps=group?.let { g ->
            (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).frameRate }
        }?.takeIf { it>0f && it.isFinite() } ?: return
        val surfaceView=playerView?.videoSurfaceView as? android.view.SurfaceView ?: return
        val surface=surfaceView.holder?.surface ?: return
        runCatching {
            if(android.os.Build.VERSION.SDK_INT>=31) {
                surface.setFrameRate(fps,android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,android.view.Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            } else if(android.os.Build.VERSION.SDK_INT>=30) {
                surface.setFrameRate(fps,android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }
    }
    private fun closeTransport(http: okhttp3.OkHttpClient?) {
        if(http==null) return
        // Closing an idle TLS connection can write close_notify; keep it off the UI thread.
        networkCleanup.execute { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }
    private fun cancelPrefetch() {
        prefetchScope?.cancel(); prefetchScope=null
        preparedNext.clear(); prefetchJob?.cancel(true); prefetchJob=null
        closeTransport(prefetchHttp); prefetchHttp=null; prefetchedFor=""
        val queued=queuedEpisode; queuedEpisode=null
        if(queued!=null) {
            val p=player
            if(p!=null) {
                val at=(p.currentMediaItemIndex+1 until p.mediaItemCount).firstOrNull { p.getMediaItemAt(it).mediaId==queued.key.substringBefore(':') }
                if(at!=null) p.removeMediaItem(at)
            }
            queued.remote.close(); closeTransport(queued.http)
        }
    }
    private fun prefetchNetworkAllowed(): Boolean {
        val caps=connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            PrefetchPolicy.networkAllowed(connectivity.isActiveNetworkMetered,caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }
    private fun queuePreparedNext(p: ExoPlayer) {
        val data=detail ?: return
        if(!foreground || player!==p || screen!="player" || playError || !library.autoNext || sleepTimer.episodesLeft==1 || queuedEpisode!=null || p.mediaItemCount!=1) return
        val next=episodeIndex+1; val id=data.episodes.getOrNull(next) ?: return
        val key="$id:${library.maxQuality}"
        if(!preparedNext.isFresh(key)) return
        val remote=preparedNext.take(key) ?: return
        val http=prefetchHttp; prefetchHttp=null
        queuedEpisode=QueuedEpisode(remote,http,next,key)
        p.addMediaSource(episodeSource(remote,id))
        android.util.Log.i("HongguoTV","Next episode queued episode=${next+1} positionMs=${p.currentPosition} remainingMs=${p.duration-p.currentPosition} preloadMs=5000")
    }
    private fun maybePrefetch() {
        val p=player ?: return; val data=detail ?: return
        if(!foreground || !p.isPlaying || playError || sleepStopped || episodeIndex>=data.episodes.lastIndex) return
        if(queuedEpisode!=null) return
        queuePreparedNext(p)
        if(queuedEpisode!=null) return
        if(!PrefetchPolicy.nearEnd(p.currentPosition,p.duration,p.playbackParameters.speed)) return
        val id=data.episodes[episodeIndex+1]; val quality=library.maxQuality; val key="$id:$quality"
        if(preparedNext.isFresh(key) || prefetchJob?.isDone==false) return
        val now=android.os.SystemClock.elapsedRealtime()
        if(readySince==0L || now-readySince<3000 || p.bufferedPosition-p.currentPosition<5000) return
        if(prefetchAttempts>=3 || (prefetchAttempts>0 && now-prefetchAttemptAt<15_000) || !prefetchNetworkAllowed()) return
        cancelPrefetch(); prefetchedFor=key; prefetchAttemptAt=now; prefetchAttempts++
        val ticket=preparedNext.begin(key)
        val http=okhttp3.OkHttpClient.Builder().dns(Ipv4FirstDns()).connectTimeout(8,java.util.concurrent.TimeUnit.SECONDS).readTimeout(15,java.util.concurrent.TimeUnit.SECONDS).callTimeout(20,java.util.concurrent.TimeUnit.SECONDS).build()
        val source=ContentRepository(http); prefetchHttp=http
        val scope=RequestScope(); prefetchScope=scope
        val pageTicket=generation
        prefetchJob=prefetchWorker.submit {
            var remote: RemoteVideo?=null
            try {
                val prepared=scope.run {
                    // Keep a larger first range for the next episode so Media3 can
                    // open and decode immediately when auto-next hands it over.
                    RemoteVideo(http,source.stream(id,quality)).also { remote=it; it.warm(2*1024*1024) }
                }
                if(preparedNext.complete(ticket,key,prepared)) {
                    android.util.Log.i("HongguoTV","Next episode preloaded key=$key bytes=2MiB")
                    main.post {
                        if(foreground && screen=="player" && generation==pageTicket && player===p) {
                            queuePreparedNext(p); updatePlaybackText()
                        }
                    }
                }
            } catch(_: Exception) { remote?.close() }
        }
    }
    private fun prefetchLabel(): String {
        val data=detail ?: return ""
        if(episodeIndex !in 0 until data.episodes.lastIndex) return ""
        val key="${data.episodes[episodeIndex+1]}:${library.maxQuality}"
        return when {
            queuedEpisode?.key==key || preparedNext.isFresh(key) -> "  ·  下一集已预载"
            prefetchJob?.isDone==false && prefetchedFor==key -> "  ·  下一集预载中"
            else -> ""
        }
    }
    private fun networkAvailable(): Boolean = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
    private fun currentPosition(): Long = pendingSeek ?: player?.currentPosition?.takeIf { playbackReady } ?: startPosition
    private fun cancelRecovery() { main.removeCallbacks(retryRunnable); retryPending=false; autoRecovery=false }
    private fun scheduleRecovery() {
        if(!foreground || !playError || !autoRecovery || retryPending) return
        if(!recovery.remaining) { autoRecovery=false; playbackText.text="已自动重试 3 次，仍无法播放。可手动重试或切换清晰度。"; return }
        if(!networkAvailable()) { playbackText.text="网络已断开，联网后自动续播 · 第 ${episodeIndex+1} 集 ${formatTime(retryPosition)}"; return }
        val delay=recovery.delayMillis ?: return
        playbackText.text="${if(retryAutoplay) "准备恢复播放" else "准备恢复暂停位置"} · ${delay/1000f} 秒后重试 ${recovery.attempts+1}/3 · ${formatTime(retryPosition)}"
        retryPending=true; main.postDelayed(retryRunnable,delay)
    }
    private fun playerFailure(problem: Throwable) {
        retryPosition=currentPosition().coerceAtLeast(0); retryAutoplay=requestedAutoplay && !sleepStopped
        saveProgress(); cancelRecovery(); cancelPrefetch()
        playError=true; player?.pause(); progressBar.visibility=View.GONE; TvMotion.visible(hud,true); controls.removeAllViews(); controls.visibility=View.VISIBLE; panel=true
        val decoding=problem is PlaybackException && problem.errorCode in 3000..4999
        val retryable=if(problem is PlaybackException) problem.errorCode in 2000..2999 || problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT else problem is java.io.IOException
        playbackText.text=if(decoding) "电视无法解码当前视频，可尝试更低清晰度。" else {
            val hint=networkHint(problem)
            if(hint=="内容服务暂时不可用") "播放失败，可重试或切换清晰度。" else hint
        }
        val actions=row(); controls.addView(actions)
        addButton(actions,"立即重试") { playEpisode(episodeIndex,retryPosition,retryAutoplay) }.requestFocus()
        addButton(actions,"尝试 720P") { library.maxQuality=720; playEpisode(episodeIndex,retryPosition,retryAutoplay) }
        addButton(actions,"返回选集") { returnToDetail(true) }
        if(retryable && !sleepStopped) {
            addButton(controls,"停止自动重试") { cancelRecovery(); playbackText.text="自动重试已停止，观看位置已保留。" }
            autoRecovery=true; scheduleRecovery()
        }
        android.util.Log.w("HongguoTV","Playback failure: ${problem.javaClass.simpleName}"+(if(problem is PlaybackException) " code=${problem.errorCodeName}" else "")+" reason=${networkHint(problem)}")
        syncMediaSession()
    }
    private fun hidePlaybackOverlay(animate: Boolean=true) {
        main.removeCallbacks(hideHud)
        panel=false; controls.clearFocus(); TvMotion.visible(hud,false,animate)
    }
    private fun showHud() {
        if(screen!="player") return
        if(!panel) controls.visibility=View.GONE
        TvMotion.visible(hud,true); updatePlaybackText(); main.removeCallbacks(hideHud)
        if(!panel && player?.isPlaying==true) main.postDelayed(hideHud,4500)
    }
    private fun updatePlaybackText() {
        transportPlay?.text=if(player?.playWhenReady==true) "暂停" else "播放"
        sleepButton?.text="定时 ${sleepTimer.label()}"
        if(screen!="player" || playError) return
        if(sleepStopped) { playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"; return }
        if(!playbackReady) { playbackText.text=(if(!requestedAutoplay) "准备后保持暂停 · " else "")+if(video==null) "正在获取播放地址…" else "正在缓冲…"; return }
        val p=player ?: return
        val duration=p.duration.coerceAtLeast(0); val position=pendingSeek ?: p.currentPosition
        val state=when { p.playbackState==Player.STATE_BUFFERING -> "缓冲中"; p.playbackState==Player.STATE_ENDED -> "本集已结束"; !p.playWhenReady -> "已暂停"; else -> "正在播放" }
        playbackText.text="$state  ·  ${formatTime(position)} / ${formatTime(duration)}  ·  $quality  ·  ${PlaybackSpeed.label(p.playbackParameters.speed)}${prefetchLabel()}"+(if(sleepTimer.active) "  ·  定时 ${sleepTimer.label()}" else "")
        progressBar.progress=if(duration>0) (position*1000/duration).toInt().coerceIn(0,1000) else 0
    }
    private fun requestPlayback(play: Boolean) {
        requestedAutoplay=play; pausedForLifecycle=!play
        if(play) sleepStopped=false
        if(!play) { player?.pause(); retryAutoplay=false; saveProgress() }
        else if(playError) { playEpisode(episodeIndex,retryPosition,true); return }
        else if(episodePanel==null) { if(player?.playbackState==Player.STATE_ENDED) player?.seekTo(0); player?.play() }
        if(!play) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showHud(); updatePlaybackText(); syncMediaSession(); if(panel && episodePanel==null) showPanel()
    }
    private fun togglePlayback()=requestPlayback(!(if(playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay))
    private fun seekTo(position: Long) {
        val p=player ?: return; if(!playbackReady || p.duration<=0) return
        main.removeCallbacks(seekRunnable); pendingSeek=null
        p.seekTo(position.coerceIn(0,(p.duration-500).coerceAtLeast(0))); updatePlaybackText(); syncMediaSession(); showHud()
    }
    private fun skipEpisode(direction: Int) {
        if(!foreground || screen!="player") return
        val count=detail?.episodes?.size ?: return
        val target=episodeIndex+direction
        if(target in 0 until count) playEpisode(target)
    }
    private fun syncMediaSession() {
        if(!foreground || screen!="player") { mediaSession.deactivate(); phonePlayback=PhonePlaybackStatus("","",0,0,false); return }
        val data=detail ?: run { phonePlayback=PhonePlaybackStatus("","",0,0,false); return }; val p=player
        val state=when {
            playError -> android.media.session.PlaybackState.STATE_ERROR
            sleepStopped || !requestedAutoplay || (p!=null && !p.playWhenReady) -> android.media.session.PlaybackState.STATE_PAUSED
            p==null || !playbackReady || p.playbackState==Player.STATE_BUFFERING -> android.media.session.PlaybackState.STATE_BUFFERING
            p.playbackState==Player.STATE_ENDED -> android.media.session.PlaybackState.STATE_STOPPED
            p.isPlaying -> android.media.session.PlaybackState.STATE_PLAYING
            else -> android.media.session.PlaybackState.STATE_PAUSED
        }
        mediaSession.update(data.series.title,episodeIndex,p?.duration?.coerceAtLeast(0) ?: 0,currentPosition(),library.playbackSpeed,state,episodeIndex>0,episodeIndex<data.episodes.lastIndex)
        phonePlayback=if(data.episodes.isNotEmpty() && episodeIndex in data.episodes.indices)
            PhonePlaybackStatus(data.series.title,data.series.id,episodeIndex,data.episodes.size,state==android.media.session.PlaybackState.STATE_PLAYING && !pausedForLifecycle,
                state=when { playError -> "error"; p?.playbackState==Player.STATE_ENDED -> "ended";
                    p==null || !playbackReady -> "loading"; state==android.media.session.PlaybackState.STATE_BUFFERING -> "buffering";
                    state==android.media.session.PlaybackState.STATE_PLAYING -> "playing"; else -> "paused" },
                position=currentPosition(),duration=p?.duration?.coerceAtLeast(0) ?: 0,speed=library.playbackSpeed,favorite=library.favorite(data.series.id),
                error=if(playError) "播放失败，可重试或切换剧集" else "",session=generation.toLong())
        else PhonePlaybackStatus("","",0,0,false)
    }
    private fun playPhoneEpisode(index: Int) {
        val data=detail ?: return
        if(!foreground || screen!="player" || data.episodes.isEmpty() || index !in data.episodes.indices) return
        val autoplay=player?.playWhenReady ?: requestedAutoplay
        playEpisode(index,autoplay=autoplay)
    }
    private fun stopForSleep() {
        sleepStopped=true; requestedAutoplay=false; retryAutoplay=false; pausedForLifecycle=true
        cancelRecovery(); cancelPrefetch(); player?.pause(); saveProgress()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"
        showHud(); if(!playError && episodePanel==null) showPanel(); syncMediaSession()
        Toast.makeText(this,"定时停止已生效",Toast.LENGTH_LONG).show()
    }
    private fun showSleepPicker(anchor: TextView) {
        val labels=listOf("关闭定时停止","15 分钟后","30 分钟后","60 分钟后","90 分钟后","本集播完","再播完 3 集","再播完 5 集")
        tvTools.choose("定时停止 · ${sleepTimer.label()}",labels,anchor,{ choice ->
            when(choice) { 0 -> sleepTimer.cancel(); in 1..4 -> sleepTimer.afterMinutes(listOf(15,30,60,90)[choice-1]); else -> sleepTimer.afterEpisodes(listOf(1,3,5)[choice-5]) }
            if(sleepTimer.episodesLeft==1) cancelPrefetch()
            if(anchor.tag!="playback-settings") anchor.text="定时 ${sleepTimer.label()}"; updatePlaybackText(); showHud()
        })
    }
    private fun frameResizeMode()=when(library.frameMode) {
        VideoFrameMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoFrameMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoFrameMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
    private fun showFramePicker(anchor: TextView,inPlayer: Boolean) {
        tvTools.choose("全局画面模式",listOf("完整画面 · 保留比例与全部内容","等比铺满 · 会裁掉部分画面与字幕","拉伸铺满 · 画面比例会改变"),anchor,{ index ->
            library.frameMode=VideoFrameMode.entries[index]; playerView?.resizeMode=frameResizeMode()
            if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "画面 " else "画面模式  ·  ")+library.frameMode.label
        })
    }
    private fun seek(direction: Int,repeat: Int) {
        val p=player ?: return; if(!playbackReady) return
        val duration=p.duration; if(duration<=0) return
        val step=if(repeat>4) 30000 else 10000
        pendingSeek=((pendingSeek ?: p.currentPosition)+direction*step).coerceIn(0,(duration-500).coerceAtLeast(0))
        main.removeCallbacks(seekRunnable); main.postDelayed(seekRunnable,250); updatePlaybackText(); showHud()
    }
    private fun showPanel() {
        if(playError) return
        panel=true; showHud(); controls.visibility=View.VISIBLE; controls.removeAllViews()
        val transport=row(); controls.addView(transport)
        val play=addButton(transport,if(player?.playWhenReady==true) "暂停" else "播放") { togglePlayback() }.also { transportPlay=it }
        addButton(transport,"上一集") { if(episodeIndex>0) playEpisode(episodeIndex-1) }.apply { isEnabled=episodeIndex>0; isFocusable=episodeIndex>0; alpha=if(episodeIndex>0) 1f else .4f }
        addButton(transport,"下一集") { if(episodeIndex<(detail?.episodes?.lastIndex ?: 0)) playEpisode(episodeIndex+1) }.apply { val enabled=episodeIndex<(detail?.episodes?.lastIndex ?: 0); isEnabled=enabled; isFocusable=enabled; alpha=if(enabled) 1f else .4f }
        val options=row().apply { setPadding(0,dp(8),0,0) }; controls.addView(options)
        lateinit var episodes: TextView
        episodes=addButton(options,"选集") { showEpisodePanel(episodes) }
        lateinit var more: TextView
        more=addButton(options,"播放设置") {
            tvTools.choose("播放设置",listOf("倍速 ${PlaybackSpeed.label(library.playbackSpeed)}","清晰度 ${library.maxQuality}P","画面 ${library.frameMode.label}","定时 ${sleepTimer.label()}","从头播放","刷新画面 · 保留进度"),more,{ choice ->
                main.post {
                    if(screen=="player" && more.isAttachedToWindow) when(choice) {
                        0 -> showSpeedPicker(more)
                        1 -> showQualityPicker(more,true)
                        2 -> showFramePicker(more,true)
                        3 -> showSleepPicker(more)
                        4 -> { seekTo(0); hidePanel() }
                        5 -> refreshPlayback()
                    }
                }
            })
        }.apply { tag="playback-settings" }
        play.requestFocus()
    }
    private fun showSpeedPicker(anchor: TextView,inPlayer: Boolean=true) {
        if(speedDialog!=null) return
        val speeds=PlaybackSpeed.options
        val selected=speeds.indexOf(library.playbackSpeed)
        val dialog=TvDialog.Builder(this).setTitle("全局默认倍速")
            .setSingleChoiceItems(speeds.map(PlaybackSpeed::label).toTypedArray(),selected) { popup,index ->
                library.playbackSpeed=speeds[index]
                if(inPlayer) player?.setPlaybackSpeed(library.playbackSpeed)
                if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "倍速 " else "默认倍速  ·  ")+PlaybackSpeed.label(library.playbackSpeed)
                if(inPlayer) updatePlaybackText()
                popup.dismiss()
            }.setNegativeButton("取消",null).create()
        speedDialog=dialog
        dialog.setOnDismissListener {
            speedDialog=null
            if(anchor.isAttachedToWindow) { anchor.requestFocus(); if(inPlayer) showHud() }
        }
        dialog.show(); dialog.focusChoice(selected)
    }
    private fun showQualityPicker(anchor: TextView,inPlayer: Boolean) {
        val options=PlaybackQuality.options
        val dialog=TvDialog.Builder(this).setTitle("全局默认清晰度")
            .setSingleChoiceItems(options.map { "${it}P" }.toTypedArray(),options.indexOf(library.maxQuality)) { popup,index ->
                val target=options[index]; val changed=library.maxQuality!=target
                library.maxQuality=target; popup.dismiss()
                if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "清晰度 " else "默认清晰度  ·  ")+"${target}P"
                if(inPlayer && changed) playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay)
            }.setNegativeButton("取消",null).create()
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
            dialog.show()
    }
    private fun showEpisodePanel(anchor: View) {
        val data=detail ?: return
        if(episodePanel!=null) return
        val ticket=generation
        val overlayWasVisible=TvMotion.isVisible(hud)
        requestedAutoplay=player?.playWhenReady ?: requestedAutoplay
        player?.pause(); saveProgress()
        var resumeOnClose=true
        val next=EpisodePanel(this,data.episodes.size,episodeIndex,
            { current,view,located -> tvTools.episodePicker(data.episodes.size,current,view,located) },
            { selected ->
                if(selected==episodeIndex) episodePanel?.dismiss()
                else { resumeOnClose=false; episodePanel?.dismiss(); playEpisode(selected) }
            },
            {
                episodePanel=null
                if(resumeOnClose && foreground && generation==ticket && screen=="player") {
                    if(requestedAutoplay && !sleepStopped && !pausedForLifecycle) player?.play()
                    if(anchor.isAttachedToWindow) anchor.requestFocus()
                    if(overlayWasVisible) showHud() else hidePlaybackOverlay()
                }
            })
        episodePanel=next; next.show()
    }
    private fun hidePanel() { if(playError) return; panel=false; controls.visibility=View.GONE; controls.clearFocus(); showHud() }
    private fun formatTime(millis: Long): String { val seconds=(millis.coerceAtLeast(0)/1000); return "%02d:%02d".format(seconds/60,seconds%60) }
    private fun saveProgress(completed: Boolean=false) {
        val p=player ?: return; val data=detail ?: return; if(!playbackReady || episodeIndex !in data.episodes.indices) return
        val finished=completed || p.playbackState==Player.STATE_ENDED
        persistProgress(episodeIndex,p.currentPosition.coerceAtLeast(0),p.duration.coerceAtLeast(0),finished)
    }
    private fun persistProgress(index: Int,position: Long,duration: Long,finished: Boolean) {
        val data=detail ?: return; if(index !in data.episodes.indices) return
        val stamp=System.currentTimeMillis()
        library.save(WatchProgress(data.series,data.episodes[index],index,position,duration,finished,stamp))
        if(::cloudAccount.isInitialized && cloudAccount.signedIn) {
            runCatching { cloudAccount.upload(CloudProgress(data.series,data.episodes[index],index,position,duration,stamp,data.episodes.size),data.episodes.size,finished) }
        }
    }
    private fun releasePlayer() {
        closeSeasonDialog()
        retiredSources.toMap().forEach { (release,source) -> main.removeCallbacks(release); source.first?.close(); closeTransport(source.second) }; retiredSources.clear()
        cancelRecovery(); cancelPrefetch(); mediaSession.deactivate(); tvTools.close()
        phonePlayback=PhonePlaybackStatus("","",0,0,false)
        val wasForeground=foreground; foreground=false
        val oldPanel=episodePanel; episodePanel=null; oldPanel?.dismiss(); foreground=wasForeground
        speedDialog?.dismiss(); speedDialog=null
        main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        playerListener?.let { player?.removeListener(it) }; playerListener=null
        video?.close(); video=null; playerView?.player=null; playerView=null; player?.release(); player=null; playbackReady=false; transportPlay=null; sleepButton=null
        closeTransport(playbackHttp); playbackHttp=null
        phonePlayback=PhonePlaybackStatus("","",0,0,false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun returnToDetail(forceDetail: Boolean=false) {
        sleepTimer.cancel(); sleepStopped=false; saveProgress(); library.flush(); invalidatePage(); releasePlayer()
        if(directPlayback && !forceDetail) showCatalog() else { directPlayback=false; showDetail(true) }
    }
    private fun goBack() {
        if(!library.isLoaded) { finish(); return }
        when(screen) {
            "player" -> if(TvMotion.isVisible(hud) && !playError) hidePlaybackOverlay() else returnToDetail()
            "detail" -> showCatalog()
            else -> if(collection!=null) { leaveCollection(); showCatalog() } else if(nav.any { it.hasFocus() }) { if(tab!=0) { switchTab(0); nav[0].requestFocus() } else finish() } else nav.getOrNull(tab)?.requestFocus()
        }
    }
    @Deprecated("TV remote back is handled through the activity")
    override fun onBackPressed()=goBack()
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(screen=="player") {
            val key=event.keyCode
            if(key==KeyEvent.KEYCODE_BACK) { if(event.action==KeyEvent.ACTION_UP) goBack(); return true }
            if(key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS,KeyEvent.KEYCODE_MEDIA_STOP,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,KeyEvent.KEYCODE_MEDIA_REWIND)) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) when(key) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> requestPlayback(true)
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> requestPlayback(false)
                    KeyEvent.KEYCODE_MEDIA_NEXT -> skipEpisode(1)
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> skipEpisode(-1)
                    KeyEvent.KEYCODE_MEDIA_STOP -> returnToDetail()
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(1,0)
                    KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-1,0)
                    else -> togglePlayback()
                }
                return true
            }
            if(!panel) {
                if(key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_MENU)) {
                    if(event.action==KeyEvent.ACTION_DOWN) when(key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> seek(-1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> seek(1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> if(event.repeatCount==0) togglePlayback()
                        KeyEvent.KEYCODE_DPAD_DOWN -> if(event.repeatCount==0) showEpisodePanel(hud)
                        KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_MENU -> if(event.repeatCount==0) showPanel()
                        else -> showHud()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if(!library.isLoaded) return
        when(screen) {
            "player" -> { val position=currentPosition(); val autoplay=player?.playWhenReady==true && !pausedForLifecycle; playEpisode(episodeIndex,position,autoplay) }
            "detail" -> if(detail!=null) showDetail(false)
            else -> showCatalog()
        }
    }
    override fun onResume() {
        super.onResume(); foreground=true; cloudAccount.resume(); tvTools.ensurePhoneSearchServer(); main.removeCallbacks(tick); main.post(tick)
        updater.resume()
        if(library.isLoaded) {
            if(initialCatalogPending) showCatalog(load=true)
            else if(restoreStoppedPage) {
                restoreStoppedPage=false
                when(screen) {
                    "player" -> playEpisode(episodeIndex,startPosition,autoplay=false)
                    "detail" -> if(detail!=null) showDetail(false) else pendingDetail?.let { openDetail(it,directPlayback,autoplay=false) } ?: showCatalog()
                    else -> showCatalog(load=!localCatalog() && catalog.isEmpty() && (tab==0 || tab==2 || tab==6 || (tab==1 && query.isNotBlank())))
                }
            } else {
                val waiting=deferredWork; deferredWork=null; waiting?.apply?.invoke()
            }
            if(screen=="catalog" && tab==0 && homeScreen!=null) {
                homeHeroPlayer?.play()
            }
            if(screen=="catalog") favoriteMonitor.check()
            if(screen=="player") syncMediaSession() else if(screen!="detail" || !directPlayback || !detailAutoplay) phonePlayback=PhonePlaybackStatus("","",0,0,false)
        } else libraryLoadError?.let(::showLibraryError)
    }
    override fun onPause() {
        cloudAccount.pause()
        closeSeasonDialog(); cancelSeasons()
        if(seasonResults.loading) seasonResults=seasonResults.copy(loading=false,error="搜索已暂停，请重试")
        updater.pause()
        cancelPhoneCatalog()
        if(phoneSearchResults.loading) phoneSearchResults=phoneSearchResults.copy(loading=false,error="电视应用已离开前台，请打开应用后重试")
        foreground=false; main.removeCallbacks(tick); cancelRecovery(); cancelPrefetch(); mediaSession.deactivate()
        phonePlayback=PhonePlaybackStatus("","",0,0,false)
        sleepTimer.cancel(); favoriteMonitor.stop(); detailAutoplay=false; requestedAutoplay=false; pausedForLifecycle=true
        homeHeroPlayer?.pause()
        player?.pause(); saveProgress(); library.flush(); super.onPause()
    }
    override fun onStop() {
        restoreStoppedPage=true; generation++; cancelWork(); tvTools.close()
        phonePlayback=PhonePlaybackStatus("","",0,0,false)
        if(screen=="player") { startPosition=currentPosition(); saveProgress(); releasePlayer() }
        else if(screen=="catalog") stopHomeHero()
        artwork.cancelPage(clearMemory=true); library.flush(); super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if(::artwork.isInitialized) artwork.trimMemory(level)
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cancelPrefetch()
    }
    override fun onDestroy() {
        generation++; cancelWork()
        if(networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        favoriteMonitor.destroy(); cloudAccount.destroy(); tvTools.destroy(); updater.destroy(); saveProgress(); releasePlayer(); library.close()
        artwork.close(); releaseCatalogViews(); mediaSession.release(); main.removeCallbacksAndMessages(null)
        cancelPhoneCatalog(); phoneCatalogWorker.shutdownNow(); cancelSeasons(); seasonWorker.shutdownNow(); io.shutdownNow(); prefetchWorker.shutdownNow(); homeHeroWorker.shutdownNow(); networkCleanup.shutdown()
        Thread({ seasonRepository.http.dispatcher.cancelAll(); seasonRepository.http.connectionPool.evictAll(); seasonRepository.http.dispatcher.executorService.shutdown() },"hongguotv-season-cleanup").start()
        Thread({ catalogRepository.http.dispatcher.cancelAll(); catalogRepository.http.connectionPool.evictAll(); catalogRepository.http.dispatcher.executorService.shutdown() },"hongguotv-catalog-cleanup").start()
        Thread({ repository.http.dispatcher.cancelAll(); repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() },"hongguotv-network-cleanup").start()
        super.onDestroy()
    }
}
