package com.gmail.volkovskiyda.jellyshelf.domain.model

/**
 * The speeds the player's speed menu offers, the wider ladder press-and-hold walks, and the rule
 * for reading a stored value back off disk.
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
     * What press-and-hold starts at, sitting mid-ladder so a swipe has room in both directions.
     *
     * Never persisted — [fromStorage] does not even accept the values above 3× that a swipe can
     * reach, which is the point: a hold is a gesture, and it ends with the finger.
     */
    const val HOLD_DEFAULT = 2f

    /**
     * The ladder a press-and-hold swipe steps through: the menu's speeds plus two that only a hold
     * can reach. 4× and 5× are skim speeds — useful for as long as a finger holds them, useless as
     * a setting to leave a video playing at, which is why they are not in [options].
     */
    val holdOptions: List<Float> = options + listOf(4f, 5f)

    /**
     * The speeds a hold swipe ticks on. Only the round landmarks: crossing 1× or 3× is worth
     * feeling, and the steps between them (0.75×, 1.25×, 1.5×, 1.75×, 2.5×) are deliberately
     * silent — ticking all eleven rungs turns a slow swipe into a buzz.
     */
    val hapticLandmarks: Set<Float> = setOf(0.5f, 1f, 2f, 3f, 4f, 5f)

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
