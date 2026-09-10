package com.gmail.volkovskiyda.jellyshelf.domain.model

/**
 * How the in-app player fits the video into its window: letterboxed, cropped to fill, or stretched
 * to fill. Cycled by the player's top-bar button and persisted app-wide, the same way a speed picked
 * from the speed menu is.
 *
 * This is *presentation*, and only the full-screen landscape player's: a Picture-in-Picture window
 * always shows the whole frame (it is a thumbnail — cropping it would hide what it exists to show),
 * and so does portrait, where filling the window with a landscape video would throw most of it away.
 * The stored mode survives both and applies again the moment the player is back in landscape.
 *
 * [storageValue] is what DataStore persists — stable across renames of the enum constants, the same
 * contract as [ThemeMode] and [UpdateSource].
 */
enum class VideoScaleMode(val storageValue: String) {
    /** The whole frame, letterboxed to preserve its shape. The pre-feature behaviour. */
    FIT("fit"),

    /** Fills the window, keeping the frame's shape by cropping whichever axis overflows. */
    ZOOM("zoom"),

    /** Fills the window by distorting the frame to its shape. */
    STRETCH("stretch"),
    ;

    /**
     * The mode one tap of the button ahead, wrapping back to [FIT]. Declaration order *is* the
     * cycle, which is why the button needs no list of its own.
     */
    fun next(): VideoScaleMode = entries[(ordinal + 1) % entries.size]

    companion object {
        /** The fresh-install default, and what every unusable stored value degrades to. */
        val DEFAULT = FIT

        /**
         * Unknown or absent stored values degrade to [DEFAULT] rather than failing the read — the
         * pre-feature behaviour, matching every other degrade in this app.
         */
        fun fromStorage(value: String?): VideoScaleMode =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}
