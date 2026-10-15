package dev.trellis.kernel.registry

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.core.VersionRange
import dev.trellis.kernel.diag.ErrorCode
import dev.trellis.kernel.plugin.PluginManifest
import dev.trellis.kernel.plugin.PluginRequirement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun mf(
    id: String,
    version: String = "1.0.0",
    sdk: String = ">=1.0.0",
    dependsOn: List<PluginRequirement> = emptyList(),
) = PluginManifest(
    id = PluginId(id),
    version = SemVer.parse(version),
    name = id,
    sdkRange = VersionRange.parse(sdk),
    dependsOn = dependsOn,
)

private fun dep(id: String, range: String = "*", optional: Boolean = false) =
    PluginRequirement(PluginId(id), VersionRange.parse(range), optional)

class DependencyResolverTest {

    private val sdk = SemVer.parse("1.0.0")

    private fun ids(r: ResolveResult) = r.order.map { it.value }

    private fun codes(r: ResolveResult, id: String) =
        r.rejected[PluginId(id)]?.map { it.code } ?: emptyList()

    @Test
    fun linearChainIsOrderedDependencyFirst() {
        val r = DependencyResolver.resolve(
            listOf(
                mf("a", dependsOn = listOf(dep("b"))),
                mf("b", dependsOn = listOf(dep("c"))),
                mf("c"),
            ),
            sdk,
        )
        assertTrue(r.isHealthy, r.errors.toString())
        assertEquals(listOf("c", "b", "a"), ids(r))
    }

    @Test
    fun diamondDependencyIsOrderedCorrectly() {
        val r = DependencyResolver.resolve(
            listOf(
                mf("d", dependsOn = listOf(dep("b"), dep("c"))),
                mf("c", dependsOn = listOf(dep("a"))),
                mf("b", dependsOn = listOf(dep("a"))),
                mf("a"),
            ),
            sdk,
        )
        assertTrue(r.isHealthy, r.errors.toString())
        assertEquals(listOf("a", "b", "c", "d"), ids(r))
    }

    @Test
    fun selfDependencyIsRejectedAsCycle() {
        val r = DependencyResolver.resolve(listOf(mf("a", dependsOn = listOf(dep("a")))), sdk)
        assertTrue(r.order.isEmpty())
        assertEquals(listOf(ErrorCode.CIRCULAR_DEPENDENCY), codes(r, "a"))
        assertTrue(r.errors.first().detail.contains("a -> a"), r.errors.first().detail)
    }

    @Test
    fun twoNodeCycleIsRejectedAndPathIsReported() {
        val r = DependencyResolver.resolve(
            listOf(
                mf("a", dependsOn = listOf(dep("b"))),
                mf("b", dependsOn = listOf(dep("a"))),
            ),
            sdk,
        )
        assertTrue(r.order.isEmpty())
        assertEquals(setOf("a", "b"), r.rejected.keys.map { it.value }.toSet())
        assertTrue(r.errors.any { it.detail.contains("a -> b -> a") }, r.errors.toString())
    }

    @Test
    fun cycleDoesNotAffectUnrelatedPlugins() {
        val r = DependencyResolver.resolve(
            listOf(
                mf("a", dependsOn = listOf(dep("b"))),
                mf("b", dependsOn = listOf(dep("a"))),
                mf("safe"),
            ),
            sdk,
        )
        assertEquals(listOf("safe"), ids(r))
        assertFalse(r.isHealthy)
    }

    @Test
    fun missingRequiredDependencyRejectsOnlyThatPlugin() {
        val r = DependencyResolver.resolve(
            listOf(mf("a", dependsOn = listOf(dep("ghost"))), mf("b")),
            sdk,
        )
        assertEquals(listOf("b"), ids(r))
        assertEquals(listOf(ErrorCode.MISSING_DEPENDENCY), codes(r, "a"))
    }

    @Test
    fun missingOptionalDependencyIsFine() {
        val r = DependencyResolver.resolve(
            listOf(mf("a", dependsOn = listOf(dep("ghost", optional = true)))),
            sdk,
        )
        assertTrue(r.isHealthy, r.errors.toString())
        assertEquals(listOf("a"), ids(r))
    }

    @Test
    fun sdkRangeMismatchRejectsPlugin() {
        val r = DependencyResolver.resolve(listOf(mf("a", sdk = ">=2.0.0")), sdk)
        assertEquals(listOf(ErrorCode.SDK_VERSION_MISMATCH), codes(r, "a"))
        assertTrue(r.order.isEmpty())
    }

    @Test
    fun dependencyVersionMismatchIsReported() {
        val r = DependencyResolver.resolve(
            listOf(mf("a", dependsOn = listOf(dep("b", ">=2.0.0"))), mf("b", version = "1.0.0")),
            sdk,
        )
        assertEquals(listOf(ErrorCode.DEPENDENCY_VERSION_MISMATCH), codes(r, "a"))
        assertEquals(listOf("b"), ids(r))
    }

    @Test
    fun failurePropagatesUpTheDependencyClosure() {
        val r = DependencyResolver.resolve(
            listOf(
                mf("a", dependsOn = listOf(dep("b"))),
                mf("b", dependsOn = listOf(dep("c"))),
                mf("c", sdk = ">=9.0.0"),
            ),
            sdk,
        )
        assertEquals(listOf(ErrorCode.SDK_VERSION_MISMATCH), codes(r, "c"))
        assertEquals(listOf(ErrorCode.DEPENDENCY_UNAVAILABLE), codes(r, "b"))
        assertEquals(listOf(ErrorCode.DEPENDENCY_UNAVAILABLE), codes(r, "a"))
        assertTrue(r.order.isEmpty())
    }

    @Test
    fun duplicatePluginIdIsRejected() {
        val r = DependencyResolver.resolve(
            listOf(mf("a", version = "1.0.0"), mf("a", version = "2.0.0")),
            sdk,
        )
        assertTrue(codes(r, "a").contains(ErrorCode.MANIFEST_INVALID))
        assertTrue(r.order.isEmpty())
    }

    @Test
    fun repeatedDependencyDeclarationDoesNotBreakOrdering() {
        val r = DependencyResolver.resolve(
            listOf(mf("a", dependsOn = listOf(dep("b"), dep("b"))), mf("b")),
            sdk,
        )
        assertTrue(r.isHealthy, r.errors.toString())
        assertEquals(listOf("b", "a"), ids(r))
    }

    @Test
    fun orderIsDeterministicRegardlessOfInputOrder() {
        val manifests = listOf(
            mf("d", dependsOn = listOf(dep("b"), dep("c"))),
            mf("c", dependsOn = listOf(dep("a"))),
            mf("b", dependsOn = listOf(dep("a"))),
            mf("a"),
            mf("e"),
        )
        val forward = DependencyResolver.resolve(manifests, sdk)
        val backward = DependencyResolver.resolve(manifests.reversed(), sdk)
        assertEquals(ids(forward), ids(backward))
        assertEquals(listOf("a", "e", "b", "c", "d"), ids(forward))
    }
}