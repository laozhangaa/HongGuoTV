// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

data class PhonePlaybackStatus(
    val title: String,
    val seriesId: String,
    val episodeIndex: Int,
    val episodeCount: Int,
    val playing: Boolean,
    val state: String = if(playing) "playing" else "paused",
    val position: Long = 0,
    val duration: Long = 0,
    val speed: Float = 1f,
    val favorite: Boolean = false,
    val error: String = "",
    val session: Long = 0
) {
    // Keep paused sessions available for remote episode selection; player teardown clears them.
    val active get()=title.isNotBlank() && seriesId.matches(Regex("[0-9]{1,30}")) &&
        (episodeIndex>=0 && episodeIndex<episodeCount && episodeCount in 1..2000 || episodeCount==0 && state in listOf("loading","error"))
    fun toJson()=org.json.JSONObject().put("active",active).put("playing",active && playing)
        .put("title",if(active) title.take(200) else "").put("key",if(active) seriesId else "").put("episode",if(active && episodeCount>0) episodeIndex+1 else 0)
        .put("episodeCount",if(active) episodeCount else 0)
        .put("state",if(active) state else "idle").put("position",if(active) position.coerceAtLeast(0) else 0)
        .put("duration",if(active) duration.coerceAtLeast(0) else 0).put("speed",speed.toDouble()).put("favorite",active && favorite)
        .put("error",if(active) error.take(200) else "")
        .put("session",if(active) session else 0)
}
