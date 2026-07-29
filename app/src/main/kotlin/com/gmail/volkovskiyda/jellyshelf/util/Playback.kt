package com.gmail.volkovskiyda.jellyshelf.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import com.gmail.volkovskiyda.jellyshelf.R
import timber.log.Timber
import java.net.URLEncoder

object Playback {

    /** Shared logcat tag for the whole playback flow, external and in-app: `adb logcat -s Playback`. */
    const val TAG = "Playback"

    /** Treat a stop within this many ms of the end as "finished" (players rarely report the exact end). */
    private const val COMPLETION_TOLERANCE_MS = 5_000L

    // Result extras, by the names the players actually use.
    private const val EXTRA_END_BY = "end_by"
    private const val EXTRA_POSITION = "position"
    private const val EXTRA_VLC_POSITION = "extra_position"
    private const val EXTRA_VLC_DURATION = "extra_duration"
    private const val END_BY_COMPLETION = "playback_completion"

    /**
     * The header Jellyfin accepts a credential in — the same one every API call uses. Public so
     * the in-app player's HTTP layer sends the credential the same way (never in the URL).
     */
    const val TOKEN_HEADER = "X-Emby-Token"

    /**
     * MX Player's request-headers extra: a String array of alternating name, value. VLC and other
     * players ignore an extra they don't know, so attaching it is never harmful.
     */
    private const val EXTRA_HEADERS = "headers"

    private fun base(serverUrl: String) = serverUrl.trim().removeSuffix("/")

    /**
     * Direct static stream URL. [credential] is appended as `api_key` only when it is non-null —
     * pass null to keep it out of the URL entirely and hand it over as a header instead.
     *
     * URL-encoded, matching [authorizedImageUrl]: a token is server-generated and in practice
     * URL-safe, but the two credential paths disagreeing about encoding is the kind of difference
     * that only shows up once some server issues a token containing a `+`.
     */
    fun streamUrl(serverUrl: String, itemId: String, credential: String?): String {
        val url = "${base(serverUrl)}/Videos/$itemId/stream?static=true"
        if (credential.isNullOrBlank()) return url
        return "$url&api_key=${URLEncoder.encode(credential, "UTF-8")}"
    }

    /** Jellyfin web details deep link — opens the item page in the Jellyfin app / browser. */
    fun detailsDeepLink(serverUrl: String, itemId: String): String =
        "${base(serverUrl)}/web/index.html#/details?id=$itemId"

    /** Position (ms) an external player reported when it exited, and whether it played to the end. */
    data class Result(val positionMs: Long, val completed: Boolean)

    /**
     * Builds a chooser intent for an external video player. Requests a position result on exit
     * (MX Player `return_result`) and, when [resumeMs] > 0, resumes there (MX Player `position`,
     * VLC `extra_position`). Launch it with an ActivityResultLauncher and pass the returned data
     * to [parseResult]. Only MX Player and VLC return a position; other players simply won't.
     *
     * **Where the credential goes.** By default it travels in an intent extra ([EXTRA_HEADERS])
     * and *not* in the URL: an `ACTION_VIEW` URL is handed to whichever app the user picks, and
     * from there it lands in that player's recent-files list, its logs, and any cast target — a
     * durable copy of a credential in places this app can't see. A header is used for the request
     * and not retained.
     *
     * The catch is that a chooser cannot know which player will be picked, so this can't be
     * decided per player: [tokenInQuery] is the user's escape hatch (Settings → Advanced) for a
     * player that ignores the headers extra and would otherwise fail with 401. VLC's support for
     * custom headers has varied by version, which is exactly what that toggle covers.
     */
    fun externalPlayerIntent(
        context: Context,
        serverUrl: String,
        itemId: String,
        credential: String,
        title: String?,
        resumeMs: Long,
        tokenInQuery: Boolean = false,
    ): Intent {
        val url = streamUrl(serverUrl, itemId, credential.takeIf { tokenInQuery })
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(url.toUri(), "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!title.isNullOrBlank()) putExtra("title", title)
            // MX Player: return position/end_by/duration to us when playback ends.
            putExtra("return_result", true)
            if (!tokenInQuery) putExtra(EXTRA_HEADERS, arrayOf(TOKEN_HEADER, credential))
            if (resumeMs > 0) {
                putExtra(EXTRA_POSITION, resumeMs.toInt())     // MX Player resume (int ms)
                putExtra(EXTRA_VLC_POSITION, resumeMs)         // VLC resume (long ms)
            }
        }
        // The credential is a secret whichever way it travelled — never log it.
        Timber.tag(TAG).d(
            "launch external player: itemId=$itemId title=$title resumeMs=$resumeMs " +
                "tokenInQuery=$tokenInQuery url=${stripCredentials(url)}",
        )
        return Intent.createChooser(view, context.getString(R.string.play_with))
    }

    /**
     * Reads the position an external player handed back on exit. Understands MX Player
     * (`position` + `end_by`) and VLC (`extra_position` + `extra_duration`). Returns null when the
     * player reported nothing (e.g. the user cancelled the chooser, or the player doesn't support it).
     */
    fun parseResult(data: Intent?): Result? {
        if (data == null) {
            Timber.tag(TAG).d("parseResult: no data returned (chooser/player cancelled, or player did not report)")
            return null
        }
        // Dump every returned extra so an unrecognized player's result keys are visible in logcat.
        data.extras?.let { extras ->
            val dump = extras.keySet().joinToString(", ") { key ->
                @Suppress("DEPRECATION")
                "$key=${extras.get(key)}"
            }
            Timber.tag(TAG).d("parseResult: result extras -> {$dump}")
        } ?: Timber.tag(TAG).d("parseResult: result has no extras")

        // Read each extra as "absent" (null) or its raw value, so the decision below can be a
        // plain function — the Bundle is the only part of this that needs Android.
        val endBy = data.getStringExtra(EXTRA_END_BY)
        val positionMs = if (data.hasExtra(EXTRA_POSITION)) data.getIntExtra(EXTRA_POSITION, -1) else null
        val vlcPositionMs =
            if (data.hasExtra(EXTRA_VLC_POSITION)) data.getLongExtra(EXTRA_VLC_POSITION, -1L) else null
        val vlcDurationMs =
            if (data.hasExtra(EXTRA_VLC_DURATION)) data.getLongExtra(EXTRA_VLC_DURATION, 0L) else null
        Timber.tag(TAG).d(
            "parseResult: end_by=$endBy position=$positionMs " +
                "extra_position=$vlcPositionMs extra_duration=$vlcDurationMs",
        )

        val result = parsePlayerResult(endBy, positionMs, vlcPositionMs, vlcDurationMs)
        if (result == null) {
            Timber.tag(TAG).w(
                "parseResult: no recognizable position extra; player did not report a resume " +
                    "point. extras=${data.extras?.keySet()}",
            )
        } else {
            Timber.tag(TAG).d("parseResult: parsed $result")
        }
        return result
    }

    /**
     * The decision behind [parseResult], over already-extracted extras — a null argument means the
     * player did not send that extra at all. Pure, so all five branches are testable on the JVM
     * (there is no Robolectric here, and a `Bundle` needs a device).
     *
     * MX Player reports [EXTRA_END_BY] and, when the user stopped mid-video, [EXTRA_POSITION]. On a
     * natural finish it sends `end_by=playback_completion` but omits the position entirely — hence
     * keying off `end_by`, not the position, or completions would be missed.
     *
     * VLC sends no completion flag at all, so completion is inferred from proximity to the end.
     * A player that reported neither falls through to null: nothing to record.
     */
    internal fun parsePlayerResult(
        endBy: String?,
        positionMs: Int?,
        vlcPositionMs: Long?,
        vlcDurationMs: Long?,
    ): Result? {
        val mxPlayer = when {
            endBy == null && positionMs == null -> null
            // Finished — position is irrelevant (marked played, resume cleared) and usually absent.
            endBy == END_BY_COMPLETION ->
                Result(positionMs?.coerceAtLeast(0)?.toLong() ?: 0L, completed = true)
            positionMs != null && positionMs >= 0 -> Result(positionMs.toLong(), completed = false)
            else -> null
        }
        val vlc = if (vlcPositionMs != null && vlcPositionMs >= 0) {
            val duration = vlcDurationMs ?: 0L
            Result(
                vlcPositionMs,
                completed = duration > 0 && vlcPositionMs >= duration - COMPLETION_TOLERANCE_MS,
            )
        } else {
            null
        }
        return mxPlayer ?: vlc
    }

    fun openInJellyfin(context: Context, serverUrl: String, itemId: String) {
        val intent = Intent(Intent.ACTION_VIEW, detailsDeepLink(serverUrl, itemId).toUri())
        launch(context, intent)
    }

    private fun launch(context: Context, intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, context.getString(R.string.no_app_found), Toast.LENGTH_SHORT).show()
        }
    }
}
