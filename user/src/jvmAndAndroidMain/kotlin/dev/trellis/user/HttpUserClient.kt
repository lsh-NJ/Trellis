package dev.trellis.user

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 账号接口返回的错误（带 HTTP 状态和服务端错误码，UI 可以直接显示 message）。 */
class UserApiException(
    val status: Int,
    val code: String,
    override val message: String,
) : IOException(message)

@Serializable
private data class RegisterRequest(
    val username: String,
    val password: String,
    val displayName: String = "",
    val inviteCode: String = "",
    val deviceName: String = "",
)

@Serializable
private data class LoginRequest(
    val username: String,
    val password: String,
    val deviceName: String = "",
)

@Serializable
private data class LoginResponse(val token: String, val expiresAt: Long, val user: UserPublic)

@Serializable
private data class MeResponse(val user: UserPublic)

@Serializable
private data class ErrorResponse(val error: String? = null, val message: String? = null)

/**
 * 账号 HTTP 客户端（注册/登录/当前用户/退出）。
 *
 * 网关的 trial token 走 `X-Token`；用户自己的会话 token 走 `Authorization: Bearer`。
 * 两者可以同时存在：网关负责挡外网，用户 token 负责「你是谁、数据归谁」。
 */
class HttpUserClient(
    baseUrl: String,
    private val gatewayToken: String = "",
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) {

    private val base: String = baseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "账号服务器地址必须以 http:// 或 https:// 开头：$baseUrl"
        }
    }

    suspend fun register(
        username: String,
        password: String,
        displayName: String = "",
        inviteCode: String = "",
        deviceName: String = "",
    ): LoginSession = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            RegisterRequest.serializer(),
            RegisterRequest(username, password, displayName, inviteCode, deviceName),
        )
        val response = request("POST", "/v1/auth/register", body.toByteArray(Charsets.UTF_8), token = null)
        parseLogin(response)
    }

    suspend fun login(username: String, password: String, deviceName: String = ""): LoginSession =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                LoginRequest.serializer(),
                LoginRequest(username, password, deviceName),
            )
            val response = request("POST", "/v1/auth/login", body.toByteArray(Charsets.UTF_8), token = null)
            parseLogin(response)
        }

    suspend fun me(token: String): UserPublic = withContext(Dispatchers.IO) {
        val response = request("GET", "/v1/auth/me", null, token = token)
        parseMe(response)
    }

    suspend fun logout(token: String) = withContext(Dispatchers.IO) {
        val (status, _) = request("POST", "/v1/auth/logout", ByteArray(0), token = token)
        if (status !in 200..299 && status != 401) {
            throw UserApiException(status, "logout_failed", "退出登录失败（HTTP $status）")
        }
    }

    private fun parseLogin(response: Pair<Int, String>): LoginSession {
        val (status, text) = response
        if (status !in 200..299) throw errorOf(status, text)
        val response = runCatching { json.decodeFromString(LoginResponse.serializer(), text) }
            .getOrElse { throw UserApiException(status, "bad_response", "账号服务器返回的不是登录结果") }
        return LoginSession(token = response.token, expiresAt = response.expiresAt, user = response.user)
    }

    private fun parseMe(response: Pair<Int, String>): UserPublic {
        val (status, text) = response
        if (status !in 200..299) throw errorOf(status, text)
        return runCatching { json.decodeFromString(MeResponse.serializer(), text).user }
            .getOrElse { throw UserApiException(status, "bad_response", "账号服务器返回的不是用户信息") }
    }

    private fun errorOf(status: Int, text: String): UserApiException {
        val parsed = runCatching { json.decodeFromString(ErrorResponse.serializer(), text) }.getOrNull()
        val code = parsed?.error.orEmpty().ifBlank { "http_$status" }
        val message = parsed?.message.orEmpty().ifBlank {
            when (status) {
                401 -> "登录已失效，请重新登录"
                403 -> "没有权限或邀请码不对"
                409 -> "用户名已被占用"
                else -> "账号请求失败（HTTP $status）"
            }
        }
        return UserApiException(status, code, message)
    }

    private fun request(
        method: String,
        path: String,
        body: ByteArray?,
        token: String?,
    ): Pair<Int, String> {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.useCaches = false
        conn.setRequestProperty("Accept", "application/json")
        if (gatewayToken.isNotBlank()) conn.setRequestProperty("X-Token", gatewayToken)
        // 用户会话 token 走专用头；Authorization 留给网关的 trial token 用。
        if (!token.isNullOrBlank()) conn.setRequestProperty("X-Trellis-Auth", "Bearer $token")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        conn.disconnect()
        return status to text
    }
}
