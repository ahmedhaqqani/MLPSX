package com.goldorb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goldorb.core.EventType
import com.goldorb.core.Phase
import com.goldorb.core.SessionConfig
import com.goldorb.core.SessionId
import com.goldorb.core.SessionSchedule
import com.goldorb.core.SessionSnapshot
import com.goldorb.data.Format
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(vm: MainViewModel, snackbar: SnackbarHostState, onOpenSettings: () -> Unit) {
    val snapshots by vm.snapshots.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Palette.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Background),
                title = {
                    Column {
                        Text("Gold ORB", fontWeight = FontWeight.SemiBold)
                        val updated = status.lastSuccess?.let { "updated ${Format.time(it / 1000)}" } ?: "not updated yet"
                        Text("${settings.symbol} · 5m · $updated", fontSize = 12.sp, color = Palette.Muted)
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "Refresh") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (status.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Palette.Gold)
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                status.error?.let { error ->
                    item {
                        Banner(error, if (status.needsLogin) "Open settings" else null, onOpenSettings)
                    }
                }
                items(SessionId.entries) { id ->
                    SessionCard(settings.session(id), snapshots[id])
                }
                item { Legend() }
            }
        }
    }
}

@Composable
private fun Banner(text: String, action: String?, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x33EF4444)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), fontSize = 13.sp)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun SessionCard(cfg: SessionConfig, snap: SessionSnapshot?) {
    val zone = ZoneId.systemDefault()
    val dotColor = snap?.let { Palette.of(it.dot) } ?: Palette.Grey
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Card)
            .border(1.dp, Palette.Line, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(cfg.id.label.uppercase(), fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 0.5.sp)
                Text(timeLine(cfg, snap, zone), fontSize = 11.5.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (snap != null) {
                Text(
                    Format.statusLabel(snap),
                    Modifier.clip(RoundedCornerShape(50)).background(dotColor.copy(alpha = 0.15f)).padding(horizontal = 9.dp, vertical = 4.dp),
                    color = dotColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.5.sp,
                )
            }
        }

        if (snap == null || (snap.orHigh == null && snap.phase != Phase.FORMING)) {
            Text(
                if (snap == null) "Waiting for data" else "No candles from TradingView for this session",
                Modifier.padding(top = 10.dp), color = Palette.Muted, fontSize = 13.sp,
            )
            return@Column
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("OR High", Format.price(snap.orHigh), Modifier.weight(1f))
            Stat("VAH", Format.price(snap.vah), Modifier.weight(1f))
            Stat("POC", Format.price(snap.poc), Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("OR Low", Format.price(snap.orLow), Modifier.weight(1f))
            Stat("VAL", Format.price(snap.valueAreaLow), Modifier.weight(1f))
            Stat("Last 5m close", Format.price(snap.lastClose), Modifier.weight(1f))
        }

        if (snap.phase == Phase.FORMING) {
            Text(
                "Opening range forms until ${Format.time(snap.rangeEndEpoch, zone)}",
                Modifier.padding(top = 8.dp), fontSize = 12.sp, color = Palette.Muted,
            )
        } else {
            Text(Format.zoneLabel(snap.zone), Modifier.padding(top = 8.dp), fontSize = 12.sp, color = Palette.Muted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Count("Above", snap.above, Palette.Green, Modifier.weight(1f))
                Count("Inside", snap.inside, Palette.Orange, Modifier.weight(1f))
                Count("Below", snap.below, Palette.Red, Modifier.weight(1f))
            }
            snap.events.filter { it.type != EventType.RANGE_SET }.takeLast(3).reversed().forEach { e ->
                val failed = e.type == EventType.FAILED_UP || e.type == EventType.FAILED_DOWN
                Text(
                    "${if (failed) "⚠ " else ""}${e.type.title} · ${Format.price(e.price)} at ${Format.time(e.time + 300, zone)}",
                    Modifier.padding(top = 6.dp),
                    fontSize = 12.sp,
                    color = if (failed) Palette.Purple else Palette.Muted,
                )
            }
        }
        MiniChart(snap)
    }
}

private fun timeLine(cfg: SessionConfig, snap: SessionSnapshot?, zone: ZoneId): String {
    val (open, close) = if (snap != null) {
        snap.openEpoch to snap.closeEpoch
    } else {
        val w = SessionSchedule.latestWindow(cfg, Instant.now())
        w.open.epochSecond to w.close.epochSecond
    }
    val today = LocalDate.now(zone)
    val date = Instant.ofEpochSecond(open).atZone(zone).toLocalDate()
    val prefix = if (date == today) "Today" else Format.day(open, zone)
    val suffix = when (snap?.phase) {
        Phase.CLOSED -> " · closed"
        Phase.FORMING -> " · range forming"
        else -> ""
    }
    return "$prefix ${Format.time(open, zone)}–${Format.time(close, zone)}$suffix"
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label.uppercase(), fontSize = 10.5.sp, color = Palette.Muted, letterSpacing = 0.5.sp, maxLines = 1)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Count(label: String, value: Int, color: Color, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Background)
            .border(1.dp, Palette.Line, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(label.uppercase(), fontSize = 10.5.sp, color = Palette.Muted, letterSpacing = 0.5.sp)
        Text(value.toString(), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun MiniChart(snap: SessionSnapshot) {
    val candles = snap.candles
    if (candles.isEmpty()) return
    Canvas(Modifier.fillMaxWidth().padding(top = 10.dp).height(96.dp)) {
        val hi = maxOf(candles.maxOf { it.h }, snap.orHigh ?: -Double.MAX_VALUE)
        val lo = minOf(candles.minOf { it.l }, snap.orLow ?: Double.MAX_VALUE)
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun y(v: Double) = (4f + (hi - v) / span * (size.height - 8f)).toFloat()

        val slots = maxOf(candles.size, 24)
        val slot = size.width / slots
        val body = (slot * 0.6f).coerceIn(1.5f, 10f)
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))

        // Shade the 30-minute opening-range period.
        val rangeCandles = candles.count { it.t < snap.rangeEndEpoch }
        drawRect(Color.White.copy(alpha = 0.04f), Offset.Zero, Size(rangeCandles * slot, size.height))

        snap.orHigh?.let { orH ->
            val orL = snap.orLow!!
            drawRect(Palette.Muted.copy(alpha = 0.10f), Offset(0f, y(orH)), Size(size.width, y(orL) - y(orH)))
            listOf(orH, orL).forEach {
                drawLine(Palette.Muted, Offset(0f, y(it)), Offset(size.width, y(it)), 1.5f, pathEffect = dash)
            }
        }
        if (snap.vah != null && snap.valueAreaLow != null) {
            drawRect(Palette.ValueArea.copy(alpha = 0.15f), Offset(0f, y(snap.vah!!)), Size(size.width, y(snap.valueAreaLow!!) - y(snap.vah!!)))
            listOf(snap.vah!!, snap.valueAreaLow!!).forEach {
                drawLine(Palette.ValueArea, Offset(0f, y(it)), Offset(size.width, y(it)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)))
            }
        }

        candles.forEachIndexed { i, c ->
            val x = (i + 0.5f) * slot
            val color = if (c.c >= c.o) Palette.Green else Palette.Red
            drawLine(color, Offset(x, y(c.h)), Offset(x, y(c.l)), 1.5f)
            val top = y(maxOf(c.o, c.c))
            val height = maxOf(1.5f, y(minOf(c.o, c.c)) - top)
            drawRect(color, Offset(x - body / 2, top), Size(body, height))
        }
    }
}

@Composable
private fun Legend() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        LegendItem("Above", Palette.Green)
        LegendItem("> VAH", Palette.LightGreen)
        LegendItem("Inside", Palette.Orange)
        LegendItem("< VAL", Palette.LightRed)
        LegendItem("Below", Palette.Red)
        LegendItem("Failed", Palette.Purple)
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 10.5.sp, color = Palette.Muted)
    }
}
