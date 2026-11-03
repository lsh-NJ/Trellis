package dev.trellis.app.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.trellis.ui.ExtraColors
import dev.trellis.ui.LocalExtraColors
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 画布的离屏渲染测试（G0-0 的「Compose 能不能量文字 + 画字」验证，兼防画布崩）。
 *
 * 手搭一棵小树喂 [KnowledgeGraphCanvas]：分类（虚）→ 子分类 → 概念（实），再带一条未分类树。
 * 两个用例：无选中（默认）和选中一个概念（聚焦 + 淡出其它节点）。
 * 截图写到 `app/desktop/build/ui-shot-graph-canvas.png`，可以直接看。
 */
class KnowledgeGraphCanvasRenderTest {

    private fun sampleGraph(): KnowledgeGraph {
        fun concept(key: String, name: String, parent: String?, notes: Int = 1) = GraphNode(
            key = key,
            kind = GraphNodeKind.CONCEPT,
            name = name,
            noteCount = notes,
            mentionCount = notes * 2,
            parentKey = parent,
        )
        fun category(key: String, name: String, parent: String?) = GraphNode(
            key = key,
            kind = GraphNodeKind.CATEGORY,
            name = name,
            parentKey = parent,
        )
        val nodes = listOf(
            category("c_检索", "检索", null),
            category("c_召回", "召回", "c_检索"),
            concept("嵌入", "嵌入", "c_检索"),
            concept("向量检索", "向量检索", "c_召回", notes = 3),
            category("c_生成", "生成", null),
            concept("RAG", "RAG", "c_生成", notes = 2),
            concept("上下文窗口", "上下文窗口", null),
        ).associateBy { it.key }
        return KnowledgeGraph(
            nodes = nodes,
            childrenOf = mapOf(
                null to listOf("c_检索", "c_生成", "上下文窗口"),
                "c_检索" to listOf("c_召回", "嵌入"),
                "c_召回" to listOf("向量检索"),
                "c_生成" to listOf("RAG"),
            ),
            stats = GraphStats(
                conceptCount = 4,
                categoryCount = 3,
                rootCategoryCount = 2,
                maxDepth = 3,
                classifiedCount = 3,
                unclassifiedCount = 1,
                relationEdgeCount = 2,
            ),
        )
    }

    private fun box(key: String, kind: GraphNodeKind, depth: Int, left: Float, top: Float, width: Float = 96f) =
        GraphNodeBox(
            key = key,
            kind = kind,
            depth = depth,
            bounds = GraphRect(left, top, left + width, top + 30f),
        )

    private fun sampleLayout(): GraphLayout {
        val nodes = listOf(
            box("c_检索", GraphNodeKind.CATEGORY, 0, 40f, 40f),
            box("c_召回", GraphNodeKind.CATEGORY, 1, 40f, 110f),
            box("嵌入", GraphNodeKind.CONCEPT, 1, 40f, 170f),
            box("向量检索", GraphNodeKind.CONCEPT, 2, 40f, 240f),
            box("c_生成", GraphNodeKind.CATEGORY, 0, 220f, 40f),
            box("RAG", GraphNodeKind.CONCEPT, 1, 220f, 110f),
            box(GRAPH_UNCLASSIFIED_KEY, GraphNodeKind.CATEGORY, 0, 400f, 40f),
            box("上下文窗口", GraphNodeKind.CONCEPT, 1, 400f, 110f),
        ).associateBy { it.key }
        val links = listOf(
            GraphLink("c_检索", "c_召回"),
            GraphLink("c_检索", "嵌入"),
            GraphLink("c_召回", "向量检索"),
            GraphLink("c_生成", "RAG"),
            GraphLink(GRAPH_UNCLASSIFIED_KEY, "上下文窗口"),
        )
        return GraphLayout(
            bounds = GraphRect(0f, 0f, 560f, 300f),
            roots = listOf("c_检索", "c_生成", GRAPH_UNCLASSIFIED_KEY),
            nodes = nodes,
            links = links,
        )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(selectedKey: String?): ByteArray {
        val scene = ImageComposeScene(width = 1100, height = 720, density = Density(1f)) {
            CompositionLocalProvider(LocalExtraColors provides ExtraColors()) {
                MaterialTheme {
                    KnowledgeGraphCanvas(
                        graph = sampleGraph(),
                        layout = sampleLayout(),
                        camera = Camera(zoom = 1f, panX = 40f, panY = 20f),
                        selectedKey = selectedKey,
                        modifier = Modifier.fillMaxSize(),
                        onCameraChange = {},
                        onNodeClick = {},
                        onCategoryToggle = {},
                        onBackgroundClick = {},
                    )
                }
            }
        }
        val image = scene.render()
        scene.close()
        return image.encodeToData()?.bytes ?: error("PNG 编码失败")
    }

    @Test
    fun rendersTreeWithFocusedNode() {
        val bytes = render(selectedKey = "向量检索")
        val out = File("build/ui-shot-graph-canvas.png")
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        println("  画布截图已写出: " + out.absolutePath + "  (" + bytes.size + " 字节)")
        assertTrue(bytes.size > 5_000, "截图像素太少，八成是空白：" + bytes.size)
    }

    @Test
    fun rendersTreeWhenNothingIsSelected() {
        val bytes = render(selectedKey = null)
        assertTrue(bytes.size > 5_000, "默认视图截图太小：" + bytes.size)
    }
}
