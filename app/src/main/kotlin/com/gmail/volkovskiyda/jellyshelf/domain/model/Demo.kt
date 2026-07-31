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

/**
 * The magic credentials that enter demo mode through the real sign-in form — the second way in,
 * next to the Settings screen's "Try demo" button.
 *
 * The button skips the sign-in choreography; typing these walks through it, which is the point:
 * the form, the user list and the failure state can all be demonstrated with no server anywhere.
 * They are matched against the **raw** server field, before any URL normalization, and nothing on
 * this path is ever persisted as a connection or sent anywhere.
 */
const val DEMO_SERVER = "jellyfin"
const val DEMO_USER = "demo"

/** Signing in as [DEMO_USER] with this password demonstrates the real authentication-error state. */
const val DEMO_BAD_PASSWORD = "incorrect"

/**
 * The id of the fake user the demo server "returns". Never persisted — the picker chip it fills is
 * presentation only — but distinct enough that it could not collide with a real Jellyfin user id.
 */
const val DEMO_USER_ID = "demo-user"
