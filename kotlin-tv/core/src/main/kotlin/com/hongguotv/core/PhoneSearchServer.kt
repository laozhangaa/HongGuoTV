// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** LAN search form, persistent or one-use. No cookies, platform credentials or external resources. */
class PhoneSearchServer(
    address: String,
    private val onQuery: (String,ContentType?)->Unit,
    private val onExpired: ()->Unit = {},
    ttlMillis: Long = 300_000,
    port: Int = 0,
    private val persistent: Boolean = false,
    private val playbackStatus: ()->PhonePlaybackStatus = { PhonePlaybackStatus("","",0,0,false) },
    private val onEpisode: (Int)->Unit = {},
    private val searchResults: ()->PhoneSearchResults = { PhoneSearchResults() },
    private val onPlay: (Long,String)->Unit = { _,_ -> },
    private val onSearchPage: (Long,Int)->Unit = { _,_ -> },
    private val onBrowse: (String,ContentType)->Unit = { _,_ -> },
    private val onControl: (PhoneControl)->Unit = {},
    private val onFavorite: (Long,String,Boolean)->Unit = { _,_,_ -> },
    private val onLibrary: (CatalogGenre,Map<String,String>)->Unit = { _,_ -> },
    private val relatedSeasons: ()->PhoneSearchResults = { PhoneSearchResults(section="seasons") },
    private val onRelatedPlay: (Long,String)->Unit = { _,_ -> }
): Closeable {
    private val stopped=AtomicBoolean(false)
    private val consumed=AtomicBoolean(false)
    private val localAddress=InetAddress.getByName(address).also { require(it.isLoopbackAddress || it.isSiteLocalAddress) }.hostAddress
    private val listener=ServerSocket(port,8).apply { soTimeout=500 }
    private val deadline=if(persistent) Long.MAX_VALUE else System.nanoTime()+ttlMillis.coerceIn(1,300_000)*1_000_000
    private val token=ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val host="$localAddress:${listener.localPort}"
    /** Persistent mode intentionally omits the one-time token from the public URL. */
    val url=if(persistent) "http://$host/" else "http://$host/$token"
    private val thread=Thread({ serve() },"hongguotv-phone-input").apply { isDaemon=true }
    init {
        thread.start()
    }
    override fun close() { stopped.set(true); runCatching { listener.close() } }
    private fun serve() {
        try {
            while(!stopped.get() && System.nanoTime()<deadline) {
                val client=try { listener.accept() } catch(_: SocketTimeoutException) { continue }
                client.use { socket ->
                    socket.soTimeout=2000
                    try { handle(socket) } catch(_: Exception) { /* Discard malformed or disconnected requests. */ }
                }
            }
        } catch(_: Exception) { /* Closing the dialog interrupts accept(). */ }
        finally {
            val expired=!stopped.get() && !consumed.get()
            close()
            if(expired) onExpired()
        }
    }
    private fun line(input: InputStream, max: Int): String {
        val out=ByteArrayOutputStream()
        while(out.size()<max) {
            val next=input.read()
            if(next<0) throw java.io.EOFException()
            if(next==10) return out.toString("US-ASCII").trimEnd('\r')
            out.write(next)
        }
        throw java.io.IOException("Header too large")
    }
    private fun handle(socket: Socket) {
        if(!socket.inetAddress.let { it.isLoopbackAddress || it.isSiteLocalAddress }) return
        val input=socket.getInputStream().buffered()
        val request=line(input,1024).split(' ')
        if(request.size!=3) return
        val headers=mutableMapOf<String,String>()
        var bytes=0
        while(true) {
            val next=line(input,4096); bytes+=next.length+2
            if(bytes>8192) return respond(socket,431,"请求过大")
            if(next.isEmpty()) break
            val at=next.indexOf(':'); if(at<=0) return respond(socket,400,"请求格式错误")
            val key=next.substring(0,at).lowercase(Locale.ROOT)
            if(headers.put(key,next.substring(at+1).trim())!=null) return respond(socket,400,"重复请求头")
        }
        fun reply(code: Int,message: String) = respond(socket,code,message,headers["accept"]?.split(',')?.any { it.substringBefore(';').trim()=="application/json" }==true)
        if(stopped.get() || (!persistent && consumed.get()) || System.nanoTime()>=deadline) return reply(410,"手机输入服务已停止，请在电视上重新打开应用")
        val pagePath=if(persistent) "/" else "/$token"
        val submitPath=if(persistent) "/submit" else "/$token/submit"
        val apiPaths=listOf("/status","/episode","/results","/play","/search-page","/browse","/control","/favorite","/seasons","/season-play")
        if(headers["host"]!=host || request[1] !in listOf(pagePath,submitPath) && !(persistent && request[1] in apiPaths)) return reply(404,"页面不存在")
        if(request[0]=="GET" && request[1]==pagePath) {
            return respond(socket,200,PhoneSearchPage.render(submitPath,token))
        }
        if(persistent && request[0]=="GET" && request[1]=="/status") {
            if(headers["origin"]?.let { !sameOrigin(it) }==true) return reply(403,"来源不匹配")
            return respond(socket,200,playbackStatus().toJson().toString(),true,rawJson=true)
        }
        if(persistent && request[0]=="GET" && request[1] in listOf("/results","/seasons")) {
            if(headers["origin"]?.let { !sameOrigin(it) }==true) return reply(403,"来源不匹配")
            return respond(socket,200,(if(request[1]=="/seasons") relatedSeasons() else searchResults()).toJson().toString(),true,rawJson=true)
        }
        if(request[0]!="POST" || request[1] !in listOf(submitPath,"/episode","/play","/search-page","/browse","/control","/favorite","/season-play")) return reply(405,"不支持的操作")
        // The fixed-port page is deliberately usable by TV browsers that emit
        // non-standard Origin values (including null). Host and LAN checks above
        // still keep the endpoint scoped to this television's local network.
        if(headers["origin"]?.let { !sameOrigin(it) }==true) return reply(403,"来源不匹配")
        if(headers.containsKey("transfer-encoding") || headers["content-type"]?.substringBefore(';')!="application/x-www-form-urlencoded") return reply(400,"请求格式错误")
        val length=headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..4096 } ?: return reply(413,"输入内容过长")
        val body=ByteArray(length); var offset=0
        while(offset<body.size) { val n=input.read(body,offset,body.size-offset); if(n<0) return; offset+=n }
        val fields=String(body,Charsets.UTF_8).split('&').map { it.split('=',limit=2) }
        if(request[1] in listOf("/browse","/control","/favorite")) {
            if(!persistent || fields.any { it.size!=2 } || fields.map { it[0] }.distinct().size!=fields.size) return reply(400,"请求格式错误")
            val values=runCatching { fields.associate { it[0] to URLDecoder.decode(it[1],"UTF-8") } }.getOrNull() ?: return reply(400,"请求格式错误")
            if(request[1]=="/browse") {
                if(values["section"]=="library") {
                    if(values.keys!=setOf("section","genre","filters")) return reply(400,"剧库请求格式错误")
                    val genre=runCatching { CatalogGenre.from(values["genre"].orEmpty()) }.getOrNull() ?: return reply(400,"剧库分类无效")
                    val filters=runCatching { val obj=org.json.JSONObject(values.getValue("filters")); require(obj.length()<=10)
                        obj.keys().asSequence().associateWith { key -> require(key.length<=50 && obj.get(key) is String); obj.getString(key).also { require(it.length<=100) } }
                    }.getOrNull() ?: return reply(400,"筛选条件无效")
                    try { respond(socket,200,org.json.JSONObject().put("message","正在加载剧库").put("revision",searchResults().revision).toString(),true,rawJson=true) }
                    finally { onLibrary(genre,filters) }
                    return
                }
                if(values.keys!=setOf("section","type") || values["section"] !in listOf("home","favorites","history")) return reply(400,"请选择剧库、收藏或最近观看")
                val type=ContentType.entries.firstOrNull { it.storedValue==values["type"] } ?: return reply(400,"请选择漫剧或短剧")
                try { respond(socket,200,org.json.JSONObject().put("message","正在加载剧库").put("revision",searchResults().revision).toString(),true,rawJson=true) }
                finally { onBrowse(values.getValue("section"),type) }
            } else if(request[1]=="/control") {
                if("action" !in values || values.keys.any { it !in listOf("action","key","value") }) return reply(400,"控制指令格式错误")
                val command=PhoneControl(values.getValue("action"),values["key"].orEmpty(),values["value"].orEmpty())
                if(command.action !in PhoneControl.actions || command.key.length>30 || command.value.length>8) return reply(400,"控制指令无效")
                if(!command.allowed(playbackStatus())) return reply(409,"电视当前状态无法执行该操作，请等待状态更新")
                try { reply(200,"指令已发送，正在等待电视确认") } finally { onControl(command) }
            } else {
                if(values.keys!=setOf("revision","id","value") || values["value"] !in listOf("0","1")) return reply(400,"收藏请求格式错误")
                val revision=values["revision"]?.toLongOrNull() ?: return reply(400,"结果编号无效")
                val snapshot=searchResults(); val id=values["id"].orEmpty()
                if(snapshot.revision!=revision || !snapshot.selectable(id)) return reply(409,"列表已更新，请重新选择")
                try { reply(200,"正在更新收藏") } finally { onFavorite(revision,id,values["value"]=="1") }
            }
            return
        }
        if(request[1] in listOf("/play","/search-page","/season-play")) {
            val actionKey=if(request[1]=="/search-page") "page" else "id"
            if(!persistent || fields.size!=2 || fields.any { it.size!=2 || it[0] !in listOf("revision",actionKey) } || fields.map { it[0] }.distinct().size!=2) return reply(400,"请求格式错误")
            val values=runCatching { fields.associate { it[0] to URLDecoder.decode(it[1],"UTF-8") } }.getOrNull() ?: return reply(400,"请求格式错误")
            val revision=values["revision"]?.toLongOrNull() ?: return reply(400,"搜索结果编号无效")
            val snapshot=if(request[1]=="/season-play") relatedSeasons() else searchResults()
            if(revision!=snapshot.revision) return reply(409,"电视搜索结果已更新，请刷新后选择")
            val id=values["id"].orEmpty()
            val target=values["page"]?.toIntOrNull()
            if(actionKey=="id" && (!id.matches(Regex("[0-9]{1,30}")) || !snapshot.selectable(id))) return reply(409,"该剧已不在当前搜索结果中，请重新搜索")
            if(actionKey=="page" && (target==null || !snapshot.canTurnTo(target))) return reply(409,"当前无法翻到该页，请刷新结果")
            try { respond(socket,200,org.json.JSONObject().put("message",if(actionKey=="id") "已通知电视播放，请稍候" else "正在加载搜索结果")
                .put("revision",revision).toString(),true,rawJson=true) }
            finally { if(request[1]=="/season-play") onRelatedPlay(revision,id) else if(actionKey=="id") onPlay(revision,id) else onSearchPage(revision,target!!) }
            return
        }
        if(request[1]=="/episode") {
            if(!persistent || fields.any { it.size!=2 || it[0] !in listOf("episode","key") } || fields.map { it[0] }.distinct().size!=fields.size) return reply(400,"集数请求格式错误")
            val values=runCatching { fields.associate { it[0] to URLDecoder.decode(it[1],"UTF-8") } }.getOrNull() ?: return reply(400,"请求格式错误")
            val number=values["episode"]?.toIntOrNull() ?: return reply(400,"请选择有效集数")
            val status=playbackStatus()
            if(values["key"]?.let { it!=status.seriesId }==true) return reply(409,"电视已切换剧集，请刷新后选择")
            if(!status.active || number !in 1..status.episodeCount) return reply(409,"电视当前没有这部剧的可播放剧集")
            if(stopped.get()) return reply(410,"电视搜索服务已停止")
            try { respond(socket,200,org.json.JSONObject().put("message","正在切换到第 $number 集").put("active",true).toString(),true,rawJson=true) }
            finally { onEpisode(number-1) }
            return
        }
        if(fields.any { it.size!=2 || it[0] !in listOf("query","type") } || fields.map { it[0] }.distinct().size!=fields.size) return reply(400,"请求格式错误")
        val values=runCatching { fields.associate { it[0] to URLDecoder.decode(it[1],"UTF-8") } }.getOrNull() ?: return reply(400,"请求格式错误")
        val query=values["query"]?.trim()
        if(query.isNullOrBlank() || query.length>80 || query.any { it.isISOControl() }) return reply(400,"请输入 1—80 个字符的剧名")
        // Old phone pages only send query; retain their current TV classification.
        val type=values["type"]?.let { value -> ContentType.entries.firstOrNull { it.storedValue==value } ?: return reply(400,"请选择漫剧或短剧") }
        if(stopped.get() || System.nanoTime()>=deadline || (!persistent && !consumed.compareAndSet(false,true))) return reply(410,"手机输入服务已停止")
        try { respond(socket,200,org.json.JSONObject().put("message","已在TV搜索，请查看结果").put("revision",searchResults().revision).toString(),true,rawJson=true) }
        finally { if(!persistent) close(); onQuery(query,type) }
    }
    private fun sameOrigin(value: String): Boolean {
        // Some TV browsers submit a normal same-page form with Origin: null.
        // Host is still checked against the exact LAN address before this point.
        if(value.trim().isEmpty() || value.trim().equals("null",ignoreCase=true)) return true
        val origin=runCatching { URI(value.trim()) }.getOrNull() ?: return false
        return origin.scheme.equals("http",ignoreCase=true) &&
            origin.host?.equals(localAddress,ignoreCase=true)==true && origin.port==listener.localPort
    }
    private fun respond(socket: Socket, code: Int, message: String, json: Boolean=false,rawJson: Boolean=false) {
        val body=(if(json) if(rawJson) message else org.json.JSONObject().put("message",message).toString() else """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><title>红果 TV · 手机搜索</title><style>${PhoneSearchPage.styles}</style></head><body>$message</body></html>""").toByteArray(Charsets.UTF_8)
        val contentType=if(json) "application/json" else "text/html"
        val header="HTTP/1.1 $code Response\r\nContent-Type: $contentType; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; script-src 'nonce-$token'; connect-src 'self'; img-src https: http:; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
}
