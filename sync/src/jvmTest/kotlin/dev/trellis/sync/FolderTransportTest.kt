package dev.trellis.sync

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderTransportTest {

    private fun tempRoot() = Files.createTempDirectory("trellis-folder-transport").toFile()

    @Test
    fun putGetList() = runBlocking {
        val root = tempRoot()
        val transport = FolderTransport(root)
        transport.put("heads/notes/dev_a.json", byteArrayOf(1, 2, 3))
        transport.put("blobs/notes/abc", byteArrayOf(4))
        assertEquals(listOf<Byte>(1, 2, 3), transport.get("heads/notes/dev_a.json")!!.toList())
        assertNull(transport.get("nope"))
        val listed = transport.list("heads/notes/").map { it.path }.sorted()
        assertEquals(listOf("heads/notes/dev_a.json"), listed)
        val all = transport.list("").map { it.path }.sorted()
        assertEquals(listOf("blobs/notes/abc", "heads/notes/dev_a.json"), all)
    }

    @Test
    fun rejectsTraversal() {
        runBlocking {
            val transport = FolderTransport(tempRoot())
            assertFailsWith<IllegalArgumentException> { transport.get("../escape.txt") }
            assertFailsWith<IllegalArgumentException> { transport.put("/absolute.txt", byteArrayOf(1)) }
            assertFailsWith<IllegalArgumentException> { transport.list("../") }
        }
    }

    @Test
    fun overwriteIsAtomicEnough() = runBlocking {
        val transport = FolderTransport(tempRoot())
        transport.put("a.json", "one".toByteArray())
        transport.put("a.json", "two".toByteArray())
        assertEquals("two", transport.get("a.json")!!.toString(Charsets.UTF_8))
        assertTrue(transport.list("").none { it.path.contains(".tmp-") })
    }
}
