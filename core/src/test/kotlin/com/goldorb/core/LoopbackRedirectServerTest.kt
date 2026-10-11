package com.goldorb.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LoopbackRedirectServerTest {

    private val http = OkHttpClient()

    private suspend fun get(url: String) = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { it.code to it.body!!.string() }
    }

    @Test
    fun capturesCodeAndAnswersBrowser() = runBlocking {
        LoopbackRedirectServer().use { server ->
            assertTrue(server.redirectUri.matches(Regex("""http://127\.0\.0\.1:\d+/callback""")))
            val waiting = async { server.awaitCallback(10_000) { "<p>ok ${it["code"]}</p>" } }
            val base = server.redirectUri.removeSuffix("/callback")
            assertEquals(404, get("$base/favicon.ico").first)
            val (code, body) = get("${server.redirectUri}?code=a%2Bb&state=xyz")
            assertEquals(200, code)
            assertEquals("<p>ok a+b</p>", body)
            assertEquals(mapOf("code" to "a+b", "state" to "xyz"), waiting.await())
        }
    }

    @Test
    fun timesOut() = runBlocking {
        LoopbackRedirectServer().use { server ->
            assertFailsWith<McpException> { server.awaitCallback(1_500) { "" } }
        }
        Unit
    }
}
