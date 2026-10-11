package com.goldorb.data

import android.net.Uri
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

    /** Discovers the server's OAuth setup, registers the app, and returns the browser URL to open. */
    suspend fun beginLogin(): String {
        val s = settings.current()
        require(s.mcpUrl.isNotBlank()) { "Enter the TradingView MCP server URL first" }
        val config = flow.prepare(s.mcpUrl, REDIRECT_URI, "Gold ORB (Android)", s.clientId.ifBlank { null })
        val verifier = Pkce.newVerifier()
        val state = Pkce.newState()
        settings.savePendingLogin(config, verifier, state)
        return flow.authorizeUrl(config, REDIRECT_URI, Pkce.challenge(verifier), state)
    }

    /** Handles goldorb://oauth/callback?code=…&state=… */
    suspend fun completeLogin(uri: Uri) {
        uri.getQueryParameter("error")?.let { err ->
            throw IllegalStateException(uri.getQueryParameter("error_description") ?: err)
        }
        val code = uri.getQueryParameter("code") ?: error("No authorization code in redirect")
        val auth = settings.currentAuth()
        val config = auth.config ?: error("Sign-in was not started from this app")
        check(auth.pendingState == uri.getQueryParameter("state")) { "Sign-in state mismatch, please try again" }
        val tokens = flow.exchangeCode(config, code, auth.pendingVerifier!!, REDIRECT_URI, nowEpoch())
        settings.saveTokens(tokens)
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
        const val REDIRECT_URI = "goldorb://oauth/callback"
    }
}
