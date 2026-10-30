// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

object PrefetchPolicy {
    /** TV Wi-Fi/hotspots can be reported as metered; that must not disable episode preparation. */
    fun networkAllowed(metered: Boolean,wifi: Boolean,ethernet: Boolean)=!metered || wifi || ethernet
    /** Start early enough that the next episode can finish its first range request on a TV. */
    const val WINDOW_MILLIS=45_000L
    /** Wall-clock time to the end matters: long episodes must not outlive the slot TTL. */
    fun nearEnd(position: Long, duration: Long, speed: Float): Boolean {
        if (duration <= 0 || position < 0 || position >= duration || !speed.isFinite() || speed <= 0) return false
        return (duration - position) / speed <= WINDOW_MILLIS
    }
}
