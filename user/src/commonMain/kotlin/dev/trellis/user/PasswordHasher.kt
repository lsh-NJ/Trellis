package dev.trellis.user

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 密码哈希。
 *
 * 格式：`pbkdf2-sha256$<iterations>$<saltBase64>$<hashBase64>`，自带算法参数，
 * 以后提高迭代次数也能验证旧密码。
 *
 * 选 SHA256 而不是 SHA512/Argon2：Android API 26 上 `PBKDF2WithHmacSHA256` 稳定可用，
 * 不需要额外依赖；210k 次在手机上登录一次几十到几百毫秒，可以接受。
 */
object PasswordHasher {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val FORMAT = "pbkdf2-sha256"
    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val HASH_BITS = 256

    private val random = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = derive(password, salt, ITERATIONS)
        val encoder = Base64.getEncoder().withoutPadding()
        return "$FORMAT\$$ITERATIONS\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
    }

    fun verify(password: String, encoded: String): Boolean {
        val parts = encoded.split('$')
        if (parts.size != 4 || parts[0] != FORMAT) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return false
        val decoder = Base64.getDecoder()
        val salt = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { decoder.decode(parts[3]) }.getOrNull() ?: return false
        val actual = runCatching { derive(password, salt, iterations, expected.size * 8) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, actual)
    }

    /** 登录成功后可以顺手判断老哈希要不要升级（目前没接自动升级，只留接口）。 */
    fun needsRehash(encoded: String): Boolean {
        val parts = encoded.split('$')
        if (parts.size != 4 || parts[0] != FORMAT) return true
        return (parts[1].toIntOrNull() ?: 0) < ITERATIONS
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int, bits: Int = HASH_BITS): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, bits)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
