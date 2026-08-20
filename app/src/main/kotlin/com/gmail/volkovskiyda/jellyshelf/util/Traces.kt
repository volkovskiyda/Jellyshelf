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
     * Turning the bare media ids a controller sends into playable items — stream URL, notification
     * metadata, resume position — on the path a tap actually takes (`onSetMediaItems`). It is the
     * first measurable slice of what Firebase's `player_startup` trace covers whole, and the only
     * part of it that is synchronous work rather than waiting for a decoder.
     *
     * An *async* section rather than a `trace { }` one: resolving suspends, so it can resume on a
     * thread other than the one it began on, and `beginSection`/`endSection` are thread-confined.
     */
    const val PLAYER_RESOLVE = "Jellyshelf.player.resolve"
}
