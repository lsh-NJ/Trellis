package dev.trellis.dsl

import dev.trellis.kernel.api.Kernel
import dev.trellis.kernel.api.KernelConfig
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.spi.Platform
import dev.trellis.kernel.time.FakeClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 换不出来的占位符不能装上才暴露。 */
class PlaceholderTest {

    @Test
    fun aBareWordPlaceholderIsCaughtBeforeItReachesTheCard() = runTest {
        val errors = bootWith(
            """{"slots":[{"point":"slot.home.section","id":"s1","title":"读书","body":"大约读了 {{ pages }} 页"}]}""",
        )
        assertTrue(errors.isNotEmpty(), "应该报错")
        assertTrue(errors.any { it.contains("命名空间.字段") }, "要说清楚该写成什么样：" + errors)
    }

    @Test
    fun aNamespacedPlaceholderPasses() = runTest {
        val errors = bootWith(
            """{"slots":[{"point":"slot.home.section","id":"s1","title":"待办","body":"还有 {{ summary.todo }} 件"}]}""",
        )
        assertFalse(errors.any { it.contains("命名空间.字段") }, "带命名空间的不该报错：" + errors)
    }

    private suspend fun bootWith(entry: String): List<String> {
        val fs = InMemoryFileSystem(
            mapOf(
                "$ROOT/p/manifest.json" to """{"id":"p","version":"1.0.0","name":"p"}""",
                "$ROOT/p/main.dsl.json" to entry,
            ),
        )
        val kernel = Kernel.boot(
            KernelConfig(
                sdkVersion = SemVer.parse("1.0.0"),
                platform = Platform.JVM,
                pluginsDir = ROOT,
                loaders = listOf(DslLoader(fs, FakeClock(1_000L))),
                clock = FakeClock(1_000L),
            ),
        )
        try {
            return kernel.errors.map { it.detail }
        } finally {
            kernel.shutdown()
        }
    }

    private companion object {
        const val ROOT = "placeholder-root"
    }
}
