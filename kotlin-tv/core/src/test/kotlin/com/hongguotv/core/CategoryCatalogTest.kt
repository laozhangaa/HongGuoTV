package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CategoryCatalogTest {
    @Test fun homeHeroPutsComicsFirstAndMarksEachType() {
        val comic = listOf(
            Series("1", "漫剧一", badge = "全 12 集"),
            Series("2", "漫剧二", badge = "漫剧 · 全 8 集")
        )
        val short = listOf(
            // This duplicate must keep the comic recommendation and its type.
            Series("2", "短剧同 id", badge = "全 20 集"),
            Series("3", "短剧一")
        )
        val content = HomeContent(comicHot = comic, shortHot = short)

        assertEquals(listOf("1", "2", "3"), content.hero.map { it.id })
        assertEquals(listOf("漫剧", "漫剧", "短剧"), content.hero.map { it.badge.substringBefore(" ·") })
        assertEquals("全 12 集", content.comicHot.first().badge)
        assertEquals("全 20 集", content.shortHot.first().badge)
    }

    @Test fun featuredShelfResolvesOpaqueRecommendationOption() {
        val calls=mutableListOf<JSONObject>()
        val catalog=CategoryCatalog { request ->
            calls+=JSONObject(request.toString())
            if(request.getString("req_type")=="only_panel") JSONObject().put("code",0).put("data",JSONObject().put("selector_rows",JSONArray()
                .put(JSONObject().put("type","genre").put("row_name","分类").put("items",JSONArray().put(JSONObject().put("selector_item_id","comic_series").put("show_name","漫剧"))))
                .put(JSONObject().put("type","recommend").put("row_name","推荐").put("items",JSONArray()
                    .put(JSONObject().put("selector_item_id","latest-id").put("show_name","最新上架"))
                    .put(JSONObject().put("selector_item_id","hot-id").put("show_name","最高热度"))))))
            else JSONObject().put("code",0).put("data",JSONObject().put("video_data",JSONArray().put(JSONObject().put("series_id","321").put("series_name","最高热度测试"))))
                .put("session_id","featured-session").put("next_offset",18).put("has_more",false)
        }
        assertEquals("321",catalog.featured(CatalogGenre.COMIC,"最高热度").items.single().id)
        val request=calls.last()
        assertEquals("hot-id",request.getJSONObject("select_items").getJSONArray("recommend").getString(0))
    }

    @Test fun panelFiltersAndOpaquePaginationAreValidated() {
        val calls=mutableListOf<JSONObject>()
        val catalog=CategoryCatalog { request ->
            calls+=JSONObject(request.toString())
            if(request.getString("req_type")=="only_panel") JSONObject().put("code",0).put("data",JSONObject().put("selector_rows",JSONArray()
                .put(JSONObject().put("type","genre").put("row_name","分类").put("items",JSONArray().put(JSONObject().put("selector_item_id","comic_series").put("show_name","漫剧"))))
                .put(JSONObject().put("type","theme").put("row_name","题材").put("items",JSONArray().put(JSONObject().put("selector_item_id","revenge").put("show_name","复仇"))))))
            else JSONObject().put("code",0).put("data",JSONObject().put("video_data",JSONArray().put(JSONObject().put("series_id","123").put("series_name","测试漫剧").put("episode_cnt",18)
                .put("series_cover","https://p3-reading-sign.fqnovelpic.com/novel-pic/abcdef123~tplv-81nmtwyey9:400:0.heic?x-signature=test")))
                .put("session_id","opaque-session").put("next_offset",18).put("has_more",request.optInt("offset")==0))
        }
        assertEquals(listOf("题材"),catalog.panel(CatalogGenre.COMIC).map { it.label })
        assertEquals(listOf("" to "全部","revenge" to "复仇"),catalog.panel(CatalogGenre.COMIC).single().options)
        assertTrue(runCatching { catalog.feed(CatalogGenre.COMIC,1,mapOf("theme" to "fake")) }.exceptionOrNull() is IllegalArgumentException)
        val first=catalog.feed(CatalogGenre.COMIC,1,mapOf("theme" to "revenge"))
        assertTrue(first.hasMore); assertEquals("123",first.items.single().id)
        assertEquals("测试漫剧",first.items.single().title)
        assertEquals("https://p3-novel.byteimg.com/novel-pic/abcdef123~tplv-shrink:640:0.image",first.items.single().cover)
        val second=catalog.feed(CatalogGenre.COMIC,2,mapOf("theme" to "revenge"))
        assertFalse(second.hasMore)
        assertEquals(18,calls.last().getInt("offset")); assertEquals("opaque-session",calls.last().getString("session_id"))
        assertEquals(2,calls.last().getInt("client_req_type"))
        assertTrue(runCatching { catalog.feed(CatalogGenre.SHORT,2,emptyMap()) }.exceptionOrNull() is IOException)
    }
}
