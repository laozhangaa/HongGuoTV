// SPDX-License-Identifier: GPL-3.0-only
// Playback session shape referenced from tsukiyo-zero/hongguoTV, 2026-10-27.
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class PlaybackDeviceRegistrationException(cause: Throwable? = null): IOException("内容设备初始化失败，请稍后重试",cause)

/** One anonymous identity shared by search, playback, recovery and preloading. */
class PlaybackDeviceSession(
    private val register: (OkHttpClient)->DeviceRegister.Registered? = { http ->
        val body=DeviceRegister.payload(VendorConstants.device)
        val request=Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(body,VendorConstants.device).forEach { (key,value) -> request.header(key,value) }
        request.post(body.toRequestBody("application/octet-stream;tt-data=a".toMediaType()))
        val transport=http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        RequestScope.execute(transport.newCall(request.build())) { response ->
            DeviceRegister.parse(cloudResponse(response),response.headers("Set-Cookie"))
        }
    }
) {
    companion object {
        val shared=PlaybackDeviceSession()
        internal const val USER_AGENT="com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)"
    }
    internal data class Profile(val values: Map<String,String>,val cookie: String)
    private val cdid=UUID.randomUUID().toString()
    private var ready: Profile?=null
    private fun checkCancelled() {
        if(Thread.currentThread().isInterrupted || RequestScope.currentCancelled()) throw InterruptedIOException("播放请求已取消")
    }
    @Synchronized internal fun profile(http: OkHttpClient): Profile {
        checkCancelled()
        ready?.let { return it }
        val identity=try { register(http) ?: throw PlaybackDeviceRegistrationException() }
            catch(e: InterruptedIOException) { throw e }
            catch(e: PlaybackDeviceRegistrationException) { throw e }
            catch(e: IOException) { throw PlaybackDeviceRegistrationException(e) }
        val valid=Regex("[1-9][0-9]{0,29}")
        if(!valid.matches(identity.deviceId) || !valid.matches(identity.installId)) throw PlaybackDeviceRegistrationException()
        checkCancelled()
        return Profile(VendorConstants.device+mapOf("device_id" to identity.deviceId,"iid" to identity.installId,"cdid" to cdid),
            identity.cookies.filterKeys { it in setOf("install_id","ttreq","store-region","store-region-src") }
                .entries.joinToString("; ") { (name,value) -> "$name=$value" }).also { ready=it }
    }
}
