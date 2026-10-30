package com.hongguotv.core

fun main(args: Array<String>) {
    val repository=ContentRepository()
    if(args.contains("--catalog")) {
        try {
            for(genre in CatalogGenre.entries) {
                val filters=repository.catalogFilters(genre)
                println("genre=${genre.label}; filters="+filters.joinToString { it.label+":"+it.options.take(5).joinToString { option -> option.second } })
                val result=repository.catalog(genre); println("items=${result.items.size}; hasMore=${result.hasMore}; first=${result.items.firstOrNull()?.title}")
            }
        } finally { repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() }
        return
    }
    if(args.contains("--search-only")) {
        try {
            val type=if(args.contains("--comic")) ContentType.COMIC else ContentType.SHORT
            val keyword=args.firstOrNull { it.startsWith("--query=") }?.substringAfter('=') ?: "修仙归来"
            val seen=mutableSetOf<String>()
            for(page in 1..2) {
                val result=repository.search(keyword,page,type)
                check(result.items.none { it.id in seen }) { "搜索翻页出现重复条目" }
                seen.addAll(result.items.map { it.id })
                println("type=${type.label}; page=$page; items=${result.items.size}; hasMore=${result.hasMore}")
                result.items.forEach { println("${it.id}: ${it.title}") }
                if(!result.hasMore) break
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if(args.contains("--ranking")) {
        try {
            val type=if(args.contains("--short")) ContentType.SHORT else ContentType.COMIC
            val first=repository.ranking(type=type)
            val metadata=checkNotNull(first.ranking)
            check(first.items.isNotEmpty())
            val seen=mutableSetOf<String>()
            for(pageNumber in listOf(1,2,metadata.totalPages).distinct().filter { it<=metadata.totalPages }) {
                val result=if(pageNumber==1) first else repository.ranking(pageNumber,type)
                val ranking=checkNotNull(result.ranking)
                check(result.items.isNotEmpty() && result.items.none { it.id in seen })
                seen.addAll(result.items.map { it.id })
                val ranks=result.items.map { checkNotNull(ranking.positions[it.id]).rank }
                check(ranks.all { it!=null && it>0 } && ranks.filterNotNull().zipWithNext().all { (a,b) -> a<b })
                check(result.items.all { ranking.positions[it.id]!!.heat.isNotBlank() })
                check(result.hasMore==(pageNumber<ranking.totalPages))
                println("type=${type.label}; rankingPage=$pageNumber/${ranking.totalPages}; items=${result.items.size}; ranks=${ranks.first()}..${ranks.last()}; firstHeat=${ranking.positions[result.items.first().id]!!.heat}; updated=${ranking.updatedText}")
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    val type=if(args.contains("--comic")) ContentType.COMIC else ContentType.SHORT
    val keyword=if(type==ContentType.COMIC) "修仙" else "总裁"
    val search=repository.search(keyword,type=type); println("search=${search.items.size}; hasMore=${search.hasMore}")
    if(type==ContentType.COMIC && search.hasMore) {
        val next=repository.search(keyword,2,type)
        println("searchPage2=${next.items.size}; overlap=${search.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
    }
    val home=repository.home(type=type); println("type=${type.label}; home=${home.items.size}; hasMore=${home.hasMore}")
    if(type==ContentType.COMIC) {
        val next=repository.home(2,type)
        println("homePage2=${next.items.size}; overlap=${home.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
    }
    val detail=repository.detail(args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) } ?: home.items.first().id)
    println("detail=${detail.series.title}; episodes=${detail.episodes.size}")
    val stream=repository.stream(detail.episodes.first())
    RemoteVideo(repository.http,stream).use { remote ->
        remote.prepare(); val first=remote.read(0,1024*1024)
        val tail=remote.read((remote.total-65536).coerceAtLeast(0),65536)
        println("quality=${stream.quality}; total=${remote.total}; first=${first.size}; tail=${tail.size}; header=${String(first,4,4)}")
        if(args.contains("--save")) java.io.File("/tmp/hongguo-kotlin-first.mp4").writeBytes(first)
    }
    repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
}
