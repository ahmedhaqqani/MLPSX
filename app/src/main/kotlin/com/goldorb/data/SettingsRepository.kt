package com.goldorb.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.goldorb.core.OAuthConfig
import com.goldorb.core.OAuthFlow
import com.goldorb.core.ProfileSettings
import com.goldorb.core.SessionConfig
import com.goldorb.core.SessionId
import com.goldorb.core.TokenSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime
import java.time.ZoneId

private val Context.dataStore by preferencesDataStore("settings")

data class AppSettings(
    val mcpUrl: String = "",
    val symbol: String = DEFAULT_SYMBOL,
    /** Optional pre-registered OAuth client ID, for servers without dynamic registration. */
    val clientId: String = "",
    /** Optional access token pasted by hand instead of the OAuth sign-in. */
    val manualToken: String = "",
    val sessions: List<SessionConfig> = SessionConfig.DEFAULTS,
    val profileRows: Int = 24,
    val valueAreaPct: Int = 70,
    val notifyRangeSet: Boolean = true,
    val notifySignals: Boolean = true,
    val monitoring: Boolean = true,
) {
    val profile: ProfileSettings get() = ProfileSettings(profileRows, valueAreaPct / 100.0)

    fun session(id: SessionId): SessionConfig = sessions.first { it.id == id }

    /** Changes to any of these make stored session results invalid. */
    val dataKey: String get() = "$DATA_VERSION|$symbol|$profileRows|$valueAreaPct|" +
        sessions.joinToString(";") { "${it.id}:${it.open}-${it.close}@${it.zone.id}" }

    companion object {
        const val DEFAULT_SYMBOL = "OANDA:XAUUSD"

        /** Bump when stored session results gain fields, so finished sessions are recomputed. */
        private const val DATA_VERSION = 2
    }
}

data class AuthState(
    val config: OAuthConfig? = null,
    val tokens: TokenSet? = null,
    val pendingVerifier: String? = null,
    val pendingState: String? = null,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val mcpUrl = stringPreferencesKey("mcp_url")
        val symbol = stringPreferencesKey("symbol")
        val clientId = stringPreferencesKey("client_id")
        val manualToken = stringPreferencesKey("manual_token")
        val rows = intPreferencesKey("vp_rows")
        val vaPct = intPreferencesKey("va_pct")
        val notifyRange = booleanPreferencesKey("notify_range")
        val notifySignals = booleanPreferencesKey("notify_signals")
        val monitoring = booleanPreferencesKey("monitoring")
        val oauthConfig = stringPreferencesKey("oauth_config")
        val tokens = stringPreferencesKey("oauth_tokens")
        val pendingVerifier = stringPreferencesKey("oauth_verifier")
        val pendingState = stringPreferencesKey("oauth_state")
        fun open(id: SessionId) = intPreferencesKey("${id.name}_open")
        fun close(id: SessionId) = intPreferencesKey("${id.name}_close")
        fun zone(id: SessionId) = stringPreferencesKey("${id.name}_zone")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map(::read)

    val auth: Flow<AuthState> = context.dataStore.data.map { p ->
        AuthState(
            config = p[Keys.oauthConfig]?.let { runCatching { OAuthFlow.decodeConfig(it) }.getOrNull() },
            tokens = p[Keys.tokens]?.let { runCatching { OAuthFlow.decodeTokens(it) }.getOrNull() },
            pendingVerifier = p[Keys.pendingVerifier],
            pendingState = p[Keys.pendingState],
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun currentAuth(): AuthState = auth.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = transform(read(p))
            p[Keys.mcpUrl] = s.mcpUrl.trim()
            p[Keys.symbol] = s.symbol.trim().ifEmpty { AppSettings.DEFAULT_SYMBOL }
            p[Keys.clientId] = s.clientId.trim()
            p[Keys.manualToken] = s.manualToken.trim()
            p[Keys.rows] = s.profileRows
            p[Keys.vaPct] = s.valueAreaPct
            p[Keys.notifyRange] = s.notifyRangeSet
            p[Keys.notifySignals] = s.notifySignals
            p[Keys.monitoring] = s.monitoring
            for (cfg in s.sessions) {
                p[Keys.open(cfg.id)] = cfg.open.toSecondOfDay() / 60
                p[Keys.close(cfg.id)] = cfg.close.toSecondOfDay() / 60
                p[Keys.zone(cfg.id)] = cfg.zone.id
            }
        }
    }

    suspend fun savePendingLogin(config: OAuthConfig, verifier: String, state: String) {
        context.dataStore.edit {
            it[Keys.oauthConfig] = OAuthFlow.encode(config)
            it[Keys.pendingVerifier] = verifier
            it[Keys.pendingState] = state
        }
    }

    suspend fun saveTokens(tokens: TokenSet) {
        context.dataStore.edit {
            it[Keys.tokens] = OAuthFlow.encode(tokens)
            it.remove(Keys.pendingVerifier)
            it.remove(Keys.pendingState)
        }
    }

    suspend fun clearAuth() {
        context.dataStore.edit {
            it.remove(Keys.oauthConfig)
            it.remove(Keys.tokens)
            it.remove(Keys.pendingVerifier)
            it.remove(Keys.pendingState)
        }
    }

    private fun read(p: Preferences): AppSettings = AppSettings(
        mcpUrl = p[Keys.mcpUrl].orEmpty(),
        symbol = p[Keys.symbol] ?: AppSettings.DEFAULT_SYMBOL,
        clientId = p[Keys.clientId].orEmpty(),
        manualToken = p[Keys.manualToken].orEmpty(),
        sessions = SessionId.entries.map { id ->
            val d = SessionConfig.default(id)
            SessionConfig(
                id = id,
                open = p[Keys.open(id)]?.let { LocalTime.ofSecondOfDay(it * 60L) } ?: d.open,
                close = p[Keys.close(id)]?.let { LocalTime.ofSecondOfDay(it * 60L) } ?: d.close,
                zone = p[Keys.zone(id)]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: d.zone,
            )
        },
        profileRows = p[Keys.rows] ?: 24,
        valueAreaPct = p[Keys.vaPct] ?: 70,
        notifyRangeSet = p[Keys.notifyRange] ?: true,
        notifySignals = p[Keys.notifySignals] ?: true,
        monitoring = p[Keys.monitoring] ?: true,
    )
}
