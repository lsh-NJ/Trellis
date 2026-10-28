package dev.trellis.activity

import kotlinx.serialization.Serializable

/**
 * 一条分类规则：命中就归到 [name] 这个分类下。
 *
 * 为什么规则写在 Trellis 里、不去读 ActivityWatch 的 `category_sets`：
 * AW 那份分类是给它自己界面画饼图用的，改一次要在 AW 的 settings 里手写正则；
 * 而 Trellis 需要的是「分类名能挂到计划任务上」，这份映射只有本程序知道。
 * 两边规则各活各的，谁也不往谁那儿写。
 */
@Serializable
data class CategoryRule(
    /** 分类路径，例如 `listOf("工作", "编程")`。用列表而不是 `"工作/编程"`：分类名里出现斜杠时不会炸。 */
    val name: List<String>,
    /** 正则。**永远按忽略大小写匹配** —— 进程名和窗口标题的大小写没有任何信息量。 */
    val pattern: String,
    /** 拿事件里的哪个字段去匹配。 */
    val field: RuleField = RuleField.APP,
    /** 数字大的先匹配；同优先级按声明顺序。 */
    val priority: Int = 100,
)

/** [CategoryRule] 拿事件里的哪个字段去匹配。 */
@Serializable
enum class RuleField {
    /** 进程名，例如 `chrome.exe`。 */
    APP,

    /** 窗口标题。用来区分「同一个浏览器在干不同的活」。 */
    TITLE,

    /** 进程名 + 空格 + 标题。 */
    BOTH,
}

/**
 * 一整套分类规则。
 *
 * 它只是数据（可序列化、能存盘、用户能改），真正干活的是 [classifier] 返回的
 * [ActivityClassifier] —— 正则在那里编译一次就复用。
 * 不把编译结果塞回这个 data class 的原因：一天 3700 条事件 × 20 条规则，
 * 每条事件现编译一次正则是七万次编译，而那一步会在拖动时间轴时被反复调用。
 */
@Serializable
data class ActivityTaxonomy(
    val rules: List<CategoryRule> = DEFAULT_RULES,
    /** 一条规则都没命中时归到这里。 */
    val uncategorized: List<String> = UNCATEGORIZED,
) {

    fun classifier(): ActivityClassifier = ActivityClassifier(rules, uncategorized)

    companion object {
        val UNCATEGORIZED: List<String> = listOf("未分类")

        /**
         * 出厂规则。故意只覆盖「一眼能认出来」的那些进程，
         * 剩下的留在「未分类」里 —— 一个塞满猜测的默认值比空表更难改，因为你不知道哪条在起作用。
         *
         * 优先级只用了一处区分：标题类规则（影音/视频）要压过进程类规则（浏览），
         * 否则在 B 站看视频会被算成「浏览」。
         */
        val DEFAULT_RULES: List<CategoryRule> = listOf(
            CategoryRule(
                name = listOf("工作", "编程"),
                pattern = "DeepSeek Harness|Code\\.exe|idea64|studio64|devenv\\.exe|pycharm|webstorm|goland|clion|rider64|sublime_text|notepad\\+\\+|gvim|emacs|Cursor\\.exe",
            ),
            CategoryRule(
                name = listOf("工作", "设计"),
                pattern = "Trellis|Blender|Maya|3ds Max|ZBrush|Substance|Photoshop|Illustrator|Figma|Krita|Inkscape|AnyLabel",
            ),
            CategoryRule(
                name = listOf("工作", "办公"),
                pattern = "WINWORD|EXCEL|POWERPNT|OUTLOOK|wps|et\\.exe|wpp|soffice|Obsidian|Typora|Notion|OneNote|Acrobat|SumatraPDF",
            ),
            CategoryRule(
                name = listOf("工作", "终端"),
                pattern = "WindowsTerminal|^cmd\\.exe$|powershell|pwsh|mintty|ConEmu|WezTerm|Tabby|alacritty",
            ),
            CategoryRule(
                name = listOf("影音", "视频"),
                pattern = "vlc|mpc-hc|PotPlayer|mpv\\.exe|bilibili|哔哩哔哩|YouTube|Netflix|腾讯视频|爱奇艺|优酷",
                field = RuleField.BOTH,
                priority = 120,
            ),
            CategoryRule(
                name = listOf("影音", "音乐"),
                pattern = "QQMusic|CloudMusic|Spotify|foobar2000|AIMP|MusicBee|网易云音乐",
                priority = 120,
            ),
            CategoryRule(
                name = listOf("游戏"),
                pattern = "steam|EpicGamesLauncher|Battle\\.net|WeGame|Xbox|aimlab|Endfield|VALORANT|RiotClient|GenshinImpact|YuanShen|LeagueClient",
                field = RuleField.BOTH,
                priority = 110,
            ),
            CategoryRule(
                name = listOf("通讯", "微信"),
                pattern = "Weixin|WeChat|微信",
                priority = 130,
            ),
            CategoryRule(
                name = listOf("通讯", "QQ"),
                pattern = "^QQ(\\.exe)?$|QQNT",
                priority = 130,
            ),
            CategoryRule(
                name = listOf("通讯", "其他"),
                pattern = "Telegram|Discord|Slack|Teams|DingTalk|钉钉|Feishu|Lark|飞书|wemeet|TencentMeeting|Zoom",
                priority = 120,
            ),
            CategoryRule(
                name = listOf("浏览", "浏览器"),
                pattern = "chrome|msedge|firefox|brave|opera|vivaldi|Arc\\.exe|360se|QQBrowser",
            ),
            CategoryRule(
                name = listOf("工具", "文件管理"),
                pattern = "^explorer\\.exe$|TotalCMD|Double Commander|Everything",
                priority = 90,
            ),
            CategoryRule(
                name = listOf("工具", "系统"),
                pattern = "Snipaste|ShareX|PowerToys|clash|verge|v2ray|7zFM|WinRAR|Bandizip",
                priority = 90,
            ),
            CategoryRule(
                name = listOf("系统"),
                pattern = "dwm\\.exe|SearchApp\\.exe|LockApp\\.exe|ShellExperienceHost|StartMenuExperienceHost|TextInputHost|ApplicationFrameHost|SystemSettings|Taskmgr|aw-qt|pythonw",
                priority = 50,
            ),
        )
    }
}

/**
 * 编译好的分类器。构造一次，之后 [classify] 只是若干次 `containsMatchIn`。
 *
 * 行为约定：
 * - 按 `priority` 从大到小试，**第一条命中的就是结果**，后面的不再看（所以「未分类」不会遮住任何东西）。
 * - 写坏的正则被静默跳过，不抛异常 —— 分类是后台整理，不该因为用户改错一条正则就整页空白。
 * - 进程名为 null（例如浏览器 watcher 的事件）时，APP 规则直接跳过，落到下一条。
 */
class ActivityClassifier(
    rules: List<CategoryRule>,
    private val uncategorized: List<String>,
) {

    private class Compiled(val name: List<String>, val field: RuleField, val regex: Regex)

    private val compiled: List<Compiled> = rules
        .sortedByDescending { it.priority }
        .mapNotNull { rule ->
            if (rule.name.isEmpty()) return@mapNotNull null
            val regex = runCatching { Regex(rule.pattern, setOf(RegexOption.IGNORE_CASE)) }.getOrNull()
                ?: return@mapNotNull null
            Compiled(rule.name.toList(), rule.field, regex)
        }

    fun classify(app: String?, title: String?): List<String> {
        for (rule in compiled) {
            val subject = when (rule.field) {
                RuleField.APP -> app
                RuleField.TITLE -> title
                RuleField.BOTH -> listOfNotNull(app, title).joinToString(" ")
            }
            if (subject != null && rule.regex.containsMatchIn(subject)) return rule.name
        }
        return uncategorized
    }
}