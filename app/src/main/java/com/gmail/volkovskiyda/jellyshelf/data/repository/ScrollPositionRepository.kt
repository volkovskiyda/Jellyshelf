package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

private val Context.scrollDataStore by preferencesDataStore(name = "scroll_positions")

data class ScrollPosition(val index: Int, val offset: Int) {
    companion object {
        val Zero = ScrollPosition(0, 0)
    }
}

/**
 * Scroll position anchored to a stable per-item key (the video [anchor], i.e. its file name)
 * rather than a raw list index, so it survives items being added, removed or renamed.
 */
data class AnchorPosition(val anchor: String, val offset: Int)

/**
 * Remembers per-screen list scroll positions ([firstVisibleItemIndex] + offset).
 *
 * An in-memory cache is the source of truth while the app is running, so restoring on tab
 * switch or list → detail → back is instant (no disk-read flicker). Writes are mirrored to
 * DataStore so positions also survive a process restart; the cache is seeded from disk on
 * first access.
 */
class ScrollPositionRepository(context: Context) {
    private val ds = context.applicationContext.scrollDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, ScrollPosition>()
    private val anchorCache = ConcurrentHashMap<String, AnchorPosition>()

    // Seeded exactly once (lazy is synchronized), blocking the first reader until disk has
    // been read. The init warm-up only primes DataStore's in-memory cache off the main thread
    // so that the first reader's blocking read is near-instant; it never touches [cache], so
    // seedFromDisk() runs on a single thread and its read-modify-writes can't race.
    private val seed = lazy { runBlocking { seedFromDisk() } }

    init {
        scope.launch { runCatching { ds.data.first() } }
    }

    private suspend fun seedFromDisk() {
        ds.data.first().asMap().forEach { (key, value) ->
            val name = key.name
            when {
                name.endsWith(ANCHOR_SUFFIX) -> {
                    val screen = name.removeSuffix(ANCHOR_SUFFIX)
                    val anchor = value as? String ?: return@forEach
                    anchorCache[screen] =
                        (anchorCache[screen] ?: AnchorPosition("", 0)).copy(anchor = anchor)
                }
                name.endsWith(ANCHOR_OFFSET_SUFFIX) -> {
                    val screen = name.removeSuffix(ANCHOR_OFFSET_SUFFIX)
                    val offset = value as? Int ?: return@forEach
                    anchorCache[screen] =
                        (anchorCache[screen] ?: AnchorPosition("", 0)).copy(offset = offset)
                }
                name.endsWith(INDEX_SUFFIX) -> {
                    val screen = name.removeSuffix(INDEX_SUFFIX)
                    val v = value as? Int ?: return@forEach
                    cache[screen] = (cache[screen] ?: ScrollPosition.Zero).copy(index = v)
                }
                name.endsWith(OFFSET_SUFFIX) -> {
                    val screen = name.removeSuffix(OFFSET_SUFFIX)
                    val v = value as? Int ?: return@forEach
                    cache[screen] = (cache[screen] ?: ScrollPosition.Zero).copy(offset = v)
                }
            }
        }
    }

    /** Last known scroll position for [key], or [ScrollPosition.Zero] if none was saved. */
    fun peek(key: String): ScrollPosition {
        seed.value
        return cache[key] ?: ScrollPosition.Zero
    }

    /** Records [position] for [key] in memory immediately and persists it to disk. */
    fun save(key: String, position: ScrollPosition) {
        if (cache.put(key, position) == position) return
        scope.launch {
            ds.edit {
                it[intPreferencesKey("$key$INDEX_SUFFIX")] = position.index
                it[intPreferencesKey("$key$OFFSET_SUFFIX")] = position.offset
            }
        }
    }

    /** Last known anchor position for [key], or `null` if none was saved. */
    fun peekAnchor(key: String): AnchorPosition? {
        seed.value
        return anchorCache[key]
    }

    /** Records the anchor [position] for [key] in memory immediately and persists it to disk. */
    fun saveAnchor(key: String, position: AnchorPosition) {
        if (anchorCache.put(key, position) == position) return
        scope.launch {
            ds.edit {
                it[stringPreferencesKey("$key$ANCHOR_SUFFIX")] = position.anchor
                it[intPreferencesKey("$key$ANCHOR_OFFSET_SUFFIX")] = position.offset
            }
        }
    }

    private companion object {
        const val INDEX_SUFFIX = ".index"
        const val OFFSET_SUFFIX = ".offset"
        const val ANCHOR_SUFFIX = ".anchor"
        const val ANCHOR_OFFSET_SUFFIX = ".anchorOffset"
    }
}
