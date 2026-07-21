package com.gmail.volkovskiyda.jellyshelf.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.gmail.volkovskiyda.jellyshelf.R

object Playback {

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
        val uri = Uri.parse(streamUrl(serverUrl, itemId, apiKey))
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
        return Intent.createChooser(view, context.getString(R.string.play_with))
    }

    /**
     * Reads the position an external player handed back on exit. Understands MX Player
     * (`position` + `end_by`) and VLC (`extra_position` + `extra_duration`). Returns null when the
     * player reported nothing (e.g. the user cancelled the chooser, or the player doesn't support it).
     */
    fun parseResult(data: Intent?): Result? {
        if (data == null) return null
        // MX Player
        if (data.hasExtra("position")) {
            val pos = data.getIntExtra("position", -1)
            if (pos >= 0) {
                val completed = data.getStringExtra("end_by") == "playback_completion"
                return Result(pos.toLong(), completed)
            }
        }
        // VLC — no explicit completion flag, so infer it from proximity to the end.
        if (data.hasExtra("extra_position")) {
            val pos = data.getLongExtra("extra_position", -1L)
            if (pos >= 0) {
                val duration = data.getLongExtra("extra_duration", 0L)
                val completed = duration > 0 && pos >= duration - COMPLETION_TOLERANCE_MS
                return Result(pos, completed)
            }
        }
        return null
    }

    fun openInJellyfin(context: Context, serverUrl: String, itemId: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(detailsDeepLink(serverUrl, itemId)))
        launch(context, intent)
    }

    private fun launch(context: Context, intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, context.getString(R.string.no_app_found), Toast.LENGTH_SHORT).show()
        }
    }
}
