package dev.trellis.user

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordHasherTest {

    @Test
    fun hashVerifiesAndIsSalted() {
        val first = PasswordHasher.hash("correct horse battery staple")
        val second = PasswordHasher.hash("correct horse battery staple")
        assertTrue(first.startsWith("pbkdf2-sha256$"))
        assertTrue(first != second, "同一个密码两次哈希应该有不同的 salt")
        assertTrue(PasswordHasher.verify("correct horse battery staple", first))
        assertFalse(PasswordHasher.verify("wrong", first))
        assertFalse(PasswordHasher.verify("correct horse battery staple", "garbage"))
    }
}
