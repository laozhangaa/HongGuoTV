// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

enum class CatalogGenre(val value: String,val label: String,val scene: String,val type: ContentType) {
    COMIC("comic_series","漫剧","comic_series",ContentType.COMIC),
    SHORT("short_play","短剧","short_series",ContentType.SHORT),
    AI("ai_series","AI剧","ai_series",ContentType.COMIC);
    companion object { fun from(value: String)=entries.firstOrNull { it.value==value } ?: throw IllegalArgumentException("剧库分类无效") }
}
data class CatalogFilter(val key: String,val label: String,val options: List<Pair<String,String>>) {
    fun json()=JSONObject().put("key",key).put("label",label).put("options",JSONArray().also { out ->
        options.forEach { (value,label) -> out.put(JSONObject().put("value",value).put("label",label)) }
    })
}
/** Native category endpoint and opaque pagination, matching the Python relay. */
internal class CategoryCatalog(private val request: (JSONObject)->JSONObject) {
    private val panels=mutableMapOf<CatalogGenre,List<CatalogFilter>>()
    private data class Cursor(val session: String,val offset: Int)
    private val cursors=linkedMapOf<Pair<String,Int>,Cursor>()
    @Synchronized fun panel(genre: CatalogGenre): List<CatalogFilter> = panels.getOrPut(genre) {
        val data=checked(request(JSONObject().put("req_type","only_panel").put("need_selector_panel",false).put("req_scene",genre.scene)))
        (data.optJSONArray("selector_rows") ?: throw IOException("剧库分类暂不可用")).objects().mapNotNull { row ->
            val key=row.str("type"); val label=row.str("row_name").replace("全部","").ifBlank { "分类" }
            val options=(row.optJSONArray("items") ?: JSONArray()).objects().mapNotNull { item ->
                val id=item.str("selector_item_id"); val name=item.str("show_name")
                if(id.isEmpty() || name.isEmpty()) null else id to name
            }.distinctBy { it.first }
            if(key.isEmpty() || key=="genre" || options.isEmpty()) null else CatalogFilter(key,label,listOf("" to "全部")+options)
        }
    }
    @Synchronized fun feed(genre: CatalogGenre,page: Int,filters: Map<String,String>): CatalogPage {
        require(page in 1..100)
        val rows=panel(genre)
        require(filters.all { (key,value) -> rows.any { it.key==key && it.options.any { option -> option.first==value } } }) { "筛选条件已变化，请重新选择" }
        val selected=JSONObject().put("genre",JSONArray().put(genre.value))
        filters.toSortedMap().forEach { (key,value) -> if(value.isNotEmpty()) selected.put(key,JSONArray().put(value)) }
        val identity=selected.toString()
        if(page==1) cursors.keys.removeAll { it.first==identity }
        val previous=if(page>1) cursors[identity to page-1] ?: throw IOException("剧库分页已过期，请返回第 1 页") else Cursor("",0)
        val data=checked(request(JSONObject().put("req_type","only_content").put("need_selector_panel",false)
            .put("req_scene",genre.scene).put("offset",previous.offset).put("limit",18)
            .put("client_req_type",if(page==1) 1 else 2).put("session_id",previous.session).put("select_items",selected)))
        val items=linkedMapOf<String,Series>()
        fun walk(value: Any?) {
            when(value) {
                is JSONObject -> {
                    val id=value.str("series_id"); val title=value.str("series_title").ifBlank { value.str("series_name") }.ifBlank { value.str("title") }
                    if(id.matches(Regex("[0-9]{1,30}")) && title.isNotBlank()) {
                        val count=value.optInt("episode_cnt")
                        items.putIfAbsent(id,Series(id,title,browserCover(value.str("series_cover").ifBlank { value.str("cover") }),value.str("series_intro"),if(count>0) "全 $count 集" else ""))
                    }
                    value.keys().forEach { walk(value.opt(it)) }
                }
                is JSONArray -> (0 until value.length()).forEach { walk(value.opt(it)) }
            }
        }
        walk(data.opt("video_data") ?: data)
        cursors[identity to page]=Cursor(data.str("session_id").ifEmpty { previous.session },data.optInt("next_offset",previous.offset+18))
        while(cursors.size>200) cursors.remove(cursors.keys.first())
        return CatalogPage(items.values.toList(),data.optBoolean("has_more") && page<100)
    }
    /**
     * Load one of the curated shelves exposed by the category selector.
     * The selector ids are opaque and can change, so resolve them by their
     * visible labels instead of embedding ids in the app.
     */
    @Synchronized fun featured(genre: CatalogGenre,orderLabel: String): CatalogPage {
        val rows=panel(genre)
        val row=rows.firstOrNull { filter ->
            filter.label.contains("推荐") || filter.options.any { it.second.contains(orderLabel) }
        }
        val option=row?.options?.firstOrNull { it.second==orderLabel }
            ?: row?.options?.firstOrNull { it.second.contains(orderLabel) }
        if(row==null || option==null || option.first.isEmpty()) throw IOException("剧库排序暂不可用：$orderLabel")
        return feed(genre,1,mapOf(row.key to option.first))
    }
    private fun checked(response: JSONObject): JSONObject {
        if(response.optInt("code",response.optInt("status_code",0))!=0) throw IOException("剧库接口暂不可用")
        return response.optJSONObject("data") ?: throw IOException("剧库数据格式异常")
    }
    private fun browserCover(value: String): String {
        val url=cleanUrl(value)
        // The category feed returns HEIC covers, which many phone browsers cannot decode.
        val match=Regex("https://p[0-9]+-reading-sign\\.fqnovelpic\\.com/novel-pic/([a-f0-9]+)~[^?]+\\.heic(?:\\?.*)?").matchEntire(url)
        return if(match!=null) "https://p3-novel.byteimg.com/novel-pic/${match.groupValues[1]}~tplv-shrink:640:0.image" else url
    }
}
