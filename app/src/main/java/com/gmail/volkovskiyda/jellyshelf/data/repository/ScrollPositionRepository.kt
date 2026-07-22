package com.gmail.volkovskiyda.jellyshelf.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gmail.volkovskiyda.jellyshelf.domain.DispatcherProvider
import com.gmail.volkovskiyda.jellyshelf.domain.model.AnchorPosition
import com.gmail.volkovskiyda.jellyshelf.domain.model.ScrollPosition
import com.gmail.volkovskiyda.jellyshelf.domain.repository.ScrollPositionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private val Context.scrollDataStore by preferencesDataStore(name = "scroll_positions")

/**
 * Remembers per-screen list scroll positions (firstVisibleItemIndex + offset).
 *
 * An in-memory cache is the source of truth while the app is running, so restoring on tab
 * switch or list → detail → back is instant (no disk-read flicker). Writes are mirrored to
 * DataStore so positions also survive a process restart; the cache is seeded from disk on
 * first access.
 */
class DefaultScrollPositionRepository(
    context: Context,
    dispatchers: DispatcherProvider,
) : ScrollPositionRepository {
    private val ds = context.applicationContext.scrollDataStore

    // Disk writes run on a single-parallelism dispatcher so two rapid saves for the same key
    // can't commit in reverse order and leave the older position on disk.
    private val writeDispatcher = dispatchers.ioSequential
    // Losing a scroll position (disk full, DataStore corruption) must never crash the app —
    // these are all fire-and-forget best-effort writes; ioScope logs and moves on.
    private val scope = dispatchers.ioScope(TAG, "scroll persistence failed")
    private val cache = ConcurrentHashMap<String, ScrollPosition>()
    private val anchorCache = ConcurrentHashMap<String, AnchorPosition>()

    // Seeded once, on an IO thread at construction. peek() never waits for it — blocking a
    // first-frame composition on a disk read is exactly the cold-start jank this cache exists
    // to avoid. Callers that want the disk value observe [awaitSeeded] and restore afterwards.
    private val seedJob = scope.launch { seedFromDisk() }

    /** True once the disk seed has been merged into the in-memory cache. */
    override val isSeeded: Boolean get() = seedJob.isCompleted

    /** Suspends until the disk seed has been merged into the in-memory cache. */
    override suspend fun awaitSeeded() = seedJob.join()

    private suspend fun seedFromDisk() {
        // Assemble complete positions first, then merge with putIfAbsent: a position saved
        // during this session (before seeding finished) always wins over the disk value —
        // merging field-by-field into the live cache could mix stale and fresh halves.
        val positions = HashMap<String, ScrollPosition>()
        val anchors = HashMap<String, AnchorPosition>()
        ds.data.first().asMap().forEach { (key, value) ->
            val name = key.name
            when {
                name.endsWith(ANCHOR_SUFFIX) -> {
                    val screen = name.removeSuffix(ANCHOR_SUFFIX)
                    val anchor = value as? String ?: return@forEach
                    anchors[screen] = (anchors[screen] ?: AnchorPosition("", 0)).copy(anchor = anchor)
                }
                name.endsWith(ANCHOR_OFFSET_SUFFIX) -> {
                    val screen = name.removeSuffix(ANCHOR_OFFSET_SUFFIX)
                    val offset = value as? Int ?: return@forEach
                    anchors[screen] = (anchors[screen] ?: AnchorPosition("", 0)).copy(offset = offset)
                }
                name.endsWith(INDEX_SUFFIX) -> {
                    val screen = name.removeSuffix(INDEX_SUFFIX)
                    val v = value as? Int ?: return@forEach
                    positions[screen] = (positions[screen] ?: ScrollPosition.Zero).copy(index = v)
                }
                name.endsWith(OFFSET_SUFFIX) -> {
                    val screen = name.removeSuffix(OFFSET_SUFFIX)
                    val v = value as? Int ?: return@forEach
                    positions[screen] = (positions[screen] ?: ScrollPosition.Zero).copy(offset = v)
                }
            }
        }
        positions.forEach { (k, v) -> cache.putIfAbsent(k, v) }
        anchors.forEach { (k, v) -> anchorCache.putIfAbsent(k, v) }
    }

    /**
     * Last known scroll position for [key], or [ScrollPosition.Zero] if none was saved (or the
     * disk seed hasn't landed yet — see [awaitSeeded]).
     */
    override fun peek(key: String): ScrollPosition = cache[key] ?: ScrollPosition.Zero

    /** Records [position] for [key] in memory immediately and persists it to disk. */
    override fun save(key: String, position: ScrollPosition) {
        if (cache.put(key, position) == position) return
        scope.launch(writeDispatcher) {
            // Read the latest cached value at commit time, so even a delayed write can only
            // ever persist the newest position.
            val latest = cache[key] ?: return@launch
            ds.edit {
                it[intPreferencesKey("$key$INDEX_SUFFIX")] = latest.index
                it[intPreferencesKey("$key$OFFSET_SUFFIX")] = latest.offset
            }
        }
    }

    /** Last known anchor position for [key], or `null` if none was saved. Waits for the seed. */
    override suspend fun peekAnchor(key: String): AnchorPosition? {
        seedJob.join()
        return anchorCache[key]
    }

    /** Records the anchor [position] for [key] in memory immediately and persists it to disk. */
    override fun saveAnchor(key: String, position: AnchorPosition) {
        if (anchorCache.put(key, position) == position) return
        scope.launch(writeDispatcher) {
            val latest = anchorCache[key] ?: return@launch
            ds.edit {
                it[stringPreferencesKey("$key$ANCHOR_SUFFIX")] = latest.anchor
                it[intPreferencesKey("$key$ANCHOR_OFFSET_SUFFIX")] = latest.offset
            }
        }
    }

    private companion object {
        const val TAG = "ScrollPositions"
        const val INDEX_SUFFIX = ".index"
        const val OFFSET_SUFFIX = ".offset"
        const val ANCHOR_SUFFIX = ".anchor"
        const val ANCHOR_OFFSET_SUFFIX = ".anchorOffset"
    }
}
