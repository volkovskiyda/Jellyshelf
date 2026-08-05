package com.gmail.volkovskiyda.jellyshelf.data.remote

import com.gmail.volkovskiyda.jellyshelf.data.repository.isPermanentFailure
import com.gmail.volkovskiyda.jellyshelf.di.provideJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MockEngine-backed unit tests for the Ktor migration. They build an [HttpClient] on Ktor's
 * MockEngine with the real [provideJson] config + ContentNegotiation + `expectSuccess = true`, hand
 * it to the actual [JellyfinClient]/[JellyfinApi], and assert on the captured outgoing request and a
 * stubbed response — no device, no server. This locks in the two things the migration changed: the
 * request-body producer (Moshi -> kotlinx.serialization) and HTTP call construction (Retrofit -> Ktor).
 */
class JellyfinApiTest {

    private val parser = Json.Default

    private class Captured {
        lateinit var request: HttpRequestData
        var body: String = ""
    }

    /**
     * A [JellyfinApi] wired to a MockEngine that records the outgoing request/body and replies with
     * [responseBody] at [status]. Uses [provideJson] so the wire-fidelity assertions guard the real
     * production Json config, not a copy.
     */
    private fun mockApi(
        baseUrl: String = "http://server:8096/",
        apiKey: String = "APIKEY",
        status: HttpStatusCode = HttpStatusCode.OK,
        responseBody: String = "",
    ): Pair<JellyfinApi, Captured> {
        val captured = Captured()
        val engine = MockEngine { request ->
            captured.request = request
            captured.body = request.body.toByteArray().decodeToString()
            respond(
                content = responseBody,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val base = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(provideJson()) }
        }
        return JellyfinClient(base).create(baseUrl, apiKey) to captured
    }

    // --- 1. Request wire-fidelity (guards encodeDefaults=true / explicitNulls=false) ---

    @Test
    fun reportProgress_keepsPausedAndPlayMethodDefaultsOnTheWire() = runTest {
        val (api, cap) = mockApi()
        api.reportProgress(ProgressBody(itemId = "vid1", positionTicks = 100L))

        val body = parser.parseToJsonElement(cap.body).jsonObject
        assertEquals("vid1", body["ItemId"]?.jsonPrimitive?.content)
        assertEquals(100L, body["PositionTicks"]?.jsonPrimitive?.long)
        // encodeDefaults=true: Moshi always serialised these non-null defaults, so Ktor must too.
        // `false` is the default because progress is reported while playing; a pause passes it explicitly.
        assertTrue(body.containsKey("IsPaused"))
        assertEquals(false, body["IsPaused"]?.jsonPrimitive?.boolean)
        assertTrue(body.containsKey("PlayMethod"))
        assertEquals("DirectPlay", body["PlayMethod"]?.jsonPrimitive?.content)
    }

    @Test
    fun sessionStartAndStop_writePascalCaseFieldsAndDefaults() = runTest {
        val (startApi, startCap) = mockApi()
        startApi.reportPlaybackStart(PlaybackStartBody(itemId = "vid1", positionTicks = 0L))

        val start = parser.parseToJsonElement(startCap.body).jsonObject
        assertEquals("vid1", start["ItemId"]?.jsonPrimitive?.content)
        assertEquals(0L, start["PositionTicks"]?.jsonPrimitive?.long)
        assertEquals("DirectPlay", start["PlayMethod"]?.jsonPrimitive?.content)
        assertEquals(true, start["CanSeek"]?.jsonPrimitive?.boolean)

        val (stopApi, stopCap) = mockApi()
        stopApi.reportPlaybackStopped(PlaybackStopBody(itemId = "vid1", positionTicks = 900L))

        val stop = parser.parseToJsonElement(stopCap.body).jsonObject
        assertEquals("vid1", stop["ItemId"]?.jsonPrimitive?.content)
        assertEquals(900L, stop["PositionTicks"]?.jsonPrimitive?.long)
    }

    @Test
    fun createPlaylist_keepsMediaTypeDefaultOnTheWire() = runTest {
        val (api, cap) = mockApi(responseBody = """{"Id":"pl1"}""")
        val result = api.createPlaylist(CreatePlaylistBody(name = "N", ids = listOf("a", "b"), userId = "u1"))

        assertEquals("pl1", result.id)
        val body = parser.parseToJsonElement(cap.body).jsonObject
        assertEquals("Video", body["MediaType"]?.jsonPrimitive?.content)
        assertEquals("N", body["Name"]?.jsonPrimitive?.content)
        assertEquals("u1", body["UserId"]?.jsonPrimitive?.content)
    }

    @Test
    fun updateUserData_omitsNullLastPlayedButKeepsPlayedDefault() = runTest {
        val (api, cap) = mockApi()
        api.updateUserData("u1", "vid1", UserItemDataBody(playbackPositionTicks = 500L))

        val body = parser.parseToJsonElement(cap.body).jsonObject
        assertEquals(500L, body["PlaybackPositionTicks"]?.jsonPrimitive?.long)
        // explicitNulls=false: a null property drops out of the JSON, as Moshi did.
        assertFalse(body.containsKey("LastPlayedDate"))
        // encodeDefaults=true: the default `played = false` still ships.
        assertTrue(body.containsKey("Played"))
        assertEquals(false, body["Played"]?.jsonPrimitive?.boolean)
    }

    // --- 2. URL / param / header construction ---

    @Test
    fun getUsers_normalizesBaseUrlAndSendsAuthAndAcceptHeaders() = runTest {
        // Base URL without a trailing slash must normalize so "Users" resolves under it.
        val (api, cap) = mockApi(baseUrl = "http://server:8096", responseBody = "[]")
        api.getUsers()

        assertEquals("http://server:8096/Users", cap.request.url.toString())
        assertEquals("APIKEY", cap.request.headers["X-Emby-Token"])
        assertEquals("application/json", cap.request.headers[HttpHeaders.Accept])
    }

    @Test
    fun getItem_putsIdInThePathAndUserIdInTheQuery() = runTest {
        val item = """{"Id":"vid1","Name":"A","UserData":{"Played":true,"PlaybackPositionTicks":0}}"""
        val (api, cap) = mockApi(responseBody = item)

        val decoded = api.getItem(userId = "u1", itemId = "vid1")

        assertEquals("http://server:8096/Items/vid1?userId=u1", cap.request.url.toString())
        assertEquals(true, decoded.userData?.played)
        assertEquals(0L, decoded.userData?.playbackPositionTicks)
    }

    @Test
    fun getItems_nullParentIdOmitsParam_nonNullIncludesIt() = runTest {
        val emptyItems = """{"Items":[],"TotalRecordCount":0}"""

        val (apiNull, capNull) = mockApi(responseBody = emptyItems)
        apiNull.getItems(userId = "u1", parentId = null)
        assertEquals("u1", capNull.request.url.parameters["userId"])
        assertFalse(capNull.request.url.parameters.contains("ParentId"))

        val (apiSet, capSet) = mockApi(responseBody = emptyItems)
        apiSet.getItems(userId = "u1", parentId = "folder1")
        assertEquals("folder1", capSet.request.url.parameters["ParentId"])
    }

    // --- 3. Response decode (guards ignoreUnknownKeys) ---

    @Test
    fun getItems_decodesFatPayloadWithUnknownKeys() = runTest {
        val fat = """
            {"Items":[{"Id":"1","Name":"A","ExtraUnknownField":123,
              "Nested":{"x":1},
              "UserData":{"Played":true,"PlaybackPositionTicks":42,"UnknownUserField":"z"}}],
             "TotalRecordCount":1,"UnmodeledTopLevel":"ignored"}
        """.trimIndent()
        val (api, _) = mockApi(responseBody = fat)

        val resp = api.getItems(userId = "u1")
        assertEquals(1, resp.items.size)
        assertEquals("A", resp.items[0].name)
        assertEquals(true, resp.items[0].userData?.played)
        assertEquals(42L, resp.items[0].userData?.playbackPositionTicks)
    }

    // --- 4. Error mapping (item 06: expectSuccess -> Response exceptions -> isPermanentFailure) ---

    @Test
    fun status401_throwsClientRequestException_classifiedPermanent() = runTest {
        val (api, _) = mockApi(status = HttpStatusCode.Unauthorized, responseBody = "unauthorized")
        val ex = runCatching { api.getUsers() }.exceptionOrNull()
        assertTrue("expected ClientRequestException, got $ex", ex is ClientRequestException)
        assertTrue(isPermanentFailure(ex!!))
    }

    @Test
    fun status500_throwsServerResponseException_classifiedTransient() = runTest {
        val (api, _) = mockApi(status = HttpStatusCode.InternalServerError, responseBody = "boom")
        val ex = runCatching { api.getUsers() }.exceptionOrNull()
        assertTrue("expected ServerResponseException, got $ex", ex is ServerResponseException)
        assertFalse(isPermanentFailure(ex!!))
    }
}
