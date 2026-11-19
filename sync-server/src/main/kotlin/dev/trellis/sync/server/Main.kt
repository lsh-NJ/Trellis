package dev.trellis.sync.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.trellis.user.LoginSession
import dev.trellis.user.UserPublic
import dev.trellis.user.UserRole
import dev.trellis.user.UserStore
import dev.trellis.user.UserResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 同步服务端参数。
 *
 * [token] 是网关的 trial token（直连调试用）；生产链路里通常由网关校验并剥掉，服务端留空。
 * [inviteCode] 非空时，第二个及之后的用户注册必须带邀请码；第一个用户自动成为管理员。
 */
data class SyncServerConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8000,
    val root: File,
    val token: String = "",
    val maxObjectBytes: Long = 64L * 1024 * 1024,
    val inviteCode: String = "",
    val sessionTtlMillis: Long = 30L * 24 * 60 * 60 * 1000,
)

/**
 * 带账号系统的薄 HTTP 服务：
 *
 * 账号：
 * - `POST /v1/auth/register`：注册（第一个用户自动 ADMIN；之后需要 inviteCode）；
 * - `POST /v1/auth/login`：登录，返回 Bearer token；
 * - `GET  /v1/auth/me`：当前用户；
 * - `POST /v1/auth/logout`：退出；
 * - `GET  /v1/users`：管理员列用户。
 *
 * 同步对象（必须带用户 Bearer token，按用户分目录 `<root>/users/<userId>/`）：
 * - `GET  /v1/object?path=...`
 * - `PUT  /v1/object?path=...`
 * - `GET  /v1/list?prefix=...`
 *
 * `/health` 不校验任何 token，方便网关/隧道探活。
 */
class SyncServer(private val config: SyncServerConfig) {

    private val root: File = config.root.canonicalFile
    private val userStore = UserStore(
        file = File(root, ".auth/users.json"),
        sessionTtlMillis = config.sessionTtlMillis,
    )
    private val authLock = Any()
    private val http: HttpServer = HttpServer.create(InetSocketAddress(config.host, config.port), 0)
    private var executor: ExecutorService? = null

    val port: Int get() = http.address.port

    fun start(): SyncServer {
        root.mkdirs()
        http.createContext("/health") { exchange -> handle(exchange) { health(exchange) } }
        http.createContext("/v1/auth") { exchange -> handle(exchange) { authApi(exchange) } }
        http.createContext("/v1/users") { exchange -> handle(exchange) { usersApi(exchange) } }
        http.createContext("/v1/object") { exchange -> handle(exchange) { objectApi(exchange) } }
        http.createContext("/v1/list") { exchange -> handle(exchange) { listApi(exchange) } }
        val pool = Executors.newFixedThreadPool(8)
        executor = pool
        http.executor = pool
        http.start()
        return this
    }

    fun stop() {
        http.stop(0)
        executor?.shutdownNow()
    }

    // ---------------------------------------------------------------- 基础设施

    private fun handle(exchange: HttpExchange, body: () -> Unit) {
        try {
            addCors(exchange)
            if (exchange.requestMethod == "OPTIONS") {
                respond(exchange, 204, ByteArray(0))
                return
            }
            // /health 不校验网关 token；其它路径先过网关 token（如果配置了）。
            if (exchange.requestURI.path != "/health" && !authorizedEdge(exchange)) {
                respondJson(exchange, 401, ErrorBody("unauthorized", "缺少或错误的网关 token"), ErrorBody.serializer())
                return
            }
            body()
        } catch (t: Throwable) {
            val message = (t.message ?: t::class.simpleName.orEmpty()).take(300)
            runCatching { respond(exchange, 500, message.toByteArray(Charsets.UTF_8)) }
        } finally {
            exchange.close()
        }
    }

    private fun authorizedEdge(exchange: HttpExchange): Boolean {
        if (config.token.isBlank()) return true
        val supplied = exchange.requestHeaders.getFirst("X-Token")
            ?: exchange.requestHeaders.getFirst("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
        return supplied == config.token
    }

    private fun health(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return respond(exchange, 405, "method not allowed".toByteArray())
        respond(exchange, 200, "ok".toByteArray(Charsets.UTF_8))
    }

    // ---------------------------------------------------------------- 账号

    private fun authApi(exchange: HttpExchange) {
        when (exchange.requestURI.path) {
            "/v1/auth/register" -> registerApi(exchange)
            "/v1/auth/login" -> loginApi(exchange)
            "/v1/auth/me" -> meApi(exchange)
            "/v1/auth/logout" -> logoutApi(exchange)
            else -> respondJson(exchange, 404, ErrorBody("not_found", "没有这个账号接口"), ErrorBody.serializer())
        }
    }

    private fun registerApi(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return respond(exchange, 405, "method not allowed".toByteArray())
        val body = parseBody(exchange, RegisterBody.serializer()) ?: return
        synchronized(authLock) {
            val first = userStore.isEmpty()
            if (!first && (config.inviteCode.isBlank() || body.inviteCode != config.inviteCode)) {
                return respondJson(exchange, 403, ErrorBody("invite_required", "需要有效的邀请码"), ErrorBody.serializer())
            }
            val role = if (first) UserRole.ADMIN else UserRole.USER
            when (val registered = userStore.register(body.username, body.password, body.displayName, role)) {
                is UserResult.Ok -> {
                    when (val session = userStore.createSession(registered.value.id, body.deviceName)) {
                        is UserResult.Ok -> respondJson(exchange, 200, session.value, LoginSession.serializer())
                        is UserResult.Failure -> respondJson(exchange, 400, ErrorBody(session.code, session.message), ErrorBody.serializer())
                    }
                }
                is UserResult.Failure -> respondJson(exchange, statusFor(registered.code), ErrorBody(registered.code, registered.message), ErrorBody.serializer())
            }
        }
    }

    private fun loginApi(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return respond(exchange, 405, "method not allowed".toByteArray())
        val body = parseBody(exchange, LoginBody.serializer()) ?: return
        when (val session = userStore.login(body.username, body.password, body.deviceName)) {
            is UserResult.Ok -> respondJson(exchange, 200, session.value, LoginSession.serializer())
            is UserResult.Failure -> respondJson(exchange, statusFor(session.code), ErrorBody(session.code, session.message), ErrorBody.serializer())
        }
    }

    private fun meApi(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return respond(exchange, 405, "method not allowed".toByteArray())
        val user = currentUser(exchange) ?: return
        respondJson(exchange, 200, MeBody(user), MeBody.serializer())
    }

    private fun logoutApi(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return respond(exchange, 405, "method not allowed".toByteArray())
        val token = bearerToken(exchange)
        if (token.isNullOrBlank() || userStore.authenticate(token) == null) {
            return respondJson(exchange, 401, ErrorBody("unauthorized", "登录已失效"), ErrorBody.serializer())
        }
        userStore.logout(token)
        respond(exchange, 204, ByteArray(0))
    }

    private fun usersApi(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return respond(exchange, 405, "method not allowed".toByteArray())
        val user = currentUser(exchange) ?: return
        if (user.role != UserRole.ADMIN) {
            return respondJson(exchange, 403, ErrorBody("forbidden", "只有管理员能看用户列表"), ErrorBody.serializer())
        }
        respondJson(exchange, 200, UsersBody(userStore.users()), UsersBody.serializer())
    }

    private fun currentUser(exchange: HttpExchange): UserPublic? {
        val token = bearerToken(exchange)
        val user = if (token.isNullOrBlank()) null else userStore.authenticate(token)
        if (user == null) {
            respondJson(exchange, 401, ErrorBody("unauthorized", "登录已失效，请重新登录"), ErrorBody.serializer())
            return null
        }
        return user
    }

    private fun bearerToken(exchange: HttpExchange): String? {
        val raw = exchange.requestHeaders.getFirst("X-Trellis-Auth")
            ?: exchange.requestHeaders.getFirst("Authorization")
        return raw?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
    }

    // ---------------------------------------------------------------- 同步对象

    private fun dataRoot(exchange: HttpExchange): File? {
        val user = currentUser(exchange) ?: return null
        val dir = File(File(root, "users"), user.id).canonicalFile
        require(dir.path.startsWith(root.path + File.separator)) { "bad user root" }
        dir.mkdirs()
        return dir
    }

    private fun objectApi(exchange: HttpExchange) {
        val baseRoot = dataRoot(exchange) ?: return
        val path = queryParam(exchange, "path")
        if (path.isBlank()) return respond(exchange, 400, "path is required".toByteArray())
        val file = try {
            resolve(path, baseRoot)
        } catch (t: IllegalArgumentException) {
            return respond(exchange, 400, (t.message ?: "bad path").toByteArray(Charsets.UTF_8))
        }
        when (exchange.requestMethod) {
            "GET" -> {
                if (!file.isFile) return respond(exchange, 404, "not found".toByteArray())
                respond(exchange, 200, file.readBytes())
            }
            "PUT" -> {
                val body = readLimited(exchange) ?: return respond(exchange, 413, "too large".toByteArray())
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp-" + System.nanoTime())
                try {
                    tmp.writeBytes(body)
                    try {
                        Files.move(
                            tmp.toPath(),
                            file.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE,
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
                respond(exchange, 204, ByteArray(0))
            }
            else -> respond(exchange, 405, "method not allowed".toByteArray())
        }
    }

    private fun listApi(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return respond(exchange, 405, "method not allowed".toByteArray())
        val baseRoot = dataRoot(exchange) ?: return
        val prefix = queryParam(exchange, "prefix")
        val dir = if (prefix.isBlank()) {
            baseRoot
        } else {
            try {
                resolve(prefix, baseRoot)
            } catch (t: IllegalArgumentException) {
                return respond(exchange, 400, (t.message ?: "bad prefix").toByteArray(Charsets.UTF_8))
            }
        }
        val base = if (dir.isDirectory) dir else baseRoot
        val files = if (dir.isFile) listOf(dir) else base.walkTopDown().filter { it.isFile }.toList()
        val body = buildJsonArray {
            files
                .filter { !it.name.contains(".tmp-") }
                .map { file -> file to file.relativeTo(baseRoot).invariantSeparatorsPath }
                .filter { (_, relative) -> relative.startsWith(prefix) }
                .sortedBy { (_, relative) -> relative }
                .forEach { (file, relative) ->
                    add(buildJsonObject {
                        put("path", relative)
                        put("size", file.length())
                    })
                }
        }.toString().toByteArray(Charsets.UTF_8)
        respond(exchange, 200, body)
    }

    // ---------------------------------------------------------------- JSON / 路径

    private fun <T> parseBody(exchange: HttpExchange, serializer: kotlinx.serialization.KSerializer<T>): T? {
        val text = readLimited(exchange, 1L * 1024 * 1024)?.toString(Charsets.UTF_8) ?: run {
            respondJson(exchange, 413, ErrorBody("too_large", "请求体太大"), ErrorBody.serializer())
            return null
        }
        return runCatching { json.decodeFromString(serializer, text) }.getOrElse {
            respondJson(exchange, 400, ErrorBody("bad_json", "请求体不是合法 JSON"), ErrorBody.serializer())
            null
        }
    }

    private fun <T> respondJson(
        exchange: HttpExchange,
        code: Int,
        value: T,
        serializer: kotlinx.serialization.KSerializer<T>,
    ) {
        val text = json.encodeToString(serializer, value)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        respond(exchange, code, text.toByteArray(Charsets.UTF_8))
    }

    private fun statusFor(code: String): Int = when (code) {
        "username_taken" -> 409
        "invalid_credentials" -> 401
        "account_disabled" -> 403
        "not_found" -> 404
        else -> 400
    }

    private fun respond(exchange: HttpExchange, code: Int, body: ByteArray) {
        if (!exchange.responseHeaders.containsKey("Content-Type")) {
            exchange.responseHeaders.set("Content-Type", "application/octet-stream")
        }
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun addCors(exchange: HttpExchange) {
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET, PUT, POST, OPTIONS")
        exchange.responseHeaders.set("Access-Control-Allow-Headers", "X-Token, X-Trellis-Auth, Authorization, Content-Type")
    }

    private fun readLimited(exchange: HttpExchange, limit: Long = config.maxObjectBytes): ByteArray? {
        val input = exchange.requestBody
        val buffer = ByteArray(8 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun queryParam(exchange: HttpExchange, name: String): String {
        val raw = exchange.requestURI.rawQuery.orEmpty()
        return raw.split("&").firstNotNullOfOrNull { pair ->
            val key = pair.substringBefore("=", "")
            if (key == name) URLDecoder.decode(pair.substringAfter("=", ""), "UTF-8") else null
        }.orEmpty()
    }

    private fun resolve(raw: String, baseRoot: File): File {
        val normalized = raw.replace('\\', '/').trim('/')
        require(normalized.isNotEmpty()) { "path is empty" }
        require(!raw.startsWith("/") && !raw.startsWith("\\\\")) { "absolute path is not allowed" }
        val segments = normalized.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "illegal path: $raw" }
        val file = File(baseRoot, normalized).canonicalFile
        require(file.path.startsWith(baseRoot.path + File.separator)) { "path escapes root: $raw" }
        return file
    }

    @Serializable
    private data class RegisterBody(
        val username: String = "",
        val password: String = "",
        val displayName: String = "",
        val inviteCode: String = "",
        val deviceName: String = "",
    )

    @Serializable
    private data class LoginBody(
        val username: String = "",
        val password: String = "",
        val deviceName: String = "",
    )

    @Serializable
    private data class MeBody(val user: UserPublic)

    @Serializable
    private data class UsersBody(val users: List<UserPublic>)

    @Serializable
    private data class ErrorBody(val error: String, val message: String)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

fun main(args: Array<String>) {
    val options = parseArgs(args)
    val root = File(options["root"] ?: "sync-data")
    val config = SyncServerConfig(
        host = options["host"] ?: "127.0.0.1",
        port = options["port"]?.toIntOrNull() ?: 8000,
        root = root,
        token = options["token"].orEmpty(),
        inviteCode = options["invite-code"].orEmpty(),
        sessionTtlMillis = (options["session-days"]?.toLongOrNull() ?: 30L) * 24 * 60 * 60 * 1000,
    )
    val server = SyncServer(config).start()
    println("trellis sync server: http://${config.host}:${server.port}  root=${root.absolutePath}")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val out = mutableMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        if (!arg.startsWith("--")) {
            index++
            continue
        }
        val key = arg.removePrefix("--")
        val value = args.getOrNull(index + 1)?.takeIf { !it.startsWith("--") }
        if (value != null) {
            out[key] = value
            index += 2
        } else {
            out[key] = ""
            index++
        }
    }
    return out
}
