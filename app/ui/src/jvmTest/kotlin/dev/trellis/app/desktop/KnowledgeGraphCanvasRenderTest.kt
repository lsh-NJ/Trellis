package dev.trellis.app.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import dev.trellis.ui.ExtraColors
import dev.trellis.ui.LocalExtraColors
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * 未分类散概念折成网格之后的画布：25 个概念应铺成 4 行多列，而不是一条横线。
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun unclassifiedConceptsRenderAsAGrid() {
        val concepts = (1..25).map { i ->
            GraphNode(
                key = "k_$i",
                kind = GraphNodeKind.CONCEPT,
                name = "未分类概念$i",
                noteCount = 0,
                mentionCount = 0,
                parentKey = null,
            )
        }
        val graph = KnowledgeGraph(
            nodes = concepts.associateBy { it.key },
            childrenOf = mapOf(null to concepts.map { it.key }),
            stats = GraphStats(
                conceptCount = concepts.size,
                categoryCount = 0,
                rootCategoryCount = 0,
                maxDepth = 1,
                classifiedCount = 0,
                unclassifiedCount = concepts.size,
                relationEdgeCount = 0,
            ),
        )
        val layout = layoutKnowledgeGraph(graph, emptySet(), null, LayoutOptions())
        assertTrue(layout.nodes.size >= concepts.size, "布局应包含所有概念")
        val scene = ImageComposeScene(width = 1100, height = 720, density = Density(1f)) {
            CompositionLocalProvider(LocalExtraColors provides ExtraColors()) {
                MaterialTheme {
                    KnowledgeGraphCanvas(
                        graph = graph,
                        layout = layout,
                        camera = fitCamera(layout.bounds, GraphSize(1100f, 720f), padding = 24f),
                        selectedKey = null,
                        modifier = Modifier.fillMaxSize(),
                        onCameraChange = {},
                        onNodeClick = {},
                        onCategoryToggle = {},
                        onBackgroundClick = {},
                    )
                }
            }
        }
        val bytes = scene.render().encodeToData()?.bytes ?: error("PNG 编码失败")
        scene.close()
        val out = File("build/ui-shot-graph-unclassified.png")
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        assertTrue(bytes.size > 5_000, "网格截图像素太少：" + bytes.size)
        // 4 行网格：25 个概念占 7 列、最后一列 1 个。
        val tops = concepts.map { layout.nodes.getValue(it.key).bounds.top }.distinct()
        assertEquals(4, tops.size, "应折成 4 行：" + tops)
    }

    /**
     * 首屏取景只装「根分类 + 一层」：深度 2 的“向量检索”不该进初始视野。
     * 整棵树 fit 下来 zoom 会被 0.25 的下限夹住，95 个概念的标签糊成纹理（评审 §3.7 P0）。
     */
    @Test
    fun initialFocusBoundsCoverOnlyTheFirstTwoLayers() {
        val layout = sampleLayout()
        val focus = initialFocusBounds(layout)
        // 深度 2 的节点 top = 240；初始视野的下沿必须比它高（不含它）。
        assertTrue(focus.bottom < 240f, "初始视野不该包含深度 2 的节点：" + focus)
        // 根分类都在里面。
        assertTrue(focus.top <= 40f, "初始视野应覆盖根分类：" + focus)
        assertTrue(focus.height < layout.bounds.height, "初始视野应比整棵树小：" + focus)
    }

    /**
     * v1.3 拖动链接：把分类「生成」拖到概念「嵌入」上，松手应回调 onLinkDrop(生成, 嵌入)。
     * 用 ImageComposeScene 发鼠标事件模拟真实指针序列（按下 → 若干次移动 → 松开）。
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun draggingCategoryOntoNodeReportsLinkDrop() {
        var dropped: Pair<String, String>? = null
        val events = mutableListOf<String>()
        val scene = ImageComposeScene(width = 1100, height = 720, density = Density(1f)) {
            CompositionLocalProvider(LocalExtraColors provides ExtraColors()) {
                MaterialTheme {
                    KnowledgeGraphCanvas(
                        graph = sampleGraph(),
                        layout = sampleLayout(),
                        camera = Camera.Default,
                        selectedKey = null,
                        modifier = Modifier.fillMaxSize(),
                        onCameraChange = { events += "camera" },
                        onNodeClick = { events += "click:$it" },
                        onCategoryToggle = { events += "toggle:$it" },
                        onBackgroundClick = { events += "background" },
                        onLinkDrop = { dragged, target ->
                            events += "drop:$dragged->$target"
                            dropped = dragged to target
                        },
                    )
                }
            }
        }
        scene.render()
        // sampleLayout：分类「生成」在 (220,40)-(316,70)，概念「嵌入」在 (40,170)-(136,200)。
        val start = Offset(268f, 55f)
        val end = Offset(88f, 185f)
        val pressed = PointerButtons(isPrimaryPressed = true)
        scene.sendPointerEvent(PointerEventType.Press, start, type = PointerType.Mouse, buttons = pressed)
        scene.render()
        repeat(8) { index ->
            val t = (index + 1) / 8f
            scene.sendPointerEvent(
                eventType = PointerEventType.Move,
                position = Offset(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t),
                type = PointerType.Mouse,
                buttons = pressed,
            )
            scene.render()
        }
        scene.render()
        scene.sendPointerEvent(PointerEventType.Release, end, type = PointerType.Mouse)
        scene.render()
        scene.close()
        assertTrue(
            dropped == ("c_生成" to "嵌入"),
            "拖动没有触发预期的链接回调：drop=$dropped events=$events",
        )
    }
}
