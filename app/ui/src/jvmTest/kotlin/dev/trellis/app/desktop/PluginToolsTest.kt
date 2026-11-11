package dev.trellis.app.desktop

import dev.trellis.ai.CapabilityCatalog
import dev.trellis.kernel.rule.ActionCall
import dev.trellis.kernel.slot.UiNode
import dev.trellis.kernel.slot.UiTap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// 用户抱怨「AGENT 造的插件效果差」的一半原因在工具回执：
// try_plugin 以前只回文件名，模型看不见卡片长什么样就 deploy。
// 这几个用例钉住「预览里必须有按钮/动作/输入框」「deploy 回执必须要求核对」。
class PluginToolsTest {

    private fun card(): UiNode = UiNode.Column(
        listOf(
            UiNode.Text("读书记录", dev.trellis.kernel.slot.UiTextStyle.Title),
            UiNode.Stat(label = "在读的书", value = "1"),
            UiNode.Badge(text = "连续 7 天"),
            UiNode.Input(
                hint = "书名",
                submitLabel = "加一本",
                onSubmit = UiTap.Plugin("addBook", mapOf("source" to "input")),
            ),
            UiNode.Button(
                label = "记一笔",
                onTap = UiTap.Host(ActionCall("task.add")),
            ),
        ),
    )

    @Test
    fun previewShowsCardIdMountPointAndTitle() {
        val text = PluginCardPreview.describe("reading-log", "slot.today.body", card())

        assertTrue(text.contains("reading-log"), text)
        assertTrue(text.contains("slot.today.body"), text)
        assertTrue(text.contains("读书记录"), text)
    }

    @Test
    fun previewShowsStatBadgeAndInput() {
        val text = PluginCardPreview.describe("reading-log", "slot.today.body", card())

        // 「在读的书 = 1」：数值就是用户截图里被画成 1.0 的那个。
        assertTrue(text.contains("在读的书 = 1"), text)
        assertTrue(text.contains("连续 7 天"), text)
        assertTrue(text.contains("浮窗输入"), text)
        assertTrue(text.contains("书名"), text)
        // 默认形态是浮窗：预览要说清「卡片上只有一行字，点开才填」。
        assertTrue(text.contains("卡片上只有一行「加一本」"), text)
        assertTrue(text.contains("点开浮窗后填「书名」"), text)
    }

    @Test
    fun previewShowsButtonLabelAndItsTarget() {
        val text = PluginCardPreview.describe("reading-log", "slot.today.body", card())

        assertTrue(text.contains("按钮"), text)
        assertTrue(text.contains("记一笔"), text)
        // 宿主动作与脚本 handler 都要能看出「点下去交给谁」。
        assertTrue(text.contains("task.add"), text)
        assertTrue(text.contains("addBook"), text)
    }

    @Test
    fun tryPluginHandsTheModelTheRenderedCardNotJustFileNames() = runTest {
        // 走真的 PluginTools（真内核 + 真沙箱），钉住「模型试跑时看得见卡片」。
        val home = File.createTempFile("trellis-try-plugin-", "")
            .let { it.delete(); it.mkdirs(); it }
        try {
            File(home, "llm.json").writeText(
                """{"endpoint":"http://localhost/invalid","apiKey":"sk-test","model":"fake"}""",
            )
            val runtime = AppRuntime.create(home)
            val pluginsDir = File(home, "plugins")
            val catalog = CapabilityCatalog.of(runtime.kernel.extensions, RENDERED_MOUNT_POINTS)
            val tools = PluginTools(
                kernel = runtime.kernel,
                installer = KernelPluginInstaller(pluginsDir, runtime.kernel),
                selfTest = PluginSelfTest(),
                pluginsDir = pluginsDir,
                catalog = catalog,
            )
            val tryPlugin = tools.all.single { it.name == "try_plugin" }
            val args = buildJsonObject {
                put(
                    "plugin",
                    buildJsonObject {
                        put("id", JsonPrimitive("ai.readinglog"))
                        put("name", JsonPrimitive("读书记录"))
                        put("description", JsonPrimitive("记录在读的书和进度"))
                        put("usage", JsonPrimitive("在首页最下面看，输入书名点提交"))
                        put("script", JsonPrimitive(READING_LOG_SCRIPT))
                    },
                )
            }

            val result = tryPlugin.call(args)

            assertTrue(!result.isError, result.content)
            assertTrue(result.content.contains("跑通了"), result.content)
            assertTrue(result.content.contains("在读的书 = 1"), result.content)
            assertTrue(!result.content.contains("1.0"), result.content)
            assertTrue(result.content.contains("浮窗输入"), result.content)
            assertTrue(result.content.contains("卡片上只有一行「加一本」"), result.content)
            assertTrue(result.content.contains("动作"), result.content)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun aScriptCardWithNumericStatPreviewsAsAnInteger() {
        // 端到端钉住用户截图里的那个 bug：脚本写 value: 1，
        // 预览（和真渲染共用同一套翻译）必须给「在读的书 = 1」，不能是「1.0」。
        val script = """
            trellis.register({
              cards: [
                { id: "reading", point: "slot.home.section", order: 10, render: function (data) {
                    return { type: "column", children: [
                      { type: "stat", label: "在读的书", value: 1 },
                      { type: "badge", text: "连续 7 天" },
                      { type: "button", label: "加一本", on: "addBook" }
                    ] };
                } }
              ],
              on: { addBook: function (data, payload) { return { save: {} }; } }
            });
        """.trimIndent()
        val sandbox = dev.trellis.script.ScriptSandbox("ai.reading-log", script)
        try {
            sandbox.boot()
            val node = dev.trellis.script.buildUiNode(
                sandbox.render("reading", PluginCardPreview.sampleData()),
            )
            val text = PluginCardPreview.describe("reading", "slot.home.section", node)

            assertTrue(text.contains("在读的书 = 1"), text)
            assertTrue(!text.contains("1.0"), text)
            assertTrue(text.contains("按钮"), text)
            assertTrue(text.contains("addBook"), text)
        } finally {
            sandbox.close()
        }
    }

    @Test
    fun aFailedRenderDoesNotThrow() {
        val text = PluginCardPreview.failed("reading-log", "slot.today.body", "boom")

        assertTrue(text.contains("reading-log"), text)
        assertTrue(text.contains("渲染失败"), text)
        assertTrue(text.contains("boom"), text)
    }

    @Test
    fun deployReceiptTellsTheModelToVerifyWithDescribeScreen() {
        val text = deployReceipt("reading-log")

        assertTrue(text.contains("装好了：reading-log"), text)
        assertTrue(text.contains("describe_screen"), text)
        assertTrue(text.contains("逐条回报"), text)
    }

    @Test
    fun guidanceNoLongerEncouragesSilentDowngrade() {
        val text = pluginGuidance()

        // 旧文案「先退一步…写进 limitation」正是静默降级的来源，不能让模型再看到。
        assertTrue(!text.contains("先退一步"), text)
        assertTrue(text.contains("先告诉用户"), text)
        assertTrue(text.contains("让用户选"), text)
        assertTrue(text.contains("describe_screen"), text)
        assertTrue(text.contains("逐条回报"), text)
    }

    @Test
    fun guidancePrefersComposingGenericHostActionsAndNamesTheGapProtocol() {
        val text = pluginGuidance()

        // 语义转向：不要求「每个插件求宿主加一个动作」，先拿通用动作拼。
        for (key in listOf(
            "list_actions", "openPage", "writeKnowledgeNote", "openKnowledgeNote",
            "notify", "startFocusTimer", "任务类动作", "通用动作", "宿主新增",
        )) {
            assertTrue(key in text, "guidance 要提到：$key")
        }
        assertTrue(text.indexOf("list_actions") < text.indexOf("openPage"), "先 list_actions 再谈组合")
        assertTrue(text.indexOf("通用动作") < text.indexOf("宿主新增"), "先组合，组合不出来才谈缺口")
        assertTrue(
            text.contains("不要每做一个插件就要求宿主加一个动作"),
            "不要把「每个插件加一个动作」教成默认做法",
        )
        assertTrue(text.contains("limitation"), "缺口要写进 limitation 当真实缺口")
        assertTrue(text.contains("你改不了宿主代码"), "新增能力不是模型自己能做的事，只能告知用户")
    }

    @Test
    fun guidanceStillCarriesTryBeforeDeploy() {
        val text = pluginGuidance()

        assertTrue(text.contains("try_plugin"), text)
        assertTrue(text.contains("deploy"), text)
        assertTrue(text.contains("预览"), text)
    }

    @Test
    fun guidanceAsksForADesignBeforeWritingFiles() {
        val text = pluginGuidance()

        // 用户这轮最核心的流程改造：先给设计、ask_choices 等确认，再写文件。
        assertTrue(text.contains("先设计、后实装"), text)
        assertTrue(text.contains("ask_choices"), text)
        assertTrue(text.contains("按这个做 / 我要改"), text)
        assertTrue(text.contains("等用户确认才写文件"), text)
        // 默认落在首页已有卡片最下方；改已有插件保留原 order。
        assertTrue(text.contains("slot.home.section"), text)
        assertTrue(text.contains("建议 900"), text)
        assertTrue(text.indexOf("先设计、后实装") < text.indexOf("要写新的"), "设计在动手之前")
        // 详情页文案与卡片文案纪律。
        assertTrue(text.contains("description"), text)
        assertTrue(text.contains("usage"), text)
        assertTrue(text.contains("插件详情页"), text)
        assertTrue(text.contains("只留必要的东西"), text)
        assertTrue(text.contains("操作说明句"), text)
        assertTrue(text.contains("同一信息只写一次"), text)
        // 输入类操作默认收进浮窗，别把控件堆在卡面。
        assertTrue(text.contains("popup"), text)
        assertTrue(text.contains("浮窗"), text)
        assertTrue(text.indexOf("怎么用 → 设计 → 缺口/限制") > 0, "完成回报顺序固定")
    }

    @Test
    fun prepareRejectsAPluginWithoutDescriptionOrUsage() = runTest {
        val home = File.createTempFile("trellis-prepare-detail-", "")
            .let { it.delete(); it.mkdirs(); it }
        try {
            val (tools, _) = toolsIn(home)
            val tryPlugin = tools.all.single { it.name == "try_plugin" }

            // 脚本草稿：卡片能跑，但没说清「干什么 / 怎么用」——详情页会空着两行。
            val result = tryPlugin.call(
                buildJsonObject {
                    put("plugin", buildJsonObject {
                        put("id", JsonPrimitive("ai.detailcheck"))
                        put("name", JsonPrimitive("详情检查"))
                        put(
                            "script",
                            JsonPrimitive(
                                """
                                trellis.register({
                                  cards: [
                                    { id: "c", point: "slot.today.body", render: function (data) {
                                        return { type: "text", text: "只有一句话" };
                                    } }
                                  ]
                                });
                                """.trimIndent(),
                            ),
                        )
                    })
                },
            )

            assertTrue(result.isError, result.content)
            // 详情页要展示的这两句缺一不可：缺了用户就不知道这插件干什么、怎么用。
            assertTrue(result.content.contains("插件详情要写说明"), result.content)
            assertTrue(result.content.contains("description"), result.content)
            assertTrue(result.content.contains("usage"), result.content)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun prepareRejectsANewPluginPlacedAboveTheHomeBottom() = runTest {
        val home = File.createTempFile("trellis-prepare-order-", "")
            .let { it.delete(); it.mkdirs(); it }
        try {
            val (tools, pluginsDir) = toolsIn(home)
            val tryPlugin = tools.all.single { it.name == "try_plugin" }

            val result = tryPlugin.call(
                buildJsonObject {
                    put("plugin", buildJsonObject {
                        put("id", JsonPrimitive("ai.readinglog"))
                        put("name", JsonPrimitive("读书记录"))
                        put("description", JsonPrimitive("记录在读的书和进度"))
                        put("howToUse", JsonPrimitive("在首页最下面看"))
                        put("slots", buildJsonArray {
                            addJsonObject {
                                put("point", JsonPrimitive("slot.home.section"))
                                put("title", JsonPrimitive("读书记录"))
                                put("order", JsonPrimitive(10))
                            }
                        })
                    })
                },
            )

            assertTrue(result.isError, result.content)
            assertTrue(result.content.contains("order"), result.content)
            assertTrue(result.content.contains("900"), result.content)
            assertTrue(!File(pluginsDir, "ai.readinglog").exists(), "报错不该留下目录")
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun updatingAnInstalledPluginKeepsItsOriginalOrder() = runTest {
        val home = File.createTempFile("trellis-prepare-update-", "")
            .let { it.delete(); it.mkdirs(); it }
        try {
            val (tools, pluginsDir) = toolsIn(home)
            // 模拟「已装机」：同名目录已存在，就不再强制 order。
            File(pluginsDir, "ai.readinglog").mkdirs()
            val tryPlugin = tools.all.single { it.name == "try_plugin" }

            val result = tryPlugin.call(
                buildJsonObject {
                    put("plugin", buildJsonObject {
                        put("id", JsonPrimitive("ai.readinglog"))
                        put("name", JsonPrimitive("读书记录"))
                        put("description", JsonPrimitive("记录在读的书和进度"))
                        put("howToUse", JsonPrimitive("在首页最下面看"))
                        put("slots", buildJsonArray {
                            addJsonObject {
                                put("point", JsonPrimitive("slot.home.section"))
                                put("title", JsonPrimitive("读书记录"))
                                put("order", JsonPrimitive(10))
                            }
                        })
                    })
                },
            )

            assertTrue(!result.isError, result.content)
            assertTrue(result.content.contains("跑通了"), result.content)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun deployRefusesANewPluginBeforeTheUserHasSeenTheDesign() = runTest {
        // 用户要的流程：先说设计、用户点头，再实装。
        // 真实模型跑的时候在思考里写了「应该先 ask_choices」，然后照样直接 deploy ——
        // 所以这条不能只写在提示词里，得让 deploy 自己拦住。
        val home = File.createTempFile("trellis-deploy-gate-", "")
            .let { it.delete(); it.mkdirs(); it }
        try {
            val (tools, pluginsDir) = toolsIn(home)
            val deploy = tools.all.single { it.name == "deploy" }
            val draft = readingLogDraft()

            val first = deploy.call(draft)
            assertTrue(first.isError, first.content)
            assertTrue(first.content.contains("先别装"), first.content)
            assertTrue(first.content.contains("ask_choices"), first.content)
            assertTrue(!File(pluginsDir, "ai.gatedlog").exists(), "被拦住时不该留下目录")

            // 用户看过设计并点了「按这个做」之后的下一轮：放行。
            tools.beginRound(designConfirmedByUser = true)
            val second = deploy.call(draft)
            assertTrue(!second.isError, second.content)
            assertTrue(File(pluginsDir, "ai.gatedlog").isDirectory, "确认之后应该真装上")
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun existingPluginCanBeUpdatedWithoutAskingForTheDesignAgain() = runTest {
        // 改自己已经装过的东西不该再走一遍设计确认（用户已经见过那张卡了）。
        val home = File.createTempFile("trellis-deploy-update-gate-", "")
            .let { it.delete(); it.mkdirs(); it }
        try {
            val (tools, pluginsDir) = toolsIn(home)
            File(pluginsDir, "ai.gatedlog").mkdirs()
            val deploy = tools.all.single { it.name == "deploy" }

            val result = deploy.call(readingLogDraft())
            assertTrue(!result.isError, result.content)
        } finally {
            home.deleteRecursively()
        }
    }

    private fun readingLogDraft() = buildJsonObject {
        put("plugin", buildJsonObject {
            put("id", JsonPrimitive("ai.gatedlog"))
            put("name", JsonPrimitive("读书"))
            put("description", JsonPrimitive("记进度、开读书笔记"))
            put("usage", JsonPrimitive("在首页最下方：写字按回车提交"))
            put(
                "script",
                JsonPrimitive(
                    """
                    trellis.register({
                      cards: [
                        { id: "c", point: "slot.home.section", order: 900, render: function (data) {
                            return { type: "text", text: "读书" };
                        } }
                      ]
                    });
                    """.trimIndent(),
                ),
            )
        })
    }

    private suspend fun toolsIn(home: File): Pair<PluginTools, File> {
        File(home, "llm.json").writeText(
            """{"endpoint":"http://localhost/invalid","apiKey":"sk-test","model":"fake"}""",
        )
        val runtime = AppRuntime.create(home)
        val pluginsDir = File(home, "plugins")
        val catalog = CapabilityCatalog.of(runtime.kernel.extensions, RENDERED_MOUNT_POINTS)
        val tools = PluginTools(
            kernel = runtime.kernel,
            installer = KernelPluginInstaller(pluginsDir, runtime.kernel),
            selfTest = PluginSelfTest(),
            pluginsDir = pluginsDir,
            catalog = catalog,
        )
        return tools to pluginsDir
    }
}

// 和用户那份读书记录插件同一形状：数字 stat 写 1，之前会被画成 1.0。
private val READING_LOG_SCRIPT = """
    trellis.register({
      cards: [
        { id: "reading", point: "slot.home.section", order: 900, render: function (data) {
            return { type: "column", children: [
              { type: "text", text: "读书记录", style: "title" },
              { type: "stat", label: "在读的书", value: 1 },
              { type: "badge", text: "连续 7 天" },
              { type: "input", hint: "书名", submitLabel: "加一本", on: "addBook" }
            ] };
        } }
      ],
      on: { addBook: function (data, payload) { return { say: "记下了" }; } }
    });
""".trimIndent()
