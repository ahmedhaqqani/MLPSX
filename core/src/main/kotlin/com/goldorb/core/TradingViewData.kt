package com.goldorb.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

/** Reads candles through the TradingView MCP server's OHLCV tool. */
class TradingViewData(private val mcp: McpClient) {

    private var ohlcvTool: String? = null

    suspend fun ohlcvToolName(): String = ohlcvTool ?: findOhlcvTool(mcp.listTools()).also { ohlcvTool = it }

    /** All bars of [interval] from [from] up to now (oldest first). */
    suspend fun fetchBars(symbol: String, interval: String, from: Instant, maxPages: Int = 4): List<Bar> {
        val tool = ohlcvToolName()
        val all = mutableListOf<Bar>()
        var dateTo: String? = null
        for (page in 0 until maxPages) {
            val payload = mcp.callTool(tool, buildJsonObject {
                put("symbol", symbol)
                put("interval", interval)
                put("date_from", from.toString())
                dateTo?.let { put("date_to", it) }
                put("count", PAGE_SIZE)
                put("format", "rows")
            })
            all += parseBars(payload)
            val hasMore = payload["has_more"]?.jsonPrimitive?.booleanOrNull == true
            dateTo = payload["next_date_to"]?.jsonPrimitive?.contentOrNull
            if (!hasMore || dateTo == null) break
        }
        return all.distinctBy { it.t }.sortedBy { it.t }.filter { it.t >= from.epochSecond }
    }

    companion object {
        const val PAGE_SIZE = 5000

        fun findOhlcvTool(names: List<String>): String =
            names.firstOrNull { Regex("(^|[-_.])get[-_]ohlcv$").containsMatchIn(it) }
                ?: names.firstOrNull { it.contains("ohlcv", ignoreCase = true) }
                ?: throw McpException("This MCP server has no OHLCV tool (tools: ${names.joinToString()})")

        fun parseBars(payload: JsonObject): List<Bar> {
            if (payload["success"]?.jsonPrimitive?.booleanOrNull == false) {
                val err = payload["error"]?.toString() ?: payload.toString().take(200)
                throw McpException("TradingView returned an error: $err")
            }
            val bars = payload["bars"] ?: return emptyList()
            return if (bars is JsonObject) {
                // format=columns: {t:[], o:[], ...}
                val t = bars["t"]!!.jsonArray
                List(t.size) { i ->
                    fun col(k: String) = bars[k]?.jsonArray?.get(i)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                    Bar(t[i].jsonPrimitive.content.toLong(), col("o"), col("h"), col("l"), col("c"), col("v"))
                }
            } else {
                bars.jsonArray.map { McpJson.decodeFromJsonElement<Bar>(it) }
            }
        }
    }
}
