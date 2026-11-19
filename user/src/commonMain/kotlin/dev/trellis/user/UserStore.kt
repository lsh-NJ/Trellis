package dev.trellis.user

import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

/**
 * 用户与会话的文件存储（服务端用）。
 *
 * 一个文件 `users.json` 里同时存用户和会话；写盘原子替换，读坏改名留档后从空开始
 * （服务端启动时应把 [loadError] 打到日志里，别静默当没人）。
 *
 * 会话只存 token 的 SHA-256；明文 token 只在 [createSession]/[login] 的返回里出现一次。
 */
class UserStore(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val sessionTtlMillis: Long = 30L * 24 * 60 * 60 * 1000,
) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val lock = Any()
    private val random = SecureRandom()

    private var data: AuthFile = load()

    var loadError: String? = null
        private set

    fun isEmpty(): Boolean = synchronized(lock) { data.users.isEmpty() }

    fun users(): List<UserPublic> = synchronized(lock) { data.users.map { it.public() } }

    fun user(id: String): UserPublic? = synchronized(lock) {
        data.users.firstOrNull { it.id == id }?.public()
    }

    fun find(username: String): UserPublic? = synchronized(lock) {
        data.users.firstOrNull { it.username.equals(username.trim(), ignoreCase = true) }?.public()
    }

    /** 第一个用户由调用方决定是否给 ADMIN（服务端通常把首个注册者设为管理员）。 */
    fun register(
        username: String,
        password: String,
        displayName: String = username,
        role: UserRole = UserRole.USER,
    ): UserResult<UserPublic> = synchronized(lock) {
        UserValidation.usernameError(username)?.let { return userFailure("invalid_username", it) }
        UserValidation.passwordError(password)?.let { return userFailure("invalid_password", it) }
        val normalized = username.trim()
        if (data.users.any { it.username.equals(normalized, ignoreCase = true) }) {
            return userFailure("username_taken", "这个用户名已经有人用了")
        }
        val timestamp = now()
        val user = UserRecord(
            id = "usr_" + randomId(),
            username = normalized,
            displayName = UserValidation.displayName(displayName, normalized),
            role = role,
            status = UserStatus.ACTIVE,
            createdAt = timestamp,
            updatedAt = timestamp,
            passwordHash = PasswordHasher.hash(password),
        )
        data = data.copy(users = data.users + user)
        persist()
        userOk(user.public())
    }

    fun login(
        username: String,
        password: String,
        deviceName: String = "",
        ttlMillis: Long = sessionTtlMillis,
    ): UserResult<LoginSession> = synchronized(lock) {
        val user = data.users.firstOrNull { it.username.equals(username.trim(), ignoreCase = true) }
            ?: return userFailure("invalid_credentials", "用户名或密码不对")
        if (user.status != UserStatus.ACTIVE) {
            return userFailure("account_disabled", "这个账号已被停用")
        }
        if (!PasswordHasher.verify(password, user.passwordHash)) {
            return userFailure("invalid_credentials", "用户名或密码不对")
        }
        val session = issueSession(user, deviceName, ttlMillis)
        userOk(session)
    }

    /** 给已存在用户直接签发会话（管理员重置密码/首次注册后自动登录时用）。 */
    fun createSession(userId: String, deviceName: String = "", ttlMillis: Long = sessionTtlMillis): UserResult<LoginSession> =
        synchronized(lock) {
            val user = data.users.firstOrNull { it.id == userId } ?: return userFailure("not_found", "找不到这个用户")
            if (user.status != UserStatus.ACTIVE) return userFailure("account_disabled", "这个账号已被停用")
            userOk(issueSession(user, deviceName, ttlMillis))
        }

    fun authenticate(token: String): UserPublic? = synchronized(lock) {
        if (token.isBlank()) return null
        val hash = TokenCodec.sha256Hex(token)
        val timestamp = now()
        val session = data.sessions.firstOrNull { it.tokenHash == hash && it.expiresAt > timestamp }
            ?: return null
        val user = data.users.firstOrNull { it.id == session.userId && it.status == UserStatus.ACTIVE }
            ?: return null
        user.public()
    }

    fun logout(token: String) {
        synchronized(lock) {
            val hash = TokenCodec.sha256Hex(token)
            val next = data.sessions.filterNot { it.tokenHash == hash }
            if (next.size != data.sessions.size) {
                data = data.copy(sessions = next)
                persist()
            }
        }
    }

    fun logoutAll(userId: String) {
        synchronized(lock) {
            val next = data.sessions.filterNot { it.userId == userId }
            if (next.size != data.sessions.size) {
                data = data.copy(sessions = next)
                persist()
            }
        }
    }

    fun setStatus(userId: String, status: UserStatus): UserResult<UserPublic> = synchronized(lock) {
        val user = data.users.firstOrNull { it.id == userId } ?: return userFailure("not_found", "找不到这个用户")
        val updated = user.copy(status = status, updatedAt = now())
        data = data.copy(
            users = data.users.map { if (it.id == userId) updated else it },
            sessions = if (status == UserStatus.DISABLED) data.sessions.filterNot { it.userId == userId } else data.sessions,
        )
        persist()
        userOk(updated.public())
    }

    fun setRole(userId: String, role: UserRole): UserResult<UserPublic> = synchronized(lock) {
        val user = data.users.firstOrNull { it.id == userId } ?: return userFailure("not_found", "找不到这个用户")
        val updated = user.copy(role = role, updatedAt = now())
        data = data.copy(users = data.users.map { if (it.id == userId) updated else it })
        persist()
        userOk(updated.public())
    }

    fun updateDisplayName(userId: String, displayName: String): UserResult<UserPublic> = synchronized(lock) {
        val user = data.users.firstOrNull { it.id == userId } ?: return userFailure("not_found", "找不到这个用户")
        val updated = user.copy(
            displayName = UserValidation.displayName(displayName, user.username),
            updatedAt = now(),
        )
        data = data.copy(users = data.users.map { if (it.id == userId) updated else it })
        persist()
        userOk(updated.public())
    }

    fun resetPassword(userId: String, newPassword: String): UserResult<Unit> = synchronized(lock) {
        UserValidation.passwordError(newPassword)?.let { return userFailure("invalid_password", it) }
        val user = data.users.firstOrNull { it.id == userId } ?: return userFailure("not_found", "找不到这个用户")
        val updated = user.copy(passwordHash = PasswordHasher.hash(newPassword), updatedAt = now())
        data = data.copy(
            users = data.users.map { if (it.id == userId) updated else it },
            sessions = data.sessions.filterNot { it.userId == userId },
        )
        persist()
        userOk(Unit)
    }

    private fun issueSession(user: UserRecord, deviceName: String, ttlMillis: Long): LoginSession {
        val timestamp = now()
        val token = TokenCodec.newToken()
        val session = SessionRecord(
            tokenHash = TokenCodec.sha256Hex(token),
            userId = user.id,
            createdAt = timestamp,
            expiresAt = timestamp + ttlMillis,
            deviceName = deviceName.take(80),
        )
        data = data.copy(sessions = data.sessions.filter { it.expiresAt > timestamp } + session)
        persist()
        return LoginSession(token = token, expiresAt = session.expiresAt, user = user.public())
    }

    private fun load(): AuthFile {
        if (!file.isFile) return AuthFile()
        return try {
            json.decodeFromString(AuthFile.serializer(), file.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        } catch (t: Throwable) {
            loadError = "用户库读取失败：${t.message ?: t::class.simpleName}"
            val backup = File(file.parentFile, file.name + ".bad-" + System.currentTimeMillis())
            runCatching { file.copyTo(backup, overwrite = true) }
            AuthFile()
        }
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val text = json.encodeToString(AuthFile.serializer(), data)
        val tmp = File(file.parentFile, file.name + ".tmp-" + System.nanoTime())
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (file.isFile) runCatching { file.copyTo(File(file.parentFile, file.name + ".bak"), overwrite = true) }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun randomId(): String {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val bytes = ByteArray(16).also(random::nextBytes)
        val chars = CharArray(26)
        var acc = 0L
        var bits = 0
        var index = 0
        for (b in bytes) {
            acc = (acc shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5 && index < chars.size) {
                bits -= 5
                chars[index++] = alphabet[((acc ushr bits) and 31L).toInt()]
            }
        }
        while (index < chars.size) chars[index++] = alphabet[0]
        return String(chars)
    }
}
