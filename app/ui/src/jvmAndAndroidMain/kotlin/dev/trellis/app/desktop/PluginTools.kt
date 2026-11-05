package dev.trellis.app.desktop

import dev.trellis.ai.AuthoredPlugin
import dev.trellis.ai.AuthoredScript
import dev.trellis.ai.ScriptActionDecl
import dev.trellis.ai.ScriptCardDecl
import dev.trellis.ai.ScriptThemeDecl
import dev.trellis.ai.CapabilityCatalog
import dev.trellis.ai.flow.Issue
import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import dev.trellis.kernel.api.Kernel
import dev.trellis.kernel.api.UninstallResult
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.script.ScriptSandbox
import dev.trellis.kernel.event.EventDeclaration
import dev.trellis.kernel.extension.KernelPoints
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 交给 agent 的那一套工具。
 *
 * ### 为什么是这几个
 *
 * 模型写不好插件的真正原因不是「不会写 JSON」，是**看不见环境**：
 * 它不知道现在装着什么、有哪些事件和动作能用、写出来的东西能不能跑。
 * 这四个问题各配一个工具，就够它自己把事做完了。
 *
 * ### 为什么没有 bash
 *
 * 给它 shell 等于把整个用户目录递过去，而且报错不在 stdout 里、
 * 每次还要起一个进程。这是「补上眼睛」，不是「放出一只手」。
 */
class PluginTools(
    private val kernel: Kernel,
    private val installer: KernelPluginInstaller,
    private val selfTest: PluginSelfTest,
    private val pluginsDir: File,
    private val catalog: CapabilityCatalog,
) {

    /** deploy 成功时装上的插件 id。外面靠它判断「这次到底装成了没有」。 */
    var deployedId: String? = null
        private set

    /** 这一轮到底动过手没有：装了、停用了、删掉了都算。只有「看了看」不算。 */
    private var acted: Boolean = false

    /**
     * 它说「做完了」之前的核对。
     *
     * 模型会理直气壮地说「这个插件已经装好了，不用再动」，而这一轮它一个文件都没写 ——
     * 它只是 list_plugins 看了一眼，然后把自己刚才的话当成了事实。
     * 这种话靠提示词防不住，得在交活的时候真的去核对。
     */
    fun reviewBeforeFinishing(): String? {
        if (acted) return null
        return "你这一轮只看了看，没有装、也没有改，所以不能说「已经装好了」。" +
            "要么现在就用 try_plugin 试跑、再用 deploy 装上去；" +
            "要么用 cannotDo 说清楚为什么不做。"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 光给工具不够，还得说清怎么用它们干活。
     * 不说，模型会把 deploy 当第一步，把工具当摆设。
     */
    val guidance: String = listOf(
        "<how_to_work>",
        "0. 先做满，再谈退：这件事如果脚本能写（有输入、有计算、有存取），就别只摆一张卡；规则能自动做，就别让用户自己点。够得着的先够。",
        "1. 先看清楚：list_plugins 看已经装着什么，list_events 和 list_actions 看有哪些事件和动作能用。",
        "2. 要改一个已装的插件：read_plugin 把它的文件读回来，在原文上改，再 deploy 同一个 id。不要用新 id 另写一个近似的东西。",
        "3. 要写新的：先 try_plugin 试跑，报错就改，跑通了再 deploy。",
        "4. 用户说某个插件不要了：remove_plugin 删掉它（进回收站，七天内能还原）；只是暂时不想用就用 disable_plugin。内置插件删不掉，别白试。",
        "5. 做不到时先退一步，再谈拒绝：换交互、换数据源、换呈现、换触发，退到哪一级就写进 limitation。四条路都试过还是不行，才 cannotDo。不吭声地退一步，比直接拒绝更糟。",
        "6. 没说 done 之前不要编事实：你只知道自己调工具看到的东西，「应该已经装好了」不是依据。",
        "7. 做「高密度数据看板」风格（纸感米白 + 荧光青柠 + 墨黑、硬边小圆角）：主题切 dev.bento.shift/bento（装了才有），" +
            "卡片样式设 bento，导航放左侧；卡片内部用 stat 配 text(size:'huge', weight:'bold') 做大数字、" +
            "text(size:'small', font:'mono') 做小标、bars / sparkline / ring 做走势、table 做明细、grid(columns:2..4) 分栏。",
        "</how_to_work>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(
        ListPlugins(),
        ListEvents(),
        ListActions(),
        ReadPlugin(),
        DisablePlugin(),
        RemovePlugin(),
        TryPlugin(),
        Deploy(),
    )

    /**
     * 把模型给的 JSON 当成插件草稿读进来。读不出来就是一条能照着改的话。
     *
     * 两种草稿：带 script 字段的是「会跑的小应用」，其余是声明式卡片。
     * 用字段而不是 kind 来区分，是因为少一个字段就少一次它写错的机会。
     */
    private fun draftOf(arguments: JsonObject): Any {
        val raw = (arguments["plugin"] as? JsonObject) ?: arguments
        if (raw["script"] != null) {
            val script = try {
                json.decodeFromString<AuthoredScript>(raw.toString())
            } catch (t: Throwable) {
                return "这份脚本插件 JSON 读不进来：" + (t.message ?: "格式不对") +
                    "。字段只有 id / name / description / usage / limitation / capabilities / script。"
            }
            val problems = script.validate()
            if (problems.isNotEmpty()) return problems
            return script
        }
        val authored = try {
            json.decodeFromString<AuthoredPlugin>(raw.toString())
        } catch (t: Throwable) {
            return "这份插件 JSON 读不进来：" + (t.message ?: "格式不对") +
                "。字段只有 id / name / description / howToUse / limitation / fields / rules / slots。"
        }
        val problems = authored.validate(catalog)
        if (problems.isNotEmpty()) return problems
        return authored
    }

    /** 一份草稿变成了真要落盘的文件。 */
    private class Prepared(
        val files: Map<String, String>,
        val capabilities: Set<String>,
    )

    private fun idOf(draft: Any): String = when (draft) {
        is AuthoredPlugin -> draft.id
        is AuthoredScript -> draft.id
        else -> ""
    }

    /**
     * 把草稿变成文件。
     *
     * 脚本草稿必须**先真跑一遍**：manifest 里的 contributes 要让宿主数出
     * 脚本实际注册了哪些卡片，而不是听模型自己说。模型说「我挂了三张」而
     * 实际只注册了一张，这种谎言只有跑一遍才看得见。
     */
    private suspend fun prepare(draft: Any): Any {
        if (draft is AuthoredPlugin) {
            return Prepared(draft.toFiles(catalog), draft.capabilities(catalog))
        }
        val script = draft as AuthoredScript
        val sandbox = ScriptSandbox(pluginId = script.id, source = script.script)
        var cards: List<ScriptCardDecl> = emptyList()
        var actions: List<ScriptActionDecl> = emptyList()
        var themes: List<ScriptThemeDecl> = emptyList()
        try {
            withContext(Dispatchers.Default) { sandbox.boot() }
            cards = sandbox.declaredCards().map { ScriptCardDecl(it.id, it.pointId) }
            actions = sandbox.declaredActions().map {
                ScriptActionDecl("script." + script.id + "." + it.id, it.label)
            }
            themes = sandbox.declaredThemes().map {
                ScriptThemeDecl(it.id, it.name, it.description, it.light, it.dark)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            return listOf("脚本跑不起来：" + (t.message ?: "未知错误"))
        } finally {
            runCatching { sandbox.close() }
        }
        if (cards.isEmpty() && themes.isEmpty()) {
            return listOf("脚本里既没有卡片也没有主题，装上不会有任何东西")
        }
        for (theme in themes) {
            val problems = themeProblems(theme.light, theme.dark)
            if (problems.isNotEmpty()) {
                return listOf("主题「" + theme.name + "」不能用：" + problems.joinToString("；"))
            }
        }
        val unknown = cards.filter { it.pointId !in catalog.mountPointIds }
        if (unknown.isNotEmpty()) {
            return listOf(
                "卡片挂到了不存在的位置：" + unknown.joinToString("、") { it.pointId } +
                    "。能用的是：" + catalog.mountPointIds.sorted().joinToString("、"),
            )
        }
        return Prepared(script.toFiles(cards, actions, themes), script.capabilities.toSet())
    }

    private inner class ListPlugins : AgentTool {
        override val name = "list_plugins"
        override val description = "看现在已经装了哪些插件。动手之前先看，别重造已有的东西。"
        override val parameters = "无"
        override suspend fun call(arguments: JsonObject): ToolResult {
            val running = kernel.liveCatalog()
            val disabled = kernel.disabledPlugins()
            if (running.isEmpty() && disabled.isEmpty()) return ToolResult("现在一个插件都没有。")
            val text = buildString {
                running.forEach { appendLine("- " + it.id.value + "　" + it.name + "：" + it.description) }
                disabled.forEach { appendLine("- " + it.value + "　（已停用）") }
            }
            return ToolResult(text.trimEnd())
        }
    }

    private inner class ListEvents : AgentTool {
        override val name = "list_events"
        override val description = "看有哪些事件能触发规则。写 rules 之前必须查。"
        override val parameters = "无"
        override suspend fun call(arguments: JsonObject): ToolResult {
            val events = kernel.extensions.all(KernelPoints.Event)
            if (events.isEmpty()) return ToolResult("现在没有任何事件。不要写 rules。")
            val text = buildString {
                events.forEach { event: EventDeclaration ->
                    appendLine("- " + event.type + "：" + event.label)
                    event.payload.forEach { field ->
                        appendLine("    {{ event." + field.name + " }}：" + field.description)
                    }
                }
            }
            return ToolResult(text.trimEnd())
        }
    }

    private inner class ListActions : AgentTool {
        override val name = "list_actions"
        override val description = "看规则和按钮能点名哪些动作、各自要什么参数。"
        override val parameters = "无"
        override suspend fun call(arguments: JsonObject): ToolResult {
            if (catalog.actions.isEmpty()) return ToolResult("现在没有任何动作。不要写 rules、不要加按钮。")
            val text = buildString {
                catalog.actions.forEach { action ->
                    val params = if (action.params.isEmpty()) {
                        "无"
                    } else {
                        action.params.joinToString("、") {
                            it.name + (if (it.required) "（必填）" else "（可选）") + "：" + it.description
                        }
                    }
                    appendLine("- " + action.id + "：" + action.label + "　参数：" + params)
                }
            }
            return ToolResult(text.trimEnd())
        }
    }

    private inner class ReadPlugin : AgentTool {
        override val name = "read_plugin"
        override val description = "读一个已装插件的源文件，用来改它。"
        override val parameters = "id：插件标识，例如 ai.today"
        override suspend fun call(arguments: JsonObject): ToolResult {
            val id = arguments["id"]?.jsonPrimitive?.content ?: return ToolResult("没给 id", isError = true)
            val dir = File(pluginsDir, id)
            if (!dir.isDirectory) return ToolResult("没有这个插件的目录：" + id, isError = true)
            val files = dir.listFiles()?.filter { it.isFile } ?: return ToolResult("目录是空的", isError = true)
            if (files.isEmpty()) return ToolResult("目录里没有文件", isError = true)
            val text = files.joinToString("\n\n") { file ->
                "--- " + file.name + " ---\n" + file.readText(Charsets.UTF_8)
            }
            return ToolResult(text)
        }
    }

    private inner class DisablePlugin : AgentTool {
        override val name = "disable_plugin"
        override val description = "停用一个插件：不再加载，文件和它的数据都留着，随时能再启用。"
        override val parameters = "id：插件 id，先用 list_plugins 看"
        override suspend fun call(arguments: JsonObject): ToolResult {
            val id = arguments["id"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (id.isBlank()) return ToolResult("没给 id，先用 list_plugins 看现在有哪些", isError = true)
            return when (val result = kernel.disable(PluginId(id))) {
                is UninstallResult.Uninstalled -> {
                    acted = true
                    ToolResult("停用了：" + id + "。数据留着，随时能启用。")
                }
                is UninstallResult.Rejected -> ToolResult("停不掉：" + result.error.detail, isError = true)
                else -> ToolResult("这个插件现在停不了：" + id, isError = true)
            }
        }
    }

    private inner class RemovePlugin : AgentTool {
        override val name = "remove_plugin"
        override val description = "删掉一个插件：文件进回收站，七天内能还原，到期连它的数据一起清。"
        override val parameters = "id：插件 id，先用 list_plugins 看"
        override suspend fun call(arguments: JsonObject): ToolResult {
            val id = arguments["id"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (id.isBlank()) return ToolResult("没给 id，先用 list_plugins 看现在有哪些", isError = true)
            if (id == deployedId) {
                return ToolResult("这是你这次刚装上的，别马上删。要换掉它就直接用同一个 id 重新 deploy。", isError = true)
            }
            return when (val result = kernel.remove(PluginId(id))) {
                is UninstallResult.Uninstalled -> ToolResult("删掉了：" + id + "，进了回收站，七天内还能还原。")
                is UninstallResult.Rejected -> ToolResult("删不掉：" + result.error.detail, isError = true)
                else -> ToolResult("这个插件现在删不了：" + id, isError = true)
            }
        }
    }

    private inner class TryPlugin : AgentTool {
        override val name = "try_plugin"
        override val description = "把草稿装进一个临时沙箱跑一遍，只报错、不留下任何东西。写不满意就先试，别急着 deploy。"
        override val parameters = "plugin：整份插件 JSON（和 deploy 一样）"
        override suspend fun call(arguments: JsonObject): ToolResult {
            val draft = draftOf(arguments)
            if (draft is String) return ToolResult(draft, isError = true)
            if (draft is List<*>) return ToolResult(draft.joinToString("\n"), isError = true)
            val prepared = prepare(draft)
            if (prepared is List<*>) return ToolResult(prepared.joinToString("\n"), isError = true)
            val files = (prepared as Prepared).files
            val issues: List<Issue> = selfTest.test(files)
            if (issues.isEmpty()) {
                return ToolResult("跑通了，没有报错。生成的文件：" + files.keys.joinToString("、"))
            }
            return ToolResult(issues.joinToString("\n") { it.render() }, isError = true)
        }
    }

    /**
     * 改已装的插件时，替它把版本号抬一档。
     *
     * 模型改的是正文，几乎不会想起版本号；而同 id 同版本在内核眼里是
     * 「重复安装」。抬版本没有歧义，这件小事不该占用它一次犯错机会。
     */
    private fun bumpIfUpdating(id: String, files: Map<String, String>): Map<String, String> {
        val installed = kernel.plugin(PluginId(id))?.manifest ?: return files
        val text = files["manifest.json"] ?: return files
        val current = runCatching {
            Json.parseToJsonElement(text).jsonObject["version"]?.jsonPrimitive?.content
        }.getOrNull() ?: return files
        if (current != installed.version.toString()) return files

        val bumped = installed.version.let { SemVer(it.major, it.minor, it.patch + 1) }
        val replaced = text.replace(
            Regex("\"version\"\\s*:\\s*\"" + Regex.escape(current) + "\""),
            "\"version\": \"" + bumped + "\"",
        )
        if (replaced == text) return files
        return files + ("manifest.json" to replaced)
    }

    private inner class Deploy : AgentTool {
        override val name = "deploy"
        override val description = "真正装上去。装之前会再自查一遍，所以先 try_plugin 通过再来。"
        override val parameters = "plugin：整份插件 JSON"
        override suspend fun call(arguments: JsonObject): ToolResult {
            val draft = draftOf(arguments)
            if (draft is String) return ToolResult(draft, isError = true)
            if (draft is List<*>) return ToolResult(draft.joinToString("\n"), isError = true)
            val prepared = prepare(draft)
            if (prepared is List<*>) return ToolResult(prepared.joinToString("\n"), isError = true)
            val ready = prepared as Prepared
            val files = bumpIfUpdating(idOf(draft), ready.files)
            val issues = selfTest.test(files)
            if (issues.isNotEmpty()) {
                return ToolResult("自查没过，没有装：\n" + issues.joinToString("\n") { it.render() }, isError = true)
            }

            val grant = installer.ensureGrants(idOf(draft), ready.capabilities)
            if (grant != null) return ToolResult(grant, isError = true)

            val failure = installer.install(files)
            if (failure != null) return ToolResult("装失败：" + failure, isError = true)

            deployedId = idOf(draft)
            acted = true
            return ToolResult("装好了：" + idOf(draft), done = true)
        }
    }
}
