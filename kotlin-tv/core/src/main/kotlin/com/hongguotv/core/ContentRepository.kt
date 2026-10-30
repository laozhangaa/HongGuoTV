// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags")) }
}
data class Detail(val series: Series, val episodes: List<String>)
data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)
data class HomeContent(
    val comicHot: List<Series> = emptyList(),
    val shortHot: List<Series> = emptyList(),
    val comicRecent: List<Series> = emptyList(),
    val shortRecent: List<Series> = emptyList()
) {
    /**
     * Recommendations used by the full-screen hero.
     *
     * The hero is intentionally comic-first: the home page is a mixed shelf,
     * but the requested visual order puts the illustrated dramas before the
     * live-action short dramas.  Keep duplicate ids only once, giving the
     * comic shelf precedence when the two upstream lists overlap.
     *
     * A type marker is prepended to the badge on the hero copy.  This keeps the
     * public type as `List<Series>` for existing TV UI callers while giving
     * the hero renderer a stable, human-readable label ("漫剧" / "短剧").
     * Source shelves remain untouched, so their episode badges are unchanged.
     */
    val hero: List<Series>
        get() = sequence {
            yieldAll(comicHot.map { it.withHeroType(ContentType.COMIC) })
            yieldAll(shortHot.map { it.withHeroType(ContentType.SHORT) })
        }.distinctBy { it.id }.toList()

    private fun Series.withHeroType(type: ContentType): Series {
        val typeLabel = type.label
        val badgeParts = badge.split('·').map(String::trim).filter {
            it.isNotBlank() && it != ContentType.COMIC.label && it != ContentType.SHORT.label
        }
        return copy(badge = (listOf(typeLabel) + badgeParts).joinToString(" · "))
    }
}
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")
class EmptyPlaybackResponseException: IOException("播放接口返回空响应，请稍后重试")

private class RetryableHttpException(val status: Int, val retryAfterMillis: Long = 0L): IOException("内容请求失败（HTTP $status）")

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().dns(Ipv4FirstDns()).connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis, private val playbackSession: PlaybackDeviceSession = PlaybackDeviceSession.shared) {
    private val signer = Signer()
    private val site = "https://hongguoduanju.com"
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long, val web: Boolean = false)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    private val categoryCatalog=CategoryCatalog { payload ->
        val values=VendorConstants.device.toMutableMap()
        for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[key]="73532"
        values["version_name"]="7.3.5.32"
        val signed=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/reading/distribution/category/landpage/v1/",values,payload.toString().toByteArray(Charsets.UTF_8))
        JSONObject(text(signed.url,signed))
    }
    fun catalogFilters(genre: CatalogGenre)=categoryCatalog.panel(genre)
    fun catalog(genre: CatalogGenre,page: Int=1,filters: Map<String,String> = emptyMap())=categoryCatalog.feed(genre,page,filters)
    fun featured(genre: CatalogGenre,orderLabel: String)=categoryCatalog.featured(genre,orderLabel)

    /** Four independent shelves used by the TV home screen. */
    fun homeContent(): HomeContent {
        // Keep the hot short-drama shelf as the availability anchor. If a
        // selector endpoint is temporarily unavailable, the legacy homepage
        // endpoint still provides a useful fallback for the hero.
        fun cancelled()=Thread.currentThread().isInterrupted || RequestScope.currentCancelled()
        val shortHot=try {
            featured(CatalogGenre.SHORT,"最高热度").items.also { if(it.isEmpty()) throw IOException("最高热度短剧为空") }
        } catch(e: Exception) {
            if(cancelled()) throw e
            home(1,ContentType.SHORT).items
        }
        if(cancelled()) throw IOException("首页请求已取消")
        val comicHot=runCatching { featured(CatalogGenre.COMIC,"最高热度").items }.getOrDefault(emptyList())
            .also { if(cancelled()) throw IOException("首页请求已取消") }
        val comicRecent=runCatching { featured(CatalogGenre.COMIC,"最新上架").items }.getOrDefault(emptyList())
            .also { if(cancelled()) throw IOException("首页请求已取消") }
        val shortRecent=runCatching { featured(CatalogGenre.SHORT,"最新上架").items }.getOrDefault(emptyList())
            .also { if(cancelled()) throw IOException("首页请求已取消") }
        return HomeContent(comicHot,shortHot,comicRecent,shortRecent)
    }
    private fun text(url: String, signed: Signer.Request? = null, retryEmptyPlayback: Boolean = false): String {
        fun buildRequest() = Request.Builder().url(if(retryEmptyPlayback) url.toHttpUrl().newBuilder().setQueryParameter("_rticket",clock().toString()).build().toString() else url)
            .header("User-Agent",if(signed==null) WEB_UA else VendorConstants.VIDEO_UA)
            .header("Accept-Language","zh-CN,zh;q=0.9").apply {
                signed?.headers?.forEach { (k,v) -> header(k,v) }
                signed?.body?.let { post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
            }.build()

        var last: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            if(Thread.currentThread().isInterrupted || RequestScope.currentCancelled()) throw IOException("请求已取消")
            try {
                return RequestScope.execute(http.newCall(buildRequest())) { response ->
                    if (!response.isSuccessful) {
                        val retryAfter=response.header("Retry-After")?.toLongOrNull()?.coerceIn(0,2_000)?.times(1_000) ?: 0L
                        if(response.code==408 || response.code==425 || response.code==429 || response.code>=500)
                            throw RetryableHttpException(response.code,retryAfter)
                        throw IOException("内容请求失败（HTTP ${response.code}）")
                    }
                    val body = response.body ?: throw IOException("内容响应为空")
                    if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("内容响应过大")
                    val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                    while(out.size()<=MAX_RESPONSE_BYTES) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }
                    val bytes=out.toByteArray()
                    if(bytes.size > MAX_RESPONSE_BYTES) throw IOException("内容响应过大")
                    String(bytes,Charsets.UTF_8).also { if(it.isBlank()) {
                        if(retryEmptyPlayback) throw EmptyPlaybackResponseException()
                        throw IOException("内容接口返回空数据，请稍后重试")
                    } }
                }
            } catch(e: RetryableHttpException) {
                last=e
                if(attempt+1<MAX_ATTEMPTS) pauseBeforeRetry(attempt,e.retryAfterMillis) else throw IOException("内容服务暂时不可用（HTTP ${e.status}），请稍后重试",e)
            } catch(e: IOException) {
                if(!(retryable(e) || retryEmptyPlayback && e is EmptyPlaybackResponseException) || attempt+1>=MAX_ATTEMPTS) throw e
                last=e; pauseBeforeRetry(attempt,0)
            }
        }
        throw IOException("内容请求失败，请稍后重试",last)
    }

    private fun pauseBeforeRetry(attempt: Int, serverDelay: Long) {
        val backoff=(250L shl attempt.coerceIn(0,3)).coerceAtMost(2_000L)
        val delay=maxOf(backoff,serverDelay).coerceAtMost(4_000L)
        try { Thread.sleep(delay) } catch(_: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("请求已取消") }
        if(Thread.currentThread().isInterrupted || RequestScope.currentCancelled()) throw IOException("请求已取消")
    }

    private fun retryable(problem: IOException): Boolean {
        if(problem is RetryableHttpException || problem is SocketTimeoutException || problem is ConnectException || problem is SSLException) return true
        val cause=problem.cause
        return cause is SocketTimeoutException || cause is ConnectException || cause is SSLException
    }
    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(o.str("series_id"),o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },cleanUrl(o.str("series_cover")),o.str("series_intro"),o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    fun ranking(page: Int = 1, type: ContentType = ContentType.COMIC): CatalogPage {
        require(page in 1..100)
        val kind=if(type==ContentType.COMIC) "comic" else "real"
        return ComicRank.parse(text("$site/rank/hot-$kind-drama?page=$page"),page,type)
    }
    fun comicRanking(page: Int = 1): CatalogPage = ranking(page,ContentType.COMIC)
    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        if(type==ContentType.COMIC) return comicRanking(page)
        val data=router("$site/category?tab=1&sort_type=1"+if(page>1) "&page=$page" else "")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("首页数据结构已变化")
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw IOException("首页数据暂不可用")
        val items=valid(rows.objects().map(::card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100
        return CatalogPage(items,items.isNotEmpty() && page<minOf(lastPage,100))
    }
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 || (page==1 && it.key.first==type && it.key.second==keyword) }
            searchCursors[Triple(type,keyword,page-1)]
        }
        if(page>1 && type==ContentType.COMIC && cursor==null) throw SearchSessionExpiredException()
        // Keep subsequent pages on the same source: website pages contain 10 mixed items,
        // while the App uses 20 items and an opaque cursor.
        if(cursor?.web==true) return webSearch(keyword,page,type)
        try { return appSearch(keyword,page,type,cursor) }
        catch(e: Exception) {
            if(e is SearchSessionExpiredException || Thread.currentThread().isInterrupted || page>1 && cursor!=null) throw e
            try { return webSearch(keyword,page,type) }
            catch(fallback: Exception) { fallback.addSuppressed(e); throw fallback }
        }
    }
    private fun rememberSearch(keyword: String, page: Int, type: ContentType, cursor: SearchCursor) = synchronized(searchCursors) {
        searchCursors[Triple(type,keyword,page)]=cursor
        while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
    }
    private fun webSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        if(keyword.codePointCount(0,keyword.length)>30) throw IOException("备用搜索最多支持 30 个字，请缩短关键词")
        // Search pages are now rendered in the browser; their HTML has no _ROUTER_DATA.
        val data=try { JSONObject(text("$site/api/search/result/?query=${Signer.encode(keyword)}&page=$page")) }
            catch(e: Exception) {
                if(Thread.currentThread().isInterrupted) throw e
                throw IOException("搜索服务暂不可用，请稍后重试",e)
            }
        val rows=data.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        if(data.optInt("pageNum",-1)!=page) throw IOException("搜索分页信息异常，请重新搜索")
        // The official web result is mixed. Its genre values identify comics (205)
        // and short dramas (203); actor cards and trailers must not enter either list.
        val genre=if(type==ContentType.COMIC) "205" else "203"
        val items=valid(rows.objects().filter { it.optJSONObject("video_data")?.str("genre")==genre }.map(::card))
        val hasMore=data.optBoolean("hasMore",page<data.optInt("totalPages",page)) && page<100
        rememberSearch(keyword,page,type,SearchCursor("","",null,clock(),web=true))
        return CatalogPage(items,hasMore)
    }
    private fun appSearch(keyword: String, page: Int, type: ContentType, cursor: SearchCursor?): CatalogPage {
        val identity=playbackSession.profile(http)
        val values=identity.values.toMutableMap()
        // This search protocol retains the dedicated comic tab (19). Newer
        // protocol versions return a different tab set for the same request.
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[k]="72232"
        values["version_name"]="7.2.2.32"
        values.putAll(linkedMapOf("query" to keyword,"count" to "20","offset" to ((page-1)*20).toString(),"tab_type" to type.searchTab.toString(),"bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to "0"))
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        val signed=Signer(device=values).sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v",values).let { request ->
            request.copy(headers=request.headers+(if(identity.cookie.isBlank()) emptyMap() else mapOf("Cookie" to identity.cookie)))
        }
        val data=JSONObject(text(signed.url,signed))
        if(data.optInt("code",-1)!=0) throw IOException("搜索接口暂不可用")
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs") ?: throw IOException("搜索格式异常")
        val tab=tabs.objects().firstOrNull { it.optInt("tab_type")==type.searchTab } ?: throw IOException("${type.label}搜索暂不可用")
        val list=mutableListOf<Series>()
        for(cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val cells=mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }.getOrNull()?.let { cells.addAll(it) }
            for(row in cells) {
                val video=row.optJSONArray("video_data")?.objects()?.firstOrNull() ?: continue
                val id=video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                val title=row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty().ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }
                val count=video.optInt("episode_cnt")
                list+=Series(id,title,cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),video.str("video_desc"),if(count>0) "全 $count 集" else video.str("rec_text"),video.str("sub_title"))
            }
        }
        rememberSearch(keyword,page,type,SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock()))
        return CatalogPage(valid(list),tab.optBoolean("has_more",false) && page<100)
    }
    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val o=router("$site/detail?series_id=$id").optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        return Detail(card(o).copy(id=id),episodes)
    }
    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        val payload="""{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]}}""".toByteArray()
        val identity=playbackSession.profile(http)
        fun model(profile: PlaybackDeviceSession.Profile): String {
            val playerUrl="https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/?"+Signer.encode(profile.values)
            val request=Signer.Request(playerUrl,mapOf("User-Agent" to PlaybackDeviceSession.USER_AGENT,"Accept" to "application/json; charset=utf-8,application/x-protobuf")+
                (if(profile.cookie.isBlank()) emptyMap() else mapOf("Cookie" to profile.cookie)),payload)
            return text(playerUrl,request,retryEmptyPlayback=true)
        }
        val data=JSONObject(try { model(identity) } catch(problem: EmptyPlaybackResponseException) {
            val recovered=playbackSession.recoverAfterEmpty(http,identity) ?: throw problem
            model(recovered)
        })
        val code=data.optInt("Code",data.optInt("code",0))
        if(code!=0) throw IOException("播放接口暂不可用（错误码 $code）")
        val map=data.optJSONObject("data") ?: throw IOException("播放信息为空")
        val entry=map.optJSONObject(id) ?: map.keys().asSequence().mapNotNull { map.optJSONObject(it) }.firstOrNull() ?: throw IOException("播放信息为空")
        val rawModel=entry.opt("video_model")
        val model=when(rawModel) {
            is JSONObject -> rawModel
            is String -> JSONObject(rawModel)
            else -> map.optJSONObject("video_model")
        } ?: throw IOException("播放模型为空")
        val directList=videoListFromModel(model)
        if(directList!=null) return selectDirectStream(directList,model,maxQuality)
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        val info=JSONObject(text(fallback)).optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
        val videoList=info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4 extractor.
        // Keep missing legacy codec labels usable, but never select a known incompatible variant.
        val rows=videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }.filter {
            it.second.str("main_url").ifEmpty { it.second.str("backup_url").ifEmpty { it.second.str("backup_url_1") } }.isNotEmpty() &&
                it.second.str("codec_type").lowercase() !in setOf("bytevc2","bvc2")
        }.map { (k,o) -> Triple(qualityNumber(o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } } } }),k,o) }.sortedByDescending { it.first }.toList()
        val selected=rows.firstOrNull { it.first<=maxQuality } ?: rows.lastOrNull() ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val item=selected.third; var url=cleanUrl(item.str("main_url").ifEmpty { item.str("backup_url").ifEmpty { item.str("backup_url_1") } }); val seed=info.str("key_seed")
        if(seed.isNotEmpty()) url=MediaCrypto.decryptUrl(url,decode64(seed))
        if(!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
        val key=item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality=if(selected.first>0) "${selected.first}P"+(if(selected.first>maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(url,key,quality)
    }
    companion object {
        private const val MAX_ATTEMPTS=3
        private const val MAX_RESPONSE_BYTES=16*1024*1024
        private const val WEB_UA="Mozilla/5.0 (Linux; Android 8.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private fun videoListFromModel(model: JSONObject): Any? {
            for(key in listOf("video_list","dynamic_video_list")) {
                val value=model.opt(key)
                if(value is JSONObject || value is JSONArray) return value
            }
            for(key in listOf("dynamic_video","video_info","data")) {
                val nested=model.optJSONObject(key) ?: continue
                val value=videoListFromModel(nested)
                if(value!=null) return value
            }
            return null
        }
        private fun collectVideoItems(value: Any?, hinted: String=""): List<Pair<String,JSONObject>> = when(value) {
            is JSONArray -> (0 until value.length()).flatMap { collectVideoItems(value.opt(it),hinted) }
            is JSONObject -> {
                if(value.has("main_url") || value.has("backup_url") || value.has("spade_a") || value.has("encrypt_info")) listOf(hinted to value)
                else value.keys().asSequence().flatMap { key ->
                    val child=value.opt(key)
                    if(child is JSONObject || child is JSONArray) collectVideoItems(child,if(key in setOf("dynamic_video","video_info","data")) hinted else key).asSequence() else emptySequence()
                }.toList()
            }
            else -> emptyList()
        }
        private fun textFrom(item: JSONObject, vararg keys: String): String {
            for(key in keys) item.str(key).takeIf { it.isNotEmpty() }?.let { return it }
            return ""
        }
        private fun selectDirectStream(value: Any, model: JSONObject, maxQuality: Int): StreamInfo {
            val rows=collectVideoItems(value).mapNotNull { (hint,item) ->
                val meta=item.optJSONObject("video_meta")
                val codec=textFrom(item,"codec_type").ifEmpty { meta?.str("codec_type").orEmpty() }.lowercase()
                if(codec in setOf("bytevc2","bvc2")) return@mapNotNull null
                val definition=textFrom(item,"definition","quality_desc","vheight","height","quality").ifEmpty {
                    textFrom(meta ?: JSONObject(),"definition","quality_desc","vheight","height","quality").ifEmpty { hint }
                }
                val quality=qualityNumber(definition)
                val url=textFrom(item,"main_url","backup_url","backup_url_1","url")
                if(url.isEmpty()) null else Triple(quality,url,item)
            }.sortedByDescending { it.first }
            val selected=rows.firstOrNull { it.first in 1..maxQuality } ?: rows.lastOrNull() ?: throw IOException("没有可用清晰度")
            var url=cleanUrl(selected.second)
            val seed=keySeed(model)
            if(seed.isNotEmpty()) runCatching { url=MediaCrypto.decryptUrl(url,decode64(seed)) }
            if(!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
            val encrypted=selected.third.str("spade_a").ifEmpty { selected.third.optJSONObject("encrypt_info")?.str("spade_a").orEmpty() }
            val key=encrypted.takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
            val quality=if(selected.first>0) "${selected.first}P" else "自动"
            return StreamInfo(url,key,quality)
        }
        private fun keySeed(model: JSONObject): String {
            model.str("key_seed").takeIf { it.isNotEmpty() }?.let { return it }
            for(key in listOf("dynamic_video","video_info","data")) {
                val nested=model.optJSONObject(key) ?: continue
                keySeed(nested).takeIf { it.isNotEmpty() }?.let { return it }
            }
            return ""
        }
        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw IOException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw IOException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw IOException("页面数据不完整")
        }
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").findAll(value).map { it.value.toInt() }.maxOrNull()
            ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
    }
}
