package com.ds.localtaskmanager.connected

import com.ds.localtaskmanager.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class CloudApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val retryable: Boolean,
    val retryAfterSeconds: Int? = null,
) : Exception(message)

data class CloudTokens(
    val accessToken: String,
    val refreshToken: String,
    val csrfToken: String,
    val accessExpiresAt: String,
)

data class CloudMembership(
    val id: String,
    val role: String,
    val spaceId: String,
    val spaceName: String,
    val timeZone: String,
    val timeZoneVersion: Int,
)

data class CloudBootstrap(
    val accountId: String,
    val memberships: List<CloudMembership>,
    val serviceMode: String,
    val autoSyncIntervalSeconds: Int?,
)

data class CloudAccountStatus(
    val status: String,
    val privacyNoticeVersion: Int,
    val requiredPrivacyNoticeVersion: Int,
    val deletionDueAt: String?,
)

data class CloudInvitation(
    val id: String,
    val spaceId: String,
    val spaceName: String,
    val createdAt: String,
    val expiresAt: String,
)

data class CloudNotificationSummary(
    val groupKey: String,
    val type: String,
    val createdAt: String,
    val notificationIds: List<String>,
    val unread: Boolean,
)

class CloudApi(
    private val baseUrl: String = BuildConfig.CLOUD_API_BASE_URL,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    private val json = Json { ignoreUnknownKeys = false }
    private val cooldown = RequestCooldown()

    suspend fun requestChallenge(email: String, purpose: String = "SIGN_IN"): String =
        request("POST", "/v1/auth/challenges", body = buildJsonObject { put("email", email); put("purpose", purpose) })
            .requiredString("challengeId")

    suspend fun verify(challengeId: String, email: String, code: String): CloudTokens =
        request("POST", "/v1/auth/verify", body = buildJsonObject {
            put("challengeId", challengeId); put("email", email); put("code", code)
        }).tokens()

    suspend fun refresh(refreshToken: String): CloudTokens =
        request("POST", "/v1/auth/refresh", body = buildJsonObject { put("refreshToken", refreshToken) }).tokens()

    suspend fun accountStatus(accessToken: String): CloudAccountStatus {
        val account = request("GET", "/v1/account", accessToken)["account"]!!.jsonObject
        return CloudAccountStatus(
            status = account.requiredString("status"),
            privacyNoticeVersion = account["privacyNoticeVersion"]?.jsonPrimitive?.intOrNull ?: 0,
            requiredPrivacyNoticeVersion = account["requiredPrivacyNoticeVersion"]?.jsonPrimitive?.intOrNull ?: 1,
            deletionDueAt = account["deletionDueAt"]?.jsonPrimitive?.contentOrNull,
        )
    }

    suspend fun acknowledgePrivacy(accessToken: String, version: Int) {
        request("POST", "/v1/account/privacy-acknowledgements", accessToken, buildJsonObject { put("version", version) })
    }

    suspend fun verifySensitive(accessToken: String, challengeId: String, email: String, code: String) {
        request("POST", "/v1/auth/verify", accessToken, buildJsonObject {
            put("challengeId", challengeId); put("email", email); put("code", code)
        })
    }

    suspend fun requestDeletion(accessToken: String, immediate: Boolean): JsonObject =
        request("POST", "/v1/account/deletion-requests", accessToken, buildJsonObject { put("mode", if (immediate) "IMMEDIATE" else "SCHEDULED") })

    suspend fun cancelDeletion(accessToken: String) {
        request("POST", "/v1/account/deletion-cancellations", accessToken, buildJsonObject { })
    }

    suspend fun exportData(accessToken: String, spaceId: String): String {
        var cursor: String? = null
        var manifest: JsonObject? = null
        val datasets = linkedMapOf<String, MutableList<kotlinx.serialization.json.JsonElement>>()
        do {
            val suffix = cursor?.let { "?cursor=${java.net.URLEncoder.encode(it, StandardCharsets.UTF_8.name())}" }.orEmpty()
            val page = request("GET", "/v1/spaces/$spaceId/export$suffix", accessToken)
            page["manifest"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.jsonObject?.let { manifest = it }
            val dataset = page["dataset"]?.jsonPrimitive?.contentOrNull
            if (dataset != null) datasets.getOrPut(dataset) { mutableListOf() }.addAll(page["records"]!!.jsonArray)
            cursor = page["nextCursor"]?.jsonPrimitive?.contentOrNull
        } while (cursor != null)
        return buildJsonObject {
            put("manifest", manifest ?: JsonObject(emptyMap()))
            put("datasets", buildJsonObject { datasets.forEach { (key, value) -> put(key, JsonArray(value)) } })
        }.toString()
    }

    suspend fun bootstrap(accessToken: String): CloudBootstrap {
        val root = request("GET", "/v1/bootstrap", accessToken = accessToken)
        val accountId = root["account"]!!.jsonObject.requiredString("id")
        val memberships = root["memberships"]!!.jsonArray.map { value ->
            val member = value.jsonObject
            val space = member["space"]!!.jsonObject
            CloudMembership(
                id = member.requiredString("id"),
                role = member.requiredString("role"),
                spaceId = space.requiredString("id"),
                spaceName = space.requiredString("name"),
                timeZone = space.requiredString("timeZone"),
                timeZoneVersion = space["timeZoneVersion"]?.jsonPrimitive?.intOrNull ?: 1,
            )
        }
        val service = root["service"]?.jsonObject
        return CloudBootstrap(
            accountId,
            memberships,
            service?.get("mode")?.jsonPrimitive?.contentOrNull ?: "NORMAL",
            service?.get("autoSyncIntervalSeconds")?.jsonPrimitive?.intOrNull,
        )
    }

    suspend fun invitations(accessToken: String): List<CloudInvitation> =
        request("GET", "/v1/invitations", accessToken = accessToken)["invitations"]!!.jsonArray.map { value ->
            val invitation = value.jsonObject
            CloudInvitation(
                id = invitation.requiredString("id"),
                spaceId = invitation.requiredString("spaceId"),
                spaceName = invitation.requiredString("spaceName"),
                createdAt = invitation.requiredString("createdAt"),
                expiresAt = invitation.requiredString("expiresAt"),
            )
        }

    suspend fun acceptInvitation(accessToken: String, invitationId: String): JsonObject =
        request("POST", "/v1/invitations/$invitationId/accept", accessToken, buildJsonObject { })

    suspend fun snapshot(accessToken: String, spaceId: String, cursor: String?): JsonObject {
        val suffix = cursor?.let { "?cursor=${java.net.URLEncoder.encode(it, StandardCharsets.UTF_8.name())}" }.orEmpty()
        return request("GET", "/v1/spaces/$spaceId/snapshot$suffix", accessToken)
    }

    suspend fun changes(accessToken: String, spaceId: String, cursor: String): JsonObject =
        request("GET", "/v1/spaces/$spaceId/changes?cursor=${java.net.URLEncoder.encode(cursor, StandardCharsets.UTF_8.name())}", accessToken)

    suspend fun commands(accessToken: String, spaceId: String, commands: JsonArray): JsonObject =
        request("POST", "/v1/spaces/$spaceId/commands", accessToken, buildJsonObject { put("commands", commands) })

    suspend fun notifications(accessToken: String, spaceId: String): List<CloudNotificationSummary> =
        request("GET", "/v1/spaces/$spaceId/notifications", accessToken)["notifications"]!!.jsonArray.map { value ->
            val notification = value.jsonObject
            CloudNotificationSummary(
                groupKey = notification.requiredString("groupKey"),
                type = notification.requiredString("type"),
                createdAt = notification.requiredString("createdAt"),
                notificationIds = notification["notificationIds"]?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    .orEmpty()
                    .ifEmpty { listOf(notification.requiredString("id")) },
                unread = notification["readAt"]?.jsonPrimitive?.contentOrNull == null,
            )
        }

    suspend fun logout(accessToken: String): Unit {
        request("POST", "/v1/auth/logout", accessToken, buildJsonObject { })
    }

    private suspend fun request(
        method: String,
        path: String,
        accessToken: String? = null,
        body: JsonObject? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        cooldown.beforeRequest(path)
        val connection = openConnection(URL(baseUrl + path)).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            if (accessToken != null) setRequestProperty("Authorization", "Bearer $accessToken")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
            if (status !in 200..299) {
                val error = parsed["error"] as? JsonObject
                val retryAfter = retryAfterSeconds(connection.getHeaderField("Retry-After"),
                    (error?.get("retryAfterSeconds") as? JsonPrimitive)?.intOrNull)
                    ?: if (status == 429) 60 else null
                if (retryAfter != null && (status == 429 || status == 503)) cooldown.pause(retryAfter)
                throw CloudApiException(
                    status,
                    error?.get("code")?.jsonPrimitive?.contentOrNull ?: "HTTP_ERROR",
                    if (status == 429) "请求过于频繁，请在 $retryAfter 秒后重试"
                    else error?.get("message")?.jsonPrimitive?.contentOrNull ?: "云端请求失败",
                    error?.get("retryable")?.jsonPrimitive?.booleanOrNull ?: (status == 429 || status >= 500),
                    retryAfter,
                )
            }
            parsed
        } catch (error: CloudApiException) {
            throw error
        } catch (error: Exception) {
            throw CloudApiException(0, "NETWORK_UNAVAILABLE", "当前无法连接服务器", true)
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonObject.tokens() = CloudTokens(
        requiredString("accessToken"),
        requiredString("refreshToken"),
        requiredString("csrfToken"),
        requiredString("accessExpiresAt"),
    )
}

internal fun JsonObject.requiredString(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull ?: error("云端响应缺少 $key")
