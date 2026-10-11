package com.goldorb.data

import android.content.Context
import com.goldorb.core.Alert
import com.goldorb.core.EventType
import com.goldorb.core.McpAuthException
import com.goldorb.core.McpClient
import com.goldorb.core.OrbUpdater
import com.goldorb.core.SessionId
import com.goldorb.core.SessionSnapshot
import com.goldorb.core.TradingViewData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.time.Instant

data class RefreshStatus(
    val refreshing: Boolean = false,
    val lastSuccess: Long? = null,
    val error: String? = null,
    val needsLogin: Boolean = false,
)

@Serializable
private data class StoredSnapshots(val dataKey: String, val snapshots: List<SessionSnapshot>)

/** Fetches candles from TradingView, runs the ORB engine and stores/notifies the results. */
class OrbRepository(
    context: Context,
    private val settings: SettingsRepository,
    private val auth: TradingViewAuth,
    private val notifier: Notifier,
    private val http: OkHttpClient,
) {
    private val file = File(context.filesDir, "snapshots.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    private val _snapshots = MutableStateFlow(load()?.snapshots?.associateBy { it.session } ?: emptyMap())
    val snapshots: StateFlow<Map<SessionId, SessionSnapshot>> = _snapshots.asStateFlow()

    private val _status = MutableStateFlow(RefreshStatus())
    val status: StateFlow<RefreshStatus> = _status.asStateFlow()

    private var client: Pair<String, TradingViewData>? = null

    suspend fun refresh() = lock.withLock {
        val s = settings.current()
        if (s.mcpUrl.isBlank()) {
            _status.update { it.copy(error = "Add your TradingView MCP server in Settings", needsLogin = true) }
            return@withLock
        }
        _status.update { it.copy(refreshing = true) }
        try {
            val stored = load()
            val previous = if (stored?.dataKey == s.dataKey) stored.snapshots.associateBy { it.session } else emptyMap()
            val data = dataFor(s.mcpUrl)
            val updater = OrbUpdater { from -> data.fetchBars(s.symbol, "1m", from) }
            val result = try {
                updater.update(s.sessions, previous, Instant.now(), s.profile)
            } catch (e: McpAuthException) {
                if (!auth.refresh()) throw e
                updater.update(s.sessions, previous, Instant.now(), s.profile)
            }
            save(StoredSnapshots(s.dataKey, result.snapshots.values.toList()))
            _snapshots.value = result.snapshots
            _status.value = RefreshStatus(lastSuccess = System.currentTimeMillis())
            result.alerts.filter { wanted(it, s) }.forEach(notifier::signal)
        } catch (e: McpAuthException) {
            _status.value = _status.value.copy(refreshing = false, error = "Connect your TradingView account in Settings", needsLogin = true)
            notifier.loginNeeded()
        } catch (e: Exception) {
            _status.value = _status.value.copy(refreshing = false, error = e.message ?: e.javaClass.simpleName, needsLogin = false)
        }
    }

    /** Drops stored results, e.g. after session times change. */
    suspend fun clear() = lock.withLock {
        withContext(Dispatchers.IO) { file.delete() }
        _snapshots.value = emptyMap()
        client = null
    }

    private fun wanted(alert: Alert, s: AppSettings): Boolean =
        if (alert.event.type == EventType.RANGE_SET) s.notifyRangeSet else s.notifySignals

    private fun dataFor(url: String): TradingViewData {
        client?.takeIf { it.first == url }?.let { return it.second }
        val mcp = McpClient(http, url, accessToken = { auth.accessToken() }, clientName = "GoldORB-Android")
        return TradingViewData(mcp).also { client = url to it }
    }

    private fun load(): StoredSnapshots? = runCatching {
        if (file.exists()) json.decodeFromString(StoredSnapshots.serializer(), file.readText()) else null
    }.getOrNull()

    private suspend fun save(s: StoredSnapshots) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, "snapshots.json.tmp")
        tmp.writeText(json.encodeToString(StoredSnapshots.serializer(), s))
        tmp.renameTo(file)
    }
}
