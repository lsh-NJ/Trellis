package dev.trellis.app.desktop

import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 眼睛和手：让 agent 真的能看见界面、真的能点。
 *
 * 只给这三样，不给 shell：一个 `exec` 等于把整个家目录递过去，而且它看不见结果、
 * 每次还起进程。这里每一步都有名字、有边界、有返回值 —— 「补上眼睛」，不是「放出一只手」。
 *
 * [describe_screen] 是文字版截图：它回答「现在哪一页、每个挂载点有什么、卡片写着什么」，
 * 纯文本模型也读得懂；[take_screenshot] 是像素版，给人看、也给下一步的 click 定坐标。
 */
class ScreenTools(private val runtime: AppRuntime) {

    /**
     * 光给工具不够，还得说清怎么用。
     *
     * 不说，模型会「装完就宣布成功」——它看不见界面，却会把自己刚写的代码当成事实。
     */
    val guidance: String = listOf(
        "<how_to_see_and_touch>",
        "1. 装了不等于界面上真的出现了。核对用 describe_screen（文字，永远可用）或 take_screenshot（像素）。",
        "2. 截图会作为图片直接交给你看（默认按视觉模型处理），同时存成 PNG 给用户；click 的坐标也用它算。",
        "3. 想看书里的某一页：open_tab 切过去，再 describe_screen。比猜页签坐标可靠。",
        "4. click 的坐标从最近一张截图的左上角算，只能点那张图里有的地方。没截图就点，会被拒绝。",
        "5. 点完、打完字之后，再 describe_screen 或 take_screenshot 确认结果，不要猜。",
        "6. 要打字：先 click 输入框，再 type_text，需要提交就 press_key(\"enter\")。",
        "</how_to_see_and_touch>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(
        DescribeScreen(),
        TakeScreenshot(),
        OpenTab(),
        Click(),
        TypeText(),
        PressKey(),
    )

    private inner class DescribeScreen : AgentTool {
        override val name: String = "describe_screen"
        override val description: String =
            "用文字描述用户现在看到的界面：在哪一页、每个挂载点上有什么卡片、卡片里写的什么、数据是多少。调试插件的眼睛。"
        override val parameters: String = "不需要参数。"
        override suspend fun call(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            ToolResult(runtime.describeScreen())
        }
    }

    private inner class TakeScreenshot : AgentTool {
        override val name: String = "take_screenshot"
        override val description: String =
            "把应用窗口截成 PNG 存到磁盘，并把这张图直接附给你看；截过之后 click 才有坐标可用。"
        override val parameters: String = "不需要参数。"
        override suspend fun call(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val screen = runtime.captureScreen()
            val failed = screen.text.startsWith("截图失败") || screen.text.startsWith("当前环境没有屏幕")
            ToolResult(screen.text, isError = failed, image = screen.dataUrl)
        }
    }

    private inner class OpenTab : AgentTool {
        override val name: String = "open_tab"
        override val description: String =
            "切到某一页：任务 / 对话 / 插件 / 诊断。切完再用 describe_screen 看那一页有什么。"
        override val parameters: String = "tab：页码名字，任务 / 对话 / 插件 / 诊断。"
        override suspend fun call(arguments: JsonObject): ToolResult {
            val tab = arguments["tab"]?.jsonPrimitive?.contentOrNull
                ?: return ToolResult("open_tab 需要 tab", isError = true)
            val text = runtime.openTab(tab)
            return ToolResult(text, isError = text.startsWith("没有这一页"))
        }
    }

    private inner class Click : AgentTool {
        override val name: String = "click"
        override val description: String =
            "按最近一张截图的像素坐标点一下鼠标左键。坐标越界会被拒绝，需要重新截图。"
        override val parameters: String =
            "x：整数，从截图左边算；y：整数，从截图上面算。"
        override suspend fun call(arguments: JsonObject): ToolResult {
            val x = arguments["x"]?.jsonPrimitive?.intOrNull
                ?: return ToolResult("click 需要整数 x", isError = true)
            val y = arguments["y"]?.jsonPrimitive?.intOrNull
                ?: return ToolResult("click 需要整数 y", isError = true)
            return withContext(Dispatchers.IO) { ToolResult(runtime.clickScreen(x, y)) }
        }
    }

    private inner class TypeText : AgentTool {
        override val name: String = "type_text"
        override val description: String =
            "往当前焦点里输入一段文字（走剪贴板，中文可用）。先 click 输入框再调用。"
        override val parameters: String = "text：字符串，要输入的文字。"
        override suspend fun call(arguments: JsonObject): ToolResult {
            val text = arguments["text"]?.jsonPrimitive?.contentOrNull
                ?: return ToolResult("type_text 需要 text", isError = true)
            return withContext(Dispatchers.IO) { ToolResult(runtime.typeText(text)) }
        }
    }

    private inner class PressKey : AgentTool {
        override val name: String = "press_key"
        override val description: String =
            "敲一个键：enter 提交、escape 关弹窗、tab 换焦点、backspace 删字、space 空格。"
        override val parameters: String = "key：enter / escape / tab / backspace / space。"
        override suspend fun call(arguments: JsonObject): ToolResult {
            val key = arguments["key"]?.jsonPrimitive?.contentOrNull
                ?: return ToolResult("press_key 需要 key", isError = true)
            val text = withContext(Dispatchers.IO) { runtime.pressKey(key) }
            return ToolResult(text, isError = text.startsWith("只认") || text.startsWith("按不动"))
        }
    }
}
