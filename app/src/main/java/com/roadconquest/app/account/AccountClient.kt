package com.roadconquest.app.account

import com.roadconquest.app.BuildConfig
import com.roadconquest.app.map.FogGrid
import org.json.JSONObject
import java.net.URI
import javax.net.ssl.HttpsURLConnection

object AccountClient {
    @Volatile internal var endpointOverrideForTests: String? = null
    @Volatile private var cachedEndpointConfig: EndpointConfig? = null

    private data class EndpointConfig(val base: String, val root: URI?)

    data class Account(
        val username: String,
        val token: String?,
        val leaderboardVisible: Boolean
    )

    class ApiException(message: String, val status: Int? = null) : Exception(message)

    fun isConfigured(): Boolean = endpointConfig().root != null

    fun signup(username: String, password: String): Account =
        auth("/v1/signup", username, password)

    fun login(username: String, password: String): Account =
        auth("/v1/login", username, password)

    fun me(token: String): Account {
        val body = request("/v1/me", "GET", null, token)
        return Account(
            body.getString("username"),
            null,
            body.optBoolean("leaderboard_visible", false)
        )
    }

    fun setLeaderboardVisible(token: String, visible: Boolean): Boolean =
        request(
            "/v1/privacy",
            "PUT",
            JSONObject().put("leaderboard_visible", visible),
            token
        ).getBoolean("leaderboard_visible")

    fun logout(token: String) {
        request("/v1/logout", "POST", JSONObject(), token)
    }
    fun registerNotificationDevice(token: String, deviceToken: String, binding: String) {
        request("/v1/notifications/device", "POST", JSONObject().put("fid", deviceToken).put("binding", binding), token)
    }
    fun removeNotificationDevice(token: String) {
        request("/v1/notifications/device", "DELETE", null, token)
    }

    fun changeUsername(token: String, username: String, password: String): Account {
        val body = request("/v1/username", "PUT",
            JSONObject().put("username", username).put("password", password), token)
        return Account(body.getString("username"), null, body.getBoolean("leaderboard_visible"))
    }

    fun deleteAccount(token: String, password: String) {
        request("/v1/account", "DELETE", JSONObject().put("password", password), token)
    }

    private fun auth(path: String, username: String, password: String): Account {
        val body = request(
            path,
            "POST",
            JSONObject().put("username", username).put("password", password),
            null
        )
        return Account(
            body.getString("username"),
            body.getString("token"),
            body.optBoolean("leaderboard_visible", false)
        )
    }

    fun publicPage(path: String): String? = endpoint(path)?.toString()

    fun competition(): JSONObject = request("/v1/competition", "GET", null, null).also {
        if (it.optBoolean("available")) requireVerifiedPolicy(it)
    }

    fun leaderboard(token: String? = null): JSONObject {
        val includeMine = !token.isNullOrBlank()
        return request(
            if (includeMine) "/v1/leaderboard?metric=cells&mine=1"
            else "/v1/leaderboard?metric=cells", "GET", null, token
        ).also {
            if (it.optBoolean("verification_available", true)) requireVerifiedPolicy(it)
            else if (it.getJSONArray("entries").length() != 0) throw ApiException("Unverified scores cannot enter a leaderboard.")
        }
    }

    fun verifiedTotals(token: String): JSONObject = request("/v1/verified/me", "GET", null, token).also(::requireVerifiedPolicy)

    fun startVerifiedDrive(token: String): JSONObject = request("/v1/verified/start", "POST", JSONObject(), token)

    fun submitVerifiedDrive(token: String, evidence: String, integrityToken: String): JSONObject = request(
        "/v1/verified/batch", "POST",
        JSONObject().put("evidence", evidence).put("integrity_token", integrityToken), token
    ).also(::requireVerifiedPolicy)

    fun friends(token: String, includeWeekly: Boolean = false): JSONObject = request(
        if (includeWeekly) "/v1/friends?include=weekly" else "/v1/friends", "GET", null, token
    )

    fun changeFriend(token: String, username: String, action: String): JSONObject = request("/v1/friends", "POST",
        JSONObject().put("username", username).put("action", action), token)

    fun friendWeek(token: String): JSONObject =
        validatedFriendWeek(request("/v1/friends/weekly?metric=cells", "GET", null, token))

    internal fun validatedFriendWeek(body: JSONObject): JSONObject = body.also {
        if (it.optBoolean("available")) requireVerifiedPolicy(it)
    }

    internal fun requireVerifiedPolicy(body: JSONObject) {
        if (body.opt("verified") != true || body.opt("scoring_policy") != 3 || body.optString("dataset") != FogGrid.VERSION) {
            throw ApiException("Verified competition requires the updated scoring service.")
        }
    }

    internal fun forumRequest(path: String, method: String, body: JSONObject?, token: String?): JSONObject =
        request("/v1/forum/$path", method, body, token, 192 * 1024, if (method == "GET") 10_000 else 30_000)

    private fun request(path: String, method: String, body: JSONObject?, token: String?, maxResponseBytes: Int = 64 * 1024, readTimeoutMs: Int = 10_000): JSONObject {
        val url = endpoint(path) ?: throw ApiException("Account service is not configured in this build.")
        val connection = url.toURL().openConnection() as HttpsURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Accept", "application/json")
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }

        try {
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxResponseBytes) throw ApiException("Account server response was too large.")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            } ?: ""
            val json = runCatching { JSONObject(responseText) }.getOrElse {
                throw ApiException("Account server returned an invalid response.", status)
            }
            if (status !in 200..299) {
                throw ApiException(json.optString("error").ifBlank { "Account request failed." }, status)
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun endpointConfig(): EndpointConfig {
        val base = (endpointOverrideForTests ?: BuildConfig.ACCOUNT_API_URL).trim().trimEnd('/')
        cachedEndpointConfig?.takeIf { it.base == base }?.let { return it }

        val root = if (base.isEmpty()) {
            null
        } else {
            runCatching { URI(base) }.getOrNull()?.takeIf {
                it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank() &&
                    it.userInfo == null && it.query == null && it.fragment == null
            }
        }
        return EndpointConfig(base, root).also { cachedEndpointConfig = it }
    }

    private fun endpoint(path: String): URI? {
        val config = endpointConfig()
        if (config.root == null) return null
        return URI(config.base + if (path.startsWith('/')) path else "/$path")
    }
}
