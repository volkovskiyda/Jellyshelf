package com.gmail.volkovskiyda.jellyshelf.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import com.gmail.volkovskiyda.jellyshelf.R
import timber.log.Timber

object Playback {

    /** Shared logcat tag for the whole external-player flow: `adb logcat -s Playback`. */
    const val TAG = "Playback"

    /** Treat a stop within this many ms of the end as "finished" (players rarely report the exact end). */
    private const val COMPLETION_TOLERANCE_MS = 5_000L

    private fun base(serverUrl: String) = serverUrl.trim().removeSuffix("/")

    /** Direct static stream URL — opens instantly in any external video player. */
    fun streamUrl(serverUrl: String, itemId: String, apiKey: String): String =
        "${base(serverUrl)}/Videos/$itemId/stream?static=true&api_key=$apiKey"

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
     */
    fun externalPlayerIntent(
        context: Context,
        serverUrl: String,
        itemId: String,
        apiKey: String,
        title: String?,
        resumeMs: Long,
    ): Intent {
        val uri = streamUrl(serverUrl, itemId, apiKey).toUri()
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!title.isNullOrBlank()) putExtra("title", title)
            // MX Player: return position/end_by/duration to us when playback ends.
            putExtra("return_result", true)
            if (resumeMs > 0) {
                putExtra("position", resumeMs.toInt())   // MX Player resume (int ms)
                putExtra("extra_position", resumeMs)      // VLC resume (long ms)
            }
        }
        // api_key is a secret — log the stream URL without it.
        Timber.tag(TAG).d("launch external player: itemId=$itemId title=$title resumeMs=$resumeMs url=${stripApiKey(streamUrl(serverUrl, itemId, apiKey))}")
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
        // MX Player: reports `end_by` and, when the user stopped mid-video, `position`. On a
        // natural finish it sends `end_by=playback_completion` but omits `position` entirely — so
        // key off `end_by`, not `position`, or completions would be missed.
        if (data.hasExtra("end_by") || data.hasExtra("position")) {
            val endBy = data.getStringExtra("end_by")
            val pos = data.getIntExtra("position", -1)
            val duration = data.getIntExtra("duration", -1)
            Timber.tag(TAG).d("parseResult: MX Player result position=$pos end_by=$endBy duration=$duration")
            if (endBy == "playback_completion") {
                // Finished — position is irrelevant (marked played, resume cleared) and usually absent.
                val result = Result(if (pos >= 0) pos.toLong() else 0L, completed = true)
                Timber.tag(TAG).d("parseResult: parsed $result")
                return result
            }
            if (pos >= 0) {
                val result = Result(pos.toLong(), completed = false)
                Timber.tag(TAG).d("parseResult: parsed $result")
                return result
            }
            Timber.tag(TAG).w("parseResult: MX Player returned no usable position (end_by=$endBy); ignoring")
        }
        // VLC — no explicit completion flag, so infer it from proximity to the end.
        if (data.hasExtra("extra_position")) {
            val pos = data.getLongExtra("extra_position", -1L)
            val duration = data.getLongExtra("extra_duration", 0L)
            Timber.tag(TAG).d("parseResult: VLC result extra_position=$pos extra_duration=$duration")
            if (pos >= 0) {
                val completed = duration > 0 && pos >= duration - COMPLETION_TOLERANCE_MS
                val result = Result(pos, completed)
                Timber.tag(TAG).d("parseResult: parsed $result")
                return result
            }
            Timber.tag(TAG).w("parseResult: VLC returned an invalid position ($pos); ignoring")
        }
        Timber.tag(TAG).w("parseResult: no recognizable position extra; player did not report a resume point. extras=${data.extras?.keySet()}")
        return null
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
