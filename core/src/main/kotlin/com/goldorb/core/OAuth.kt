package com.goldorb.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Serializable
data class AuthServerMetadata(
    val issuer: String? = null,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("registration_endpoint") val registrationEndpoint: String? = null,
    @SerialName("scopes_supported") val scopesSupported: List<String>? = null,
)

@Serializable
data class OAuthClient(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String? = null,
)

/** Everything needed to run the authorization-code flow against one MCP server. */
@Serializable
data class OAuthConfig(
    val resource: String,
    val metadata: AuthServerMetadata,
    val client: OAuthClient,
    val scope: String? = null,
)

@Serializable
data class TokenSet(
    val accessToken: String,
    val refreshToken: String? = null,
    /** Epoch seconds, or null if the server gave no expiry. */
    val expiresAt: Long? = null,
) {
    fun isExpired(nowEpoch: Long, skewSeconds: Long = 60): Boolean =
        expiresAt != null && nowEpoch >= expiresAt - skewSeconds
}

class OAuthException(message: String) : Exception(message)

object Pkce {
    private val random = SecureRandom()

    fun newVerifier(): String = randomUrlSafe(48)

    fun newState(): String = randomUrlSafe(24)

    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun randomUrlSafe(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(::base64Url)

    private fun base64Url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}

/**
 * OAuth 2.1 for MCP servers (MCP authorization spec): protected-resource discovery,
 * authorization-server metadata, dynamic client registration, PKCE code flow and refresh.
 */
class OAuthFlow(private val http: OkHttpClient) {

    /** Finds the authorization server for [mcpUrl] and registers this app as a client. */
    suspend fun prepare(mcpUrl: String, redirectUri: String, clientName: String, presetClientId: String?): OAuthConfig {
        val challenge = probeChallenge(mcpUrl)
        val resourceMeta = fetchResourceMetadata(mcpUrl, challenge?.let(::resourceMetadataUrl))
        val resource = resourceMeta?.get("resource")?.jsonPrimitive?.contentOrNull ?: mcpUrl
        val authServer = resourceMeta?.get("authorization_servers")?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?: mcpUrl.toHttpUrl().let { "${it.scheme}://${it.host}${portSuffix(it)}" }
        val metadata = fetchAuthServerMetadata(authServer) ?: legacyMetadata(authServer)
        val scope = challenge?.let(::challengeScope)
            ?: resourceMeta?.get("scopes_supported")?.jsonArray?.joinToString(" ") { it.jsonPrimitive.content }?.ifBlank { null }

        val client = if (!presetClientId.isNullOrBlank()) {
            OAuthClient(presetClientId.trim())
        } else {
            val endpoint = metadata.registrationEndpoint
                ?: throw OAuthException("The server doesn't support automatic app registration. Enter a Client ID in Advanced settings.")
            register(endpoint, redirectUri, clientName)
        }
        return OAuthConfig(resource, metadata, client, scope)
    }

    fun authorizeUrl(config: OAuthConfig, redirectUri: String, codeChallenge: String, state: String): String =
        config.metadata.authorizationEndpoint.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", config.client.clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("code_challenge", codeChallenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .addQueryParameter("resource", config.resource)
            .apply { config.scope?.let { addQueryParameter("scope", it) } }
            .build().toString()

    suspend fun exchangeCode(config: OAuthConfig, code: String, verifier: String, redirectUri: String, nowEpoch: Long): TokenSet =
        tokenRequest(config, nowEpoch) {
            add("grant_type", "authorization_code")
            add("code", code)
            add("redirect_uri", redirectUri)
            add("code_verifier", verifier)
        }

    suspend fun refresh(config: OAuthConfig, refreshToken: String, nowEpoch: Long): TokenSet =
        tokenRequest(config, nowEpoch, keepRefresh = refreshToken) {
            add("grant_type", "refresh_token")
            add("refresh_token", refreshToken)
        }

    private suspend fun tokenRequest(
        config: OAuthConfig,
        nowEpoch: Long,
        keepRefresh: String? = null,
        fields: FormBody.Builder.() -> Unit,
    ): TokenSet {
        val form = FormBody.Builder().apply {
            fields()
            add("client_id", config.client.clientId)
            config.client.clientSecret?.let { add("client_secret", it) }
            add("resource", config.resource)
        }.build()
        val json = postForJson(Request.Builder().url(config.metadata.tokenEndpoint).post(form).build())
        val access = json["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw OAuthException("Token response had no access_token")
        val expiresIn = json["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        return TokenSet(
            accessToken = access,
            refreshToken = json["refresh_token"]?.jsonPrimitive?.contentOrNull ?: keepRefresh,
            expiresAt = expiresIn?.let { nowEpoch + it },
        )
    }

    private suspend fun register(endpoint: String, redirectUri: String, clientName: String): OAuthClient {
        val body = buildJsonObject {
            put("client_name", clientName)
            putJsonArray("redirect_uris") { add(redirectUri) }
            putJsonArray("grant_types") { add("authorization_code"); add("refresh_token") }
            putJsonArray("response_types") { add("code") }
            put("token_endpoint_auth_method", "none")
        }
        val json = postForJson(
            Request.Builder().url(endpoint).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        )
        return McpJson.decodeFromJsonElement(OAuthClient.serializer(), json)
    }

    /** Sends an unauthenticated MCP initialize to read the server's WWW-Authenticate challenge. */
    private suspend fun probeChallenge(mcpUrl: String): String? = withContext(Dispatchers.IO) {
        val body = """{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"${McpClient.PROTOCOL_VERSION}","capabilities":{},"clientInfo":{"name":"GoldORB","version":"1.0"}}}"""
        val req = Request.Builder().url(mcpUrl)
            .header("Accept", "application/json, text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        runCatching { http.newCall(req).execute().use { it.header("WWW-Authenticate") } }.getOrNull()
    }

    private suspend fun fetchResourceMetadata(mcpUrl: String, fromChallenge: String?): JsonObject? {
        val url = mcpUrl.toHttpUrl()
        val candidates = listOfNotNull(
            fromChallenge,
            wellKnown(url, "oauth-protected-resource", insertPath = true),
            wellKnown(url, "oauth-protected-resource", insertPath = false),
        ).distinct()
        return candidates.firstNotNullOfOrNull { getJson(it) }
    }

    private suspend fun fetchAuthServerMetadata(issuer: String): AuthServerMetadata? {
        val url = issuer.toHttpUrl()
        val path = url.encodedPath.trimEnd('/')
        val base = "${url.scheme}://${url.host}${portSuffix(url)}"
        val candidates = if (path.isEmpty()) {
            listOf("$base/.well-known/oauth-authorization-server", "$base/.well-known/openid-configuration")
        } else {
            listOf(
                "$base/.well-known/oauth-authorization-server$path",
                "$base/.well-known/openid-configuration$path",
                "$base$path/.well-known/openid-configuration",
            )
        }
        return candidates.firstNotNullOfOrNull { candidate ->
            getJson(candidate)?.let { runCatching { McpJson.decodeFromJsonElement(AuthServerMetadata.serializer(), it) }.getOrNull() }
        }
    }

    private fun legacyMetadata(authServer: String): AuthServerMetadata {
        val u = authServer.toHttpUrl()
        val base = "${u.scheme}://${u.host}${portSuffix(u)}"
        return AuthServerMetadata(base, "$base/authorize", "$base/token", "$base/register")
    }

    private suspend fun getJson(url: String): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
                if (!r.isSuccessful) null else McpJson.parseToJsonElement(r.body!!.string()).jsonObject
            }
        }.getOrNull()
    }

    private suspend fun postForJson(request: Request): JsonObject = withContext(Dispatchers.IO) {
        http.newCall(request.newBuilder().header("Accept", "application/json").build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw OAuthException("HTTP ${r.code} from ${request.url.host}: ${text.take(300)}")
            McpJson.parseToJsonElement(text).jsonObject
        }
    }

    companion object {
        fun resourceMetadataUrl(wwwAuthenticate: String): String? =
            Regex("""resource_metadata="([^"]+)"""").find(wwwAuthenticate)?.groupValues?.get(1)

        fun challengeScope(wwwAuthenticate: String): String? =
            Regex("""scope="([^"]+)"""").find(wwwAuthenticate)?.groupValues?.get(1)

        fun wellKnown(url: HttpUrl, name: String, insertPath: Boolean): String {
            val base = "${url.scheme}://${url.host}${portSuffix(url)}/.well-known/$name"
            val path = url.encodedPath.trimEnd('/')
            return if (insertPath && path.isNotEmpty()) base + path else base
        }

        private fun portSuffix(url: HttpUrl): String =
            if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"

        fun encode(config: OAuthConfig): String = McpJson.encodeToString(config)
        fun decodeConfig(s: String): OAuthConfig = McpJson.decodeFromString(OAuthConfig.serializer(), s)
        fun encode(tokens: TokenSet): String = McpJson.encodeToString(tokens)
        fun decodeTokens(s: String): TokenSet = McpJson.decodeFromString(TokenSet.serializer(), s)
    }
}
