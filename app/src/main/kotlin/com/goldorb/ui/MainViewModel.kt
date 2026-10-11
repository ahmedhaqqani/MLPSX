package com.goldorb.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goldorb.container
import com.goldorb.core.OrbUpdater
import com.goldorb.core.SessionConfig
import com.goldorb.core.SessionId
import com.goldorb.core.SessionSchedule
import com.goldorb.data.AppSettings
import com.goldorb.service.MonitorService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val c = app.container

    val snapshots = c.repository.snapshots
    val status = c.repository.status
    val settings: StateFlow<AppSettings> =
        c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val connected: StateFlow<Boolean> =
        combine(c.settings.auth, c.settings.settings) { a, s -> a.tokens != null || s.manualToken.isNotBlank() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val _openUrl = Channel<String>(Channel.BUFFERED)
    val openUrl = _openUrl.receiveAsFlow()

    fun refresh() {
        viewModelScope.launch { c.repository.refresh() }
    }

    /** Refreshes right after every 5-minute close while the screen is visible. */
    suspend fun refreshWhileVisible() {
        while (true) {
            c.repository.refresh()
            val now = Instant.now()
            delay(Duration.between(now, OrbUpdater.nextRefresh(now)).toMillis().coerceAtLeast(5_000))
        }
    }

    fun startMonitoringIfEnabled() {
        viewModelScope.launch { if (c.settings.current().monitoring) MonitorService.start(app) }
    }

    /** Saves the server URL and symbol, then opens the TradingView sign-in page. */
    fun connect(mcpUrl: String, symbol: String) {
        viewModelScope.launch {
            c.settings.update { it.copy(mcpUrl = mcpUrl, symbol = symbol) }
            runCatching { c.auth.beginLogin() }
                .onSuccess { _openUrl.send(it) }
                .onFailure { _messages.send("Couldn't start sign-in: ${it.message}") }
        }
    }

    fun handleRedirect(uri: Uri) {
        viewModelScope.launch {
            runCatching { c.auth.completeLogin(uri) }
                .onSuccess {
                    _messages.send("Connected to TradingView")
                    c.repository.refresh()
                }
                .onFailure { _messages.send("Sign-in failed: ${it.message}") }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            c.auth.disconnect()
            _messages.send("Disconnected from TradingView")
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            val before = c.settings.current()
            c.settings.update(transform)
            val after = c.settings.current()
            if (before.monitoring != after.monitoring) {
                if (after.monitoring) MonitorService.start(app) else MonitorService.stop(app)
            }
            if (before.dataKey != after.dataKey || before.mcpUrl != after.mcpUrl || before.manualToken != after.manualToken) {
                c.repository.clear()
                c.repository.refresh()
            }
        }
    }

    /** Wall-clock time of a session's open/close in the phone's time zone. */
    fun localTime(cfg: SessionConfig, open: Boolean): LocalTime =
        SessionSchedule.toZone(if (open) cfg.open else cfg.close, cfg.zone, ZoneId.systemDefault(), LocalDate.now(cfg.zone))

    /** Stores a time the user picked in their own time zone, converted to the market's zone. */
    fun setSessionTime(id: SessionId, open: Boolean, local: LocalTime) {
        update { s ->
            s.copy(sessions = s.sessions.map { cfg ->
                if (cfg.id != id) return@map cfg
                val market = SessionSchedule.toZone(local, ZoneId.systemDefault(), cfg.zone, LocalDate.now(ZoneId.systemDefault()))
                if (open) cfg.copy(open = market) else cfg.copy(close = market)
            })
        }
    }

    fun resetSessions() = update { it.copy(sessions = SessionConfig.DEFAULTS) }
}
