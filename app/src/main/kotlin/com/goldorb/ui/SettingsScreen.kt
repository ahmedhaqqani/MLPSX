package com.goldorb.ui

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldorb.core.SessionConfig
import com.goldorb.data.AppSettings
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Palette.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Background),
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TradingViewSection(vm, settings, connected)
            SessionsSection(vm, settings)
            ProfileSection(vm, settings)
            NotificationSection(vm, settings)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        content()
    }
}

@Composable
private fun Hint(text: String) = Text(text, fontSize = 12.sp, color = Palette.Muted)

@Composable
private fun TradingViewSection(vm: MainViewModel, s: AppSettings, connected: Boolean) {
    var url by rememberSaveable(s.mcpUrl) { mutableStateOf(s.mcpUrl) }
    var symbol by rememberSaveable(s.symbol) { mutableStateOf(s.symbol) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var clientId by rememberSaveable(s.clientId) { mutableStateOf(s.clientId) }
    var token by rememberSaveable(s.manualToken) { mutableStateOf(s.manualToken) }
    val dirty = url != s.mcpUrl || symbol != s.symbol

    Section("TradingView") {
        Hint("Prices come from the TradingView MCP server. Paste its URL, then sign in with your TradingView account.")
        OutlinedTextField(
            url, { url = it.trim() }, Modifier.fillMaxWidth(),
            label = { Text("MCP server URL") }, placeholder = { Text("https://…/mcp") }, singleLine = true,
        )
        OutlinedTextField(symbol, { symbol = it.trim().uppercase() }, Modifier.fillMaxWidth(), label = { Text("Gold symbol") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("OANDA:XAUUSD", "COMEX:GC1!").forEach { option ->
                FilterChip(selected = symbol == option, onClick = { symbol = option }, label = { Text(option, fontSize = 12.sp) })
            }
        }
        Hint("XAUUSD is spot gold (broker tick volume). GC1! is COMEX gold futures (real exchange volume).")
        if (dirty) {
            Button(onClick = { vm.update { it.copy(mcpUrl = url, symbol = symbol) } }) { Text("Save") }
        }

        Text(
            if (connected) "● Connected" else "○ Not connected",
            color = if (connected) Palette.Green else Palette.Muted, fontWeight = FontWeight.Medium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = url.isNotBlank(),
                onClick = { vm.connect(url, symbol) },
            ) { Text(if (connected) "Reconnect" else "Connect TradingView") }
            if (connected) OutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
        }

        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced") }
        if (advanced) {
            Hint("Only needed if the server can't register the app automatically, or if you have an access token.")
            OutlinedTextField(clientId, { clientId = it }, Modifier.fillMaxWidth(), label = { Text("OAuth client ID (optional)") }, singleLine = true)
            OutlinedTextField(
                token, { token = it }, Modifier.fillMaxWidth(),
                label = { Text("Access token (optional)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(onClick = { vm.update { it.copy(clientId = clientId, manualToken = token) } }) { Text("Save advanced") }
        }
    }
}

@Composable
private fun SessionsSection(vm: MainViewModel, s: AppSettings) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val offset = zone.rules.getOffset(Instant.now()).id.replace("Z", "+00:00")
    val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    Section("Session times") {
        Hint("Your time zone: ${zone.id} (UTC$offset), detected automatically. Times are shown in your time and adjust for daylight saving in Tokyo, London and New York.")
        s.sessions.forEach { cfg ->
            val open = vm.localTime(cfg, open = true)
            val close = vm.localTime(cfg, open = false)
            fun pick(isOpen: Boolean, current: LocalTime) {
                TimePickerDialog(context, { _, h, m -> vm.setSessionTime(cfg.id, isOpen, LocalTime.of(h, m)) }, current.hour, current.minute, true).show()
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cfg.id.label, fontWeight = FontWeight.Medium)
                    Hint("${hhmm.format(cfg.open)}–${hhmm.format(cfg.close)} ${cityOf(cfg)} time")
                }
                TextButton(onClick = { pick(true, open) }) { Text("Open ${hhmm.format(open)}") }
                TextButton(onClick = { pick(false, close) }) { Text("Close ${hhmm.format(close)}") }
            }
        }
        Hint("The opening range is the first 30 minutes after each open. A session stops updating at its close and resets when it next opens.")
        TextButton(onClick = vm::resetSessions) { Text("Reset to default times") }
    }
}

private fun cityOf(cfg: SessionConfig): String =
    cfg.zone.id.substringAfterLast('/').replace('_', ' ').ifEmpty { cfg.zone.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }

@Composable
private fun ProfileSection(vm: MainViewModel, s: AppSettings) {
    var rows by remember(s.profileRows) { mutableFloatStateOf(s.profileRows.toFloat()) }
    var va by remember(s.valueAreaPct) { mutableFloatStateOf(s.valueAreaPct.toFloat()) }
    Section("Volume profile") {
        Hint("Built from the same first 30 minutes as the opening range.")
        Text("Rows: ${rows.toInt()}", fontSize = 14.sp)
        Slider(
            value = rows, onValueChange = { rows = it }, valueRange = 12f..60f, steps = 47,
            onValueChangeFinished = { vm.update { it.copy(profileRows = rows.toInt()) } },
        )
        Text("Value area: ${va.toInt()}%", fontSize = 14.sp)
        Slider(
            value = va, onValueChange = { va = it }, valueRange = 50f..90f, steps = 39,
            onValueChangeFinished = { vm.update { it.copy(valueAreaPct = va.toInt()) } },
        )
    }
}

@Composable
private fun NotificationSection(vm: MainViewModel, s: AppSettings) {
    val context = LocalContext.current
    val notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val exactAllowed = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    Section("Notifications & background") {
        SwitchRow("Opening range set", s.notifyRangeSet) { v -> vm.update { it.copy(notifyRangeSet = v) } }
        SwitchRow("Breakout, FAILED and re-break", s.notifySignals) { v -> vm.update { it.copy(notifySignals = v) } }
        SwitchRow("Keep watching in background", s.monitoring) { v -> vm.update { it.copy(monitoring = v) } }
        Hint("Background watching checks after every 5-minute close while a session is open and sleeps between sessions and at weekends.")
        if (!notificationsAllowed) {
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }) { Text("Allow notifications") }
        }
        if (!exactAllowed) {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }) { Text("Allow exact 5-minute timing") }
        }
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }) { Text("Battery optimisation settings") }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
