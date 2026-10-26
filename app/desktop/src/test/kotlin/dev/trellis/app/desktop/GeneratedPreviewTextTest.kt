package dev.trellis.app.desktop

import dev.trellis.kernel.slot.UiNode
import dev.trellis.kernel.slot.UiTap
import dev.trellis.kernel.slot.UiTextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 脚本插件的卡片是运行时画出来的节点树，预览卡只有标题和正文两个位置。
// 这几个用例钉住「摊平」这一步：摊不开就误报「这个插件没有往界面上放任何卡片」。
class GeneratedPreviewTextTest {

    @Test
    fun aScriptCardIsFlattenedIntoATitleAndABody() {
        val node = UiNode.Column(
            listOf(
                UiNode.Text("这周 3 个", UiTextStyle.Title),
                UiNode.Text("今天 1 个"),
                UiNode.Input(
                    hint = "今天做了几个",
                    submitLabel = "记一笔",
                    onSubmit = UiTap.Plugin("add"),
                ),
            ),
        )

        val (title, body) = node.toPreview()

        assertEquals("这周 3 个", title)
        assertTrue(body.contains("今天 1 个"), body)
        assertTrue(body.contains("今天做了几个"), body)
    }

    @Test
    fun aNoticeOnlyCardStillGetsATitle() {
        val (title, body) = UiNode.Notice("今天已经记过了").toPreview()

        assertEquals("今天已经记过了", title)
        assertEquals("", body)
    }

    @Test
    fun anEmptyTreeBecomesAPlaceholderInsteadOfNothing() {
        val (title, _) = UiNode.Divider.toPreview()

        assertTrue(title.isNotBlank(), "摊不出文字也要给占位，否则预览会误报没有卡片")
    }
}
