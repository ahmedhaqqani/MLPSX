package com.goldorb.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicLong

open class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The server answered 401: the user has to (re)connect their TradingView account. */
class McpAuthException(val wwwAuthenticate: String?) : McpException("TradingView authorization required")

private class McpSessionExpired : McpException("MCP session expired")

internal val McpJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Minimal MCP client for the Streamable HTTP transport: JSON-RPC over POST, with
 * replies as plain JSON or as a server-sent-event stream.
 */
class McpClient(
    private val http: OkHttpClient,
    private val endpoint: String,
    private val accessToken: suspend () -> String?,
    private val clientName: String = "GoldORB",
    private val clientVersion: String = "1.0",
) {
    private val ids = AtomicLong(1)
    private val mutex = Mutex()
    private var sessionId: String? = null
    private var protocolVersion: String? = null
    private var initialized = false

    suspend fun listTools(): List<String> {
        val names = mutableListOf<String>()
        var cursor: String? = null
        do {
            val params = buildJsonObject { cursor?.let { put("cursor", it) } }
            val result = request("tools/list", params)
            result["tools"]?.jsonArray?.forEach { tool ->
                tool.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.let(names::add)
            }
            cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull
        } while (cursor != null)
        return names
    }

    /** Calls a tool and returns its JSON payload (structured content, or the text content parsed as JSON). */
    suspend fun callTool(name: String, arguments: JsonObject): JsonObject {
        val result = request("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        })
        return parseToolResult(result)
    }

    private suspend fun request(method: String, params: JsonObject?): JsonObject = mutex.withLock {
        try {
            ensureInitialized()
            rpc(method, params)!!
        } catch (e: McpSessionExpired) {
            resetSession()
            ensureInitialized()
            rpc(method, params)!!
        }
    }

    private fun resetSession() {
        sessionId = null
        initialized = false
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        val result = rpc("initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", buildJsonObject {
                put("name", clientName)
                put("version", clientVersion)
            })
        })!!
        protocolVersion = result["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: PROTOCOL_VERSION
        rpc("notifications/initialized", null, notification = true)
        initialized = true
    }

    private suspend fun rpc(method: String, params: JsonObject?, notification: Boolean = false): JsonObject? {
        val id = ids.getAndIncrement()
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            if (!notification) put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        val token = accessToken()
        val builder = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json, text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
        token?.let { builder.header("Authorization", "Bearer $it") }
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        protocolVersion?.let { builder.header("MCP-Protocol-Version", it) }

        return withContext(Dispatchers.IO) {
            http.newCall(builder.build()).execute().use { response ->
                if (response.code == 401) throw McpAuthException(response.header("WWW-Authenticate"))
                if (response.code == 404 && sessionId != null) throw McpSessionExpired()
                if (!response.isSuccessful) {
                    val snippet = response.body?.string()?.take(300).orEmpty()
                    throw McpException("TradingView MCP HTTP ${response.code}: $snippet")
                }
                response.header("Mcp-Session-Id")?.let { sessionId = it }
                if (notification) return@use null

                val body = response.body ?: throw McpException("Empty response to $method")
                val contentType = response.header("Content-Type").orEmpty()
                val message = if (contentType.startsWith("text/event-stream")) {
                    body.charStream().buffered().useLines { lines ->
                        parseSse(lines).firstOrNull { it.idEquals(id) }
                    }
                } else {
                    McpJson.parseToJsonElement(body.string()).let { el ->
                        if (el is JsonArray) el.map { it.jsonObject }.firstOrNull { it.idEquals(id) } else el.jsonObject
                    }
                } ?: throw McpException("No reply to $method")
                unwrap(message, method)
            }
        }
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        private val JSON_MEDIA = "application/json".toMediaType()

        /** Splits a server-sent-event stream into its JSON `data:` messages. */
        fun parseSse(lines: Sequence<String>): Sequence<JsonObject> = sequence {
            val data = StringBuilder()
            suspend fun SequenceScope<JsonObject>.flush() {
                if (data.isNotEmpty()) {
                    runCatching { McpJson.parseToJsonElement(data.toString()).jsonObject }.getOrNull()?.let { yield(it) }
                    data.clear()
                }
            }
            for (line in lines) {
                when {
                    line.isEmpty() -> flush()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").removePrefix(" "))
                    }
                }
            }
            flush()
        }

        fun unwrap(message: JsonObject, method: String): JsonObject {
            message["error"]?.let { err ->
                val msg = (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: err.toString()
                throw McpException("$method failed: $msg")
            }
            return message["result"]?.jsonObject ?: JsonObject(emptyMap())
        }

        fun parseToolResult(result: JsonObject): JsonObject {
            val texts = result["content"]?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
            if (result["isError"]?.jsonPrimitive?.booleanOrNull == true) {
                throw McpException(texts.joinToString(" ").ifBlank { "Tool call failed" })
            }
            (result["structuredContent"] as? JsonObject)?.let { return it }
            for (text in texts) {
                val parsed = runCatching { McpJson.parseToJsonElement(text) }.getOrNull()
                if (parsed is JsonObject) return parsed
            }
            throw McpException("Unexpected tool result: ${texts.joinToString(" ").take(200)}")
        }

        private fun JsonObject.idEquals(id: Long): Boolean {
            val v: JsonElement = this["id"] ?: return false
            val p = v as? JsonPrimitive ?: return false
            return p.longOrNull == id || p.contentOrNull == id.toString()
        }
    }
}
