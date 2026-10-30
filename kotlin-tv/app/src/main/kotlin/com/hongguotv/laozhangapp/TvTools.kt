// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.laozhangapp

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hongguotv.core.EpisodeTarget
import com.hongguotv.core.ContentType
import com.hongguotv.core.PhoneSearchServer
import com.hongguotv.core.LibraryTransferServer
import com.hongguotv.core.BackupData
import com.hongguotv.core.CatalogFilter
import java.net.Inet4Address
import java.util.concurrent.Executors

/** Native modal controls kept separate from catalog/player navigation. */
class TvTools(private val activity: Activity, private val onPhoneSearch: (String,ContentType?)->Unit = { _,_ -> }) {
    companion object { const val PHONE_SEARCH_PORT = 38209 }
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var dialog: TvDialog?=null
    val showing: Boolean get()=dialog?.isShowing==true
    private var phone: java.io.Closeable?=null
    private var phoneServer: PhoneSearchServer?=null
    private var phoneAddress=""
    private var phoneStarting=false
    private var phoneStartingAddress=""
    private var phoneServerRevision=0
    private var phoneError: String?=null
    private var phoneDialogContent: LinearLayout?=null
    private var phonePlaybackStatus: ()->com.hongguotv.core.PhonePlaybackStatus = { com.hongguotv.core.PhonePlaybackStatus("","",0,0,false) }
    private var phoneEpisodeSelect: (Int)->Unit = {}
    fun setPhonePlaybackCallbacks(status: ()->com.hongguotv.core.PhonePlaybackStatus,selectEpisode: (Int)->Unit) { phonePlaybackStatus=status; phoneEpisodeSelect=selectEpisode }
    private var phoneResults: ()->com.hongguotv.core.PhoneSearchResults = { com.hongguotv.core.PhoneSearchResults() }
    private var phonePlay: (Long,String)->Unit = { _,_ -> }
    private var phonePage: (Long,Int)->Unit = { _,_ -> }
    private var phoneBrowse: (String,ContentType)->Unit = { _,_ -> }
    private var phoneControl: (com.hongguotv.core.PhoneControl)->Unit = {}
    private var phoneFavorite: (Long,String,Boolean)->Unit = { _,_,_ -> }
    private var phoneLibrary: (com.hongguotv.core.CatalogGenre,Map<String,String>)->Unit = { _,_ -> }
    private var phoneSeasons: ()->com.hongguotv.core.PhoneSearchResults = { com.hongguotv.core.PhoneSearchResults() }
    private var phoneRelatedPlay: (Long,String)->Unit = { _,_ -> }
    fun setPhoneLibraryCallbacks(browse: (com.hongguotv.core.CatalogGenre,Map<String,String>)->Unit,
        seasons: ()->com.hongguotv.core.PhoneSearchResults,play: (Long,String)->Unit) {
        phoneLibrary=browse; phoneSeasons=seasons; phoneRelatedPlay=play
    }
    fun setPhoneSearchCallbacks(results: ()->com.hongguotv.core.PhoneSearchResults,play: (Long,String)->Unit,turnPage: (Long,Int)->Unit,
        browse: (String,ContentType)->Unit,control: (com.hongguotv.core.PhoneControl)->Unit,favorite: (Long,String,Boolean)->Unit) {
        phoneResults=results; phonePlay=play; phonePage=turnPage; phoneBrowse=browse; phoneControl=control; phoneFavorite=favorite
    }
    private fun dp(value: Int)=(value*activity.resources.displayMetrics.density).toInt()
    private fun label(value: String,size: Float=17f)=TextView(activity).apply { text=value; textSize=size; setTextColor(TvStyle.text); setPadding(dp(4),dp(4),dp(4),dp(4)); setLineSpacing(dp(3).toFloat(),1f) }
    private fun column()=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(4),dp(16),dp(8)) }
    private fun open(next: TvDialog,anchor: View?) {
        dialog?.dismiss(); dialog=next
        next.setOnDismissListener { if(dialog===next) { phone?.close(); phone=null; dialog=null }; if(anchor?.isAttachedToWindow==true) anchor.requestFocus() }
        next.show()
    }
    fun close() { dialog?.dismiss(); phone?.close(); phone=null }
    fun destroy() { close(); phoneServerRevision++; phoneServer?.close(); phoneServer=null; worker.shutdownNow() }
    data class PhoneRemoteState(val url: String?,val loading: Boolean,val error: String?)
    fun phoneRemoteState()=PhoneRemoteState(phoneServer?.url,phoneStarting,phoneError)
    private fun networkAddress(): String? {
        val connectivity=activity.getSystemService(ConnectivityManager::class.java)
        val network=connectivity.activeNetwork
        val capabilities=connectivity.getNetworkCapabilities(network) ?: return null
        if(!(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null
        return connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }
    /** Keep the LAN search page available while the app is running. */
    fun ensurePhoneSearchServer() {
        if(worker.isShutdown) return
        val address=runCatching { networkAddress() }.getOrNull()
        if(address==null) {
            if(phoneServer!=null || phoneStarting) {
                phoneServerRevision++; phoneServer?.close(); phoneServer=null
                phoneAddress=""; phoneStarting=false; phoneStartingAddress=""
            }
            phoneError="请让电视连接 Wi-Fi 或有线网络，再刷新二维码"
            refreshPhoneDialog()
            return
        }
        if(phoneServer!=null && phoneAddress==address) return
        if(phoneStarting && phoneStartingAddress==address) return
        val revision=++phoneServerRevision
        phoneServer?.close(); phoneServer=null; phoneAddress=""; phoneError=null; phoneStarting=true
        phoneStartingAddress=address
        worker.execute {
            try {
                val session=PhoneSearchServer(address,{ query,type -> main.post { onPhoneSearch(query,type) } },port=PHONE_SEARCH_PORT,persistent=true,
                    playbackStatus=phonePlaybackStatus,onEpisode=phoneEpisodeSelect,searchResults=phoneResults,onPlay=phonePlay,onSearchPage=phonePage,
                    onBrowse=phoneBrowse,onControl=phoneControl,onFavorite=phoneFavorite,
                    onLibrary=phoneLibrary,relatedSeasons=phoneSeasons,onRelatedPlay=phoneRelatedPlay)
                main.post {
                    if(revision!=phoneServerRevision || worker.isShutdown) { session.close(); return@post }
                    phoneStarting=false
                    phoneStartingAddress=""
                    phoneServer=session; phoneAddress=address
                    refreshPhoneDialog()
                }
            } catch(e: Exception) {
                main.post {
                    if(revision!=phoneServerRevision || worker.isShutdown) return@post
                    phoneStarting=false; phoneStartingAddress=""
                    phoneError="无法打开手机遥控端口 $PHONE_SEARCH_PORT，请重试"
                    refreshPhoneDialog()
                }
            }
        }
    }
    private fun refreshPhoneDialog() {
        val content=phoneDialogContent ?: return
        if(dialog==null || !dialog!!.isShowing) return
        content.removeAllViews()
        val url=phoneServer?.url
        when {
            url!=null -> {
                val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
                val size=dp(150); val space=dp(8)
                row.addView(qr(url),LinearLayout.LayoutParams(size,size))
                val instructions=column()
                instructions.addView(label("扫码打开手机遥控",16f))
                instructions.addView(label("手机搜索、选剧并在电视播放，也可查看当前集数和切换剧集。",13f))
                instructions.addView(label(url,12f))
                row.addView(instructions,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=space })
                content.addView(row)
            }
            phoneError!=null -> content.addView(label(phoneError!!,16f))
            else -> content.addView(label("正在打开手机搜索端口 $PHONE_SEARCH_PORT…",16f))
        }
        if(phoneStarting) main.postDelayed({ refreshPhoneDialog() },300)
    }
    fun info(title: String,message: String,anchor: View?) = open(TvDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("知道了",null).create(),anchor)
    fun confirm(title: String,message: String,anchor: View?,action: ()->Unit) {
        val next=TvDialog.Builder(activity).setTitle(title).setMessage(message).setNegativeButton("取消",null).setPositiveButton("确认") { _,_-> action() }.create()
        open(next,anchor); next.getButton(TvDialog.BUTTON_NEGATIVE).requestFocus()
    }
    fun choose(title: String,labels: List<String>,anchor: View?,onChoice: (Int)->Unit,extra: String?=null,onExtra: ()->Unit={}) {
        val builder=TvDialog.Builder(activity).setTitle(title).setItems(labels.toTypedArray()) { _,index-> onChoice(index) }.setNegativeButton("取消",null)
        if(extra!=null) builder.setNeutralButton(extra) { _,_-> onExtra() }
        val next=builder.create(); open(next,anchor); next.focusChoice()
    }
    fun catalogFilters(title: String,rows: List<CatalogFilter>,current: Map<String,String>,anchor: View?,apply: (Map<String,String>)->Unit) {
        val draft=current.toMutableMap()
        val content=column()
        fun render() {
            content.removeAllViews()
            content.addView(label("选择喜欢的分类，确认后更新剧库。返回可保留原条件。",13f).apply { setTextColor(TvStyle.muted) })
            rows.forEach { filter ->
                content.addView(label(filter.label,15f).apply { setTextColor(TvStyle.muted); setPadding(dp(4),dp(14),dp(4),dp(6)) })
                val cells=mutableListOf<Pair<String,TextView>>()
                fun paint() { cells.forEach { (value,cell) ->
                    val selected=draft[filter.key].orEmpty()==value
                    cell.isSelected=selected
                    cell.setTextColor(if(cell.hasFocus()) TvStyle.background else if(selected) TvStyle.accent else TvStyle.text)
                    TvMotion.focus(cell,cell.hasFocus(),if(cell.hasFocus()) TvStyle.accent else if(selected) TvStyle.raised else TvStyle.background,
                        if(cell.hasFocus() || selected) TvStyle.accent else TvStyle.outline,9)
                } }
                val columns=TvLayout.columns(activity,TvLayout.dialogWidth(activity)-dp(84),92,5,6)
                filter.options.chunked(columns).forEach { options ->
                    val row=LinearLayout(activity)
                    options.forEach { (value,label) ->
                        val cell=TvDialog.button(activity,label) {
                            if(value.isEmpty()) draft.remove(filter.key) else draft[filter.key]=value
                            paint()
                        }.apply { textSize=13f; setPadding(dp(5),dp(10),dp(5),dp(10)); setOnFocusChangeListener { _,_ -> paint() } }
                        cells+=value to cell
                        row.addView(cell,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) })
                    }
                    repeat(columns-options.size) { row.addView(Space(activity),LinearLayout.LayoutParams(0,1,1f)) }
                    content.addView(row)
                }
                paint()
            }
        }
        render()
        val next=TvDialog.Builder(activity).setTitle("$title · 筛选剧库").setView(content)
            .setNeutralButton("重置条件",null).setNegativeButton("取消",null)
            .setPositiveButton("查看结果") { _,_ -> apply(draft.toMap()) }.create()
        open(next,anchor)
        next.getButton(TvDialog.BUTTON_NEUTRAL).setOnClickListener {
            draft.clear(); render(); next.getButton(TvDialog.BUTTON_NEUTRAL).requestFocus()
        }
        content.getFocusables(View.FOCUS_FORWARD).firstOrNull()?.requestFocus()
    }
    fun episodePicker(count: Int,current: Int,anchor: View?,select: (Int)->Unit) {
        val content=column(); var digits=(current+1).toString(); var replace=true
        val value=label(digits,28f).apply { gravity=Gravity.CENTER }; content.addView(value)
        val error=label("定位到该集，再按确认播放",14f); content.addView(error)
        fun digit(key: String) { digits=if(replace) key else (digits+key).take(6); replace=false; value.text=digits; error.text="定位到该集，再按确认播放" }
        fun erase() { digits=if(replace) "" else digits.dropLast(1); replace=false; value.text=digits.ifEmpty { "—" } }
        val buttons=mutableListOf<TextView>()
        listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("清空","0","退格")).forEach { keys ->
            val row=LinearLayout(activity)
            keys.forEach { key ->
                val button=TvDialog.button(activity,key) { when(key) { "清空"->{ digits="";replace=false;value.text="—" }; "退格"->erase(); else->digit(key) } }
                buttons+=button
                row.addView(button,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) })
            }
            content.addView(row)
        }
        val next=TvDialog.Builder(activity).setTitle("跳转集数 · 1—$count").setView(content).setNegativeButton("取消",null).setPositiveButton("定位",null).create()
        next.setOnKeyListener { _,key,event ->
            when {
                key in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { if(event.action==KeyEvent.ACTION_DOWN) digit((key-KeyEvent.KEYCODE_0).toString()); true }
                key==KeyEvent.KEYCODE_DEL -> { if(event.action==KeyEvent.ACTION_DOWN) erase(); true }
                else -> false
            }
        }
        open(next,anchor)
        val locate=next.getButton(TvDialog.BUTTON_POSITIVE)
        val cancel=next.getButton(TvDialog.BUTTON_NEGATIVE)
        buttons.forEachIndexed { i,button ->
            button.nextFocusLeftId=buttons[if(i%3==0) i else i-1].id
            button.nextFocusRightId=buttons[if(i%3==2) i else i+1].id
            button.nextFocusUpId=buttons[if(i<3) i else i-3].id
            button.nextFocusDownId=if(i<9) buttons[i+3].id else locate.id
        }
        locate.nextFocusUpId=buttons[10].id; cancel.nextFocusUpId=buttons[9].id
        next.getButton(TvDialog.BUTTON_POSITIVE).setOnClickListener {
            val target=EpisodeTarget.parse(digits,count)
            if(target==null) error.text="请输入 1—$count 之间的集数"
            else { next.dismiss(); select(target) }
        }
        buttons.first().requestFocus()
    }
    fun phoneQrBitmap(url: String,dark: Int=Color.BLACK,light: Int=Color.WHITE): Bitmap {
        val bits=QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,360,360)
        val pixels=IntArray(360*360) { i->if(bits[i%360,i/360]) dark else light }
        return Bitmap.createBitmap(pixels,360,360,Bitmap.Config.ARGB_8888)
    }
    private fun qr(url: String)=ImageView(activity).apply {
        setImageBitmap(phoneQrBitmap(url))
        contentDescription="二维码"
    }
    fun phoneInput(anchor: View?) {
        phoneError=null
        ensurePhoneSearchServer()
        val content=column(); phoneDialogContent=content
        val next=TvDialog.Builder(activity).setTitle("手机遥控").setView(content).setNegativeButton("关闭",null).create()
        open(next,anchor)
        refreshPhoneDialog()
        next.setOnDismissListener {
            if(dialog===next) { phone?.close(); phone=null; phoneDialogContent=null; dialog=null }
            if(anchor?.isAttachedToWindow==true) anchor.requestFocus()
        }
    }
    fun libraryTransfer(backup: String,anchor: View?,restore: (BackupData,Boolean)->Unit) {
        val connectivity=activity.getSystemService(ConnectivityManager::class.java)
        val network=connectivity.activeNetwork; val capabilities=connectivity.getNetworkCapabilities(network)
        val address=connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        if(address==null || capabilities==null || !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
            info("备份与恢复","请让电视和手机连接同一局域网后重试。",anchor); return
        }
        val content=column(); val status=label("正在生成二维码…"); content.addView(status)
        val next=TvDialog.Builder(activity).setTitle("手机备份与恢复").setView(content).setNegativeButton("关闭",null).create()
        open(next,anchor)
        worker.execute {
            try {
                val session=LibraryTransferServer(address,backup,{ data -> main.post {
                    if(dialog!==next) return@post
                    next.dismiss()
                    val preview=column()
                    preview.addView(label("备份含 ${data.favorites.size} 部收藏、${data.history.size} 条观看记录、${data.searches.size} 条搜索、${data.later.size} 部稍后看、${data.hidden.size} 部已隐藏推荐。\n\n收藏合并，观看位置以较新的记录为准；已有收藏优先保留（最多 500 部），历史保留最近 200 条。",16f))
                    val settings=CheckBox(activity).apply { text="同时恢复播放设置和内容分类"; isChecked=false; TvStyle.checkbox(this) }; preview.addView(settings)
                    val confirm=TvDialog.Builder(activity).setTitle("确认恢复本机记录")
                        .setView(preview).setNegativeButton("取消",null)
                        .setPositiveButton("合并恢复") { _,_-> restore(data,settings.isChecked) }.create()
                    open(confirm,anchor); confirm.getButton(TvDialog.BUTTON_NEGATIVE).requestFocus()
                } },{ main.post { if(dialog===next) { content.removeAllViews(); content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(dialog!==next) { session.close(); return@post }
                    phone=session; content.removeAllViews()
                    val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
                    row.addView(qr(session.url),LinearLayout.LayoutParams(dp(185),dp(185)))
                    val instructions=column()
                    instructions.addView(label("手机扫码，用浏览器下载备份或选择备份文件恢复。\n仅限同一局域网；关闭即失效，最长 5 分钟。恢复需在电视确认。",16f))
                    instructions.addView(label(session.url,12f)); row.addView(instructions,LinearLayout.LayoutParams(0,-2,1f)); content.addView(row)
                }
            } catch(_: Exception) { main.post { if(dialog===next) status.text="无法开启备份传输，请检查网络后重试。" } }
        }
    }
}
