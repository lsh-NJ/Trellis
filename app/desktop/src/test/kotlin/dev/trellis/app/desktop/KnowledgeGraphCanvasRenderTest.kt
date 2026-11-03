package dev.trellis.app.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.trellis.ui.ExtraColors
import dev.trellis.ui.LocalExtraColors
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 画布的离屏渲染测试（G0-0 的「Compose 能不能量文字 + 画字」验证，兼防画布崩）。
 *
 * 用一张手搭的小布局喂 [KnowledgeGraphCanvas]，不依赖布局模块：
 * 既证明 `rememberTextMeasurer` / `drawText` 在这套 Compose Desktop 上能用，
 * 也保证以后改画布不会在「有分类、有芯片、有聚焦边」时抛异常。
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
        val nodes = listOf(
            GraphNode(key = "c_检索", kind = GraphNodeKind.CATEGORY, name = "检索", sortOrder = 0),
            GraphNode(key = "c_生成", kind = GraphNodeKind.CATEGORY, name = "生成", sortOrder = 1),
            concept("嵌入", "嵌入", "c_检索"),
            concept("向量检索", "向量检索", "c_检索", notes = 3),
            concept("重排", "重排", "c_检索"),
            concept("RAG", "RAG", "c_生成", notes = 2),
            GraphNode(
                key = "c_ai",
                kind = GraphNodeKind.CATEGORY,
                name = "对齐",
                origin = NodeOrigin.AI,
                confirmed = false,
            ),
            concept("上下文窗口", "上下文窗口", "c_ai"),
        ).associateBy { it.key }
        val edges = listOf(
            GraphEdge("嵌入", "向量检索", GraphEdgeKind.PREREQUISITE, GraphEdgeSource.EXPLICIT, 0.8f),
            GraphEdge("向量检索", "RAG", GraphEdgeKind.APPLIES, GraphEdgeSource.USER, 1f),
            GraphEdge("嵌入", "RAG", GraphEdgeKind.RELATED, GraphEdgeSource.HEURISTIC, 0.5f),
        )
        return KnowledgeGraph(
            nodes = nodes,
            childrenOf = mapOf(
                null to listOf("c_检索", "c_生成", "c_ai"),
                "c_检索" to listOf("嵌入", "向量检索", "重排"),
                "c_生成" to listOf("RAG"),
                "c_ai" to listOf("上下文窗口"),
            ),
            edges = edges,
            stats = GraphStats(
                conceptCount = 5,
                categoryCount = 3,
                rootCategoryCount = 3,
                maxDepth = 2,
                classifiedCount = 5,
                unclassifiedCount = 0,
                relationEdgeCount = 3,
                aiSuggestionCount = 1,
            ),
        )
    }

    private fun sampleLayout(): GraphLayout {
        fun chip(key: String, left: Float, top: Float): GraphChipItem =
            GraphChipItem(key, GraphRect(left, top, left + 96f, top + 30f))

        val unclassified = GraphBox(
            categoryKey = GRAPH_UNCLASSIFIED_KEY,
            depth = 1,
            collapsed = false,
            bounds = GraphRect(0f, 420f, 520f, 520f),
            headerBounds = GraphRect(0f, 420f, 520f, 450f),
            chips = emptyList(),
        )
        val aiBox = GraphBox(
            categoryKey = "c_ai",
            depth = 2,
            collapsed = false,
            bounds = GraphRect(560f, 160f, 800f, 300f),
            headerBounds = GraphRect(560f, 160f, 800f, 190f),
            chips = listOf("上下文窗口"),
        )
        val generation = GraphBox(
            categoryKey = "c_生成",
            depth = 2,
            collapsed = false,
            bounds = GraphRect(300f, 160f, 540f, 300f),
            headerBounds = GraphRect(300f, 160f, 540f, 190f),
            chips = listOf("RAG"),
            children = listOf(aiBox),
        )
        val retrieval = GraphBox(
            categoryKey = "c_检索",
            depth = 2,
            collapsed = false,
            bounds = GraphRect(200f, 80f, 560f, 380f),
            headerBounds = GraphRect(200f, 80f, 560f, 110f),
            chips = listOf("嵌入", "向量检索", "重排"),
            children = listOf(generation),
        )
        val roots = listOf(retrieval, unclassified)
        val chips = listOf(
            chip("嵌入", 220f, 140f),
            chip("向量检索", 220f, 190f),
            chip("重排", 220f, 240f),
            chip("RAG", 320f, 220f),
            chip("上下文窗口", 580f, 220f),
        )
        return GraphLayout(
            bounds = GraphRect(0f, 80f, 800f, 520f),
            roots = roots,
            boxesByKey = listOf(retrieval, generation, aiBox, unclassified).associateBy { it.categoryKey },
            chipsByKey = chips.associateBy { it.key },
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
                        showCoOccurrence = true,
                        modifier = Modifier.fillMaxSize(),
                        onCameraChange = {},
                        onChipClick = {},
                        onCategoryClick = {},
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
    fun rendersCanvasWithFocusRelations() {
        val bytes = render(selectedKey = "向量检索")
        val out = File("build/ui-shot-graph-canvas.png")
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        println("  画布截图已写出: " + out.absolutePath + "  (" + bytes.size + " 字节)")
        assertTrue(bytes.size > 5_000, "截图像素太少，八成是空白：" + bytes.size)
    }

    @Test
    fun rendersCanvasWhenNothingIsSelected() {
        val bytes = render(selectedKey = null)
        assertTrue(bytes.size > 5_000, "默认视图截图太小：" + bytes.size)
    }
}
