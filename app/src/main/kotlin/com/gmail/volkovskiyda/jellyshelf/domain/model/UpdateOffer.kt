package com.gmail.volkovskiyda.jellyshelf.domain.model

/**
 * An update worth showing, and whether the user asked for it.
 *
 * [requested] is provenance, not a property of the release, and it changes two things:
 *
 * - **Where the dialog may appear.** An offer nobody asked for interrupts, so it waits for the
 *   Library tab. One the user asked for by tapping "Check now" is the answer to a question they
 *   just posed, and belongs on the screen they posed it from — making them navigate to Library to
 *   read it is a puzzle, not a safeguard.
 * - **Whether showing it burns the once-a-day interrupt floor.** It should not: a dialog the user
 *   summoned is not an interruption, and spending the floor on it would swallow the next automatic
 *   offer — quite possibly of a newer build.
 *
 * Carried with the offer rather than in a second flow beside it, so the two facts cannot disagree
 * about which offer is on screen.
 */
data class UpdateOffer(val info: UpdateInfo, val requested: Boolean)
