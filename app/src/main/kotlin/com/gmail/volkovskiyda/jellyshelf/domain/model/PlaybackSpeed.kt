package com.gmail.volkovskiyda.jellyshelf.domain.model

/**
 * The speeds the player's speed menu offers, and the rule for reading one back off disk.
 *
 * In the domain rather than beside the menu because the repository validates the persisted value
 * against it. Two copies of the list would let a speed a later build drops read back as one no menu
 * item matches — the chip would say "1.3×" with nothing ticked.
 */
object PlaybackSpeed {
    /** The usual video-player spread, up to 3× for skimming talk-heavy videos. */
    val options: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    /** Normal speed: the fresh-install default, and what every unusable stored value degrades to. */
    const val DEFAULT = 1f

    /**
     * Unknown, absent or garbage values degrade to [DEFAULT] rather than failing a launch — the
     * same contract as [ThemeMode.fromStorage] and the duration filter's bucket id.
     *
     * Exact float equality is sound here: every entry of [options] is a multiple of 0.25, so it
     * round-trips through a float preference bit for bit. It is also the comparison the menu's
     * check mark already makes.
     */
    fun fromStorage(value: Float?): Float = options.firstOrNull { it == value } ?: DEFAULT
}
