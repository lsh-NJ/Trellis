package dev.trellis.feature.learning

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 计划文档存储：`<home>/plans/docs/<planId>.md` 的读写与容错解析。 */
class PlanDocStoreTest {

    private val home: File = Files.createTempDirectory("trellis-plan-doc").toFile()
    private val store = PlanDocStore(home)

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    private val sample = """
        # 目标与验收
        - 目标A：把 TypeScript 学扎实
        - 验收：能独立写一个类型安全的模块

        ## 2026-W45
        - 计算机网络 3 节（约 90 分钟）
        - 物理 2 节

        ## 2026-W46
        - 数学（约 120 分钟）
        - 英语阅读 约90分钟

        ## 待安排
        - 数据结构实验
        - 编译原理课程设计（约 180 分钟）
    """.trimIndent()

    @Test
    fun saveThenReadRoundTripsTheMarkdown() {
        val markdown = "# 目标与验收\n- 目标A：学完\n"
        val file = store.save("pln_test1", markdown)

        assertEquals(File(File(File(home, "plans"), "docs"), "pln_test1.md"), file)
        assertTrue(file.isFile)
        assertEquals(markdown, store.read("pln_test1"))
        assertNotNull(store.load("pln_test1"))
    }

    @Test
    fun aDocumentPathIsGuardedAgainstDirectoryTraversal() {
        assertEquals(
            File(File(File(home, "plans"), "docs"), "pln_abc.md"),
            store.fileFor("pln_abc"),
        )
        assertFailsWith<IllegalArgumentException> { store.fileFor("../evil") }
        assertFailsWith<IllegalArgumentException> { store.fileFor("a/b") }
        assertNull(store.read("../evil"))
        assertNull(store.load("../evil"))
    }

    @Test
    fun aMissingDocumentReadsAsNull() {
        assertNull(store.read("pln_nothing"))
        assertNull(store.load("pln_nothing"))
        assertFalse(File(File(home, "plans"), "docs").exists())
    }

    @Test
    fun saveCreatesTheDocsDirectoryAndReplacesTheOldDocument() {
        store.save("pln_test1", "第一版")
        assertEquals("第一版", store.read("pln_test1"))

        store.save("pln_test1", "第二版")
        assertEquals("第二版", store.read("pln_test1"))
        assertEquals(listOf("pln_test1.md"), File(File(home, "plans"), "docs").list()!!.toList())
    }

    @Test
    fun parseReadsTargetsWeeksAndUnscheduledInDocumentOrder() {
        val doc = store.parse(sample)

        assertEquals(
            listOf("目标A：把 TypeScript 学扎实", "验收：能独立写一个类型安全的模块"),
            doc.targets,
        )
        assertEquals(listOf("2026-W45", "2026-W46"), doc.weeks.keys.toList())
        assertEquals(
            listOf(
                PlanDocItem("计算机网络 3 节", 90),
                PlanDocItem("物理 2 节", null),
            ),
            doc.weeks["2026-W45"],
        )
        assertEquals(
            listOf(
                PlanDocItem("数学", 120),
                PlanDocItem("英语阅读", 90),
            ),
            doc.weeks["2026-W46"],
        )
        assertEquals(
            listOf(
                PlanDocItem("数据结构实验", null),
                PlanDocItem("编译原理课程设计", 180),
            ),
            doc.unscheduled,
        )
    }

    @Test
    fun parseUnderstandsSeveralMinutesHintSpellings() {
        val doc = store.parse(
            """
            ## 待安排
            - 甲（约 90 分钟）
            - 乙(90分钟)
            - 丙 约90分钟
            - 丁（90 分钟）
            - 戊 90 分钟
            - 己（约 0 分钟）
            - 庚：复习第一章，约30分钟
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                PlanDocItem("甲", 90),
                PlanDocItem("乙", 90),
                PlanDocItem("丙", 90),
                PlanDocItem("丁", 90),
                PlanDocItem("戊 90 分钟", null),
                PlanDocItem("己（约 0 分钟）", null),
                PlanDocItem("庚：复习第一章", 30),
            ),
            doc.unscheduled,
        )
    }

    @Test
    fun parseToleratesJunkLinesAndUnknownSections() {
        val doc = store.parse(
            """
            一份随手写的笔记
            - 这个开头没有小节，忽略
            # 目标与验收
            这不是 bullet，忽略
            - 目标A
            ## 2026-W45
            - 条目一
            ### 子标题下面不认
            - 子条目忽略
            ## 不认识的节
            - 也不认
            ## 待安排
            * 星号条目
            1. 编号条目
            """.trimIndent(),
        )

        assertEquals(listOf("目标A"), doc.targets)
        assertEquals(mapOf("2026-W45" to listOf(PlanDocItem("条目一", null))), doc.weeks)
        assertEquals(listOf(PlanDocItem("星号条目", null), PlanDocItem("编号条目", null)), doc.unscheduled)
    }

    @Test
    fun anEmptyOrMissingSectionsDocumentParsesToAnEmptyStructure() {
        assertEquals(PlanDoc(), store.parse(""))
        assertEquals(PlanDoc(), store.parse("# 目标与验收\n"))
        assertEquals(PlanDoc(), store.parse("## 2026-W45\n"))
        // 空小节不产生空的 week 条目。
        val doc = store.parse("## 2026-W45\n\n## 2026-W46\n- 条目")
        assertEquals(listOf("2026-W46"), doc.weeks.keys.toList())
    }

    @Test
    fun aCorruptFileReadsAsNullInsteadOfThrowing() {
        val file = store.fileFor("pln_broken")
        file.parentFile.mkdirs()
        // 非法 UTF-8 字节：0xC3 后面必须是续字节，0x28 不是。
        file.writeBytes(byteArrayOf(0x23, 0x20, 0xC3.toByte(), 0x28, 0x0A))

        assertNull(store.read("pln_broken"))
        assertNull(store.load("pln_broken"))
    }

    @Test
    fun parseReadsStagesWithWeekRangesAndResources() {
        val doc = store.parse(
            """
            # 目标与验收
            - 目标A：做一个能讲的项目
            ## 阶段
            ### 阶段 1 · 基础语法（2026-W45 – 2026-W46，约 2 周）
            - 聚焦：类型系统与函数式写法
            - 交付物：一个类型安全的 CLI 小工具
            - 过关标准：能讲清每个类型别名的取舍
            - 资源：
              - [TypeScript 官方手册](https://www.typescriptlang.org/docs/) — 前 4 章
              - [类型体操](https://github.com/type-challenges/type-challenges)
            ### 阶段 2 · 工程化（2026-W47 – 2026-W48）
            - 聚焦：构建与测试
            ## 2026-W45
            - 条目一
            """.trimIndent(),
        )

        assertEquals(listOf("目标A：做一个能讲的项目"), doc.targets)
        assertEquals(listOf("2026-W45"), doc.weeks.keys.toList())
        assertEquals(2, doc.stages.size)
        val first = doc.stages.first()
        assertEquals("基础语法", first.name)
        assertEquals("2026-W45", first.weekStart)
        assertEquals("2026-W46", first.weekEnd)
        assertEquals(2, first.weeks)
        assertEquals("类型系统与函数式写法", first.focus)
        assertEquals("一个类型安全的 CLI 小工具", first.deliverable)
        assertEquals("能讲清每个类型别名的取舍", first.acceptance)
        assertEquals(
            listOf(
                DocResource("TypeScript 官方手册", "https://www.typescriptlang.org/docs/", "前 4 章"),
                DocResource("类型体操", "https://github.com/type-challenges/type-challenges", ""),
            ),
            first.resources,
        )
        assertEquals("工程化", doc.stages[1].name)
        assertEquals("2026-W47", doc.stages[1].weekStart)
        assertEquals(emptyList(), doc.stages[1].resources)
    }

    @Test
    fun parseReadsTheLegacyBulletStageFormat() {
        val doc = store.parse(
            """
            ## 阶段
            - 阶段 1 · 基础语法（约 2 周）：类型系统
              - 交付物：一个类型安全的 CLI 小工具
              - 过关标准：能讲清取舍
              - 资源：[官方手册](https://www.typescriptlang.org/docs/) — 第 1 章
            """.trimIndent(),
        )

        assertEquals(1, doc.stages.size)
        val stage = doc.stages.single()
        assertEquals("基础语法", stage.name)
        assertEquals(2, stage.weeks)
        assertNull(stage.weekStart)
        assertEquals("类型系统", stage.focus)
        assertEquals("一个类型安全的 CLI 小工具", stage.deliverable)
        assertEquals("能讲清取舍", stage.acceptance)
        assertEquals(
            listOf(DocResource("官方手册", "https://www.typescriptlang.org/docs/", "第 1 章")),
            stage.resources,
        )
    }

    @Test
    fun stageForWeekMatchesTheWeekRangeAndSkipsStagesWithoutOne() {
        store.save(
            "pln_stage_week",
            """
            ## 阶段
            ### 阶段 1 · 基础语法（2026-W45 – 2026-W46）
            - 聚焦：类型
            ### 阶段 2 · 工程化（约 2 周）
            - 聚焦：构建
            """.trimIndent(),
        )

        assertEquals("基础语法", store.stageForWeek("pln_stage_week", "2026-W45")?.name)
        assertEquals("基础语法", store.stageForWeek("pln_stage_week", "2026-W46")?.name)
        assertNull(store.stageForWeek("pln_stage_week", "2026-W47"))
        assertNull(store.stageForWeek("pln_missing", "2026-W45"))
    }

    @Test
    fun saveFailsWithAPlainMessageWhenTheDirectoryCannotBeCreated() {
        val blocked = File(home, "blocked")
        blocked.writeText("这里是个文件，不是目录")
        val failing = PlanDocStore(blocked)

        assertFailsWith<IOException> { failing.save("pln_test1", "内容") }
    }
}
