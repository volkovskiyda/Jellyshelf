package com.gmail.volkovskiyda.jellyshelf.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class PrunePolicyTest {

    @Test
    fun `a full listing prunes with the grace period`() {
        assertEquals(Prune.GRACE, prunePolicy(scopeChanged = false, storedCount = 100, seenCount = 100))
    }

    @Test
    fun `a listing missing a few videos still prunes with the grace period`() {
        assertEquals(Prune.GRACE, prunePolicy(scopeChanged = false, storedCount = 100, seenCount = 97))
    }

    @Test
    fun `exactly half the stored videos is still trusted`() {
        assertEquals(Prune.GRACE, prunePolicy(scopeChanged = false, storedCount = 100, seenCount = 50))
    }

    @Test
    fun `a listing under half the stored videos prunes nothing`() {
        assertEquals(Prune.NOTHING, prunePolicy(scopeChanged = false, storedCount = 100, seenCount = 49))
    }

    @Test
    fun `an empty listing over a stocked library prunes nothing`() {
        assertEquals(Prune.NOTHING, prunePolicy(scopeChanged = false, storedCount = 100, seenCount = 0))
    }

    @Test
    fun `the first sync into an empty database prunes with the grace period`() {
        assertEquals(Prune.GRACE, prunePolicy(scopeChanged = false, storedCount = 0, seenCount = 0))
    }

    @Test
    fun `a narrowed library scope deletes everything outside it at once`() {
        assertEquals(Prune.IMMEDIATE, prunePolicy(scopeChanged = true, storedCount = 100, seenCount = 3))
    }
}
