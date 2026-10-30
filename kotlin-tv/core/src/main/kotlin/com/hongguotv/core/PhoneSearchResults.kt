// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** Immutable copy of the TV search page, safely shared with the LAN server. */
data class PhoneSearchResults(
    val revision: Long = 0,
    val query: String = "",
    val type: ContentType = ContentType.COMIC,
    val page: Int = 1,
    val loading: Boolean = false,
    val items: List<Series> = emptyList(),
    val hasMore: Boolean = false,
    val error: String = "",
    val section: String = "search",
    val favorites: Set<String> = emptySet(),
    val progress: Map<String,String> = emptyMap(),
    val genre: CatalogGenre = CatalogGenre.COMIC,
    val filterRows: List<CatalogFilter> = emptyList(),
    val filters: Map<String,String> = emptyMap()
) {
    fun selectable(id: String) = !loading && error.isEmpty() && items.any { it.id==id }
    fun canTurnTo(target: Int) = revision>0 && !loading && error.isEmpty() &&
        (target==page-1 && target>=1 || target==page+1 && hasMore)
    fun toJson()=org.json.JSONObject().put("revision",revision).put("query",query).put("type",type.storedValue)
        .put("page",page).put("loading",loading).put("hasMore",hasMore).put("error",error).put("section",section)
        .put("genre",genre.value).put("filterRows",org.json.JSONArray().also { rows -> filterRows.forEach { rows.put(it.json()) } })
        .put("filters",org.json.JSONObject(filters))
        .put("items",org.json.JSONArray().also { rows -> items.forEach { item ->
            rows.put(org.json.JSONObject().put("id",item.id).put("title",item.title.take(200))
                .put("cover",item.cover.takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty())
                .put("badge",item.badge.take(80)).put("tags",item.tags.take(160)).put("favorite",item.id in favorites)
                .put("progress",progress[item.id].orEmpty()))
        } })
}
