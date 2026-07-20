package com.gmail.volkovskiyda.jellyshelf.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object Playback {

    private fun base(serverUrl: String) = serverUrl.trim().removeSuffix("/")

    /** Direct static stream URL — opens instantly in any external video player. */
    fun streamUrl(serverUrl: String, itemId: String, apiKey: String): String =
        "${base(serverUrl)}/Videos/$itemId/stream?static=true&api_key=$apiKey"

    /** Jellyfin web details deep link — opens the item page in the Jellyfin app / browser. */
    fun detailsDeepLink(serverUrl: String, itemId: String): String =
        "${base(serverUrl)}/web/index.html#/details?id=$itemId"

    fun openInExternalPlayer(context: Context, serverUrl: String, itemId: String, apiKey: String) {
        val uri = Uri.parse(streamUrl(serverUrl, itemId, apiKey))
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(context, Intent.createChooser(intent, "Play with"))
    }

    fun openInJellyfin(context: Context, serverUrl: String, itemId: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(detailsDeepLink(serverUrl, itemId)))
        launch(context, intent)
    }

    private fun launch(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No app found to handle this", Toast.LENGTH_SHORT).show()
        }
    }
}
