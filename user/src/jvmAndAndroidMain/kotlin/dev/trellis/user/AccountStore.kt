package dev.trellis.user

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * 客户端本地账号缓存：`<home>/account.json`。
 *
 * 只存 `UserPublic` + 会话 token + 服务器地址，**不存密码、不存 passwordHash**。
 * 缓存的作用：启动时不用联网也能显示当前账号；token 失效时由服务端 401 触发重新登录。
 *
 * 文件里有 token，桌面端尽量设成 600（POSIX 文件系统）；Android 的 filesDir 本身
 * 只有本应用可读。
 */
class AccountStore(private val home: File) {

    val file: File = File(home, "account.json")
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun load(): LocalAccount? {
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(LocalAccount.serializer(), file.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        }.getOrNull()
    }

    fun save(account: LocalAccount) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp-" + System.nanoTime())
        try {
            tmp.writeText(json.encodeToString(LocalAccount.serializer(), account), Charsets.UTF_8)
            restrict(tmp)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            restrict(file)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private fun restrict(target: File) {
        // Windows/部分文件系统不支持 POSIX 权限，失败就算了（桌面 600 只是纵深防御）。
        runCatching {
            Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }
}
