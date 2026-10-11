package com.goldorb.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * Receives the OAuth redirect on http://127.0.0.1:<port>/callback (RFC 8252 loopback
 * redirect), for servers that don't accept custom app schemes such as goldorb://.
 */
class LoopbackRedirectServer : Closeable {
    private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1_000 }

    val redirectUri: String = "http://127.0.0.1:${socket.localPort}$PATH"

    /**
     * Waits for the browser to hit the callback, answers it with the page from [page],
     * and returns the query parameters (code, state, or error).
     */
    suspend fun awaitCallback(timeoutMs: Long, page: (Map<String, String>) -> String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            var params: Map<String, String>? = null
            while (params == null) {
                ensureActive()
                if (System.currentTimeMillis() > deadline) throw McpException("Sign-in timed out, please try again")
                val client = try {
                    socket.accept()
                } catch (e: SocketTimeoutException) {
                    continue
                }
                params = client.use { serve(it, page) }
            }
            params
        }

    /** Answers one request; returns the callback parameters, or null for any other path. */
    private fun serve(client: Socket, page: (Map<String, String>) -> String): Map<String, String>? {
        client.soTimeout = 10_000
        val requestLine = client.getInputStream().bufferedReader().readLine().orEmpty()
        val target = requestLine.split(" ").getOrNull(1).orEmpty()
        val out = client.getOutputStream()
        if (target.substringBefore('?') != PATH) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return null
        }
        val params = parseQuery(target.substringAfter('?', ""))
        val body = page(params).toByteArray()
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
        return params
    }

    override fun close() = socket.close()

    companion object {
        const val PATH = "/callback"

        fun parseQuery(query: String): Map<String, String> =
            query.split('&').filter { it.isNotEmpty() }.associate { pair ->
                URLDecoder.decode(pair.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }
    }
}
