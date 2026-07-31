package com.gmail.volkovskiyda.jellyshelf.domain.model

/**
 * The `jellyfinItemId` every seeded demo row carries.
 *
 * Demo videos have no Jellyfin item behind them, but the app treats a null item id as "not on the
 * server" and disables playback on it (`DetailScreen`'s play gate). A sentinel keeps that gate —
 * and every other `itemId != null` check — working without a demo branch at each site.
 *
 * It is deliberately not a plausible Jellyfin id: nothing may ever send it to a server. Nothing
 * does, because a demo install has no credentials and every server call already declines without
 * them; this is the second line of defence, readable in a database dump.
 */
const val DEMO_ITEM_ID = "demo"
