// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

data class PhoneControl(val action: String,val key: String="",val value: String="") {
    fun allowed(status: PhonePlaybackStatus): Boolean {
        if(action in listOf("home","stop")) return key.isEmpty() && value.isEmpty()
        if(!status.active || key!=status.seriesId) return false
        return when(action) {
            "play","pause" -> value.isEmpty() && status.episodeCount>0 && status.state !in listOf("loading","error")
            "retry" -> value.isEmpty() && status.state=="error"
            "previous" -> value.isEmpty() && status.episodeIndex>0
            "next" -> value.isEmpty() && status.episodeIndex+1<status.episodeCount
            "seek" -> value in listOf("-10","10") && status.duration>0 && status.state !in listOf("loading","error")
            "speed" -> value.toFloatOrNull()?.let { it.isFinite() && it in PlaybackSpeed.options }==true
            "favorite" -> value in listOf("0","1")
            else -> false
        }
    }
    companion object { val actions=setOf("home","stop","play","pause","retry","previous","next","seek","speed","favorite") }
}
