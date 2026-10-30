package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class ContentRepositoryTest {
    private fun streamRepository(vararg variants: Triple<Int,String,String>) = repository { request ->
        if(request.url.host=="example.com") {
            val rows=JSONObject()
            variants.forEachIndexed { index,(height,codec,url) -> rows.put("video_$index",JSONObject().put("vheight",height).put("codec_type",codec).put("main_url",url)) }
            JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
        } else """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
    }

    @Test fun incompatibleByteVc2DoesNotBecomeAudioOnlyPlayback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(1080,"bytevc1","https://example.com/hevc"))
        val result=repo.stream("123",720)
        assertEquals("https://example.com/hevc",result.url)
        assertEquals("1080P（兼容资源）",result.quality)
    }

    @Test fun compatibleLowerQualityWinsBeforeHigherResolutionFallback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(480,"h264","https://example.com/avc"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("480P",repo.stream("123",720).quality)
    }

    @Test fun entirelyIncompatibleSourceFailsBeforeStartingAudio() {
        val repo=streamRepository(Triple(720,"ByteVC2","https://example.com/unsupported"),Triple(480,"bvc2","https://example.com/unsupported2"))
        assertThrows(IOException::class.java) { repo.stream("123",720) }
    }

    @Test fun legacyMissingCodecLabelKeepsQualityLimit() {
        val repo=streamRepository(Triple(720,"","https://example.com/legacy"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("720P",repo.stream("123",720).quality)
    }

    private fun repository(clock: () -> Long = System::currentTimeMillis, respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build(),clock,PlaybackDeviceSession { DeviceRegister.Registered("12345","67890") }
    )

    private fun directPlaybackResponse() = """{"code":0,"data":{"123":{"video_model":{"video_list":[{"definition":"1080p","main_url":"https://example.com/1080.mp4","video_meta":{"codec_type":"h264","vheight":1080}}]}}}}"""

    @Test fun playbackUsesRegisteredIdentityAndKeepsItAcrossRecoveryAndRepositories() {
        val registrations=AtomicInteger()
        val attempts=AtomicInteger()
        val requests=mutableListOf<Request>()
        val session=PlaybackDeviceSession {
            registrations.incrementAndGet()
            DeviceRegister.Registered("456789","987654",cookies=mapOf("install_id" to "987654","store-region" to "cn","sessionid" to "excluded-account-cookie"))
        }
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            requests+=chain.request()
            val body=if(attempts.getAndIncrement()<2) "" else directPlaybackResponse()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        var now=1000L
        val first=ContentRepository(client,{ now++ },session)
        val second=ContentRepository(client,{ now++ },session)
        assertEquals("1080P",first.stream("123").quality)
        assertEquals("1080P",second.stream("123").quality)
        assertEquals(1,registrations.get())
        assertEquals(4,requests.size)
        assertEquals(4,requests.map { it.url.queryParameter("_rticket") }.distinct().size)
        requests.forEach { request ->
            assertEquals("/novel/player/multi_video_model/v1/",request.url.encodedPath)
            assertEquals("456789",request.url.queryParameter("device_id"))
            assertEquals("987654",request.url.queryParameter("iid"))
            assertTrue(request.header("User-Agent").orEmpty().contains("com.phoenix.read/71532"))
            assertEquals("install_id=987654; store-region=cn",request.header("Cookie"))
            assertNull(request.header("x-medusa"))
            val buffer=okio.Buffer(); request.body!!.writeTo(buffer)
            assertEquals("123",JSONObject(buffer.readUtf8()).getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(0))
        }
    }

    @Test fun persistentlyEmptyPlaybackStopsAfterThreeRequestsWithoutReplacingIdentity() {
        val registrations=AtomicInteger()
        val attempts=AtomicInteger()
        val session=PlaybackDeviceSession { registrations.incrementAndGet(); DeviceRegister.Registered("456","789") }
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            attempts.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("".toResponseBody()).build()
        }.build()
        val repo=ContentRepository(client,System::currentTimeMillis,session)
        assertThrows(EmptyPlaybackResponseException::class.java) { repo.stream("123") }
        assertEquals(3,attempts.get()); assertEquals(1,registrations.get())
    }

    private fun rankPayload(page: Int = 1, totalPages: Int = 2) = JSONObject()
        .put("isSuccess",true)
        .put("rankList",JSONArray().put(JSONObject().put("seriesId","123").put("title","测试漫剧")
            .put("cover","https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
            .put("episodeCount",12).put("tags",JSONArray().put("玄幻"))))
        .put("pagination",JSONObject().put("pageNum",page).put("totalPages",totalPages))

    private fun script(name: String, args: JSONArray) =
        "<script data-fn-name=\"$name\" data-fn-args=\"${args.toString().replace("&","&amp;").replace("\"","&quot;")}\"></script>"

    private fun currentRank(payload: JSONObject, type: ContentType = ContentType.COMIC) = script("mergeLoaderData",JSONArray()
        .put("rank_hot-${if(type==ContentType.COMIC) "comic" else "real"}-drama/page").put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun shortRankingUsesDedicatedSourceAndPreservesPaginationAndMetadata() {
        val payload=rankPayload(2,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("title","测试短剧").put("rank",21).put("heatText","8800万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-real-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-real-drama/page":{"rankKey":"real","pageNum":2,"updatedText":"今日已更新"}}};</script>"""+currentRank(payload,ContentType.SHORT)
        }
        val result=repo.ranking(2,ContentType.SHORT)
        assertEquals("测试短剧",result.items.single().title)
        assertEquals(RankPosition(21,"8800万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("今日已更新",result.ranking.updatedText)
        assertTrue(result.hasMore)
    }

    @Test fun rankingTypesRejectEachOthersPayloads() {
        for(type in ContentType.entries) {
            val other=if(type==ContentType.COMIC) ContentType.SHORT else ContentType.COMIC
            val repo=repository { currentRank(rankPayload(),other) }
            assertThrows(IOException::class.java) { repo.ranking(type=type) }
        }
    }

    @Test fun shortRankingSupportsLegacyPayloadAndLastPage() {
        val repo=repository { script("r",JSONArray().put("rank_hot-real-drama/page").put("content").put(rankPayload(3,3))) }
        val result=repo.ranking(3,ContentType.SHORT)
        assertEquals(3,result.ranking!!.totalPages)
        assertFalse(result.hasMore)
        assertThrows(IOException::class.java) { repo.ranking(2,ContentType.SHORT) }
        assertThrows(IllegalArgumentException::class.java) { repo.ranking(0,ContentType.SHORT) }
    }

    @Test fun renderedShortRankingChecksTypeAndPreservesRankHeatAndLastPage() {
        val html="""<script>_ROUTER_DATA={"loaderData":{"rank_hot-real-drama/page":{"rankKey":"real","pageNum":2,"updatedText":"今日已更新"}}};</script>
            <article aria-labelledby="rank-title-456"><span class="pc-badge-number-example">21</span>
            <h2 id="rank-title-456">短剧</h2><p class="pc-metrics-example">800万热度</p></article>
            <nav aria-label="榜单分页"><a href="/rank/hot-real-drama?page=1">1</a><span aria-current="page">2</span></nav>"""
        val result=repository { html }.ranking(2,ContentType.SHORT)
        assertEquals(RankPosition(21,"800万热度"),result.ranking!!.positions["456"])
        assertEquals(2,result.ranking.totalPages)
        assertEquals("今日已更新",result.ranking.updatedText)
        assertFalse(result.hasMore)
        assertThrows(IOException::class.java) { repository { html.replace("\"rankKey\":\"real\"","\"rankKey\":\"comic\"") }.ranking(2,ContentType.SHORT) }
    }

    @Test fun comicRankingPreservesSourceRankHeatAndUpdateRatherThanRenumbering() {
        val payload=rankPayload(2,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",23).put("heatText","5746万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"9月22日已更新"}}};</script>"""+currentRank(payload)
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(23,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("9月22日已更新",result.ranking.updatedText)
        assertTrue(result.hasMore)
        assertFalse(result.items.single().toJson().has("rank"))
    }

    @Test fun missingRankIsNotInventedAndLastPageStops() {
        val result=repository { currentRank(rankPayload(5,5)) }.comicRanking(5)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(null,""),result.ranking!!.positions["123"])
        assertFalse(result.hasMore)
    }

    @Test fun renderedRankingPreservesPageTwoRankAndHeat() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">21</span>
                <h2 id="rank-title-123">漫剧</h2><p class="pc-metrics-example"><span>5746万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=5">5</a></nav>"""
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(21,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("今日已更新",result.ranking.updatedText)
    }

    @Test fun rankingRejectsUnexpectedPageInsteadOfShowingWrongRanks() {
        val repo=repository { currentRank(rankPayload(1,5)) }
        assertThrows(IOException::class.java) { repo.comicRanking(2) }
        assertThrows(IllegalArgumentException::class.java) { repo.comicRanking(0) }
    }

    @Test fun renderedLastPageCountsCurrentPageWithoutAnAnchor() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":5}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">81</span><h2 id="rank-title-123">漫剧</h2></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=4">4</a><span aria-current="page">5</span></nav>"""
        }
        val result=repo.comicRanking(5)
        assertEquals(5,result.ranking!!.totalPages)
        assertFalse(result.hasMore)
    }

    @Test fun comicHomeUsesDedicatedRankAndRealPagination() {
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            currentRank(rankPayload(2,2))
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试漫剧",page.items.single().title)
        assertEquals("https://example.com/cover.jpg",page.items.single().cover)
        assertEquals("全 12 集",page.items.single().badge)
        assertFalse(page.hasMore)
    }

    @Test fun legacyRankIsStillSupported() {
        val repo=repository { script("r",JSONArray().put("rank_hot-comic-drama/page").put("content").put(rankPayload())) }
        assertTrue(repo.home(1,ContentType.COMIC).hasMore)
    }

    @Test fun comicHomeDoesNotUseUnrelatedRankData() {
        val repo=repository { script("r",JSONArray().put("rank_hot-real-drama/page").put("content").put(rankPayload())) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun rejectedComicRankRemainsAnError() {
        val repo=repository { currentRank(rankPayload().put("isSuccess",false)) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun renderedComicRankWorksWhenStreamedPayloadIsMissing() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2}}};</script>
                <article aria-labelledby="rank-title-123"><a href="/detail?series_id=123"><img src="https://example.com/a.jpg"></a>
                <h2 id="rank-title-123">测试 &amp; 漫剧</h2><p class="pc-description-example">简介</p><p class="pc-metrics-example">123万热度</p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=1">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=3">3</a></nav>"""
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试 & 漫剧",page.items.single().title)
        assertEquals("https://example.com/a.jpg",page.items.single().cover)
        assertTrue(page.hasMore)
    }

    @Test fun comicSearchCarriesCursorAndKeepsTypesAndQueriesSeparate() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","comic-session").put("passback","comic-cursor").put("next_offset",25))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals("comic-session",requests[1].url.queryParameter("search_id"))
        assertEquals("comic-cursor",requests[1].url.queryParameter("passback"))
        assertEquals("25",requests[1].url.queryParameter("offset"))
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("重生",2,ContentType.COMIC) }
        assertEquals(2,requests.size)
    }

    @Test fun expiredCursorRequestsFreshSearchInsteadOfDuplicateOffsetPage() {
        var now=0L
        var requests=0
        val repo=repository(clock={ now }) {
            requests++
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","session").put("passback","cursor"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        now=300001L
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("修仙",2,ContentType.COMIC) }
        assertEquals(1,requests)
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals(3,requests)
    }

    @Test fun shortHomeStillUsesCategoryAndStopsOnLastPage() {
        val repo=repository { request ->
            assertEquals("1",request.url.queryParameter("tab"))
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"456","series_name":"测试短剧"}],"pagination":{"totalPages":1}}}};</script>"""
        }
        val result=repo.home(1,ContentType.SHORT)
        assertEquals("456",result.items.single().id)
        assertFalse(result.hasMore)
    }

    private fun searchTab(type: Int,id: String) = JSONObject().put("tab_type",type).put("has_more",true)
        .put("data",JSONArray().put(JSONObject().put("video_data",JSONArray().put(JSONObject()
            .put("series_id",id).put("title","测试剧集").put("episode_cnt",8)))))

    @Test fun comicSearchRequestsAndSelectsOnlyComicTab() {
        var requests=0
        val repo=repository { request ->
            requests++
            assertEquals("19",request.url.queryParameter("tab_type"))
            assertEquals(if(requests==1) "0" else "20",request.url.queryParameter("offset"))
            assertEquals("修仙",request.url.queryParameter("query"))
            JSONObject().put("code",0).put("search_tabs",JSONArray()
                .put(searchTab(11,"111")).put(searchTab(19,"999"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("999"),repo.search("修仙",2,ContentType.COMIC).items.map { it.id })
    }

    @Test fun searchSharesAnonymousIdentityAndKeepsComicProtocolAndCursor() {
        val registrations=AtomicInteger()
        val requests=mutableListOf<Request>()
        val session=PlaybackDeviceSession {
            registrations.incrementAndGet()
            DeviceRegister.Registered("456789","987654",cookies=mapOf("install_id" to "987654","sessionid" to "excluded"))
        }
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            requests+=chain.request()
            val tab=chain.request().url.queryParameter("tab_type")!!.toInt()
            val response=JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(tab,"999")
                .put("search_id","session").put("passback","cursor").put("next_offset",25)))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(response.toString().toResponseBody()).build()
        }.build()
        val first=ContentRepository(client,System::currentTimeMillis,session)
        val second=ContentRepository(client,System::currentTimeMillis,session)
        first.search("修仙",1,ContentType.COMIC)
        first.search("修仙",2,ContentType.COMIC)
        second.search("总裁",1,ContentType.SHORT)
        assertEquals(1,registrations.get())
        assertEquals(listOf("19","19","11"),requests.map { it.url.queryParameter("tab_type") })
        assertEquals("25",requests[1].url.queryParameter("offset"))
        assertEquals("cursor",requests[1].url.queryParameter("passback"))
        requests.forEach { request ->
            assertEquals("456789",request.url.queryParameter("device_id"))
            assertEquals("987654",request.url.queryParameter("iid"))
            assertEquals("72232",request.url.queryParameter("version_code"))
            assertEquals("7.2.2.32",request.url.queryParameter("version_name"))
            assertEquals("install_id=987654",request.header("Cookie"))
            assertNotNull(request.header("x-medusa"))
        }
    }

    @Test fun emptyComicSearchDoesNotFallBackToShortResults() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"search_tabs":[{"tab_type":19,"data":[],"has_more":false}]}""" }
        assertTrue(repo.search("无结果",1,ContentType.COMIC).items.isEmpty())
        assertEquals(1,requests)
    }

    @Test fun missingComicTabDoesNotFallBackToShortResults() {
        var requests=0
        val repo=repository { request ->
            requests++
            if(request.url.host!="hongguoduanju.com") """{"code":0,"search_tabs":[{"tab_type":11,"data":[]}]}"""
            else webSearchPage(1,1,webVideo("123","短剧","203"))
        }
        assertTrue(repo.search("修仙",1,ContentType.COMIC).items.isEmpty())
        assertEquals(2,requests)
    }

    @Test fun shortSearchKeepsWebsiteFallback() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size==1) throw IOException("offline")
            assertEquals("/api/search/result/",request.url.encodedPath)
            webSearchPage(1,1,webVideo("456","测试短剧","203"))
        }
        assertEquals("456",repo.search("短剧",1,ContentType.SHORT).items.single().id)
        assertEquals(2,paths.size)
    }

    @Test fun transientHttpFailureIsRetriedWithoutChangingTheSignedRequest() {
        val attempts=AtomicInteger(0)
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            if(attempts.getAndIncrement()<2) Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(503).message("Unavailable").header("Retry-After","0").body("busy".toResponseBody()).build()
            else Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(11,"456"))).toString().toResponseBody()).build()
        }.build()
        val repo=ContentRepository(client,System::currentTimeMillis,PlaybackDeviceSession { DeviceRegister.Registered("12345","67890") })
        val result=repo.search("重试",1,ContentType.SHORT)
        assertEquals(listOf("456"),result.items.map { it.id })
        assertEquals(3,attempts.get())
    }

    private fun webVideo(id: String,title: String,genre: String) = JSONObject().put("video_data",JSONObject()
        .put("series_id",id).put("series_title",title).put("genre",genre).put("episode_cnt",12)
        .put("series_cover","https://example.com/cover.jpg"))

    private fun webSearchPage(page: Int,total: Int,vararg rows: JSONObject) = JSONObject()
        .put("pageNum",page).put("totalPages",total).put("hasMore",page<total)
        .put("searchList",JSONArray(rows.toList())).toString()

    @Test fun emptyAppResponseUsesWebApiAndKeepsComicPaginationOnThatSource() {
        val requests=mutableListOf<Request>()
        val keyword="修仙归来，我的未婚妻"
        val repo=repository { request ->
            requests+=request
            if(request.url.host!="hongguoduanju.com") ""
            else {
                assertEquals("/api/search/result/",request.url.encodedPath)
                assertTrue(request.header("User-Agent").orEmpty().startsWith("Mozilla/5.0"))
                assertFalse(request.header("User-Agent").orEmpty().contains("com.kylin.read"))
                assertEquals(keyword,request.url.queryParameter("query"))
                val page=request.url.queryParameter("page")!!.toInt()
                webSearchPage(page,2,webVideo((900+page).toString(),"测试漫剧 $page","205"),
                    webVideo("123","真人短剧","203"),webVideo("456","预告片","261"),
                    JSONObject().put("doc_type",46).put("name","演员"))
            }
        }
        val first=repo.search(keyword,1,ContentType.COMIC)
        assertEquals(listOf("901"),first.items.map { it.id })
        assertEquals("全 12 集",first.items.single().badge)
        assertTrue(first.hasMore)
        val second=repo.search(keyword,2,ContentType.COMIC)
        assertEquals(listOf("902"),second.items.map { it.id })
        assertFalse(second.hasMore)
        assertEquals(3,requests.size)
        assertEquals(1,requests.count { it.url.host!="hongguoduanju.com" })
    }

    @Test fun filteredEmptyWebPageRetainsNextPageAndShortLibraryExcludesComics() {
        val repo=repository { request ->
            if(request.url.host!="hongguoduanju.com") ""
            else webSearchPage(1,2,webVideo("123","漫剧","205"))
        }
        val result=repo.search("修仙",1,ContentType.SHORT)
        assertTrue(result.items.isEmpty())
        assertTrue(result.hasMore)
    }

    @Test fun webSearchRejectsWrongPageAndDoesNotPresentBrokenResponseAsNoMatches() {
        for(response in listOf("", "<html>error</html>", "{}",webSearchPage(1,2))) {
            val repo=repository { request ->
                if(request.url.host!="hongguoduanju.com") "" else response
            }
            if(response==webSearchPage(1,2)) {
                repo.search("修仙",1,ContentType.COMIC)
                assertThrows(IOException::class.java) { repo.search("修仙",2,ContentType.COMIC) }
            } else {
                val error=assertThrows(IOException::class.java) { repo.search("修仙",1,ContentType.COMIC) }
                assertFalse(error.message.orEmpty().contains("End of input"))
            }
        }
    }

    @Test fun appPaginationFailureDoesNotSwitchToWebWithDifferentPageSize() {
        var requests=0
        val repo=repository {
            requests++
            if(requests==1) JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999"))).toString()
            else ""
        }
        repo.search("修仙",1,ContentType.COMIC)
        assertThrows(IOException::class.java) { repo.search("修仙",2,ContentType.COMIC) }
        assertEquals(2,requests)
    }
}
