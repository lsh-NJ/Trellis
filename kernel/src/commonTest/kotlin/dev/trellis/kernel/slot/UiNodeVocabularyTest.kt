package dev.trellis.kernel.slot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 词表的小不变量（批 6）：插件写的卡片最终由宿主排版，插件表达力的缺口要在这里钉住。
 */
class UiNodeVocabularyTest {
    @Test
    fun textIsUnlimitedUnlessThePluginAsksOtherwise() {
        assertNull(UiNode.Text("随便多长").maxLines)
        assertEquals(2, UiNode.Text("两行", maxLines = 2).maxLines)
    }

    @Test
    fun noticesCanSayWarningWithoutSayingError() {
        val tones = UiNoticeTone.entries.map { it.name }
        assertTrue("Warning" in tones, "提示条需要「警告」这一档，不能只有普通/出错")
        assertEquals(UiNoticeTone.Hint, UiNode.Notice("默认还是普通提示").tone)
    }

    @Test
    fun newRowsAndControlsArePlainData() {
        val row = UiNode.ListRow("计算机网络速通", subtitle = "第 3 周", trailing = "进行中")
        assertEquals("计算机网络速通", row.title)
        assertEquals(null, row.onTap, "不写 on/action 的行只是展示，不该可点")

        val toggle = UiNode.Switch("每天提醒", checked = true, onTap = UiTap.Plugin("toggle"))
        assertTrue(toggle.checked)

        val chip = UiNode.Chip("本周", selected = true)
        assertTrue(chip.selected)
        assertEquals(null, chip.onTap)
    }
}
