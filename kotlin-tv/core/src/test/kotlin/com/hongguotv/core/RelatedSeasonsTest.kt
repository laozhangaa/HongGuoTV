package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class RelatedSeasonsTest {
    @Test fun chineseSeasonNumbersAreParsedAndNormalized() {
        assertEquals(RelatedSeasons.Title("仙途",1),RelatedSeasons.parse("仙途第一季"))
        assertEquals(RelatedSeasons.Title("仙途",2),RelatedSeasons.parse("仙途第二季"))
        assertEquals(RelatedSeasons.Title("仙途",14),RelatedSeasons.parse("仙途第十四季"))
        assertEquals(RelatedSeasons.Title("仙途",3),RelatedSeasons.parse("仙途第３季"))
    }
    @Test fun candidatesMustBeSameRootAndLaterExplicitSeason() {
        val current=Series("1","仙途第一季")
        val rows=listOf(Series("1","仙途第一季"),Series("2","仙途第二季"),Series("3","仙途第三季"),
            Series("4","另一个故事第二季"),Series("5","仙途第一季"))
        assertEquals(listOf("2","3"),RelatedSeasons.select(current,rows).map { it.id })
    }
    @Test fun queryRemovesSeasonMarkerOnly() {
        assertEquals("和离后我带女儿横扫魔域",RelatedSeasons.query("和离后我带女儿横扫魔域第一季"))
    }
    @Test fun laterPagesAreScannedSoEarlierSeasonsAreNotHiddenByHeatRanking() {
        val pages=mutableListOf<Int>()
        val found=RelatedSeasons.search(Series("1","仙途第一季")) { page ->
            pages+=page
            if(page==1) CatalogPage(listOf(Series("3","仙途第三季")),true)
            else CatalogPage(listOf(Series("2","仙途第二季")),false)
        }
        assertEquals(listOf("2","3"),found.map { it.id }); assertEquals(listOf(1,2),pages)
    }
    @Test fun unrelatedFirstPageCanContinueButSearchIsBounded() {
        val pages=mutableListOf<Int>()
        val found=RelatedSeasons.search(Series("1","仙途第一季")) { page ->
            pages+=page
            CatalogPage(if(page==2) listOf(Series("2","仙途第二季")) else listOf(Series("9","其他剧")),true)
        }
        assertEquals(listOf("2"),found.map { it.id }); assertEquals(listOf(1,2),pages)
        pages.clear()
        assertTrue(RelatedSeasons.search(Series("1","仙途第一季")) { page -> pages+=page; CatalogPage(emptyList(),true) }.isEmpty())
        assertEquals(listOf(1,2,3),pages)
    }
}
