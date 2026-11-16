@file:androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)

package com.gmail.volkovskiyda.jellyshelf.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ForwardingMediaSourceFactory
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import com.gmail.volkovskiyda.jellyshelf.util.Playback

/**
 * How far before a saved position a resumed video may start: back to the keyframe before it, if
 * that keyframe is no further back than this; exactly on the position otherwise.
 *
 * A resume used to start exactly where the video was left, and a video can only start decoding at a
 * keyframe. Everything from that keyframe up to the position was decoded and thrown away before the
 * first picture, with the screen black and the sound held back until the picture caught up. Measured
 * 2026-11-03 on a mid-range API 34 phone resuming AV1 talks, which it decodes in software: 11–49
 * frames decoded only to be dropped, 0.3–0.8 s of decoding, and 0.8–1.4 s from the tap to the first
 * frame against ~0.5 s for the same phone starting a video on a keyframe. A hardware decoder pays the
 * same shape at a few milliseconds a frame.
 *
 * Starting on the keyframe instead replays at most this much of what was already watched, which a
 * resume arguably wants anyway — the sentence the video was paused in, said again. Ten seconds is a
 * bound, not the expectation: the replay is however far back the keyframe is, and a longer gap than
 * this plays from the exact position, as before. User seeks are untouched — they go through the
 * player's own seek parameters, which stay exact.
 */
internal val RESUME_SEEK_PARAMETERS = SeekParameters(RESUME_REPLAY_MAX_US, NO_TOLERANCE_AFTER_US)

/** The furthest back of its saved position a resumed video may start. */
private const val RESUME_REPLAY_MAX_US = 10_000_000L

/** Never later than the saved position: what was not watched yet is not skipped. */
private const val NO_TOLERANCE_AFTER_US = 0L

/**
 * A resume seek: to [positionMs], or to the keyframe before it — [RESUME_SEEK_PARAMETERS] for this
 * one seek, and the player's own parameters again for every seek after it.
 *
 * For a video the queue moves to, which media3 starts at zero and the service then seeks to where it
 * was left. The three calls reach the playback thread in order, so the seek between them is the only
 * one the loose parameters apply to. A video that is not prepared yet when the seek arrives gets a
 * new period at the position instead, and [KeyframeStartSourceFactory] snaps that one.
 */
internal fun ExoPlayer.resumeSeekTo(positionMs: Long) {
    val exact = seekParameters
    setSeekParameters(RESUME_SEEK_PARAMETERS)
    seekTo(positionMs)
    setSeekParameters(exact)
}

/**
 * Wraps every direct-play source so its first track selection starts on a keyframe — see
 * [RESUME_SEEK_PARAMETERS].
 *
 * The first selection is the one that applies the start position a queue opens with: media3 has no
 * seek to adjust there, it prepares the stream at the position and adopts whatever position the
 * period's first [MediaPeriod.selectTracks] answers with (`MediaPeriodHolder.handlePrepared`,
 * media3 1.11). So moving the position back is a matter of handing the period an earlier one —
 * no extra request, no reload, no reported seek. A position of zero, which every queued video after
 * the first starts from, has nothing to move.
 *
 * The HLS transcode is left exact: the fallback that starts it resumes at the moment the direct
 * stream failed, and its segments are the server's, not the file's keyframes.
 */
internal class KeyframeStartSourceFactory(factory: MediaSource.Factory) : ForwardingMediaSourceFactory(factory) {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val source = super.createMediaSource(mediaItem)
        if (mediaItem.isHlsTranscode()) return source
        return KeyframeStartSource(source)
    }

    /**
     * Told apart by the MIME type both kinds of item carry ([containerMimeType], and the HLS
     * fallback's own), and by the playlist's file name only when an item names none. This runs once
     * per queued video, on the main thread inside `setMediaItems`, and reading the file name parses
     * the URL's whole path: across a 1,245-video queue that was ~15 ms of the main thread
     * (2026-11-15).
     */
    private fun MediaItem.isHlsTranscode(): Boolean {
        val configuration = localConfiguration ?: return false
        val mimeType = configuration.mimeType ?: return configuration.uri.lastPathSegment == Playback.HLS_PLAYLIST
        return mimeType == MimeTypes.APPLICATION_M3U8
    }
}

private class KeyframeStartSource(source: MediaSource) : WrappingMediaSource(source) {
    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = KeyframeStartPeriod(super.createPeriod(id, allocator, startPositionUs))

    override fun releasePeriod(mediaPeriod: MediaPeriod) =
        super.releasePeriod((mediaPeriod as KeyframeStartPeriod).period)
}

/**
 * [period], except that its first track selection asks for the keyframe before the position it is
 * given. Every other call passes straight through, and the callback is re-pointed at this wrapper so
 * media3 only ever sees the one period it created.
 *
 * **Every** [MediaPeriod] method is forwarded, the interface's default ones included: a default left
 * out runs the interface's no-op instead of the real period's override, and nothing fails — media3
 * 1.11's `MediaPeriodHolder` calls [setUsesStreamPrerollFlags], which the progressive period
 * implements. Re-check the interface after a media3 bump.
 */
@Suppress("TooManyFunctions") // a forwarding MediaPeriod implements the whole interface, by design
private class KeyframeStartPeriod(val period: MediaPeriod) : MediaPeriod, MediaPeriod.Callback {

    private var callback: MediaPeriod.Callback? = null
    private var selected = false

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        period.prepare(this, positionUs)
    }

    override fun onPrepared(mediaPeriod: MediaPeriod) {
        callback?.onPrepared(this)
    }

    override fun onContinueLoadingRequested(source: MediaPeriod) {
        callback?.onContinueLoadingRequested(this)
    }

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        val startUs = if (selected || positionUs <= 0L) {
            positionUs
        } else {
            period.getAdjustedSeekPositionUs(positionUs, RESUME_SEEK_PARAMETERS)
        }
        selected = true
        return period.selectTracks(selections, mayRetainStreamFlags, streams, streamResetFlags, startUs)
    }

    override fun maybeThrowPrepareError() = period.maybeThrowPrepareError()

    override fun getTrackGroups(): TrackGroupArray = period.trackGroups

    override fun getStreamKeys(trackSelections: List<ExoTrackSelection>) = period.getStreamKeys(trackSelections)

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = period.discardBuffer(positionUs, toKeyframe)

    override fun readDiscontinuity(): Long = period.readDiscontinuity()

    override fun seekToUs(positionUs: Long): Long = period.seekToUs(positionUs)

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        period.getAdjustedSeekPositionUs(positionUs, seekParameters)

    override fun getBufferedPositionUs(): Long = period.bufferedPositionUs

    override fun getNextLoadPositionUs(): Long = period.nextLoadPositionUs

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean = period.continueLoading(loadingInfo)

    override fun isLoading(): Boolean = period.isLoading

    override fun reevaluateBuffer(positionUs: Long) = period.reevaluateBuffer(positionUs)

    override fun setEndPositionUs(endPositionUs: Long): Long = period.setEndPositionUs(endPositionUs)

    override fun setUsesStreamPrerollFlags() = period.setUsesStreamPrerollFlags()
}
