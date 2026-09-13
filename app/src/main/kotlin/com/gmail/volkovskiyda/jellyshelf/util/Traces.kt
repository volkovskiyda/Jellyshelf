package com.gmail.volkovskiyda.jellyshelf.util

/**
 * The names of this app's own system-trace sections, in one place because two things read them and
 * only one of them can see this file.
 *
 * A section written with `androidx.tracing`'s `trace("…") { }` costs a branch when nothing is
 * capturing a trace, so these stay in every build type rather than being gated to one. On API 31+ —
 * this app's `minSdk` — app tracing is on for every non-debuggable process, so a release or
 * `benchmarkRelease` APK emits them without `Trace.forceEnableAppTracing()` or a `<profileable>`
 * entry in the manifest.
 *
 * Two readers:
 *  - **Perfetto / Android Studio's system trace**, for a human looking at one recording. `Jellyshelf.`
 *    prefixes every name so the app's own slices sort together and separate from the framework's.
 *  - **`:baselineprofile`'s `JourneyBenchmark`**, which measures them with `TraceSectionMetric` and
 *    is what turns them into a regression signal across app versions. That module is a
 *    `com.android.test` targeting `:app` and cannot see app classes, so it repeats these strings as
 *    constants of its own. They are a contract between the two files: renaming one here without
 *    renaming it there does not fail to compile, it silently measures nothing.
 *
 * Firebase Performance carries its own traces (`library_sync`, `player_startup`) and is not this.
 * That one samples real installs in the field and reports minutes later; these are local, exact, and
 * free. Where they cover the same span the code writes both, deliberately.
 */
object Traces {

    /** `Application.onCreate` end to end, and the DI graph inside it. */
    const val APP_ON_CREATE = "Jellyshelf.app.onCreate"
    const val START_KOIN = "Jellyshelf.app.startKoin"

    /**
     * `MainActivity.onCreate`. Covers the window setup and the `setContent` call — *not* the first
     * composition, which the platform runs on the first measure pass after `onCreate` returns.
     * Compose emits its own sections for that.
     */
    const val ACTIVITY_ON_CREATE = "Jellyshelf.activity.onCreate"

    /**
     * One browse emission: the projected rows a list renders, mapped to domain objects. The hot
     * path — `BrowseCostBenchmark` measured it at 325–545 ms over 10,000 rows before the projection
     * landed — so it carries [BROWSE_ROWS] beside it, because a duration means nothing here without
     * the row count that produced it.
     */
    const val LIBRARY_BROWSE = "Jellyshelf.library.browse"

    /** Rows in the emission [LIBRARY_BROWSE] is timing. A counter track, not a slice. */
    const val BROWSE_ROWS = "Jellyshelf.library.rows"

    /**
     * One ranked-search emission: full rows scored against the query. Separate from
     * [LIBRARY_BROWSE] because it reads and scores `description`, `tags` and `youtubeCategories`
     * that browse never touches, and it runs on every keystroke.
     */
    const val LIBRARY_SEARCH = "Jellyshelf.library.search"

    /** A full library sync, spanning exactly what Firebase's `library_sync` trace spans. */
    const val LIBRARY_SYNC = "Jellyshelf.library.sync"

    /**
     * One emission of a single category's videos: the same projected-row mapping [LIBRARY_BROWSE]
     * covers, for the list behind a category rather than the library's own.
     *
     * Its own name because it was not distinguishable before. Both lists end in the same helper, so
     * a recording — and `JourneyBenchmark`'s `Mode.Sum` over [LIBRARY_BROWSE] — counted a category's
     * emissions as the library's. The benchmark's journey never opens a category, which is the only
     * reason that number was ever right.
     */
    const val CATEGORY_VIDEOS = "Jellyshelf.category.videos"

    /**
     * Loading one video for the detail screen: the whole stored row, mapped through the three JSON
     * converters the browse projection exists to skip.
     *
     * One row rather than a list, so what this measures is the converters rather than a per-row
     * cost. The screen's second flow — the categories a video appears in — is deliberately not
     * measured: it is the same visit, and spanning it would double the events for a number nobody
     * asked for.
     */
    const val DETAIL_LOAD = "Jellyshelf.detail.load"

    /**
     * One emission of the Categories tab's list: every category with its video count.
     *
     * Worth its own section because the count is a correlated subquery per row — the cost grows
     * with the number of categories *and* with the library behind them.
     */
    const val CATEGORIES_LIST = "Jellyshelf.categories.list"

    /**
     * One emission of a category search, scored and mapped. Runs on every debounced keystroke, and
     * the query underneath it scans every video's description with `LIKE '%…%'` — the most
     * expensive thing the Categories tab can be asked to do.
     */
    const val CATEGORIES_SEARCH = "Jellyshelf.categories.search"

    /**
     * One emission of the "Others" tab: six count queries combined into virtual categories. Cheap
     * per row and six flows wide, so what this measures is the fan-in, not the mapping.
     */
    const val CATEGORIES_OTHERS = "Jellyshelf.categories.others"

    /**
     * Turning the bare media ids a controller sends into playable items — stream URL, notification
     * metadata, resume position — on the path a tap actually takes (`onSetMediaItems`). It is the
     * first measurable slice of what Firebase's `player_startup` trace covers whole, and the only
     * part of it that is synchronous work rather than waiting for a decoder.
     *
     * An *async* section rather than a `trace { }` one: resolving suspends, so it can resume on a
     * thread other than the one it began on, and `beginSection`/`endSection` are thread-confined.
     */
    const val PLAYER_RESOLVE = "Jellyshelf.player.resolve"

    /**
     * A tap to the first frame it produces: the same window Firebase's `player_startup` trace
     * covers, as a system-trace section so a macrobenchmark can read it without a release build and
     * a week of field data. [PLAYER_RESOLVE] is the first slice of this one.
     *
     * Async, and for the same reason [PLAYER_RESOLVE] is — it spans a suspend-and-decode, so it
     * neither begins nor ends on one known thread.
     *
     * Unlike the Firebase trace it does *not* skip the bundled demo clip. That skip exists to keep
     * demo runs out of production analytics, which does not apply to a local measurement — and a
     * section that vanished on a device with no server would make the benchmark silently
     * unmeasurable rather than obviously wrong.
     */
    const val PLAYER_STARTUP = "Jellyshelf.player.startup"

    /**
     * One media item to the first frame of the next: what a queue advance costs, whether it came
     * from the next button or from an item simply ending.
     *
     * Separate from [PLAYER_STARTUP] because the two are moved by different things — a transition
     * reuses a prepared player and a warm connection — and because the queue's *first* item fires a
     * transition too (`MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED`). That one is deliberately not
     * measured here: counting it would make every startup also report a transition, and the two
     * metrics would stop being independent.
     *
     * Async, like the two above, and for the same reason.
     */
    const val PLAYER_TRANSITION = "Jellyshelf.player.transition"
}
