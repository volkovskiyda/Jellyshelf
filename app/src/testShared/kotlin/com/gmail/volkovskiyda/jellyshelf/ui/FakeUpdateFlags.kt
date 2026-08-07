package com.gmail.volkovskiyda.jellyshelf.ui

import com.gmail.volkovskiyda.jellyshelf.data.remote.UpdateFlags

/**
 * Remote flags a test decides, defaulting to the shipped behaviour — every switch off.
 *
 * `refresh` is a no-op: the real one reaches Firebase, and no test wants that. It counts calls so
 * one can assert that startup asks at all.
 */
class FakeUpdateFlags(var legacySignIn: Boolean = false) : UpdateFlags {
    var refreshes = 0

    override fun forceLegacySignIn() = legacySignIn

    override fun refresh() {
        refreshes++
    }
}
