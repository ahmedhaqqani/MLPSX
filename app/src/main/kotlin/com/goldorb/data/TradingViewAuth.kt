package com.goldorb.data

import com.goldorb.core.LoopbackRedirectServer
import com.goldorb.core.OAuthFlow
import com.goldorb.core.Pkce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/** Signs the user in to the TradingView MCP server with OAuth (PKCE) and keeps the token fresh. */
class TradingViewAuth(
    private val settings: SettingsRepository,
    http: OkHttpClient,
) {
    private val flow = OAuthFlow(http)
    private val refreshLock = Mutex()

    /**
     * Runs the whole sign-in: registers the app with the server, opens the TradingView
     * login page via [openBrowser], waits for the browser to come back to a temporary
     * http://127.0.0.1 address on this phone, then exchanges the code for tokens.
     */
    suspend fun login(openBrowser: suspend (String) -> Unit) {
        val s = settings.current()
        require(s.mcpUrl.isNotBlank()) { "Enter the TradingView MCP server URL first" }
        LoopbackRedirectServer().use { server ->
            val redirect = server.redirectUri
            val config = flow.prepare(s.mcpUrl, redirect, "Gold ORB (Android)", s.clientId.ifBlank { null })
            val verifier = Pkce.newVerifier()
            val state = Pkce.newState()
            settings.savePendingLogin(config, verifier, state)
            openBrowser(flow.authorizeUrl(config, redirect, Pkce.challenge(verifier), state))

            val params = server.awaitCallback(LOGIN_TIMEOUT_MS) { resultPage(it) }
            params["error"]?.let { throw IllegalStateException(params["error_description"] ?: it) }
            check(params["state"] == state) { "Sign-in state mismatch, please try again" }
            val code = params["code"] ?: error("No authorization code in redirect")
            settings.saveTokens(flow.exchangeCode(config, code, verifier, redirect, nowEpoch()))
        }
    }

    /** Current bearer token, refreshed first if it is about to expire. */
    suspend fun accessToken(): String? {
        settings.current().manualToken.takeIf { it.isNotBlank() }?.let { return it }
        val auth = settings.currentAuth()
        val tokens = auth.tokens ?: return null
        if (!tokens.isExpired(nowEpoch())) return tokens.accessToken
        return if (refresh()) settings.currentAuth().tokens?.accessToken else tokens.accessToken
    }

    /** Called after a 401. Returns true when a new token was obtained. */
    suspend fun refresh(): Boolean = refreshLock.withLock {
        val auth = settings.currentAuth()
        val config = auth.config ?: return false
        val refreshToken = auth.tokens?.refreshToken ?: return false
        runCatching { flow.refresh(config, refreshToken, nowEpoch()) }
            .onSuccess { settings.saveTokens(it) }
            .isSuccess
    }

    suspend fun disconnect() = settings.clearAuth()

    private fun nowEpoch() = System.currentTimeMillis() / 1000

    companion object {
        private const val LOGIN_TIMEOUT_MS = 5 * 60_000L

        /** Opens the app again from the browser (handled by MainActivity's goldorb:// filter). */
        private const val RETURN_LINK = "intent://oauth/callback#Intent;scheme=goldorb;package=com.goldorb;end"

        private fun resultPage(params: Map<String, String>): String {
            val ok = params.containsKey("code") && !params.containsKey("error")
            val title = if (ok) "Signed in to TradingView" else "Sign-in failed"
            val detail = if (ok) "You can return to Gold ORB." else htmlEscape(params["error_description"] ?: params["error"] ?: "Unknown error")
            return """<!doctype html><html><head><meta charset="utf-8">
                |<meta name="viewport" content="width=device-width,initial-scale=1">
                |<title>Gold ORB</title>
                |<style>body{background:#0e1116;color:#e6e9ef;font-family:sans-serif;display:flex;flex-direction:column;
                |align-items:center;justify-content:center;min-height:90vh;margin:0 16px;text-align:center}
                |a{margin-top:24px;background:#f5b942;color:#000;padding:14px 28px;border-radius:999px;text-decoration:none;font-weight:600}</style>
                |</head><body><h2>$title</h2><p>$detail</p><a href="$RETURN_LINK">Return to Gold ORB</a>
                |<script>setTimeout(function(){location.href="$RETURN_LINK"},400)</script></body></html>""".trimMargin()
        }

        private fun htmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
