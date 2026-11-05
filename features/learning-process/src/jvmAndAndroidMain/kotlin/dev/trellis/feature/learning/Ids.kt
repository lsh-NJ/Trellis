package dev.trellis.feature.learning

import java.security.SecureRandom

/**
 * ID 生成：`t_` + ULID（时间有序 + 随机）。
 *
 * 为什么不用自增主键：自增会把「规模」暴露给外部；ULID 还能按时间粗排序，
 * 本地离线生成也不会冲突。
 */
internal object Ids {

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford base32
    private val random = SecureRandom()

    fun next(prefix: String = "t_"): String = prefix + ulid()

    fun command(): String = next("cmd_")

    private fun ulid(): String {
        val time = System.currentTimeMillis()
        val chars = CharArray(26)
        var t = time
        for (i in 9 downTo 0) {
            chars[i] = ALPHABET[(t and 31L).toInt()]
            t = t ushr 5
        }
        val bytes = ByteArray(10)
        random.nextBytes(bytes)
        var acc = 0L
        var bits = 0
        var index = 10
        for (b in bytes) {
            acc = (acc shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5 && index < 26) {
                bits -= 5
                chars[index++] = ALPHABET[((acc ushr bits) and 31L).toInt()]
            }
        }
        while (index < 26) chars[index++] = ALPHABET[0]
        return String(chars)
    }
}
