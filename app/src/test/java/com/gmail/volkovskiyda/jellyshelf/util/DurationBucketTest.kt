package com.gmail.volkovskiyda.jellyshelf.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationBucketTest {

    @Test
    fun `unknown durations fall into no bucket`() {
        assertNull(DurationBucket.of(0))
        assertNull(DurationBucket.of(-10))
    }

    @Test
    fun `boundaries are half-open`() {
        assertEquals(DurationBucket.UNDER_10, DurationBucket.of(1))
        assertEquals(DurationBucket.UNDER_10, DurationBucket.of(10 * 60 - 1))
        assertEquals(DurationBucket.FROM_10_TO_30, DurationBucket.of(10 * 60))
        assertEquals(DurationBucket.FROM_10_TO_30, DurationBucket.of(30 * 60 - 1))
        assertEquals(DurationBucket.FROM_30_TO_60, DurationBucket.of(30 * 60))
        assertEquals(DurationBucket.FROM_30_TO_60, DurationBucket.of(60 * 60 - 1))
        assertEquals(DurationBucket.OVER_60, DurationBucket.of(60 * 60))
        assertEquals(DurationBucket.OVER_60, DurationBucket.of(Long.MAX_VALUE - 1))
    }

    @Test
    fun `bucket ids sort in duration order`() {
        val ids = DurationBucket.entries.map { it.id }
        assertEquals(ids.sorted(), ids)
    }
}
