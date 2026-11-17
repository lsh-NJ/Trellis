package dev.trellis.feature.learning

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 计划文档存储：文档是 `<home>/knowledge/plan-docs/` 下的 Markdown 文件（总览 / 周 / 草稿）。
 * 解析仍是纯函数；旧的 `plan_doc` 表与 `plans/docs` 只做一次性迁移来源。
 */
class PlanDocStoreTest {

    private val home: File = Files.createTempDirectory("trellis-plan-doc").toFile()
    private val service = LearningPlanStore.open(File(File(home, "plans"), "plan.db").toPath())
    private val store = PlanDocStore(home)

    /** `<home>/knowledge/plan-docs/`：计划文档根。 */
    private val docsRoot = File(File(home, "knowledge"), "plan-docs")

    @AfterTest
    fun tearDown() {
        store.close()
        service.close()
        home.deleteRecursively()
    }

    private fun env(): CommandEnvelope = CommandEnvelope(
        commandId = "cmd_test_" + UUID.randomUUID(),
        actor = Actor.USER,
        origin = "test",
    )

    private fun newPlan(title: String = "测试计划"): String {
        val created = service.createShell(env(), title, "把这件事做完")
        assertTrue(created.ok, "建计划失败：" + created.error?.message)
        return created.value!!.planId
    }

    /** 某份计划在计划文档目录里的文件路径（默认总文档 `总览.md`）。 */
    private fun planDocFile(planId: String, name: String = "总览.md"): File =
        File(File(docsRoot, planId), name)

    /** 草稿文件路径。 */
    private fun draftFile(draftId: String): File =
        File(File(docsRoot, "_drafts"), draftId + ".md")

    /** 直接往旧 `plan_doc` 表塞一行：模拟 2026-11-15 之后、文件化之前的旧数据。 */
    private fun insertLegacyDoc(planId: String, kind: String, week: String, markdown: String) {
        val db = PlanDatabase.open(File(File(home, "plans"), "plan.db").toPath())
        try {
            db.connection.prepareStatement(
                "INSERT INTO plan_doc(id,plan_id,kind,week,markdown,updated_at) VALUES(?,?,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, "doc_" + UUID.randomUUID().toString().replace("-", ""))
                ps.setString(2, planId)
                ps.setString(3, kind)
                ps.setString(4, week)
                ps.setString(5, markdown)
                ps.setString(6, "2026-11-15T00:00:00Z")
                ps.executeUpdate()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun saveThenReadRoundTripsTheMarkdownIntoThePlanDocsFolder() {
        val planId = newPlan()
        val markdown = "# 目标与验收\n- 目标A：学完\n"
        store.save(planId, markdown)

        assertEquals(markdown, store.read(planId))
        // 真相是文件：`knowledge/plan-docs/<planId>/总览.md`。
        val file = planDocFile(planId)
        assertTrue(file.isFile, "总文档要落在 knowledge/plan-docs 下：" + file)
        assertEquals(markdown, file.readText(Charsets.UTF_8))
        assertNotNull(store.load(planId))
        assertTrue(planId in store.planIds())
    }

    @Test
    fun aMissingDocumentReadsAsNull() {
        val planId = newPlan()
        assertNull(store.read(planId))
        assertNull(store.load(planId))
        assertTrue(store.weeks(planId).isEmpty())
        assertFalse(planId in store.planIds())
    }

    @Test
    fun saveReplacesTheOldDocument() {
        val planId = newPlan()
        store.save(planId, "第一版")
        assertEquals("第一版", store.read(planId))

        store.save(planId, "第二版")
        assertEquals("第二版", store.read(planId))
        assertEquals("第二版", planDocFile(planId).readText(Charsets.UTF_8))
    }

    @Test
    fun anInvalidPlanIdIsRejectedInsteadOfTouchingAnotherFile() {
        assertNull(store.read("../evil"))
        assertNull(store.load("../evil"))
        assertFailsWith<IllegalArgumentException> { store.save("../evil", "内容") }
        assertFailsWith<IllegalArgumentException> { store.saveWeek("../evil", "2026-W45", "内容") }
    }

    @Test
    fun savingADocumentForAnUnknownPlanFails() {
        assertFailsWith<IllegalArgumentException> { store.save("pln_missing", "内容") }
        assertFailsWith<IllegalArgumentException> { store.saveWeek("pln_missing", "2026-W45", "内容") }
    }

    @Test
    fun weekDocumentsRoundTripNewestFirst() {
        val planId = newPlan()
        store.saveWeek(planId, "2026-W45", "## 2026-W45\n- 条目一")
        store.saveWeek(planId, "2026-W46", "## 2026-W46\n- 条目二")

        assertEquals(listOf("2026-W46", "2026-W45"), store.weeks(planId))
        assertEquals("## 2026-W45\n- 条目一", store.readWeek(planId, "2026-W45"))
        assertTrue(planDocFile(planId, "2026-W45.md").isFile, "周文档按周命名放进计划目录")
        assertFailsWith<IllegalArgumentException> { store.saveWeek(planId, "第45周", "内容") }
    }

    @Test
    fun planIdsListsPlansThatHaveAnyDocument() {
        val overviewPlan = newPlan("有总览的计划")
        val weekOnlyPlan = newPlan("只有周文档的计划")
        val emptyPlan = newPlan("没有文档的计划")
        store.save(overviewPlan, "# 目标与验收\n")

        store.saveWeek(weekOnlyPlan, "2026-W45", "## 2026-W45\n- 条目")
        assertEquals(listOf(overviewPlan, weekOnlyPlan).sorted(), store.planIds())
        assertFalse(emptyPlan in store.planIds())
    }

    @Test
    fun aBomPrefixedFileIsReadWithoutTheBom() {
        val planId = newPlan("BOM 计划")
        planDocFile(planId).apply {
            parentFile?.mkdirs()
            writeText("\uFEFF# 目标与验收\n- 目标A\n", Charsets.UTF_8)
        }

        // Windows 工具写出来的文件常带 BOM：读的时候去掉，解析不要被它带偏。
        assertEquals("# 目标与验收\n- 目标A\n", store.read(planId))
        assertEquals(listOf("目标A"), store.load(planId)?.targets)
    }

    @Test
    fun aCorruptDocumentFileReadsAsNullInsteadOfCrashing() {
        val planId = newPlan("坏文档计划")
        planDocFile(planId).apply {
            parentFile?.mkdirs()
            // 非法 UTF-8 字节：0xC3 后面必须是续字节，0x28 不是。
            writeBytes(byteArrayOf(0x23, 0x20, 0xC3.toByte(), 0x28, 0x0A))
        }

        assertNull(store.read(planId))
        assertNull(store.load(planId))
        assertEquals(emptyList(), store.stages(planId))
    }

    @Test
    fun legacyPlanDocRowsAreMigratedIntoMissingFiles() {
        val planId = newPlan("表格里的旧文档")
        insertLegacyDoc(planId, "overview", "", "# 目标与验收\n- 表格里的旧目标\n")
        insertLegacyDoc(planId, "week", "2026-W45", "## 2026-W45\n- 表格里的旧条目\n")

        // 新开一个 store 触发迁移：旧表的行补成文件，之后读的就都是文件。
        val reopened = PlanDocStore(home)
        try {
            assertEquals("# 目标与验收\n- 表格里的旧目标\n", reopened.read(planId))
            assertEquals("## 2026-W45\n- 表格里的旧条目\n", reopened.readWeek(planId, "2026-W45"))
            assertEquals("# 目标与验收\n- 表格里的旧目标\n", planDocFile(planId).readText(Charsets.UTF_8))
            assertTrue(planDocFile(planId, "2026-W45.md").isFile)
            assertTrue(planId in reopened.planIds())
            assertEquals(listOf("2026-W45"), reopened.weeks(planId))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun migratingLegacyRowsNeverOverwritesExistingFiles() {
        val planId = newPlan("不覆盖计划")
        store.save(planId, "文件里的新内容")
        // 旧表里是上一版：迁移只补缺失的文件，绝不能把已经改过的文件覆盖回旧内容。
        insertLegacyDoc(planId, "overview", "", "旧表里的内容")

        val reopened = PlanDocStore(home)
        try {
            assertEquals("文件里的新内容", reopened.read(planId))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun legacyMarkdownFilesAreImportedOnceAndNeverOverwrite() {
        val planId = newPlan("旧文档计划")
        val docs = File(File(home, "plans"), "docs")
        File(docs, planId).mkdirs()
        assertTrue(docs.mkdirs() || docs.isDirectory)
        File(docs, "$planId.md").writeText("# 目标与验收\n- 旧的目标\n", Charsets.UTF_8)
        File(File(docs, planId), "2026-W45.md").writeText("## 2026-W45\n- 旧条目\n", Charsets.UTF_8)

        // 新开一个 store 会触发迁移；已有行不覆盖。
        val reopened = PlanDocStore(home)
        try {
            assertEquals("# 目标与验收\n- 旧的目标\n", reopened.read(planId))
            assertEquals("## 2026-W45\n- 旧条目\n", reopened.readWeek(planId, "2026-W45"))
            assertTrue(planDocFile(planId).isFile)

            File(docs, "$planId.md").writeText("后来改过的文件", Charsets.UTF_8)
            val third = PlanDocStore(home)
            try {
                assertEquals("# 目标与验收\n- 旧的目标\n", third.read(planId))
            } finally {
                third.close()
            }
        } finally {
            reopened.close()
        }
    }

    @Test
    fun aCorruptLegacyFileIsSkippedInsteadOfCrashingTheStore() {
        val planId = newPlan("坏文档计划")
        val docs = File(File(home, "plans"), "docs")
        docs.mkdirs()
        // 非法 UTF-8 字节：0xC3 后面必须是续字节，0x28 不是。
        File(docs, "$planId.md").writeBytes(byteArrayOf(0x23, 0x20, 0xC3.toByte(), 0x28, 0x0A))

        val reopened = PlanDocStore(home)
        try {
            assertNull(reopened.read(planId))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun draftsRoundTripAndStayOutOfPlanIds() {
        val markdown = "# 总计划：从零学 TypeScript\n\n## 阶段\n"
        store.saveDraft("draft_abc12345", markdown)

        assertEquals(markdown, store.readDraft("draft_abc12345"))
        assertTrue(draftFile("draft_abc12345").isFile, "草稿放在 plan-docs/_drafts 下")
        assertEquals(listOf("draft_abc12345"), store.draftIds())
        // 草稿不是计划文档，不能混进「有文档的计划」列表。
        assertFalse("draft_abc12345" in store.planIds())
        assertFalse("_drafts" in store.planIds())
    }

    @Test
    fun adoptingADraftWritesTheOverviewAndDeletesTheDraft() {
        val planId = newPlan("草稿落成计划")
        val markdown = "# 目标与验收\n- 目标A：学完\n## 阶段\n### 阶段 1 · 基础\n"
        store.saveDraft("draft_adopt", markdown)

        store.adoptDraft("draft_adopt", planId)

        assertEquals(markdown, store.read(planId))
        assertTrue(planDocFile(planId).isFile)
        assertNull(store.readDraft("draft_adopt"))
        assertFalse(draftFile("draft_adopt").exists())
        assertTrue(store.draftIds().isEmpty())
    }

    @Test
    fun adoptingIntoAMissingPlanKeepsTheDraft() {
        store.saveDraft("draft_orphan", "# 总计划\n")

        assertFailsWith<IllegalArgumentException> { store.adoptDraft("draft_orphan", "pln_missing") }
        // 写失败时草稿不能被删：用户还得拿它再试一次。
        assertEquals("# 总计划\n", store.readDraft("draft_orphan"))
    }

    @Test
    fun draftIdsAreValidatedAndDeletionToleratesMissingOnes() {
        assertFailsWith<IllegalArgumentException> { store.saveDraft("../evil", "内容") }
        assertNull(store.readDraft("../evil"))
        store.deleteDraft("draft_missing") // 不存在就当删过了
        store.deleteDraft("../evil") // 不合法 id 直接忽略，不碰任何文件
        assertEquals(emptyList(), store.draftIds())
        assertNull(store.readDraft("draft_missing"))
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

    /**
     * 2026-11-17 起周文档的条目用体量档标时长（小/中/大 = 15/30/45），不再写由预算
     * 摊出来的分钟数；但老文档的裸分钟数还得继续认，两者不能互相打架。
     */
    @Test
    fun parseUnderstandsSizeBands() {
        val doc = store.parse(
            """
            ## 2026-W45
            - 甲（中 · 约 30 分钟）
            - 乙（大）
            - 丙（小·15分钟）
            - 丁（大 O 表示）
            - 戊（约 90 分钟）
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                PlanDocItem("甲", 30),
                PlanDocItem("乙", 45),
                PlanDocItem("丙", 15),
                // 括号里的「大 O 表示」不是档位：不能把说明文字吃进时长。
                PlanDocItem("丁（大 O 表示）", null),
                // 老格式照旧。
                PlanDocItem("戊", 90),
            ),
            doc.weeks["2026-W45"],
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
        val planId = newPlan("阶段计划")
        store.save(
            planId,
            """
            ## 阶段
            ### 阶段 1 · 基础语法（2026-W45 – 2026-W46）
            - 聚焦：类型
            ### 阶段 2 · 工程化（约 2 周）
            - 聚焦：构建
            """.trimIndent(),
        )

        assertEquals("基础语法", store.stageForWeek(planId, "2026-W45")?.name)
        assertEquals("基础语法", store.stageForWeek(planId, "2026-W46")?.name)
        assertNull(store.stageForWeek(planId, "2026-W47"))
        assertNull(store.stageForWeek("pln_missing", "2026-W45"))
    }
}
