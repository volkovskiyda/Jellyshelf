package com.gmail.volkovskiyda.jellyshelf.domain.model

/**
 * Which channel the app watches for a newer build of itself. The app has no Play listing, so there
 * is nothing to ask by default — the user picks the channel they actually installed from.
 *
 * [NONE] is the fresh-install default and the only value that touches no network at all. That
 * matters beyond politeness: it is what keeps Test Lab runs, the live UI journey and the
 * `nonMinifiedRelease` / `benchmarkRelease` profiling variants from fetching releases, since those
 * last two are non-debug builds carrying a real version code.
 *
 * [storageValue] is what DataStore persists — stable across renames of the enum constants, the same
 * contract as [ThemeMode].
 */
enum class UpdateSource(val storageValue: String) {
    NONE("none"),
    GITHUB("github"),
    APP_DISTRIBUTION("app_distribution"),
    ;

    companion object {
        /**
         * Unknown or absent stored values degrade to [NONE] rather than failing the read.
         *
         * The degrade target is deliberate and not just "the first constant": an unreadable
         * preferences file must not start making network calls the user never opted into. Every
         * other degrade in this app picks the pre-feature behaviour, and here that is "don't check".
         */
        fun fromStorage(value: String?): UpdateSource =
            entries.firstOrNull { it.storageValue == value } ?: NONE
    }
}
