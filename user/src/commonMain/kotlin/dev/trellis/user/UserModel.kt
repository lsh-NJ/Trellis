package dev.trellis.user

import kotlinx.serialization.Serializable

@Serializable
enum class UserRole { ADMIN, USER }

@Serializable
enum class UserStatus { ACTIVE, DISABLED }

/**
 * 服务端保存的完整用户记录。
 *
 * **passwordHash 永远不出服务端**：客户端只拿 [UserPublic]。导出/同步用户信息时也不要
 * 带这个字段。
 */
@Serializable
data class UserRecord(
    val id: String,
    val username: String,
    val displayName: String,
    val role: UserRole = UserRole.USER,
    val status: UserStatus = UserStatus.ACTIVE,
    val createdAt: Long,
    val updatedAt: Long,
    val passwordHash: String,
) {
    fun public(): UserPublic = UserPublic(
        id = id,
        username = username,
        displayName = displayName,
        role = role,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

/** 可以安全地发给客户端/另一个设备的用户信息。 */
@Serializable
data class UserPublic(
    val id: String,
    val username: String,
    val displayName: String,
    val role: UserRole,
    val status: UserStatus,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 会话只存 token 的 SHA-256，明文 token 只在登录响应里出现一次。 */
@Serializable
data class SessionRecord(
    val tokenHash: String,
    val userId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val deviceName: String = "",
)

/** `<server root>/.auth/users.json` 的完整内容。 */
@Serializable
data class AuthFile(
    val schema: Int = 1,
    val users: List<UserRecord> = emptyList(),
    val sessions: List<SessionRecord> = emptyList(),
)

/** 登录/注册成功后的返回；客户端把它（除 token 外）缓存到本地账号文件。 */
@Serializable
data class LoginSession(
    val token: String,
    val expiresAt: Long,
    val user: UserPublic,
)

/** 本地账号缓存（`<home>/account.json`）；不保存密码。 */
@Serializable
data class LocalAccount(
    val schema: Int = 1,
    val serverUrl: String,
    val gatewayToken: String = "",
    val userId: String,
    val username: String,
    val displayName: String,
    val role: UserRole,
    val token: String,
    val expiresAt: Long,
    val savedAt: Long,
)

sealed class UserResult<out T> {
    data class Ok<T>(val value: T) : UserResult<T>()
    data class Failure(val code: String, val message: String) : UserResult<Nothing>()
}

fun <T> userOk(value: T): UserResult<T> = UserResult.Ok(value)

fun userFailure(code: String, message: String): UserResult<Nothing> = UserResult.Failure(code, message)

object UserValidation {

    private val usernamePattern = Regex("^[A-Za-z0-9._-]{3,32}$")
    private const val MIN_PASSWORD = 8
    private const val MAX_PASSWORD = 128

    fun usernameError(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return "用户名不能为空"
        if (!usernamePattern.matches(value)) return "用户名只能用 3–32 位字母、数字、点、下划线或短横线"
        return null
    }

    fun passwordError(raw: String): String? {
        if (raw.length < MIN_PASSWORD) return "密码至少 $MIN_PASSWORD 位"
        if (raw.length > MAX_PASSWORD) return "密码太长（最多 $MAX_PASSWORD 位）"
        return null
    }

    fun displayName(raw: String, username: String): String =
        raw.trim().take(40).ifBlank { username }
}
