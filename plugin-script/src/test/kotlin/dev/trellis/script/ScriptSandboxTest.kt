package dev.trellis.script

import dev.trellis.kernel.slot.UiFont
import dev.trellis.kernel.slot.UiNode
import dev.trellis.kernel.slot.UiNoticeTone
import dev.trellis.kernel.slot.UiTextSize
import dev.trellis.kernel.slot.UiTextStyle
import dev.trellis.kernel.slot.UiTextWeight
import dev.trellis.kernel.slot.UiTone
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScriptSandboxTest {

    private val helloScript = """
        trellis.register({
          cards: [
            { id: "hello", point: "slot.home.section", order: 10, render: function (data) {
                return { type: "text", text: "你好 " + data.summary.total };
            } }
          ],
          on: {
            save: function (data, payload) {
              return { say: "记下了", save: { last: payload.value } };
            }
          }
        });
    """

    @Test
    fun aScriptCanRegisterACardAndRenderIt() {
        val sandbox = ScriptSandbox("dev.test", helloScript)
        sandbox.boot()

        assertEquals(1, sandbox.declaredCards().size)
        assertEquals("slot.home.section", sandbox.declaredCards().first().pointId)

        val raw = sandbox.render("hello", mapOf("summary" to mapOf("total" to 3)))
        val node = NodeBuilder.build(raw)
        assertEquals(UiNode.Text("你好 3"), node)
    }

    @Test
    fun aClickComesBackAsWhatToSave() {
        val sandbox = ScriptSandbox("dev.test", helloScript)
        sandbox.boot()

        val result = sandbox.runHandler("save", emptyMap(), mapOf("value" to "42"))
        assertEquals("记下了", result.say)
        assertEquals(mapOf("last" to "42"), result.save)
    }

    @Test
    fun javaIsNotReachable() {
        val sandbox = ScriptSandbox("dev.test", "var x = java.lang.System.exit(0);")
        val failure = assertFailsWith<ScriptFailure> { sandbox.boot() }
        assertTrue(failure.message!!.contains("java"), "报错该提到 java：${failure.message}")
    }

    @Test
    fun anEndlessLoopIsCutOff() {
        val script = """
            trellis.register({
              cards: [ { id: "spin", point: "slot.home.section", render: function () { while (true) {} } } ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script, budgetMs = 300)
        sandbox.boot()
        val failure = assertFailsWith<ScriptFailure> { sandbox.render("spin", emptyMap()) }
        assertTrue(failure.message!!.contains("跑太久"), "报错该说跑太久：${failure.message}")
    }

    @Test
    fun aScriptCanShowANoticeInsteadOfAFakeCard() {
        val script = """
            trellis.register({
              cards: [ { id: "hello", point: "slot.home.section", render: function () {
                return { type: "notice", text: "今天已经记过了，明天再来" };
              } } ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()
        val node = NodeBuilder.build(sandbox.render("hello", emptyMap()))
        assertEquals(UiNode.Notice("今天已经记过了，明天再来"), node)
        assertEquals(UiNoticeTone.Hint, (node as UiNode.Notice).tone, "插件自己的提示不该显示成错误")
    }

    @Test
    fun aNodeTheHostDoesNotKnowBecomesANotice() {
        val script = """
            trellis.register({
              cards: [ { id: "odd", point: "slot.home.section", render: function () { return { type: "iframe" }; } } ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()
        val node = NodeBuilder.build(sandbox.render("odd", emptyMap()))
        assertTrue(node is UiNode.Notice, "认不出的节点必须变成提示，实际是 $node")
        assertEquals(
            UiNoticeTone.Problem,
            (node as UiNode.Notice).tone,
            "宿主诊断要显眼，不能混进插件自己的提示",
        )
    }

    @Test
    fun aCardCanBeComposedLikeAPage() {
        val script = """
            trellis.register({
              cards: [ { id: "week", point: "slot.home.section", render: function () {
                return {
                  type: "section", title: "这一周",
                  children: [
                    { type: "row", gap: 12, children: [
                      { type: "stat", label: "读书", value: "12 页" },
                      { type: "stat", label: "俯卧撑", value: "80 个" }
                    ] },
                    { type: "grid", columns: 2, children: [
                      { type: "badge", text: "连续 7 天", tone: "success" },
                      { type: "text", text: "状态不错", tone: "muted" }
                    ] },
                    { type: "progress", value: 12, max: 20, label: "本周目标" },
                    { type: "spacer", height: 4 }
                  ]
                };
              } } ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()

        val section = NodeBuilder.build(sandbox.render("week", emptyMap())) as UiNode.Section
        assertEquals("这一周", section.title)

        val row = section.children[0] as UiNode.Row
        assertEquals(12, row.gap)
        assertEquals(UiNode.Stat("读书", "12 页"), row.children[0])

        val grid = section.children[1] as UiNode.Grid
        assertEquals(2, grid.columns)
        assertEquals(UiNode.Badge("连续 7 天", UiTone.Success), grid.children[0])
        assertEquals(UiNode.Text("状态不错", UiTextStyle.Body, UiTone.Muted), grid.children[1])

        val progress = section.children[2] as UiNode.Progress
        assertEquals(12, progress.value)
        assertEquals(20, progress.max)
        assertEquals("本周目标", progress.label)

        assertEquals(UiNode.Spacer(4), section.children[3])
    }

    @Test
    fun oversizedNumbersAreClampedInsteadOfCrashingTheCard() {
        val script = """
            trellis.register({
              cards: [ { id: "odd", point: "slot.home.section", render: function () {
                return { type: "grid", columns: 99, children: [
                  { type: "progress", value: 50, max: 10 }
                ] };
              } } ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()

        val grid = NodeBuilder.build(sandbox.render("odd", emptyMap())) as UiNode.Grid
        assertEquals(12, grid.columns, "分栏最多 12 栏")
        val progress = grid.children[0] as UiNode.Progress
        assertEquals(10, progress.value, "进度不能超过上限")
        assertEquals(10, progress.max)
    }

    @Test
    fun aCardCanUseFinerTypeAndDrawCharts() {
        val script = """
            trellis.register({
              cards: [ { id: "stats", point: "slot.home.section", render: function () {
                return { type: "column", children: [
                  { type: "text", text: "本周", size: "huge", weight: "bold", font: "mono" },
                  { type: "bars", label: "每天读的页数", items: [
                    { label: "一", value: 3 }, { label: "二", value: 8 }
                  ] },
                  { type: "sparkline", values: [1, 4, 2, 6], label: "最近四天" },
                  { type: "ring", value: 7, max: 10, label: "目标" }
                ] };
              } } ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()

        val column = NodeBuilder.build(sandbox.render("stats", emptyMap())) as UiNode.Column

        val title = column.children[0] as UiNode.Text
        assertEquals("本周", title.text)
        assertEquals(UiTextSize.Huge, title.size)
        assertEquals(UiTextWeight.Bold, title.weight)
        assertEquals(UiFont.Mono, title.font)

        val bars = column.children[1] as UiNode.Bars
        assertEquals(2, bars.items.size)
        assertEquals("二", bars.items[1].label)
        assertEquals(8, bars.items[1].value)

        val spark = column.children[2] as UiNode.Sparkline
        assertEquals(listOf(1, 4, 2, 6), spark.values)
        assertEquals(6, spark.max, "没给 max 就用样本里的最大值")

        val ring = column.children[3] as UiNode.Ring
        assertEquals(7, ring.value)
        assertEquals(10, ring.max)
        assertEquals("目标", ring.label)
    }

    @Test
    fun aCardCanDrawColumnsAndAnArea() {
        val script = """
            trellis.register({
              cards: [ { id: "charts", point: "slot.home.section", render: function () {
                return { type: "column", children: [
                  { type: "columns", values: [3, 8, 5, 9], labels: ["一", "二", "三", "四"], label: "近四天" },
                  { type: "area", values: [10, 30, 50, 80], max: 100, label: "累计" }
                ] };
              } } ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()

        val column = NodeBuilder.build(sandbox.render("charts", emptyMap())) as UiNode.Column

        val columns = column.children[0] as UiNode.Columns
        assertEquals(listOf(3, 8, 5, 9), columns.values)
        assertEquals(listOf("一", "二", "三", "四"), columns.labels)
        assertEquals("近四天", columns.label)
        assertEquals(null, columns.max, "没给 max 时交给宿主按样本最大值归一")

        val area = column.children[1] as UiNode.Area
        assertEquals(listOf(10, 30, 50, 80), area.values)
        assertEquals(100, area.max)
        assertEquals("累计", area.label)
    }

    @Test
    fun aCardCanShowADotMatrixCountdown() {
        val script = """
            trellis.register({
              cards: [ { id: "countdown", point: "slot.report.chart", render: function () {
                return { type: "column", children: [
                  { type: "dotmatrix", value: "20", label: "距开演" },
                  { type: "dotmatrix", value: "9:30" }
                ] };
              } } ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()

        val column = NodeBuilder.build(sandbox.render("countdown", emptyMap())) as UiNode.Column
        val dots = column.children[0] as UiNode.DotMatrix
        assertEquals("20", dots.value)
        assertEquals("距开演", dots.label)
        assertEquals("9:30", (column.children[1] as UiNode.DotMatrix).value)
    }

    @Test
    fun aCardCanAskForTheCondensedNumberFamily() {
        val script = """
            trellis.register({
              cards: [ { id: "kpi", point: "slot.home.section", render: function () {
                return { type: "column", children: [
                  { type: "text", text: "1,248", size: "huge", weight: "bold", font: "condensed" },
                  { type: "text", text: "本周总页数", font: "mono" }
                ] };
              } } ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()

        val column = NodeBuilder.build(sandbox.render("kpi", emptyMap())) as UiNode.Column
        assertEquals(UiFont.Condensed, (column.children[0] as UiNode.Text).font)
        assertEquals(UiFont.Mono, (column.children[1] as UiNode.Text).font)
    }

    @Test
    fun aGridChildCanSpanSeveralColumns() {
        val script = """
            trellis.register({
              cards: [ { id: "bento", point: "slot.home.section", render: function () {
                return { type: "grid", columns: 12, children: [
                  { type: "stat", label: "本周", value: "1,248", span: 8 },
                  { type: "stat", label: "今天", value: "62", span: 4 },
                  { type: "text", text: "第三块", span: 99 }
                ] };
              } } ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()

        val grid = NodeBuilder.build(sandbox.render("bento", emptyMap())) as UiNode.Grid
        assertEquals(12, grid.columns)
        val wide = grid.children[0] as UiNode.Cell
        assertEquals(8, wide.span)
        assertEquals(UiNode.Stat("本周", "1,248"), wide.child)
        assertEquals(4, (grid.children[1] as UiNode.Cell).span)
        assertEquals(12, (grid.children[2] as UiNode.Cell).span, "span 超过栏数时夹到 columns")
    }

    @Test
    fun aCardCanShowAnIconOrAnImage() {
        val script = """
            trellis.register({
              cards: [ { id: "art", point: "slot.home.section", render: function () {
                return { type: "column", children: [
                  { type: "icon", glyph: "📚", size: 99 },
                  { type: "image", src: "art/hero.png", alt: "读书封面", height: 999 }
                ] };
              } } ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()

        val column = NodeBuilder.build(sandbox.render("art", emptyMap())) as UiNode.Column

        val icon = column.children[0] as UiNode.Icon
        assertEquals("📚", icon.glyph)
        assertEquals(64, icon.size, "图标最多 64，不能一句话占半屏")

        val image = column.children[1] as UiNode.Image
        assertEquals("art/hero.png", image.ref)
        assertEquals("读书封面", image.alt)
        assertEquals(240, image.height, "图片高度最多 240")
    }

    @Test
    fun anImageWithoutSrcBecomesAVisibleProblem() {
        val script = """
            trellis.register({
              cards: [ { id: "broken", point: "slot.home.section", render: function () {
                return { type: "image", alt: "没写路径" };
              } } ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()

        val notice = NodeBuilder.build(sandbox.render("broken", emptyMap())) as UiNode.Notice
        assertEquals(UiNoticeTone.Problem, notice.tone)
        assertTrue(notice.text.contains("src"), notice.text)
    }

    @Test
    fun aCardThatForgotItsMountPointIsRefusedAtBoot() {
        val script = """
            trellis.register({ cards: [ { id: "x", render: function () { return "hi"; } } ] });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        val failure = assertFailsWith<ScriptFailure> { sandbox.boot() }
        assertTrue(failure.message!!.contains("point"), failure.message)
    }

    @Test
    fun aCardCanDrawATable() {
        val script = """
            trellis.register({
              cards: [ { id: "board", point: "slot.home.section", render: function () {
                return { type: "table", caption: "阵容", headers: ["乐队", "舞台", "状态"],
                  rows: [ ["海边", "主舞台", "ok"], ["午夜", "二号台", "wait"] ] };
              } } ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()

        val table = NodeBuilder.build(sandbox.render("board", emptyMap())) as UiNode.Table
        assertEquals("阵容", table.caption)
        assertEquals(listOf("乐队", "舞台", "状态"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals("wait", table.rows[1][2])
    }

    @Test
    fun aTableClampsItsSizeInsteadOfBreakingTheCard() {
        val script = """
            trellis.register({
              cards: [ { id: "big", point: "slot.home.section", render: function () {
                var headers = [];
                for (var i = 0; i < 9; i++) { headers.push("列" + i); }
                var rows = [];
                for (var r = 0; r < 25; r++) { rows.push(["值" + r]); }
                return { type: "table", headers: headers, rows: rows };
              } } ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()

        val table = NodeBuilder.build(sandbox.render("big", emptyMap())) as UiNode.Table
        assertEquals(6, table.headers.size, "最多 6 列")
        assertEquals(20, table.rows.size, "最多 20 行")
    }

    @Test
    fun aTableWithoutRowsBecomesAVisibleProblem() {
        val script = """
            trellis.register({ cards: [ { id: "empty", point: "slot.home.section", render: function () {
              return { type: "table", headers: ["列"] };
            } } ] });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()

        val notice = NodeBuilder.build(sandbox.render("empty", emptyMap())) as UiNode.Notice
        assertEquals(UiNoticeTone.Problem, notice.tone)
        assertTrue(notice.text.contains("rows"), notice.text)
    }

    @Test
    fun aCardCanStepACounter() {
        val script = """
            trellis.register({
              cards: [ { id: "pages", point: "slot.home.section", render: function () {
                return { type: "stepper", label: "今天读了几页", value: 12, unit: "页", max: 500, on: "addPages" };
              } } ],
              on: {
                addPages: function (data, payload) {
                  return { say: "记下了", save: { lastDelta: payload.delta } };
                }
              }
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()

        val stepper = NodeBuilder.build(sandbox.render("pages", emptyMap())) as UiNode.Stepper
        assertEquals("今天读了几页", stepper.label)
        assertEquals(12, stepper.value)
        assertEquals("页", stepper.unit)
        assertEquals(500, stepper.max)
        val tap = stepper.onStep as dev.trellis.kernel.slot.UiTap.Plugin
        assertEquals("addPages", tap.handler)

        val result = sandbox.runHandler("addPages", emptyMap(), mapOf("delta" to "1"))
        assertEquals("记下了", result.say)
        assertEquals("1", result.save["lastDelta"])
    }

    @Test
    fun aStepperClampsItsValueAndNeedsAHandler() {
        val script = """
            trellis.register({
              cards: [
                { id: "huge", point: "slot.home.section", render: function () {
                  return { type: "stepper", label: "页数", value: 9999, min: 0, max: 100, on: "x" };
                } },
                { id: "broken", point: "slot.home.section", render: function () {
                  return { type: "stepper", label: "没处理函数", value: 1 };
                } }
              ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()

        val huge = NodeBuilder.build(sandbox.render("huge", emptyMap())) as UiNode.Stepper
        assertEquals(100, huge.value, "超过 max 的值要夹回来")

        val broken = NodeBuilder.build(sandbox.render("broken", emptyMap())) as UiNode.Notice
        assertEquals(UiNoticeTone.Problem, broken.tone)
        assertTrue(broken.text.contains("on"), "缺 on 时要明确说缺什么")
    }

    @Test
    fun aScriptCanDeclareATheme() {
        val script = """
            trellis.register({
              cards: [],
              theme: {
                id: "warm-paper",
                name: "暖纸",
                description: "偏黄纸张的浅色主题",
                light: {
                  primary: "#8A5A2B", onPrimary: "#FFFFFF",
                  background: "#FBF5EA", onBackground: "#221E18",
                  surface: "#FFFDF8", onSurface: "#221E18",
                  surfaceVariant: "#EFE6D8", onSurfaceVariant: "#4A443B"
                }
              }
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()

        val themes = sandbox.declaredThemes()
        assertEquals(1, themes.size)
        assertEquals("warm-paper", themes.first().id)
        assertEquals("#8A5A2B", themes.first().light["primary"])
        assertTrue(themes.first().dark.isEmpty(), "没写 dark 时留空，由宿主派生")
    }

    @Test
    fun theBentoSampleThemeStillBoots() {
        // examples/plugins 里的样片主题插件：它能被沙箱跑起来、声明出两套 token，
        // 才不会出现「文件在仓库里、装上却报错」这种交付事故。
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "examples/plugins/a-shift-bento/main.js") }
            .firstOrNull { it.exists() } ?: return

        val sandbox = ScriptSandbox("dev.bento.shift", file.readText())
        sandbox.boot()

        val theme = sandbox.declaredThemes().single()
        assertEquals("bento", theme.id)
        assertEquals("#141414", theme.light["primary"])
        assertEquals("#D6F24B", theme.dark["primary"])
        assertTrue(theme.light.size >= 8, "必备 token 至少 8 个：" + theme.light.keys)
        assertEquals("3", theme.shape["cardRadius"])
        assertEquals("mono", theme.type["labelFamily"])
        // 岗位色：数据用靛、风险用砖红。缺了它图表只能单色，样片的「蓝颜料」就没了。
        assertEquals("#2C4A63", theme.extra["data"])
        assertEquals("#E2DED2", theme.extra["dataTrack"])
        assertEquals("#4F6A12", theme.extra["accentText"])
        assertEquals("#B23A2E", theme.extra["danger"])
    }

    @Test
    fun aThemeIdThatIsNotAPluginNamespaceIsRejectedAtBoot() {
        val script = """
            trellis.register({ cards: [], theme: { id: "Warm Paper", name: "暖纸", light: {} } });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        val failure = assertFailsWith<ScriptFailure> { sandbox.boot() }
        assertTrue(failure.message!!.contains("主题"), failure.message)
    }

    @Test
    fun aThemeWithoutLightIsRefusedAtBoot() {
        val script = """
            trellis.register({ cards: [], theme: { id: "warm", name: "暖纸", light: {} } });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        val failure = assertFailsWith<ScriptFailure> { sandbox.boot() }
        assertTrue(failure.message!!.contains("light"), failure.message)
    }

    @Test
    fun aCardCanDrawACheckinStrip() {
        val script = """
            trellis.register({
              cards: [
                { id: "mood", point: "slot.home.section", order: 10, render: function (data) {
                    return { type: "heat", values: [5, 0, 3, 2, 0, 4, 1], max: 5, labels: ["一","二","三","四","五","六","日"] };
                } }
              ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()

        val node = NodeBuilder.build(sandbox.render("mood", emptyMap()))
        val strip = node as UiNode.HeatStrip
        assertEquals(listOf(5, 0, 3, 2, 0, 4, 1), strip.values)
        assertEquals(5, strip.max)
        assertEquals(7, strip.labels.size)
    }

    @Test
    fun theShiftBoardSampleBootsAndDrawsARealDashboard() {
        // 这份样片是「agent 能拼到多密」的上限演示：如果它跑不起来，
        // 说明提示词里承诺的节点和宿主实际能力脱节了。
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "examples/plugins/bento-shift-board/main.js") }
            .firstOrNull { it.exists() } ?: return

        val sandbox = ScriptSandbox("dev.bento.shiftboard", file.readText())
        sandbox.boot()

        val cards = sandbox.declaredCards()
        assertEquals(2, cards.size)
        assertEquals("slot.report.chart", cards.first { it.id == "shift" }.pointId)

        val day = 86_400_000L
        val now = 7 * day
        val data = mapOf(
            "now" to now,
            "summary" to mapOf(
                "total" to 6, "done" to 4, "doing" to 0, "todo" to 2, "overdue" to 1,
                "dueToday" to 1, "spentMinutes" to 120, "streakDays" to 3,
                "completionRate" to 67, "oldestOpenTask" to "把方案发出去",
            ),
            "tasks" to listOf(
                mapOf("id" to "t1", "title" to "把方案发出去", "status" to "TODO", "spentMinutes" to 25, "dueAt" to now, "completedAt" to null),
                mapOf("id" to "t2", "title" to "交周报", "status" to "DONE", "spentMinutes" to 40, "dueAt" to null, "completedAt" to now),
                mapOf("id" to "t3", "title" to "回邮件", "status" to "DONE", "spentMinutes" to 10, "dueAt" to null, "completedAt" to now - day),
            ),
        )

        val board = NodeBuilder.build(sandbox.render("board", data))
        val flattened = flattenNode(board)
        assertTrue(flattened.any { it is UiNode.HeatStrip }, "首页卡该有打卡方格")
        assertTrue(flattened.any { it is UiNode.Sparkline }, "首页卡该有走势")

        val shift = NodeBuilder.build(sandbox.render("shift", data))
        val shiftFlat = flattenNode(shift)
        assertTrue(shiftFlat.any { it is UiNode.Bars }, "趋势卡该有柱状")
        assertTrue(shiftFlat.any { it is UiNode.Table }, "趋势卡该有明细表")
        assertTrue(shiftFlat.filterIsInstance<UiNode.Stat>().any { it.label == "逾期" })
    }

    @Test
    fun theStageBoardSampleBootsSeedsAndDrawsRichCards() {
        // 复杂样片：三个挂载点、三个渲染函数、六个交互，全走同一套词汇表。
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "examples/plugins/bento-stage-board/main.js") }
            .firstOrNull { it.exists() } ?: return

        val sandbox = ScriptSandbox("dev.bento.stageboard", file.readText())
        sandbox.boot()

        val cards = sandbox.declaredCards()
        assertEquals(3, cards.size)
        assertEquals(
            setOf("slot.home.header", "slot.home.section", "slot.report.chart"),
            cards.map { it.pointId }.toSet(),
        )

        val day = 86_400_000L
        val now = 20_000L * day + 36_000L * 1_000L
        val seeded = sandbox.runHandler("seed", emptyMap(), emptyMap())
        assertEquals("742", seeded.save["stage:sold:early"])
        assertTrue(seeded.save.size >= 8, "演示数据该覆盖票档、预算、检查、日志：" + seeded.save.keys)

        val data = mapOf("now" to now, "store" to seeded.save)
        val stage = flattenNode(NodeBuilder.build(sandbox.render("stage", data)))
        assertTrue(stage.any { it is UiNode.Bars }, "票档该有柱状")
        assertTrue(stage.any { it is UiNode.HeatStrip }, "该有现场人数方格")
        assertTrue(stage.any { it is UiNode.Table }, "票档该有表格")
        assertTrue(stage.filterIsInstance<UiNode.Ring>().size >= 2, "上座率/预算两个环")

        val ops = flattenNode(NodeBuilder.build(sandbox.render("ops", data)))
        assertTrue(ops.any { it is UiNode.Stepper }, "现场人数该有 stepper")
        assertTrue(ops.count { it is UiNode.Table } >= 1, "风险/日志该有表格")

        val world = mapOf("now" to now, "store" to seeded.save)
        val sold = sandbox.runHandler("sell", world, mapOf("tier" to "door"))
        assertEquals("317", sold.save["stage:sold:door"])
        val crowd = sandbox.runHandler("checkin", world, emptyMap())
        assertEquals("127", crowd.save["stage:crowd"])
    }

    private fun flattenNode(node: UiNode): List<UiNode> = buildList {
        add(node)
        when (node) {
            is UiNode.Column -> node.children.forEach { addAll(flattenNode(it)) }
            is UiNode.Row -> node.children.forEach { addAll(flattenNode(it)) }
            is UiNode.Section -> node.children.forEach { addAll(flattenNode(it)) }
            is UiNode.Grid -> node.children.forEach { addAll(flattenNode(it)) }
            is UiNode.Cell -> addAll(flattenNode(node.child))
            else -> Unit
        }
    }

    @Test
    fun aHeatStripClampsItsCellsAndNeedsValues() {
        val script = """
            trellis.register({
              cards: [
                { id: "big", point: "slot.home.section", order: 10, render: function (data) {
                    var values = [];
                    for (var i = 0; i < 100; i++) { values.push("3"); }
                    return { type: "heat", values: values, max: 400 };
                } },
                { id: "broken", point: "slot.home.section", order: 20, render: function (data) {
                    return { type: "heat" };
                } }
              ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()

        val big = NodeBuilder.build(sandbox.render("big", emptyMap())) as UiNode.HeatStrip
        assertEquals(28, big.values.size, "最多 28 格")
        assertEquals(100, big.max, "max 夹到 100")

        val broken = NodeBuilder.build(sandbox.render("broken", emptyMap())) as UiNode.Notice
        assertEquals(UiNoticeTone.Problem, broken.tone)
        assertTrue(broken.text.contains("values"), broken.text)
    }
}
