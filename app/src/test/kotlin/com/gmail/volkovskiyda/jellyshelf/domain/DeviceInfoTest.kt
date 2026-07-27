package com.gmail.volkovskiyda.jellyshelf.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `MediaBrowser` authorization header. Jellyfin parses it field by field and rejects the login
 * outright if it is malformed, so the shape is worth pinning — and the values come from
 * `Build.MODEL`, which is free-form vendor text.
 */
class DeviceInfoTest {

    private val info = DeviceInfo(clientName = "Jellyshelf", deviceName = "Pixel 5", version = "1.2.3")

    @Test
    fun `builds the four fields Jellyfin expects, in order`() {
        assertEquals(
            """MediaBrowser Client="Jellyshelf", Device="Pixel 5", DeviceId="abc-123", Version="1.2.3"""",
            mediaBrowserAuthHeader(info, "abc-123"),
        )
    }

    /** A quote would close the field early and corrupt every field after it. */
    @Test
    fun `strips quotes and backslashes that would break the header`() {
        val header = mediaBrowserAuthHeader(info.copy(deviceName = """Pixel "5"\x"""), "abc-123")

        assertTrue(header, header.contains("""Device="Pixel 5x""""))
        assertEquals(8, header.count { it == '"' }) // exactly the four field delimiters
    }

    /** Header values must be ASCII; vendor device names are not guaranteed to be. */
    @Test
    fun `drops non-ascii characters from a vendor device name`() {
        val header = mediaBrowserAuthHeader(info.copy(deviceName = "Xiaomi 红米 K30"), "abc-123")

        assertTrue(header, header.contains("""Device="Xiaomi  K30""""))
        assertTrue(header, header.all { it.code in 0x20..0x7E })
    }

    /** A name that sanitizes away entirely must not leave an empty field. */
    @Test
    fun `falls back to a placeholder when nothing printable survives`() {
        val header = mediaBrowserAuthHeader(info.copy(deviceName = "红米"), "abc-123")

        assertTrue(header, header.contains("""Device="unknown""""))
    }
}
