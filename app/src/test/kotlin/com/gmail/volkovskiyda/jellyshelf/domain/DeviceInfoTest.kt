package com.gmail.volkovskiyda.jellyshelf.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `MediaBrowser` authorization header. Jellyfin parses it field by field and rejects the login
 * outright if it is malformed, so the shape is worth pinning — and the values come from
 * `Build.MODEL`, which is free-form vendor text.
 */
class DeviceInfoTest {

    private val info =
        DeviceInfo(clientName = "Jellyshelf Android", deviceName = "Spare phone", version = "1.2.3")

    @Test
    fun `builds the four identity fields Jellyfin expects, in order`() {
        assertEquals(
            """MediaBrowser Client="Jellyshelf Android", Device="Spare phone", """ +
                """DeviceId="abc-123", Version="1.2.3"""",
            mediaBrowserAuthHeader(info, "abc-123"),
        )
    }

    /**
     * Since Jellyfin 12 this header is the only carrier for an API call's credential —
     * `X-Emby-Token` and the lowercase `api_key` query parameter both answer 401 — so every
     * authenticated call appends `Token`.
     */
    @Test
    fun `appends the credential as a trailing Token field`() {
        assertEquals(
            """MediaBrowser Client="Jellyshelf Android", Device="Spare phone", """ +
                """DeviceId="abc-123", Version="1.2.3", Token="TOK"""",
            mediaBrowserAuthHeader(info, "abc-123", token = "TOK"),
        )
    }

    /** A blank credential must leave the field off entirely, not send `Token=""`. */
    @Test
    fun `omits the Token field when there is no credential`() {
        listOf(null, "", "  ").forEach { token ->
            val header = mediaBrowserAuthHeader(info, "abc-123", token = token)
            assertFalse(header, header.contains("Token="))
        }
    }

    /** What the media stack sends: the credential alone, since a token identifies its own device. */
    @Test
    fun `builds a token-only header for the media stack`() {
        assertEquals("""MediaBrowser Token="TOK"""", mediaBrowserTokenHeader("TOK"))
    }

    /** A quote would close the field early and corrupt every field after it. */
    @Test
    fun `strips quotes and backslashes that would break the header`() {
        val header = mediaBrowserAuthHeader(info.copy(deviceName = """FP "5"\x"""), "abc-123")

        assertTrue(header, header.contains("""Device="FP 5x""""))
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

    @Test
    fun `prefers the name the owner gave the device`() {
        assertEquals("Spare phone", deviceDisplayName("Spare phone", "Fairphone", "FP5"))
    }

    @Test
    fun `qualifies the model with the manufacturer when there is no owner name`() {
        assertEquals("Fairphone FP5", deviceDisplayName(null, "Fairphone", "FP5"))
        assertEquals("Fairphone FP5", deviceDisplayName("", "Fairphone", "FP5"))
        assertEquals("Fairphone FP5", deviceDisplayName("   ", "Fairphone", "FP5"))
    }

    /** Android pre-fills the setting with the model, which is not an owner choice worth keeping. */
    @Test
    fun `treats an owner name equal to the model as unset`() {
        assertEquals("Fairphone FP5", deviceDisplayName("fp5", "Fairphone", "FP5"))
        assertEquals("Fairphone FP5", deviceDisplayName(" FP5 ", "Fairphone", "FP5"))
    }

    /** Plenty of vendors already put their name in the model; "Xiaomi Xiaomi 14" reads as a bug. */
    @Test
    fun `does not repeat a manufacturer the model already carries`() {
        assertEquals("Xiaomi 14", deviceDisplayName(null, "Xiaomi", "Xiaomi 14"))
        assertEquals("XIAOMI 14", deviceDisplayName(null, "Xiaomi", "XIAOMI 14"))
    }

    /** `Build.MANUFACTURER` is "Fairphone" on a Fairphone but "samsung" on a Galaxy. */
    @Test
    fun `capitalizes a lowercase manufacturer`() {
        assertEquals("Samsung SM-S911B", deviceDisplayName(null, "samsung", "SM-S911B"))
    }
}
