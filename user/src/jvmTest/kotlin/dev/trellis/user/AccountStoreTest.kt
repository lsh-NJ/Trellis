package dev.trellis.user

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountStoreTest {

    @Test
    fun saveLoadClear() {
        val home = Files.createTempDirectory("trellis-account").toFile()
        val store = AccountStore(home)
        assertNull(store.load())

        val account = LocalAccount(
            serverUrl = "https://example.com",
            gatewayToken = "edge",
            userId = "usr_1",
            username = "alice",
            displayName = "Alice",
            role = UserRole.ADMIN,
            token = "session-token",
            expiresAt = 123,
            savedAt = 456,
        )
        store.save(account)
        assertEquals(account, store.load())

        store.clear()
        assertNull(store.load())
    }
}
