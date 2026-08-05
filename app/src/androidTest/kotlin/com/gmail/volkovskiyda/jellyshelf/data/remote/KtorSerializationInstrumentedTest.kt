package com.gmail.volkovskiyda.jellyshelf.data.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gmail.volkovskiyda.jellyshelf.di.provideJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
import org.junit.runner.RunWith

/**
 * Runs the migrated kotlinx.serialization + Ktor ContentNegotiation path inside a real APK process
 * (ART, not the JVM), against a MockEngine so it needs no server. This proves the DTO
 * (de)serialization and request-body wire fidelity hold on-device — a lighter stand-in for the
 * R8/keep-rule validation that only a release build fully confirms.
 */
@RunWith(AndroidJUnit4::class)
class KtorSerializationInstrumentedTest {

    private fun api(responseBody: String, captureBody: (String) -> Unit = {}): JellyfinApi {
        val engine = MockEngine { request ->
            captureBody(request.body.toByteArray().decodeToString())
            respond(responseBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(provideJson()) }
        }
        return JellyfinClient(client).create("http://server:8096", "APIKEY")
    }

    @Test
    fun decodesFatItemsPayloadWithUnknownKeys() = runTest {
        val fat = """
            {"Items":[{"Id":"1","Name":"A","UnknownField":9,
              "UserData":{"Played":true,"PlaybackPositionTicks":7,"Extra":"y"}}],
             "TotalRecordCount":1,"UnmodeledTopLevel":true}
        """.trimIndent()

        val resp = api(fat).getItems(userId = "u1")
        assertEquals(1, resp.items.size)
        assertEquals("A", resp.items[0].name)
        assertEquals(true, resp.items[0].userData?.played)
        assertEquals(7L, resp.items[0].userData?.playbackPositionTicks)
    }

    @Test
    fun progressBodyKeepsDefaultsOnDevice() = runTest {
        var body = ""
        api(responseBody = "", captureBody = { body = it })
            .reportProgress(ProgressBody(itemId = "vid1", positionTicks = 3L))

        val obj = Json.Default.parseToJsonElement(body).jsonObject
        assertTrue(obj.containsKey("IsPaused"))
        assertEquals(false, obj["IsPaused"]?.jsonPrimitive?.boolean)
        assertEquals("DirectPlay", obj["PlayMethod"]?.jsonPrimitive?.content)
    }

    @Test
    fun progressBodyCarriesAnExplicitPausedFlagOnDevice() = runTest {
        var body = ""
        api(responseBody = "", captureBody = { body = it })
            .reportProgress(ProgressBody(itemId = "vid1", positionTicks = 3L, isPaused = true))

        val obj = Json.Default.parseToJsonElement(body).jsonObject
        assertEquals(true, obj["IsPaused"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun playbackStartBodyKeepsDefaultsOnDevice() = runTest {
        var body = ""
        api(responseBody = "", captureBody = { body = it })
            .reportPlaybackStart(PlaybackStartBody(itemId = "vid1", positionTicks = 10_000_000L))

        val obj = Json.Default.parseToJsonElement(body).jsonObject
        assertEquals("vid1", obj["ItemId"]?.jsonPrimitive?.content)
        assertEquals(10_000_000L, obj["PositionTicks"]?.jsonPrimitive?.long)
        assertEquals("DirectPlay", obj["PlayMethod"]?.jsonPrimitive?.content)
        assertTrue(obj.containsKey("CanSeek"))
        assertEquals(true, obj["CanSeek"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun playbackStopBodyWritesPascalCaseOnDevice() = runTest {
        var body = ""
        api(responseBody = "", captureBody = { body = it })
            .reportPlaybackStopped(PlaybackStopBody(itemId = "vid1", positionTicks = 42L))

        val obj = Json.Default.parseToJsonElement(body).jsonObject
        assertEquals("vid1", obj["ItemId"]?.jsonPrimitive?.content)
        assertEquals(42L, obj["PositionTicks"]?.jsonPrimitive?.long)
        // explicitNulls=false on device too: no session id, no field.
        assertFalse(obj.containsKey("PlaySessionId"))
    }

    @Test
    fun playSessionIdSurvivesOnDevice() = runTest {
        var body = ""
        api(responseBody = "", captureBody = { body = it })
            .reportProgress(ProgressBody(itemId = "vid1", positionTicks = 3L, playSessionId = "ps-1"))

        val obj = Json.Default.parseToJsonElement(body).jsonObject
        assertEquals("ps-1", obj["PlaySessionId"]?.jsonPrimitive?.content)
    }

    @Test
    fun userItemDataOmitsNullButKeepsDefaultOnDevice() = runTest {
        var body = ""
        api(responseBody = "", captureBody = { body = it })
            .updateUserData("u1", "vid1", UserItemDataBody(playbackPositionTicks = 5L))

        val obj = Json.Default.parseToJsonElement(body).jsonObject
        assertFalse(obj.containsKey("LastPlayedDate"))
        assertTrue(obj.containsKey("Played"))
        assertEquals(false, obj["Played"]?.jsonPrimitive?.boolean)
    }
}
