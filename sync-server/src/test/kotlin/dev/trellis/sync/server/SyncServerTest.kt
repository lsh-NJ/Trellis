package dev.trellis.sync.server

import dev.trellis.sync.HttpSyncTransport
import dev.trellis.sync.SyncConfig
import dev.trellis.sync.SyncHttpException
import dev.trellis.sync.TrellisSyncService
import dev.trellis.user.HttpUserClient
import dev.trellis.user.LoginSession
import dev.trellis.user.UserRole
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncServerTest {

    private fun startServer(
        token: String = "gateway",
        inviteCode: String = "",
    ): SyncServer {
        val root = Files.createTempDirectory("trellis-sync-server").toFile()
        return SyncServer(
            SyncServerConfig(
                host = "127.0.0.1",
                port = 0,
                root = root,
                token = token,
                inviteCode = inviteCode,
            ),
        ).start()
    }

    private fun base(server: SyncServer): String = "http://127.0.0.1:${server.port}"

    private suspend fun register(
        server: SyncServer,
        username: String = "alice",
        password: String = "password123",
        inviteCode: String = "",
        gatewayToken: String = "gateway",
    ): LoginSession {
        val client = HttpUserClient(base(server), gatewayToken = gatewayToken)
        return client.register(username, password, displayName = username, inviteCode = inviteCode)
    }

    @Test
    fun roundTripThroughHttpTransport() = runBlocking {
        val server = startServer()
        try {
            val session = register(server)
            val transport = HttpSyncTransport(
                baseUrl = base(server),
                token = "gateway",
                bearerToken = session.token,
            )
            transport.put("heads/notes/dev_a.json", byteArrayOf(1, 2, 3))
            transport.put("blobs/notes/abc", "hello".toByteArray())
            assertTrue(transport.get("heads/notes/dev_a.json")!!.contentEquals(byteArrayOf(1, 2, 3)))
            assertEquals("hello", transport.get("blobs/notes/abc")!!.toString(Charsets.UTF_8))
            assertNull(transport.get("missing"))
            assertEquals(
                listOf("heads/notes/dev_a.json"),
                transport.list("heads/notes/").map { it.path },
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun gatewayTokenIsEnforced() = runBlocking {
        val server = startServer()
        try {
            val transport = HttpSyncTransport(
                baseUrl = base(server),
                token = "wrong",
                bearerToken = "whatever",
            )
            val error = assertFailsWith<SyncHttpException> { transport.get("x") }
            assertEquals(401, error.status)
        } finally {
            server.stop()
        }
    }

    @Test
    fun firstUserIsAdminAndSecondNeedsInvite() = runBlocking {
        val server = startServer(inviteCode = "invite-123")
        try {
            val alice = register(server, username = "alice")
            assertEquals(UserRole.ADMIN, alice.user.role)

            val client = HttpUserClient(base(server), gatewayToken = "gateway")
            val denied = assertFailsWith<dev.trellis.user.UserApiException> {
                client.register("bob", "password123")
            }
            assertEquals(403, denied.status)

            val bob = client.register("bob", "password123", inviteCode = "invite-123")
            assertEquals(UserRole.USER, bob.user.role)
        } finally {
            server.stop()
        }
    }

    @Test
    fun loginMeLogout() = runBlocking {
        val server = startServer()
        try {
            val registered = register(server)
            val client = HttpUserClient(base(server), gatewayToken = "gateway")
            val logged = client.login("alice", "password123", deviceName = "test")
            assertEquals("alice", client.me(logged.token).username)
            client.logout(logged.token)
            val error = assertFailsWith<dev.trellis.user.UserApiException> { client.me(logged.token) }
            assertEquals(401, error.status)
        } finally {
            server.stop()
        }
    }

    @Test
    fun usersAreIsolatedByAccount() = runBlocking {
        val server = startServer(inviteCode = "invite-123")
        try {
            val alice = register(server, username = "alice")
            val bob = register(server, username = "bob", inviteCode = "invite-123")
            val aliceTransport = HttpSyncTransport(base(server), token = "gateway", bearerToken = alice.token)
            val bobTransport = HttpSyncTransport(base(server), token = "gateway", bearerToken = bob.token)
            aliceTransport.put("notes/a.md", "alice-data".toByteArray())
            bobTransport.put("notes/a.md", "bob-data".toByteArray())
            assertEquals("alice-data", aliceTransport.get("notes/a.md")!!.toString(Charsets.UTF_8))
            assertEquals("bob-data", bobTransport.get("notes/a.md")!!.toString(Charsets.UTF_8))
        } finally {
            server.stop()
        }
    }

    @Test
    fun objectApiRequiresUserToken() = runBlocking {
        val server = startServer()
        try {
            val transport = HttpSyncTransport(base(server), token = "gateway")
            val error = assertFailsWith<SyncHttpException> { transport.get("x") }
            assertEquals(401, error.status)
        } finally {
            server.stop()
        }
    }

    @Test
    fun pathTraversalIsRejected() = runBlocking {
        val server = startServer()
        try {
            val session = register(server)
            val url = URL("http://127.0.0.1:${server.port}/v1/object?path=..%2Fescape.txt")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("X-Token", "gateway")
            conn.setRequestProperty("Authorization", "Bearer ${session.token}")
            assertEquals(400, conn.responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun twoHomesSyncThroughHttpServer() = runBlocking {
        val server = startServer()
        try {
            val session = register(server)
            val root = Files.createTempDirectory("trellis-sync-e2e").toFile()
            val homeA = File(root, "A").apply { mkdirs() }
            val homeB = File(root, "B").apply { mkdirs() }
            val notesA = File(File(homeA, "knowledge"), "notes").apply { mkdirs() }
            File(notesA, "n1.md").writeText("# 笔记一", Charsets.UTF_8)

            val config = SyncConfig(
                enabled = true,
                transport = SyncConfig.TRANSPORT_HTTP,
                baseUrl = base(server),
                token = "gateway",
            )
            val serviceA = TrellisSyncService(homeA, config, "test", bearerToken = session.token)
            val serviceB = TrellisSyncService(homeB, config, "test", bearerToken = session.token)
            serviceA.sync()
            serviceB.sync()

            assertEquals(
                "# 笔记一",
                File(File(File(homeB, "knowledge"), "notes"), "n1.md").readText(Charsets.UTF_8),
            )
        } finally {
            server.stop()
        }
    }
}
