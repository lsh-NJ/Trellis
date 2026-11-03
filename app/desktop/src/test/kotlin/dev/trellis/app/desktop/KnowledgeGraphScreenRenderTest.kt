package dev.trellis.app.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 「网络」视图的离屏渲染测试（设计 §10.3）。
 *
 * 走真实 [AppTheme] + 真实 [KnowledgeGraphView]：种子 3 篇笔记 → 图构建 → 布局 → 画布，
 * 产出 `build/ui-shot-graph-view.png` 供人工看「分类树默认视图 + 聚焦关联」长什么样。
 * 只在没有显示器也能跑的软件光栅化里执行，不碰真实用户数据。
 */
class KnowledgeGraphScreenRenderTest {

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(runtime: AppRuntime, dark: Boolean): ByteArray {
        // density 用 2 模拟 Retina：布局尺寸（dp）与 12sp 文字（px）必须按 density
        // 对齐，否则中文标签会被压成一两个字——这条截图就是防这个回归的。
        val scene = ImageComposeScene(width = 2200, height = 1400, density = Density(2f)) {
            AppTheme(
                prefs = runtime.uiPrefs,
                themeScheme = runtime.themeScheme(dark),
                dark = dark,
                chrome = runtime.chromeStyle(),
                extra = runtime.extraColors(dark),
            ) {
                KnowledgeGraphView(
                    runtime = runtime,
                    notesVersion = 1,
                    modifier = Modifier.fillMaxSize(),
                    onOpenNote = { _, _, _ -> },
                )
            }
        }
        scene.render() // 第一帧让布局/LaunchedEffect 先跑一轮
        val image = scene.render()
        scene.close()
        return image.encodeToData()?.bytes ?: error("PNG 编码失败")
    }

    private fun seedNotes(store: KnowledgeStore, metaStore: ConceptMetaStore) {
        fun note(title: String, body: String, concepts: List<String>) {
            val created = store.create(title)
            store.save(created.copy(body = body, concepts = concepts))
            metaStore.ensureConcepts(concepts)
        }
        note(
            "RAG 综述",
            "# RAG 综述\n\n[[RAG 检索增强生成]] 把 [[向量检索]] 与 [[提示工程]] 串起来：先检索再生成。\n\n" +
                "## 关键环节\n\n[[向量检索]] 负责召回，[[提示工程]] 决定模型怎么用上下文，[[上下文窗口]] 决定能塞多少。\n",
            listOf("RAG 检索增强生成", "向量检索", "提示工程", "上下文窗口"),
        )
        note(
            "检索与嵌入",
            "# 检索与嵌入\n\n[[嵌入]] 把文本变成向量，[[向量检索]] 按相似度找回片段，[[重排]] 再精排。\n\n" +
                "## 顺序\n\n[[嵌入]] → [[向量检索]] → [[重排]]。\n",
            listOf("嵌入", "向量检索", "重排"),
        )
        note(
            "评估与对齐",
            "# 评估与对齐\n\n[[RAG 检索增强生成]] 要配 [[基准测试]] 与 [[评估]]；[[提示工程]] 影响答案质量。\n",
            listOf("RAG 检索增强生成", "基准测试", "评估", "提示工程"),
        )
    }

    @Test
    fun rendersGraphViewOffscreen() {
        val home = Files.createTempDirectory("trellis-graph-ui").toFile().apply { deleteOnExit() }
        val runtime = runBlocking { AppRuntime.create(home) }
        val store = runtime.knowledgeStore
        val metaStore = ConceptMetaStore(home)
        seedNotes(store, metaStore)

        // 用户策展：1 个大类 + 3 个子分类 + 2 个孙分类 + 归属；留两个概念在未分类。
        // 「大类 → 小类」可以任意嵌套（v1.2 不预制深度），这里演示「检索/生成/评估」收进「RAG 系统」。
        val now = runtime.clock.nowMs()
        val ragSystem = "c_ffffffff"
        val retrieval = "c_aaaaaaaa"
        val generation = "c_bbbbbbbb"
        val evaluation = "c_cccccccc"
        val user = GraphUserData(
            updatedAt = now,
            categories = listOf(
                GraphCategory(id = ragSystem, name = "RAG 系统", order = 0),
                GraphCategory(id = retrieval, name = "检索", parentId = ragSystem, order = 0),
                GraphCategory(id = generation, name = "生成", parentId = ragSystem, order = 1),
                GraphCategory(id = evaluation, name = "评估", parentId = ragSystem, order = 2),
                GraphCategory(id = "c_dddddddd", name = "召回", parentId = retrieval, order = 0),
                GraphCategory(id = "c_eeeeeeee", name = "重排", parentId = retrieval, order = 1),
            ),
            assignments = mapOf(
                conceptIdentityKey("嵌入") to "c_dddddddd",
                conceptIdentityKey("向量检索") to "c_dddddddd",
                conceptIdentityKey("重排") to "c_eeeeeeee",
                conceptIdentityKey("RAG 检索增强生成") to generation,
                conceptIdentityKey("提示工程") to generation,
                conceptIdentityKey("基准测试") to evaluation,
                conceptIdentityKey("评估") to evaluation,
            ),
        )
        val graphStore = KnowledgeGraphStore(home, runtime.clock)
        graphStore.saveUser(user)
        // AI 缓存种子：一条归类建议（未分类概念「上下文窗口」）+ 一条关系建议。
        val reviewNote = store.allNotes().first { it.title == "RAG 综述" }
        graphStore.saveCache(
            GraphAiCache(
                promptVersion = GRAPH_PROMPT_VERSION,
                updatedAt = now,
                noteSuggestions = mapOf(
                    reviewNote.id to AiNoteSuggestions(
                        bodyHash = noteBodyHash(reviewNote),
                        categories = listOf(
                            AiCategorySuggestion(
                                name = "上下文窗口",
                                categoryPath = listOf("生成"),
                                reason = "上下文窗口是生成侧的资源上限",
                                createdAt = now,
                            ),
                        ),
                        relations = listOf(
                            AiRelationSuggestion(
                                from = conceptIdentityKey("向量检索"),
                                to = conceptIdentityKey("重排"),
                                kind = GraphEdgeKind.RELATED,
                                confidence = 0.6f,
                                reason = "检索流程里前后相邻",
                                evidence = "按相似度找回片段",
                                createdAt = now,
                            ),
                        ),
                    ),
                ),
            ),
        )

        KnowledgeGraphViewState.tab = KnowledgeTab.Graph
        KnowledgeGraphViewState.selectedKey = conceptIdentityKey("RAG 检索增强生成")
        KnowledgeGraphViewState.camera = Camera(zoom = 0.9f, panX = 20f, panY = 20f)

        val light = render(runtime, dark = false)
        val out = File("build/ui-shot-graph-view.png")
        out.parentFile?.mkdirs()
        out.writeBytes(light)
        println("  网络视图截图已写出: " + out.absolutePath + "  (" + light.size + " 字节)")
        assertTrue(light.size > 5_000, "PNG 太小了，八成是空白画面：" + light.size)

        val dark = render(runtime, dark = true)
        File("build/ui-shot-graph-view-dark.png").writeBytes(dark)
        assertTrue(dark.size > 5_000, "深色截图太小了，八成是空白画面：" + dark.size)

        // 分类浮窗：确认「新建上级分类 / 移动到分类…」这些新入口真的在界面上。
        KnowledgeGraphViewState.selectedKey = generation
        val categoryShot = render(runtime, dark = false)
        File("build/ui-shot-graph-category.png").writeBytes(categoryShot)
        assertTrue(categoryShot.size > 5_000, "分类浮窗截图太小了：" + categoryShot.size)

        // AI 建议面板（G1）：面板上能看到「归类建议 / 关系建议」两段与确认/忽略按钮。
        KnowledgeGraphViewState.selectedKey = null
        KnowledgeGraphViewState.suggestionsOpen = true
        val suggestionShot = render(runtime, dark = false)
        File("build/ui-shot-graph-suggestions.png").writeBytes(suggestionShot)
        assertTrue(suggestionShot.size > 5_000, "AI 建议面板截图太小了：" + suggestionShot.size)
        KnowledgeGraphViewState.suggestionsOpen = false

        KnowledgeGraphViewState.clearFocus()
        runBlocking { runtime.shutdown() }
    }
}
