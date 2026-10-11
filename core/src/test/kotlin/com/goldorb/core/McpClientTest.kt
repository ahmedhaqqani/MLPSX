package com.goldorb.core

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class McpClientTest {

    private val barsJson =
        """{"bars":[{"c":4195.095,"h":4195.605,"l":4194.67,"o":4194.675,"t":1791579300,"v":280},""" +
            """{"c":4195.36,"h":4195.435,"l":4194.805,"o":4195.075,"t":1791579360,"v":226}],"count":2,"has_more":false,"success":true}"""

    private fun server(requireToken: String? = "tok"): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (requireToken != null && request.getHeader("Authorization") != "Bearer $requireToken") {
                    return MockResponse().setResponseCode(401)
                        .setHeader("WWW-Authenticate", """Bearer resource_metadata="https://x/.well-known/oauth-protected-resource"""")
                }
                val body = request.body.readUtf8()
                val id = Regex("\"id\":(\\d+)").find(body)?.groupValues?.get(1)
                return when {
                    "\"initialize\"" in body -> MockResponse()
                        .setHeader("Mcp-Session-Id", "s1")
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{}}}""")
                    "notifications/initialized" in body -> MockResponse().setResponseCode(202)
                    "tools/list" in body -> MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[{\"name\":\"mcp-tv-search-symbols\"},{\"name\":\"mcp-tv-get-ohlcv\"}]}}\n\n")
                    "tools/call" in body -> {
                        check(request.getHeader("Mcp-Session-Id") == "s1")
                        val text = barsJson.replace("\"", "\\\"")
                        MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody("data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"$text\"}]}}\n\n")
                    }
                    else -> MockResponse().setResponseCode(400)
                }
            }
        }
        start()
    }

    @Test
    fun fetchesBarsThroughMcp() = runTest {
        val srv = server()
        val mcp = McpClient(OkHttpClient(), srv.url("/mcp").toString(), { "tok" })
        val data = TradingViewData(mcp)
        val bars = data.fetchBars("OANDA:XAUUSD", "1m", Instant.ofEpochSecond(1791579000))
        assertEquals(2, bars.size)
        assertEquals(4195.36, bars[1].c)
        assertEquals("mcp-tv-get-ohlcv", data.ohlcvToolName())
        srv.shutdown()
    }

    @Test
    fun unauthorizedRaisesAuthException() = runTest {
        val srv = server()
        val mcp = McpClient(OkHttpClient(), srv.url("/mcp").toString(), { null })
        val e = assertFailsWith<McpAuthException> { mcp.listTools() }
        assertEquals("https://x/.well-known/oauth-protected-resource", OAuthFlow.resourceMetadataUrl(e.wwwAuthenticate!!))
        srv.shutdown()
    }

    @Test
    fun findsOhlcvToolUnderDifferentNames() {
        assertEquals("get_ohlcv", TradingViewData.findOhlcvTool(listOf("search", "get_ohlcv")))
        assertEquals("mcp-tv-get-ohlcv", TradingViewData.findOhlcvTool(listOf("mcp-tv-get-ohlcv")))
    }

    @Test
    fun pkceMatchesRfc7636Example() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun wellKnownUrls() {
        val u = "https://mcp.example.com/tv/mcp".toHttpUrl()
        assertEquals("https://mcp.example.com/.well-known/oauth-protected-resource/tv/mcp", OAuthFlow.wellKnown(u, "oauth-protected-resource", true))
        assertEquals("https://mcp.example.com/.well-known/oauth-protected-resource", OAuthFlow.wellKnown(u, "oauth-protected-resource", false))
    }

    @Test
    fun oauthDiscoveryRegistrationAndExchange() = runTest {
        val srv = MockWebServer()
        val base = srv.url("/").toString().trimEnd('/')
        srv.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val json = MockResponse().setHeader("Content-Type", "application/json")
                return when (request.path) {
                    "/mcp" -> MockResponse().setResponseCode(401)
                        .setHeader("WWW-Authenticate", """Bearer resource_metadata="$base/.well-known/oauth-protected-resource/mcp", scope="read"""")
                    "/.well-known/oauth-protected-resource/mcp" ->
                        json.setBody("""{"resource":"$base/mcp","authorization_servers":["$base"]}""")
                    "/.well-known/oauth-authorization-server" -> json.setBody(
                        """{"issuer":"$base","authorization_endpoint":"$base/authorize","token_endpoint":"$base/token","registration_endpoint":"$base/register"}"""
                    )
                    "/register" -> json.setBody("""{"client_id":"cid-123"}""")
                    "/token" -> {
                        val form = request.body.readUtf8()
                        check("code_verifier=v" in form && "client_id=cid-123" in form)
                        json.setBody("""{"access_token":"AT","refresh_token":"RT","expires_in":3600,"token_type":"Bearer"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val flow = OAuthFlow(OkHttpClient())
        val cfg = flow.prepare("$base/mcp", "goldorb://oauth/callback", "GoldORB", null)
        assertEquals("cid-123", cfg.client.clientId)
        assertEquals("read", cfg.scope)
        val url = flow.authorizeUrl(cfg, "goldorb://oauth/callback", "ch", "st").toHttpUrl()
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("$base/mcp", url.queryParameter("resource"))
        val tokens = flow.exchangeCode(cfg, "code", "v", "goldorb://oauth/callback", nowEpoch = 1000)
        assertEquals(TokenSet("AT", "RT", 4600), tokens)
        assertEquals(cfg, OAuthFlow.decodeConfig(OAuthFlow.encode(cfg)))
        srv.shutdown()
    }
}
