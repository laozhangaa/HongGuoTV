// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** Conservative title matching: only explicitly numbered later seasons are candidates. */
object RelatedSeasons {
    private const val MAX_SEARCH_PAGES = 5
    data class Title(val root: String,val season: Int)
    private val marker=Regex("第([0-9０-９一二三四五六七八九十百零〇两]+)[季部]")
    private fun number(value: String): Int {
        val normalized=value.map { if(it in '０'..'９') '0'+(it-'０') else it }.joinToString("")
        normalized.toIntOrNull()?.let { return it }
        var total=0; var digit=0
        normalized.forEach { char ->
            when(char) {
                '十','百' -> { total+=(if(digit==0) 1 else digit)*(if(char=='十') 10 else 100); digit=0 }
                else -> digit=when(char) { '两' -> 2; '零','〇' -> 0; else -> "一二三四五六七八九".indexOf(char)+1 }
            }
        }
        return total+digit
    }
    fun parse(value: String): Title {
        val found=marker.find(value)
        val root=marker.replace(value,"").replace(Regex("[\\s\\p{P}\\p{S}]"),"")
        return Title(root,found?.groupValues?.get(1)?.let(::number)?.coerceAtLeast(1) ?: 1)
    }
    fun query(value: String)=marker.replace(value,"").trim().trim('（','）','(',')',' ','-','—').take(80)
    fun search(current: Series,loadPage: (Int)->CatalogPage): List<Series> {
        val all=mutableListOf<Series>()
        // Search ranking is usually ordered by heat, so page 1 may contain
        // season 3/4 while season 2 is on a later page. Scan a few pages
        // before selecting and sorting candidates by their season number.
        for(page in 1..MAX_SEARCH_PAGES) {
            val result=loadPage(page); all+=result.items
            if(!result.hasMore) break
        }
        return select(current,all)
    }
    fun select(current: Series,rows: List<Series>): List<Series> {
        val title=parse(current.title)
        if(title.root.length<2) return emptyList()
        return rows.distinctBy { it.id }.filter { row ->
            val candidate=parse(row.title)
            row.id!=current.id && candidate.root==title.root && candidate.season>title.season
        }.sortedWith(compareBy<Series> { parse(it.title).season }.thenBy { it.title }).take(20)
    }
}
