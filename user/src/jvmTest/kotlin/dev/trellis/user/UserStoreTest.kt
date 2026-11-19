package dev.trellis.user

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserStoreTest {

    private fun store(): Pair<UserStore, File> {
        val file = Files.createTempDirectory("trellis-user-store").resolve("users.json").toFile()
        return UserStore(file) to file
    }

    @Test
    fun registerLoginAuthenticateLogout() {
        val (store, _) = store()
        val registered = (store.register("alice", "password123", "Alice") as UserResult.Ok).value
        assertEquals(UserRole.USER, registered.role)
        assertEquals(1, store.users().size)

        val login = (store.login("alice", "password123", "desktop") as UserResult.Ok).value
        assertTrue(login.token.isNotBlank())
        assertEquals("alice", store.authenticate(login.token)?.username)

        store.logout(login.token)
        assertNull(store.authenticate(login.token))
    }

    @Test
    fun wrongPasswordAndDuplicateUsername() {
        val (store, _) = store()
        store.register("alice", "password123")
        assertTrue(store.login("alice", "wrong-password") is UserResult.Failure)
        assertTrue(store.register("ALICE", "password123") is UserResult.Failure)
        assertTrue(store.register("bob", "short") is UserResult.Failure)
        assertTrue(store.register("a", "password123") is UserResult.Failure)
    }

    @Test
    fun sessionsPersistAcrossReload() {
        val (store, file) = store()
        store.register("alice", "password123")
        val token = (store.login("alice", "password123") as UserResult.Ok).value.token
        val reloaded = UserStore(file)
        assertEquals("alice", reloaded.authenticate(token)?.username)
    }

    @Test
    fun disabledUserCannotLoginAndSessionsAreDropped() {
        val (store, _) = store()
        val alice = (store.register("alice", "password123") as UserResult.Ok).value
        val token = (store.login("alice", "password123") as UserResult.Ok).value.token
        assertNotNull(store.authenticate(token))

        store.setStatus(alice.id, UserStatus.DISABLED)
        assertNull(store.authenticate(token))
        assertTrue(store.login("alice", "password123") is UserResult.Failure)
    }

    @Test
    fun resetPasswordInvalidatesOldSessions() {
        val (store, _) = store()
        val alice = (store.register("alice", "password123") as UserResult.Ok).value
        val token = (store.login("alice", "password123") as UserResult.Ok).value.token
        assertTrue(store.resetPassword(alice.id, "new-password-456") is UserResult.Ok)
        assertNull(store.authenticate(token))
        assertTrue(store.login("alice", "password123") is UserResult.Failure)
        assertTrue(store.login("alice", "new-password-456") is UserResult.Ok)
    }

    @Test
    fun passwordHashIsNeverInPublicUser() {
        val (store, file) = store()
        store.register("alice", "password123")
        val publicText = store.users().toString()
        assertNotEquals(true, publicText.contains("passwordHash"))
        assertTrue(file.readText().contains("passwordHash"), "服务端文件里应该有哈希")
        assertTrue(!file.readText().contains("password123"), "服务端文件里不能有明文密码")
    }
}
