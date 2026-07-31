package com.gmail.volkovskiyda.jellyshelf.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether a sign-in is the magic demo one. They matter more than their size
 * suggests: they run *before* `normalizeServerUrl` and before any Ktor call, so getting them wrong
 * either fires a real request at a host called `jellyfin` or, worse, swallows a real server's
 * sign-in and quietly seeds a demo over it.
 *
 * Free functions, so this needs no `Application` and no device.
 */
class DemoCredentialsTest {

    @Test
    fun `the demo server is matched as typed, trimmed and case-insensitively`() {
        assertTrue(isDemoServer("jellyfin"))
        assertTrue(isDemoServer("Jellyfin"))
        assertTrue(isDemoServer("JELLYFIN"))
        assertTrue("keyboards add spaces", isDemoServer("  jellyfin "))
    }

    @Test
    fun `a real server that merely mentions jellyfin is not the demo`() {
        assertFalse(isDemoServer("https://jellyfin.example.org"))
        assertFalse(isDemoServer("jellyfin.local"))
        assertFalse(isDemoServer("http://jellyfin"))
        assertFalse(isDemoServer(""))
    }

    @Test
    fun `the demo sign-in needs the demo user as well as the demo server`() {
        assertTrue(isDemoSignIn("jellyfin", "demo"))
        assertTrue(isDemoSignIn(" JELLYFIN ", " Demo "))
        assertFalse("another user on the demo server is not a demo sign-in", isDemoSignIn("jellyfin", "alice"))
        assertFalse("the demo user on a real server is a real sign-in", isDemoSignIn("https://example.org", "demo"))
    }

    @Test
    fun `only the one password demonstrates the failure state`() {
        assertTrue(isDemoAuthFailure("incorrect"))
        assertTrue(isDemoAuthFailure("Incorrect"))
        assertFalse(isDemoAuthFailure("correct"))
        assertFalse(isDemoAuthFailure("incorrecto"))
        assertFalse("anything else enters the demo", isDemoAuthFailure("hunter2"))
    }
}
