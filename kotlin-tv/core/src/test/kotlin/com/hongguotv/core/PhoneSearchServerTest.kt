package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhoneSearchServerTest {
    private fun request(server: PhoneSearchServer,method: String="GET",path: String?=null,body: String="",headers: String="",host: String?=null): String {
        val uri=URI(server.url)
        return Socket("127.0.0.1",uri.port).use { socket ->
            socket.soTimeout=3000
            val bytes=body.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().write(("$method ${path ?: uri.path} HTTP/1.1\r\nHost: ${host ?: uri.authority}\r\n"+headers+if(method=="POST") "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${bytes.size}\r\n\r\n" else "\r\n").toByteArray()+bytes)
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
    @Test fun `phone form accepts one unicode search without reflecting it in HTML`() {
        val received=CountDownLatch(1); var query=""
        PhoneSearchServer("127.0.0.1",{ value,type -> query=value; assertNull(type); received.countDown() }).use { server ->
            val page=request(server)
            assertTrue(page.startsWith("HTTP/1.1 200")); assertTrue(page.contains("Cache-Control: no-store")); assertTrue(page.contains("form-action 'self'"))
            val value="测试 <script>alert(1)</script>"
            val reply=request(server,"POST",URI(server.url).path+"/submit","query="+URLEncoder.encode(value,"UTF-8"))
            assertTrue(reply.startsWith("HTTP/1.1 200")); assertFalse(reply.contains(value))
            assertTrue(received.await(2,TimeUnit.SECONDS)); assertEquals(value,query)
            assertTrue(runCatching { request(server) }.isFailure)
        }
    }
    @Test fun `wrong token host and cross site posts cannot submit`() {
        var submitted=false
        PhoneSearchServer("127.0.0.1",{ _,_ -> submitted=true }).use { server ->
            assertTrue(request(server,path="/wrong").startsWith("HTTP/1.1 404"))
            assertTrue(request(server,host="evil.example").startsWith("HTTP/1.1 404"))
            assertTrue(request(server,"POST",URI(server.url).path+"/submit","query=test","Origin: http://evil.example\r\n").startsWith("HTTP/1.1 403"))
            assertFalse(submitted)
        }
    }
    @Test fun `invalid and oversized input does not consume the session`() {
        PhoneSearchServer("127.0.0.1",{ _,_ -> }).use { server ->
            for(body in listOf("query=","query="+"x".repeat(81),"query=a&query=b","query=hello%0Aworld","query=%zz")) {
                val response=request(server,"POST",URI(server.url).path+"/submit",body)
                assertTrue(response,response.startsWith("HTTP/1.1 400"))
            }
            assertTrue(request(server,"POST",URI(server.url).path+"/submit","query="+"x".repeat(4096)).startsWith("HTTP/1.1 413"))
            assertTrue(request(server).startsWith("HTTP/1.1 200"))
        }
    }
    @Test fun `closing or expiry makes a session unreachable`() {
        val closed=PhoneSearchServer("127.0.0.1",{ _,_ -> }); closed.close()
        assertTrue(runCatching { request(closed) }.isFailure)
        val expired=CountDownLatch(1)
        PhoneSearchServer("127.0.0.1",{ _,_ -> fail("expired session submitted") },{ expired.countDown() },1).use { server ->
            assertTrue(expired.await(2,TimeUnit.SECONDS))
            assertTrue(runCatching { request(server) }.isFailure)
        }
    }

    @Test fun `persistent page sends either classification repeatedly with JSON acknowledgement`() {
        val received=java.util.concurrent.LinkedBlockingQueue<Pair<String,ContentType?>>()
        PhoneSearchServer("127.0.0.1",{ query,type -> received.put(query to type) },persistent=true).use { server ->
            assertEquals("/",URI(server.url).path)
            for(type in ContentType.entries) {
                val reply=request(server,"POST","/submit","type=${type.storedValue}&query="+URLEncoder.encode("修仙归来，我的未婚妻","UTF-8"),"Accept: application/json\r\nOrigin: null\r\n")
                assertTrue(reply,reply.startsWith("HTTP/1.1 200"))
                assertTrue(reply.contains("Content-Type: application/json; charset=utf-8"))
            assertEquals("已在TV搜索，请查看结果",org.json.JSONObject(reply.substringAfter("\r\n\r\n")).getString("message"))
                assertEquals("修仙归来，我的未婚妻" to type,received.poll(2,TimeUnit.SECONDS))
                assertTrue(request(server).startsWith("HTTP/1.1 200"))
            }
        }
    }

    @Test fun `playback status endpoint reports current episode only when a series is active`() {
        var status=PhonePlaybackStatus("藏不住的故事","12345",3,12,true)
        PhoneSearchServer("127.0.0.1",{ _,_ -> },persistent=true,playbackStatus={ status }).use { server ->
            val reply=request(server,path="/status")
            assertTrue(reply,reply.startsWith("HTTP/1.1 200"))
            val json=org.json.JSONObject(reply.substringAfter("\r\n\r\n"))
            assertTrue(json.getBoolean("active")); assertTrue(json.getBoolean("playing"))
            assertEquals(4,json.getInt("episode")); assertEquals(12,json.getInt("episodeCount"))
            assertEquals("12345",json.getString("key")); assertEquals("藏不住的故事",json.getString("title"))
            status=PhonePlaybackStatus("藏不住的故事","12345",3,12,false)
            val paused=org.json.JSONObject(request(server,path="/status").substringAfter("\r\n\r\n"))
            assertTrue(paused.getBoolean("active")); assertFalse(paused.getBoolean("playing")); assertEquals("藏不住的故事",paused.getString("title"))
            status=PhonePlaybackStatus("","",0,0,false)
            assertFalse(org.json.JSONObject(request(server,path="/status").substringAfter("\r\n\r\n")).getBoolean("active"))
        }
    }

    @Test fun `phone library request only accepts supported genres and selector filters`() {
        val selections=java.util.concurrent.LinkedBlockingQueue<Pair<CatalogGenre,Map<String,String>>>()
        PhoneSearchServer("127.0.0.1",{ _,_ -> },persistent=true,onLibrary={ genre,filters -> selections.put(genre to filters) }).use { server ->
            fun browse(body: String)=request(server,"POST","/browse",body,"Origin: null\r\nAccept: application/json\r\n")
            val invalid=browse("section=library&genre=../../&filters=%7B%7D")
            assertTrue(invalid,invalid.startsWith("HTTP/1.1 400"))
            assertTrue(selections.isEmpty())
            val valid=browse("section=library&genre=ai_series&filters=%7B%7D")
            assertTrue(valid,valid.startsWith("HTTP/1.1 200"))
            assertEquals(CatalogGenre.AI to emptyMap<String,String>(),selections.poll(2,TimeUnit.SECONDS))
            val filtered=browse("section=library&genre=comic_series&filters=%7B%22theme%22%3A%22revenge%22%7D")
            assertTrue(filtered,filtered.startsWith("HTTP/1.1 200"))
            assertEquals(CatalogGenre.COMIC to mapOf("theme" to "revenge"),selections.poll(2,TimeUnit.SECONDS))
        }
    }

    @Test fun `phone playback controls validate actions and series identity`() {
        val received=java.util.concurrent.LinkedBlockingQueue<PhoneControl>()
        val status=PhonePlaybackStatus("当前剧","12345",3,8,true,duration=100000,speed=1.25f)
        PhoneSearchServer("127.0.0.1",{ _,_ -> },persistent=true,playbackStatus={ status },onControl={ received.put(it) }).use { server ->
            fun control(body: String)=request(server,"POST","/control",body,"Origin: null\r\nAccept: application/json\r\n")
            assertTrue(control("action=seek&key=12345&value=10").startsWith("HTTP/1.1 200"))
            assertEquals(PhoneControl("seek","12345","10"),received.poll(2,TimeUnit.SECONDS))
            assertTrue(control("action=speed&key=12345&value=1.5").startsWith("HTTP/1.1 200"))
            assertEquals(PhoneControl("speed","12345","1.5"),received.poll(2,TimeUnit.SECONDS))
            for(body in listOf("action=speed&key=12345&value=9","action=next&key=99999","action=exec&key=12345","action=home&key=12345")) {
                val reply=control(body)
                assertTrue(reply,reply.startsWith("HTTP/1.1 400") || reply.startsWith("HTTP/1.1 409"))
                assertTrue(received.isEmpty())
            }
        }
    }

    @Test fun `episode selector validates live playback then forwards selected episode`() {
        val selections=java.util.concurrent.LinkedBlockingQueue<Int>()
        var status=PhonePlaybackStatus("当前剧","12345",2,15,true)
        PhoneSearchServer("127.0.0.1",{ _,_ -> },persistent=true,playbackStatus={ status },onEpisode={ selections.put(it) }).use { server ->
            val path="/episode"
            fun choose(number: String)=request(server,"POST",path,"episode=$number","Accept: application/json\r\n")
            val successReply=choose("4")
            assertTrue(successReply,successReply.startsWith("HTTP/1.1 200"))
            val success=org.json.JSONObject(successReply.substringAfter("\r\n\r\n"))
            assertEquals(4,selections.poll(2,TimeUnit.SECONDS)?.plus(1))
            assertEquals("正在切换到第 4 集",success.getString("message"))
            for(number in listOf("0","16")) {
                val reply=choose(number)
                assertTrue(reply,reply.startsWith("HTTP/1.1 409"))
                assertTrue(selections.isEmpty())
            }
            for(number in listOf("abc","2&episode=3")) {
                val reply=choose(number)
                assertTrue(reply,reply.startsWith("HTTP/1.1 400"))
                assertTrue(selections.isEmpty())
            }
            status=PhonePlaybackStatus("","",0,0,false)
            assertTrue(choose("1").startsWith("HTTP/1.1 409"))
            assertTrue(selections.isEmpty())
        }
    }

    @Test fun `point play and pagination reject stale or unavailable snapshots`() {
        val plays=java.util.concurrent.LinkedBlockingQueue<Pair<Long,String>>()
        val seasons=java.util.concurrent.LinkedBlockingQueue<Pair<Long,String>>()
        val pages=java.util.concurrent.LinkedBlockingQueue<Pair<Long,Int>>()
        var snapshot=PhoneSearchResults(revision=5,query="测试",page=1,items=listOf(Series("123","第一季")),hasMore=true,section="search")
        val related=PhoneSearchResults(revision=9,items=listOf(Series("456","第二季")),section="seasons")
        PhoneSearchServer("127.0.0.1",{ _,_ -> },persistent=true,searchResults={ snapshot },relatedSeasons={ related },
            onPlay={ revision,id -> plays.put(revision to id) },onRelatedPlay={ revision,id -> seasons.put(revision to id) },
            onSearchPage={ revision,page -> pages.put(revision to page) }).use { server ->
            fun post(path: String,body: String)=request(server,"POST",path,body,"Accept: application/json\r\n")
            assertTrue(post("/play","revision=5&id=123").startsWith("HTTP/1.1 200"))
            assertEquals(5L to "123",plays.poll(2,TimeUnit.SECONDS))
            assertTrue(post("/play","revision=4&id=123").startsWith("HTTP/1.1 409"))
            assertTrue(post("/play","revision=5&id=456").startsWith("HTTP/1.1 409"))
            assertTrue(plays.isEmpty())
            assertTrue(post("/search-page","revision=5&page=3").startsWith("HTTP/1.1 409"))
            assertTrue(post("/search-page","revision=5&page=2").startsWith("HTTP/1.1 200"))
            assertEquals(5L to 2,pages.poll(2,TimeUnit.SECONDS))
            snapshot=snapshot.copy(loading=true)
            assertTrue(post("/play","revision=5&id=123").startsWith("HTTP/1.1 409"))
            assertTrue(post("/search-page","revision=5&page=2").startsWith("HTTP/1.1 409"))
            assertTrue(post("/season-play","revision=5&id=123").startsWith("HTTP/1.1 409"))
            assertTrue(post("/season-play","revision=9&id=456").startsWith("HTTP/1.1 200"))
            assertEquals(9L to "456",seasons.poll(2,TimeUnit.SECONDS))
            assertTrue(plays.isEmpty()); assertTrue(pages.isEmpty())
        }
    }

    @Test fun `phone page is responsive and submits without navigating or clearing fields`() {
        PhoneSearchServer("127.0.0.1",{ _,_ -> },persistent=true).use { server ->
            val page=request(server)
            assertTrue(page,page.startsWith("HTTP/1.1 200"))
            assertTrue(page.contains("name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\""))
            assertTrue(page.contains("<main class=\"page\">"))
            assertTrue(page.contains("name=\"type\" value=\"comic\" checked"))
            assertTrue(page.contains("name=\"type\" value=\"short\""))
            assertTrue(page.contains("@media(max-width:379px)"))
            assertTrue(page.contains("grid-template-columns:repeat(2,minmax(0,1fr))"))
            assertTrue(page.contains("env(safe-area-inset-bottom)")); assertTrue(page.contains("box-sizing:border-box"))
            assertTrue(page.contains("event.preventDefault()")); assertTrue(page.contains("已在 TV 搜索"))
            assertTrue(page.contains("finally")); assertFalse(page.contains("location.href"))
            assertFalse(page.contains("window.location")); assertFalse(page.contains("form.reset("))
            assertTrue(page.contains("script-src 'nonce-"))
            assertTrue(page.contains("id=\"tv-toggle\"")); assertTrue(page.contains("id=\"tv-panel\""))
            assertTrue(page.contains("/status")); assertTrue(page.contains("/episode"))
            assertTrue(page.contains("id=\"tv-scrim\"")); assertTrue(page.contains("panel-close"))
            assertTrue(page.contains("899px")); assertTrue(page.contains("900px"))
            assertTrue(page.contains("data-section=\"library\"")); assertTrue(page.contains("data-genre=\"ai_series\""))
            assertTrue(page.contains("id=\"playback-speed\"")); assertTrue(page.contains("后续季"))
            assertTrue(page.contains("images.weserv.nl")); assertTrue(page.contains("poster-fallback")); assertTrue(page.contains("海报暂不可用"))
        }
    }

    @Test fun `invalid classifications cannot trigger a search or consume the session`() {
        val received=java.util.concurrent.LinkedBlockingQueue<Pair<String,ContentType?>>()
        PhoneSearchServer("127.0.0.1",{ query,type -> received.put(query to type) }).use { server ->
            val path=URI(server.url).path+"/submit"
            for(body in listOf("query=test&type=", "query=test&type=other", "query=test&type=comic&type=short", "query=test&type=%zz", "type=comic", "query=test&extra=short")) {
                val reply=request(server,"POST",path,body,"Accept: application/json\r\n")
                assertTrue(reply,reply.startsWith("HTTP/1.1 400"))
                assertTrue(org.json.JSONObject(reply.substringAfter("\r\n\r\n")).getString("message").isNotBlank())
                assertTrue(received.isEmpty())
            }
            assertTrue(request(server,"POST",path,"query=test&type=short").startsWith("HTTP/1.1 200"))
            assertEquals("test" to ContentType.SHORT,received.poll(2,TimeUnit.SECONDS))
        }
    }
}
