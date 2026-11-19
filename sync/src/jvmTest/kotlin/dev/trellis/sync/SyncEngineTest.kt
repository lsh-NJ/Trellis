package dev.trellis.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 双设备集成测试：两个本地 home + 一个共享「远端文件夹」。
 *
 * 覆盖：新增传播、各自修改、同 key 冲突、删除、删除 vs 编辑、新设备首次全量、幂等。
 */
class SyncEngineTest {

    private class Rig {
        private val root: File = Files.createTempDirectory("trellis-sync-engine").toFile()
        private val remoteDir: File = File(root, "remote").apply { mkdirs() }
        private val transport = FolderTransport(remoteDir)

        inner class Device(val name: String) {
            val home: File = File(root, name).apply { mkdirs() }
            private val notesDir: File = File(home, "notes").apply { mkdirs() }
            var wall: Long = 1_000_000L
            val store = SyncStore(home)
            val clock = HlcClock(store.deviceId()) { wall }
            val engine = SyncEngine(
                remote = transport,
                store = store,
                clock = clock,
                domains = listOf(FileTreeDomain("notes", notesDir, setOf(".md"))),
                platform = "test",
            )

            fun write(name: String, body: String) {
                File(notesDir, name).writeText(body, Charsets.UTF_8)
            }

            fun read(name: String): String? =
                File(notesDir, name).takeIf { it.isFile }?.readText(Charsets.UTF_8)

            fun delete(name: String) {
                File(notesDir, name).delete()
            }

            fun conflictFiles(): List<File> =
                File(File(home, "sync/conflicts"), "notes").listFiles()?.toList().orEmpty()

            suspend fun sync(): SyncReport = engine.sync()
        }

        fun device(name: String): Device = Device(name)
    }

    private suspend fun syncAll(vararg devices: Rig.Device) {
        repeat(3) { devices.forEach { it.sync() } }
    }

    @Test
    fun createPropagates() = runBlocking {
        val rig = Rig()
        val a = rig.device("A")
        val b = rig.device("B")

        a.write("a.md", "hello")
        val ra = a.sync()
        assertTrue(ra.ok, ra.summary())
        b.sync()

        assertEquals("hello", b.read("a.md"))
    }

    @Test
    fun independentEditsConverge() = runBlocking {
        val rig = Rig()
        val a = rig.device("A")
        val b = rig.device("B")

        a.write("a.md", "a1")
        a.sync()
        b.sync()

        a.wall += 100
        a.write("a.md", "a2")
        b.wall += 200
        b.write("b.md", "b1")

        syncAll(a, b)

        assertEquals("a2", b.read("a.md"))
        assertEquals("b1", a.read("b.md"))
    }

    @Test
    fun sameFileEditedOnBothKeepsConflictCopy() = runBlocking {
        val rig = Rig()
        val a = rig.device("A")
        val b = rig.device("B")

        a.write("a.md", "base")
        a.sync()
        b.sync()

        a.wall += 100
        a.write("a.md", "from-A")
        b.wall += 200
        b.write("a.md", "from-B")

        a.sync()
        val bReport = b.sync()
        a.sync()

        // 晚推送的一方（B）成为主版本，两边收敛。
        assertEquals("from-B", a.read("a.md"))
        assertEquals("from-B", b.read("a.md"))
        assertTrue(bReport.conflicts >= 1, "B 应该看到一次冲突：$bReport")
        assertTrue(b.conflictFiles().isNotEmpty(), "B 应保留 A 的冲突副本")
    }

    @Test
    fun deletePropagates() = runBlocking {
        val rig = Rig()
        val a = rig.device("A")
        val b = rig.device("B")

        a.write("a.md", "hello")
        a.sync()
        b.sync()
        assertNotNull(b.read("a.md"))

        a.delete("a.md")
        a.sync()
        b.sync()

        assertNull(b.read("a.md"), "删除应通过墓碑传播")
    }

    @Test
    fun localDeleteVersusRemoteEditRestoresRemote() = runBlocking {
        val rig = Rig()
        val a = rig.device("A")
        val b = rig.device("B")

        a.write("a.md", "base")
        a.sync()
        b.sync()

        a.delete("a.md")
        a.sync()

        b.wall += 100
        b.write("a.md", "edited-on-B")
        val bReport = b.sync()
        a.sync()

        assertEquals("edited-on-B", a.read("a.md"), "远端编辑应把被本地删掉的文件恢复")
        assertTrue(bReport.conflicts >= 1)
        assertTrue(b.conflictFiles().isNotEmpty())
    }

    @Test
    fun remoteDeleteVersusLocalEditResurrects() = runBlocking {
        val rig = Rig()
        val a = rig.device("A")
        val b = rig.device("B")

        a.write("a.md", "base")
        a.sync()
        b.sync()

        a.delete("a.md")
        a.sync()

        b.wall += 100
        b.write("a.md", "edited-on-B")
        val bReport = b.sync()
        a.sync()

        assertEquals("edited-on-B", b.read("a.md"))
        assertEquals("edited-on-B", a.read("a.md"), "本地编辑不应被远端删除静默清掉")
        assertTrue(bReport.conflicts >= 1)
    }

    @Test
    fun secondSyncIsNoop() = runBlocking {
        val rig = Rig()
        val a = rig.device("A")
        val b = rig.device("B")

        a.write("a.md", "hello")
        b.write("b.md", "world")
        syncAll(a, b)

        val report = a.sync()
        assertEquals(0, report.pulled, "$report")
        assertEquals(0, report.pushed, "$report")
        assertEquals(0, report.conflicts, "$report")
        assertTrue(report.ok)
    }

    @Test
    fun newDeviceBootstrapsEverything() = runBlocking {
        val rig = Rig()
        val a = rig.device("A")
        val b = rig.device("B")
        val c = rig.device("C")

        a.write("a.md", "from-a")
        b.write("b.md", "from-b")
        syncAll(a, b)

        c.sync()

        assertEquals("from-a", c.read("a.md"))
        assertEquals("from-b", c.read("b.md"))
    }

    @Test
    fun sameContentFromTwoDevicesDoesNotConflict() = runBlocking {
        val rig = Rig()
        val a = rig.device("A")
        val b = rig.device("B")

        a.write("a.md", "same")
        a.sync()
        b.write("a.md", "same")
        val report = b.sync()

        assertEquals(0, report.conflicts, "$report")
        assertEquals("same", a.read("a.md"))
        assertEquals("same", b.read("a.md"))
    }

    @Test
    fun deviceIdIsStableAcrossStoreInstances() {
        val root = Files.createTempDirectory("trellis-sync-device").toFile()
        val first = SyncStore(root).deviceId()
        val second = SyncStore(root).deviceId()
        assertEquals(first, second)
        assertTrue(first.startsWith("dev_"))
    }
}
