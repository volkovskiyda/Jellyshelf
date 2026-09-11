@file:androidx.annotation.OptIn(UnstableApi::class)

package com.gmail.volkovskiyda.jellyshelf.playback

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.gmail.volkovskiyda.jellyshelf.util.Playback
import com.gmail.volkovskiyda.jellyshelf.util.stripCredentials
import timber.log.Timber

/**
 * Reopens an HTTP stream that has sat idle long enough to have died unnoticed, instead of reading a
 * socket nobody has touched since before the user paused.
 *
 * **The problem this exists for.** ExoPlayer does not close a stream while playback is paused; it
 * stops *reading* one. `ProgressiveMediaPeriod` loads in one-megabyte steps and then blocks its
 * loader thread on a condition variable until the player asks for more, which — with the buffer
 * full — is not until playback resumes. The response stays open the whole time with no read in
 * flight, so the media client's socket timeout is not counting either: see
 * `provideMediaHttpClient` in `di/AppModule.kt`, whose timeouts are connect and socket only,
 * precisely because a stream is one long request. The same is true, for longer, of the *next* queued
 * video: `PRELOAD_TARGET_DURATION_US` buffers a few seconds of it and then stops, so its socket
 * idles from that moment until the queue reaches it.
 *
 * Five to thirty minutes is long enough for that socket to be gone — a reverse proxy's idle timeout,
 * a NAT or VPN mapping expiring, Wi-Fi sleeping, or the phone moving to mobile data and stranding
 * the old connection. Nothing observes it until the next read, and what the user sees then depends
 * on how it died: a clean close costs one retry, while a silent drop costs the full socket timeout
 * per attempt, three retries at increasing backoff, and only then a player error — the better part
 * of a minute of spinner for a video that was simply paused too long.
 *
 * **What this does about it.** A read arriving more than [idleReopenMs] after the previous one
 * closes the upstream and opens it again at the byte the stream had reached
 * ([DataSpec.subrange] carries the position, the remaining length, the headers and the flags), then
 * reads as if nothing had happened. ExoPlayer is never told: no error, no retry, no state change.
 *
 * **Why thirty seconds is safe.** Normal playback never idles that long. `DefaultLoadControl` keeps
 * about fifty seconds buffered and resumes loading as soon as consumption drops it below that, so
 * gaps between reads while a video plays are fractions of a second. Only a real pause crosses this,
 * which is the case this is for. It also sits below the idle windows that actually close
 * connections in the wild — nginx defaults to sixty seconds, Cloudflare to a hundred.
 *
 * **A failing reopen propagates on purpose.** If the stream cannot be reopened, the exception leaves
 * `read` exactly as an ordinary read failure would, and ExoPlayer's existing retry opens a fresh
 * connection from scratch — which is the right answer and is already well tested. Swallowing it here
 * would only add a second retry ladder underneath media3's own.
 *
 * **Wrapped around HTTP only.** `PlaybackService` installs this inside the factory that builds the
 * Ktor source, so `asset:` demo clips, which `DefaultDataSource` routes to a local reader, never see
 * it — there is no socket there to go stale.
 *
 * **The clock is monotonic.** [SystemClock.elapsedRealtime] rather than the injected `TimeProvider`,
 * whose own KDoc says it is wall time and that "nothing here is suitable for measuring a duration":
 * a clock correction mid-pause must not be read as ten minutes of idleness, nor hide one.
 *
 * Not thread-safe, and does not need to be: media3 creates one `DataSource` per loadable and only
 * that loadable's loader thread touches it, which is what makes the plain vars below correct.
 */
internal class IdleReconnectDataSource(
    private val upstream: DataSource,
    private val idleReopenMs: Long = IDLE_REOPEN_MS,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : DataSource {

    /** The spec the stream was opened with, so a reopen can ask for the rest of the same range. */
    private var dataSpec: DataSpec? = null

    /** How much of that spec has been handed to the caller, i.e. where a reopen must resume. */
    private var bytesRead = 0L

    /** When the upstream was last actually touched — opened, or a read that returned. */
    private var lastActivityMs = 0L

    private var opened = false

    /**
     * Set once the upstream reports the end of the data.
     *
     * Without it an idle wait after the last byte would reopen the stream only to read the end of it
     * again: the extractor can come back for a final read long after the data ran out, and at that
     * point there is nothing left to fetch.
     */
    private var endOfInput = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        bytesRead = 0L
        endOfInput = false
        opened = true
        lastActivityMs = elapsedRealtime()
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        // The contract says a zero-length read returns zero without touching the source, so it is
        // neither evidence that the socket is alive nor a reason to reopen it.
        if (length == 0) return upstream.read(buffer, offset, length)
        val spec = dataSpec
        if (spec != null && !endOfInput && elapsedRealtime() - lastActivityMs > idleReopenMs) {
            reopen(spec)
        }
        val read = upstream.read(buffer, offset, length)
        // Stamped after the read returns, not before: a read that spent the whole socket timeout
        // dying on a stale connection is the opposite of activity, and timing from when it started
        // would count its own duration as time the socket was known to be good.
        lastActivityMs = elapsedRealtime()
        when {
            read == C.RESULT_END_OF_INPUT -> endOfInput = true
            read > 0 -> bytesRead += read
        }
        return read
    }

    private fun reopen(spec: DataSpec) {
        val idleMs = elapsedRealtime() - lastActivityMs
        val resumeAt = spec.position + bytesRead
        Timber.tag(Playback.TAG).i(
            "reopening ${stripCredentials(spec.uri.toString())} after ${idleMs / MILLIS_PER_SECOND}s " +
                "idle at byte $resumeAt",
        )
        upstream.close()
        // subrange(0) hands back the same spec, so a stream idled before its first byte simply
        // reconnects where it was.
        upstream.open(spec.subrange(bytesRead))
        lastActivityMs = elapsedRealtime()
    }

    override fun close() {
        // Guarded rather than unconditional so a close after a failed open — which the DataSource
        // contract requires callers to make — cannot close an upstream that was never opened.
        if (opened) {
            opened = false
            upstream.close()
        }
        dataSpec = null
    }
}

/**
 * How long a stream may go untouched before it is treated as stale rather than idle.
 *
 * Thirty seconds: above any gap normal playback produces, below the idle timeouts that actually
 * close connections. The reasoning is in [IdleReconnectDataSource]'s KDoc, which is also where to
 * look before changing it.
 */
private const val IDLE_REOPEN_MS = 30_000L

private const val MILLIS_PER_SECOND = 1_000L
