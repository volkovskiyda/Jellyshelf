@file:androidx.annotation.OptIn(UnstableApi::class)

package com.gmail.volkovskiyda.jellyshelf.playback

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * media3's media-button receiver, with one extra question asked first: is there anything to resume?
 *
 * A headset or Bluetooth play button reaches a dead process as a broadcast, and media3's receiver
 * answers it by starting [PlaybackService] *in the foreground*. That is right when a video is
 * waiting to be resumed and wrong when nothing is — a foreground service that never posts a
 * notification is killed by the platform with `ForegroundServiceDidNotStartInTimeException`, and
 * neither failing the resumption future nor stopping the service early avoids it: the first waits
 * out the ten-second deadline, the second trips the same check on the way down.
 *
 * So the decision moves earlier, to the one place it can still be "don't start at all". media3
 * makes room for exactly this — it already declines non-`play` key events for the same reason.
 *
 * The answer has to be immediate, inside the broadcast, which is why it comes from
 * [ResumableCache] rather than from the DataStore key the resumption itself reads.
 */
class MediaButtonGate : MediaButtonReceiver(), KoinComponent {

    private val resumable: ResumableCache by inject()

    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean =
        resumable.peek() && super.shouldStartForegroundService(context, intent)
}
