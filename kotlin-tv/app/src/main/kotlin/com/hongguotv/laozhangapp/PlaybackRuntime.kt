// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.laozhangapp

import android.content.Context
import android.media.MediaCodec
import android.os.Build
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import java.util.Locale

/** A conservative output path for the reported XGIMI continuous-playback issue. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object PlaybackRuntime {
    val device = "${Build.MANUFACTURER}/${Build.BRAND}/${Build.MODEL}/${Build.DEVICE}/${Build.PRODUCT}"
    private val identity = device.lowercase(Locale.ROOT)
    val projectorCompatibility = identity.contains("xgimi") || identity.contains("极米") ||
        Regex("(^|[^a-z0-9])z6x([^a-z0-9]|$)").containsMatchIn(identity)

    fun renderers(context: Context) = DefaultRenderersFactory(context).apply {
        setEnableDecoderFallback(true)
        if (projectorCompatibility) forceDisableMediaCodecAsynchronousQueueing()
    }

    fun logSession(episode: Int, reused: Boolean, preloaded: Boolean) {
        Log.i("HongguoTV", "Playback pipeline episode=$episode device=$device sdk=${Build.VERSION.SDK_INT}" +
            " projectorCompatibility=$projectorCompatibility reused=$reused preloaded=$preloaded")
    }
}

/** Metadata only: never log signed media URLs, account credentials or content keys. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackDiagnostics : AnalyticsListener, VideoFrameMetadataListener {
    private var decoder = "unknown"
    private var input = "unknown"
    private var lastPresentationUs=Long.MIN_VALUE
    private var lastReleaseNs=0L

    override fun onVideoFrameAboutToBeRendered(presentationTimeUs: Long,releaseTimeNs: Long,format: Format,mediaFormat: android.media.MediaFormat?) {
        if(lastReleaseNs>0 && presentationTimeUs<lastPresentationUs-1_000_000) {
            Log.i("HongguoTV","Video frame boundary gapMs=${(releaseTimeNs-lastReleaseNs)/1_000_000} previousPtsMs=${lastPresentationUs/1000} nextPtsMs=${presentationTimeUs/1000}")
        }
        lastPresentationUs=presentationTimeUs; lastReleaseNs=releaseTimeNs
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime,state: Int) {
        Log.i("HongguoTV","Playback state ${at(eventTime)} state=$state")
    }

    private fun at(event: AnalyticsListener.EventTime): String {
        val timeline = event.timeline
        val id = if (event.windowIndex in 0 until timeline.windowCount)
            timeline.getWindow(event.windowIndex, Timeline.Window()).mediaItem.mediaId else "unknown"
        return "mediaId=$id positionMs=${event.eventPlaybackPositionMs}"
    }

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
        initializedTimestampMs: Long, initializationDurationMs: Long) {
        decoder = decoderName
        Log.i("HongguoTV", "Video decoder ${at(eventTime)} name=$decoder initMs=$initializationDurationMs")
    }

    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?) {
        input = "mime=${format.sampleMimeType} codec=${format.codecs} size=${format.width}x${format.height}" +
            " fps=${format.frameRate} bitrate=${format.bitrate} rotation=${format.rotationDegrees}"
        Log.i("HongguoTV", "Video input ${at(eventTime)} $input reuse=${decoderReuseEvaluation?.result}")
    }

    override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
        Log.i("HongguoTV", "Video output ${at(eventTime)} size=${videoSize.width}x${videoSize.height}" +
            " pixelRatio=${videoSize.pixelWidthHeightRatio}")
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        Log.w("HongguoTV", "Video dropped ${at(eventTime)} frames=$droppedFrames elapsedMs=$elapsedMs decoder=$decoder $input")
    }

    override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
        val diagnostic = (videoCodecError as? MediaCodec.CodecException)?.diagnosticInfo.orEmpty()
        Log.w("HongguoTV", "Video codec error ${at(eventTime)} decoder=$decoder" +
            " type=${videoCodecError.javaClass.simpleName} diagnostic=$diagnostic $input")
    }

    override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
        decoderCounters.ensureUpdated()
        Log.i("HongguoTV", "Video summary ${at(eventTime)} decoder=$decoder" +
            " rendered=${decoderCounters.renderedOutputBufferCount} dropped=${decoderCounters.droppedBufferCount}" +
            " maxConsecutiveDropped=${decoderCounters.maxConsecutiveDroppedBufferCount}" +
            " skipped=${decoderCounters.skippedOutputBufferCount}")
    }
}
