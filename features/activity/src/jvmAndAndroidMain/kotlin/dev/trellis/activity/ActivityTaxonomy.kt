package dev.trellis.activity

import kotlinx.serialization.Serializable
import java.net.URI

/**
 * 分类体系：一棵「大类 → 小类 → …」的树 + 每个分类上挂的正则规则。
 *
 * 和 ActivityWatch 的关系：**借机制，不共享数据**。
 * AW 的 `category_sets` 是给它自己画饼图用的，存成「名字路径数组 + 一条规则」；
 * 这里借了它的三条行为约定（[ActivityClassifier] 里写死）：
 *
 * 1. **层级越深越优先**：一条事件同时命中「工作」和「工作/编程」时归到更具体的那个；
 * 2. **显式 priority 压过层级**：`priority` 不为 null 时直接当排名用，不再看深度；
 * 3. **同排名先声明者胜**：AW 用稳定排序取第一个，这里也一样（顺序 = 分类列表顺序 + 规则声明顺序）。
 *
 * 另外两处刻意的不同（都有理由，见字段注释）：
 * - 分类有**稳定 id**（AW 用名字路径当身份，改名 = 另一条分类；这里改名不影响历史归因和以后挂任务）；
 * - 规则可以**显式选匹配字段**（进程名 / 标题 / URL / 域名），AW 是全局 app+title。
 *
 * 这个类只是**数据**（可序列化、能存盘、用户能改）；编译好的匹配器是 [classifier] 返回的
 * [ActivityClassifier]。不把正则编译结果塞回 data class：一天几千条事件 × 几十条规则，
 * 每条事件现编译一次正则会成为拖动时间轴时的卡顿来源。
 */
@Serializable
data class ActivityTaxonomy(
    /** 存盘格式版本。以后字段改名时用它做迁移，不要靠猜。 */
    val version: Int = CURRENT_VERSION,
    /** 扁平存放全部分类；父子关系靠 [CategoryNode.parentId]，展示顺序靠 [CategoryNode.order]。 */
    val categories: List<CategoryNode> = DEFAULT_CATEGORIES,
) {

    /** 编译一次，之后复用。规则改了就重新构造一个 taxonomy。 */
    fun classifier(): ActivityClassifier = ActivityClassifier(this)

    fun node(id: String?): CategoryNode? = id?.let { target -> categories.firstOrNull { it.id == target } }

    /** 某个父分类下的直接子分类，按 [CategoryNode.order] 排（同 order 按 id，保证顺序稳定）。 */
    fun childrenOf(parentId: String?): List<CategoryNode> =
        categories.filter { it.parentId == parentId }.sortedWith(compareBy({ it.order }, { it.id }))

    /** 从根到 [id] 的完整路径（含自己）。id 不存在或父子成环时返回尽量长的一段。 */
    fun pathOf(id: String): List<CategoryNode> {
        val out = ArrayList<CategoryNode>()
        val seen = HashSet<String>()
        var current = node(id)
        while (current != null && seen.add(current.id)) {
            out += current
            current = node(current.parentId)
        }
        out.reverse()
        return out
    }

    /** 从根到 [id] 的分类名，例如 `["工作", "编程"]`。 */
    fun pathNames(id: String): List<String> = pathOf(id).map { it.name }

    /** 深度：根分类是 1，二级是 2……。不存在返回 0。 */
    fun depthOf(id: String): Int = pathOf(id).size

    /** 所有后代（不含自己）。 */
    fun descendantsOf(id: String): List<CategoryNode> {
        val out = ArrayList<CategoryNode>()
        val queue = ArrayDeque(childrenOf(id))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            out += current
            queue += childrenOf(current.id)
        }
        return out
    }

    /** [candidateId] 是不是 [ancestorId] 本身或它的后代。给「按大类过滤」用。 */
    fun isDescendantOf(candidateId: String, ancestorId: String): Boolean =
        candidateId == ancestorId || pathOf(candidateId).any { it.id == ancestorId }

    /** 按名字路径找分类，例如 `findByPath(listOf("工作", "编程"))`。 */
    fun findByPath(path: List<String>): CategoryNode? =
        categories.firstOrNull { pathNames(it.id) == path }

    /**
     * 插入或替换一个分类（按 id），返回新树。
     *
     * 给程序内的分类编辑器用：界面只改一个节点的名字/颜色/规则，然后整棵树换掉，
     * 不在原地改 [categories]（数据类保持不可变，存盘失败时手里的旧树还能用）。
     */
    fun withCategory(node: CategoryNode): ActivityTaxonomy {
        val exists = categories.any { it.id == node.id }
        val next = if (exists) {
            categories.map { if (it.id == node.id) node else it }
        } else {
            categories + node
        }
        return copy(categories = next)
    }

    /**
     * 删掉一个分类，返回新树：它的子类挂到它的父级下（不连坐删光用户建的小类），
     * 它自己的规则一起删掉。`uncategorized` 是保留分类，删不掉。
     */
    fun withoutCategory(id: String): ActivityTaxonomy {
        if (id == UNCATEGORIZED_ID) return this
        val target = node(id) ?: return this
        val next = categories
            .filterNot { it.id == id }
            .map { if (it.parentId == id) it.copy(parentId = target.parentId) else it }
        return copy(categories = next)
    }

    /** 给新建分类生成一个没被占用的 id：`cat-1`、`cat-2`……（id 定了就别再改，历史归因靠它）。 */
    fun nextCategoryId(): String {
        val used = categories.mapTo(HashSet()) { it.id }
        var n = 1
        while (("cat-$n") in used) n++
        return "cat-$n"
    }

    /**
     * 补齐可以推导的东西，交给 classifier / UI 用：
     * - 缺颜色（空串或写坏）的按 id 哈希从 [PALETTE] 里取一个稳定的颜色；
     * - 没有 `uncategorized` 兜底分类时补一个。
     *
     * 不做的事：不修父子环、不删重复 id —— 那是 [validate] 报给用户看的事，
     * 后台整理不该偷偷改用户的数据。
     */
    fun normalised(): ActivityTaxonomy {
        val filled = categories.map { category ->
            category.copy(color = if (isHexColor(category.color)) category.color.uppercase() else paletteColor(category.id))
        }.toMutableList()
        if (filled.none { it.id == UNCATEGORIZED_ID }) {
            filled += CategoryNode(
                id = UNCATEGORIZED_ID,
                name = UNCATEGORIZED_NAME,
                parentId = null,
                color = UNCATEGORIZED_COLOR,
                rules = emptyList(),
                order = Int.MAX_VALUE,
            )
        }
        return copy(categories = filled)
    }

    /** 存盘/编辑器保存前跑一遍，返回人能看懂的问题清单；空列表 = 没问题。 */
    fun validate(): List<String> {
        val issues = ArrayList<String>()
        val duplicated = categories.groupBy { it.id }.filter { it.value.size > 1 }.keys
        if (duplicated.isNotEmpty()) issues += "分类 id 重复：${duplicated.joinToString("、")}"
        if (categories.none { it.id == UNCATEGORIZED_ID }) {
            issues += "缺少 id 为 `$UNCATEGORIZED_ID` 的兜底分类（未分类）"
        }
        for (category in categories) {
            val label = "分类「${category.name.ifBlank { category.id }}」"
            if (category.id.isBlank()) issues += "存在 id 为空的分类"
            if (category.name.isBlank()) issues += "$label 的名字为空"
            if (category.parentId != null && node(category.parentId) == null) {
                issues += "$label 的父分类 `${category.parentId}` 不存在"
            }
            if (category.parentId != null && category.parentId == category.id) {
                issues += "$label 的父分类是它自己"
            }
            if (category.color.isNotBlank() && !isHexColor(category.color)) {
                issues += "$label 的颜色 `${category.color}` 不是 #RRGGBB 格式"
            }
            for (rule in category.rules) {
                val pattern = rule.pattern.trim()
                if (pattern.isEmpty()) {
                    issues += "$label 有一条空正则（空正则会匹配一切，必须显式删掉）"
                    continue
                }
                runCatching { Regex(pattern, regexOptions(rule)) }.onFailure {
                    issues += "$label 的正则 `$pattern` 在 JVM 里编译不过：${it.message}"
                }
            }
        }
        val cycles = cycleCategoryIds()
        if (cycles.isNotEmpty()) issues += "分类父子关系成环：${cycles.joinToString("、")}"
        return issues
    }

    /** 参与父子环的分类 id（正常数据返回空集）。 */
    private fun cycleCategoryIds(): Set<String> {
        val bad = LinkedHashSet<String>()
        for (category in categories) {
            val seen = LinkedHashSet<String>()
            var current: CategoryNode? = category
            while (current != null) {
                if (!seen.add(current.id)) {
                    bad += seen
                    break
                }
                current = node(current.parentId)
            }
        }
        return bad
    }

    companion object {
        const val CURRENT_VERSION: Int = 1

        /** 兜底分类的固定 id。改名可以，改 id 会让历史归因对不上，所以它是保留字。 */
        const val UNCATEGORIZED_ID: String = "uncategorized"
        const val UNCATEGORIZED_NAME: String = "未分类"
        const val UNCATEGORIZED_COLOR: String = "#9CA3AF"

        /**
         * 默认色板，取自 ActivityWatch 默认分类的配色（绿/红/橙/黄/青…），
         * 在深色和浅色主题下都能当色块用。新增分类没填颜色时按 id 哈希取一个。
         */
        val PALETTE: List<String> = listOf(
            "#4ADE80", "#F87171", "#FB923C", "#FACC15", "#A3E635", "#67E8F9", "#F472B6", "#A78BFA",
            "#60A5FA", "#94A3B8", "#34D399", "#FBBF24", "#38BDF8", "#C4B5FD", "#5EEAD4", "#FCA5A5",
        )

        /** id 决定的稳定颜色：同一个分类无论出现多少次都是同一个颜色。 */
        fun paletteColor(id: String): String {
            val index = (id.hashCode().toLong() and 0x7FFF_FFFFL).toInt() % PALETTE.size
            return PALETTE[index]
        }

        /** `#RRGGBB`（大小写都收）。 */
        fun isHexColor(value: String): Boolean = HEX_COLOR.matches(value)

        /**
         * 规则用到哪些正则选项。
         *
         * - `MULTILINE`：和 AW 一致（它固定拼 `m` 标志），让 `^`/`$` 按行匹配；
         * - `IGNORE_CASE`：进程名/标题的大小写没有信息量，默认开（规则里可以关掉）。
         *
         * 注意 JVM 的 `Regex` 语法和 AW 服务端（Python `re` / Rust `regex`）不完全等价：
         * 常见的字符类、分组、量词、`|` 都一样，但 Python 的 `(?P<name>...)` / Rust 的 `\p{...}`
         * 这类写法会编译失败 —— 失败会被 [ActivityClassifier.problems] 收走，不会静默算错。
         */
        fun regexOptions(rule: CategoryRule): Set<RegexOption> = buildSet {
            add(RegexOption.MULTILINE)
            if (rule.ignoreCase) add(RegexOption.IGNORE_CASE)
        }

        /**
         * 从 URL 里抠主机名，给 [RuleField.DOMAIN] 用：`https://www.bilibili.com/video/x` → `bilibili.com`。
         * 没协议头的裸域名（`example.com/x`）也能处理；实在解析不出来返回 null（这条规则跳过）。
         */
        fun domainOf(url: String?): String? {
            if (url.isNullOrBlank()) return null
            val host = runCatching { URI(url).host }.getOrNull()
                ?: runCatching { URI("http://$url").host }.getOrNull()
                ?: return null
            return host.lowercase().removePrefix("www.")
        }

        val DEFAULT_BROWSER_APPS: List<String> = listOf(
            "chrome", "msedge", "firefox", "brave", "opera", "vivaldi",
            "arc\\.exe", "360se", "qqbrowser", "safari", "thorium", "yandex",
        )

        /**
         * 出厂分类。故意只覆盖「一眼能认出来」的那些进程：
         * 一个塞满猜测的默认值比空表更难改，因为你不知道哪条在起作用。
         *
         * 结构上分成几个大类当文件夹，小类挂规则；`浏览/浏览器` 是最底层的兜底 ——
         * 浏览器 URL 规则（[RuleField.DOMAIN]）会以更高 priority 盖过它，没被任何网站规则命中的
         * 浏览器时间才落在「浏览/浏览器」里。
         */
        val DEFAULT_CATEGORIES: List<CategoryNode> = listOf(
            // ── 工作 ──────────────────────────────────────────────
            category("work", "工作", null, "#4ADE80", 0),
            category(
                "work.coding", "编程", "work", "#34D399", 0,
                rule(
                    "DeepSeek Harness|Code\\.exe|idea64|studio64|devenv\\.exe|pycharm|webstorm|goland|clion|rider64|" +
                        "sublime_text|notepad\\+\\+|gvim|emacs|Cursor\\.exe",
                ),
                rule(
                    "github\\.com|stackoverflow\\.com|developer\\.mozilla\\.org|kotlinlang\\.org|" +
                        "developer\\.android\\.com|docs\\.oracle\\.com",
                    fields = setOf(RuleField.DOMAIN),
                    priority = 120,
                ),
            ),
            category(
                "work.design", "设计", "work", "#F472B6", 1,
                rule(
                    "Trellis|Blender|Maya|3ds Max|ZBrush|Substance|Photoshop|Illustrator|Figma|Krita|" +
                        "Inkscape|AnyLabel",
                ),
            ),
            category(
                "work.office", "办公", "work", "#60A5FA", 2,
                rule(
                    "WINWORD|EXCEL|POWERPNT|OUTLOOK|wps|et\\.exe|wpp|soffice|Obsidian|Typora|Notion|" +
                        "OneNote|Acrobat|SumatraPDF",
                ),
            ),
            category(
                "work.terminal", "终端", "work", "#A78BFA", 3,
                rule("WindowsTerminal|^cmd\\.exe$|powershell|pwsh|mintty|ConEmu|WezTerm|Tabby|alacritty"),
            ),

            // ── 影音 ──────────────────────────────────────────────
            category("media", "影音", null, "#F87171", 1),
            category(
                "media.video", "视频", "media", "#FB923C", 0,
                // 视频同时看进程名和标题：B 站在浏览器里打开时，进程是 chrome，线索只在标题/URL 上。
                rule(
                    "vlc|mpc-hc|PotPlayer|mpv\\.exe|bilibili|哔哩哔哩|YouTube|Netflix|腾讯视频|爱奇艺|优酷",
                    fields = setOf(RuleField.APP, RuleField.TITLE),
                    priority = 120,
                ),
                rule(
                    "bilibili\\.com|youtube\\.com|youtu\\.be|netflix\\.com|iqiyi\\.com|v\\.qq\\.com|" +
                        "youku\\.com|douyin\\.com|mgstage\\.com",
                    fields = setOf(RuleField.DOMAIN),
                    priority = 120,
                ),
            ),
            category(
                "media.music", "音乐", "media", "#A3E635", 1,
                rule("QQMusic|CloudMusic|Spotify|foobar2000|AIMP|MusicBee|网易云音乐", priority = 120),
                rule(
                    "music\\.163\\.com|open\\.spotify\\.com|y\\.qq\\.com|music\\.youtube\\.com",
                    fields = setOf(RuleField.DOMAIN),
                    priority = 120,
                ),
            ),

            // ── 游戏 / 通讯 ───────────────────────────────────────
            category(
                "games", "游戏", null, "#F59E0B", 2,
                rule(
                    "steam|EpicGamesLauncher|Battle\\.net|WeGame|Xbox|aimlab|Endfield|VALORANT|" +
                        "RiotClient|GenshinImpact|YuanShen|LeagueClient",
                    fields = setOf(RuleField.APP, RuleField.TITLE),
                    priority = 110,
                ),
            ),
            category("comm", "通讯", null, "#67E8F9", 3),
            category(
                "comm.wechat", "微信", "comm", "#22D3EE", 0,
                rule("Weixin|WeChat|微信", priority = 130),
            ),
            category(
                "comm.qq", "QQ", "comm", "#38BDF8", 1,
                rule("^QQ(\\.exe)?$|QQNT", priority = 130),
            ),
            category(
                "comm.other", "其他", "comm", "#5EEAD4", 2,
                rule(
                    "Telegram|Discord|Slack|Teams|DingTalk|钉钉|Feishu|Lark|飞书|wemeet|TencentMeeting|Zoom",
                    priority = 120,
                ),
            ),

            // ── 浏览 / 工具 / 系统 ────────────────────────────────
            category("browse", "浏览", null, "#94A3B8", 4),
            category(
                "browse.browser", "浏览器", "browse", "#CBD5E1", 0,
                rule("chrome|msedge|firefox|brave|opera|vivaldi|Arc\\.exe|360se|QQBrowser"),
            ),
            category("tools", "工具", null, "#C4B5FD", 5),
            category(
                "tools.files", "文件管理", "tools", "#8B5CF6", 0,
                rule("^explorer\\.exe$|TotalCMD|Double Commander|Everything", priority = 90),
            ),
            category(
                "tools.system", "系统工具", "tools", "#7C3AED", 1,
                rule("Snipaste|ShareX|PowerToys|clash|verge|v2ray|7zFM|WinRAR|Bandizip", priority = 90),
            ),
            category(
                "system", "系统", null, "#64748B", 6,
                rule(
                    "dwm\\.exe|SearchApp\\.exe|LockApp\\.exe|ShellExperienceHost|StartMenuExperienceHost|" +
                        "TextInputHost|ApplicationFrameHost|SystemSettings|Taskmgr|aw-qt|pythonw",
                    priority = 50,
                ),
            ),

            // ── 兜底 ──────────────────────────────────────────────
            category(UNCATEGORIZED_ID, UNCATEGORIZED_NAME, null, UNCATEGORIZED_COLOR, Int.MAX_VALUE),
        )

        private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")
    }
}

/** 分类树上的一个节点。[rules] 只作用在这个节点自己身上。 */
@Serializable
data class CategoryNode(
    /** 稳定 id（保留字 `uncategorized`）。改名、挪父分类都不变，历史归因因此还能对上。 */
    val id: String,
    /** 单段名字，例如 `编程`。完整路径由 [ActivityTaxonomy.pathNames] 拼。 */
    val name: String,
    /** 父分类 id；null = 一级分类。 */
    val parentId: String? = null,
    /** 颜色标签，`#RRGGBB`。空串会在 [ActivityTaxonomy.normalised] 里自动补一个稳定色。 */
    val color: String = "",
    /** 命中这里任意一条规则就归到这个分类。 */
    val rules: List<CategoryRule> = emptyList(),
    /** 同级展示顺序，小的在前。 */
    val order: Int = 0,
)

/**
 * 一条分类规则。
 *
 * 行为约定（[ActivityClassifier] 实现）：
 * - [fields] 是**或**关系：任何一个字段命中就算这条规则命中；
 * - [priority] 为 null 时排名 = 层级深度 × 10（越深越优先）；显式填了就用这个值；
 * - 排名最高的规则胜出；同排名按「分类列表顺序 → 规则声明顺序」取第一条；
 * - 空正则、编译失败、[enabled] 为 false 的规则一律不参与匹配。
 */
@Serializable
data class CategoryRule(
    /** 正则原文。JVM `Regex` 语法（见 [ActivityTaxonomy.regexOptions] 的说明）。 */
    val pattern: String,
    /** 拿哪些字段去试。默认只试进程名，标题/URL 要显式写进来。 */
    val fields: Set<RuleField> = setOf(RuleField.APP),
    /** 大小写敏感与否。进程名和标题默认忽略大小写。 */
    val ignoreCase: Boolean = true,
    /** 显式排名；null = 按层级深度推导。数字大的先匹配。 */
    val priority: Int? = null,
    /** 临时停用一条规则（不用删掉）。 */
    val enabled: Boolean = true,
)

/** 规则拿事件里的哪个字段去匹配。 */
@Serializable
enum class RuleField {
    /** 进程名，例如 `chrome.exe`。 */
    APP,

    /** 窗口标题。用来区分「同一个浏览器在干不同的活」。 */
    TITLE,

    /** 网页完整 URL（来自浏览器 watcher 的 `web.tab.current`）。可能带查询串，敏感内容也会进来。 */
    URL,

    /** URL 的域名（`www.` 已去掉）。写网站规则首选这个，比整条 URL 稳。 */
    DOMAIN,
}

/**
 * 编译好的分类器。构造一次，之后 [classify] 只是若干次 `containsMatchIn`。
 *
 * 写坏的正则**不静默吞掉**：收进 [problems]，交给设置页/诊断页提示用户 ——
 * 静默跳过会让用户以为规则生效了，实际全落到未分类。
 */
class ActivityClassifier(taxonomy: ActivityTaxonomy) {

    private class Compiled(
        val categoryId: String,
        val path: List<String>,
        val color: String,
        val fields: Set<RuleField>,
        val regex: Regex,
        val rank: Int,
        val depth: Int,
        val pattern: String,
        /** 同排名时的先后：分类列表顺序在前，规则声明顺序在后。 */
        val order: Int,
    )

    private val issues = ArrayList<String>()
    private val model = taxonomy.normalised()
    private val compiled: List<Compiled> = compile()
    private val uncategorized: CategoryMatch = run {
        val node = model.node(ActivityTaxonomy.UNCATEGORIZED_ID)
        CategoryMatch(
            categoryId = ActivityTaxonomy.UNCATEGORIZED_ID,
            path = listOf(node?.name ?: ActivityTaxonomy.UNCATEGORIZED_NAME),
            color = node?.color ?: ActivityTaxonomy.UNCATEGORIZED_COLOR,
        )
    }

    /** 编译期发现的问题（空正则、编译不过的正则）。每次构造 classifier 时重新收集。 */
    val problems: List<String> get() = issues.toList()

    private fun compile(): List<Compiled> {
        val out = ArrayList<Compiled>()
        model.categories.forEachIndexed { categoryIndex, category ->
            val path = model.pathNames(category.id).ifEmpty { listOf(category.name.ifBlank { category.id }) }
            val depth = path.size
            category.rules.forEachIndexed { ruleIndex, rule ->
                if (!rule.enabled) return@forEachIndexed
                val pattern = rule.pattern.trim()
                if (pattern.isEmpty()) {
                    issues += "分类「${path.joinToString("/")}」有一条空正则，已忽略"
                    return@forEachIndexed
                }
                val regex = runCatching { Regex(pattern, ActivityTaxonomy.regexOptions(rule)) }.getOrElse {
                    issues += "分类「${path.joinToString("/")}」的正则 `$pattern` 编译失败：${it.message}"
                    return@forEachIndexed
                }
                out += Compiled(
                    categoryId = category.id,
                    path = path,
                    color = category.color,
                    fields = rule.fields,
                    regex = regex,
                    rank = rule.priority ?: (depth * 10),
                    depth = depth,
                    pattern = pattern,
                    order = categoryIndex * 10_000 + ruleIndex,
                )
            }
        }
        out.sortWith(compareByDescending<Compiled> { it.rank }.thenBy { it.order })
        return out
    }

    /** 归到哪个分类。没命中任何规则时返回「未分类」。 */
    fun classify(app: String?, title: String?, url: String? = null): CategoryMatch {
        val domain = ActivityTaxonomy.domainOf(url)
        for (rule in compiled) {
            if (matches(rule, app, title, url, domain)) {
                return CategoryMatch(
                    categoryId = rule.categoryId,
                    path = rule.path,
                    color = rule.color,
                    pattern = rule.pattern,
                    rank = rule.rank,
                )
            }
        }
        return uncategorized
    }

    /**
     * 为什么归到这个分类：列出所有命中的规则（按排名从高到低），第一条就是结果。
     *
     * 给「规则调试」用 —— 用户写了一条正则看不到生效，需要知道是没命中、被更高优先级压了、
     * 还是字段选错了。以后设置页里的「试一试」按钮直接读这个。
     */
    fun explain(app: String?, title: String?, url: String? = null): ClassificationExplanation {
        val domain = ActivityTaxonomy.domainOf(url)
        val hits = ArrayList<RuleHit>()
        for (rule in compiled) {
            val matched = rule.fields.filter { field ->
                val subject = subject(field, app, title, url, domain)
                subject != null && rule.regex.containsMatchIn(subject)
            }
            if (matched.isEmpty()) continue
            hits += RuleHit(
                categoryId = rule.categoryId,
                categoryPath = rule.path,
                color = rule.color,
                pattern = rule.pattern,
                rank = rule.rank,
                depth = rule.depth,
                matchedFields = matched,
            )
        }
        val chosen = hits.firstOrNull()?.let {
            CategoryMatch(it.categoryId, it.categoryPath, it.color, it.pattern, it.rank)
        } ?: uncategorized
        return ClassificationExplanation(app = app, title = title, url = url, hits = hits, chosen = chosen)
    }

    private fun matches(rule: Compiled, app: String?, title: String?, url: String?, domain: String?): Boolean =
        rule.fields.any { field ->
            val subject = subject(field, app, title, url, domain)
            subject != null && rule.regex.containsMatchIn(subject)
        }

    private fun subject(field: RuleField, app: String?, title: String?, url: String?, domain: String?): String? =
        when (field) {
            RuleField.APP -> app
            RuleField.TITLE -> title
            RuleField.URL -> url
            RuleField.DOMAIN -> domain
        }
}

/** 一条事件的归类结果。 */
data class CategoryMatch(
    val categoryId: String,
    /** 完整名字路径，例如 `["工作", "编程"]`。 */
    val path: List<String>,
    /** 分类颜色，`#RRGGBB`。 */
    val color: String,
    /** 命中的那条正则（未分类时为 null）。 */
    val pattern: String? = null,
    /** 命中的规则排名（未分类时为 0）。 */
    val rank: Int = 0,
) {
    /** 展示形式，例如 `工作/编程`。 */
    val pathText: String get() = path.joinToString("/")

    val isUncategorized: Boolean get() = categoryId == ActivityTaxonomy.UNCATEGORIZED_ID
}

/** [ActivityClassifier.explain] 里的一条命中记录。 */
data class RuleHit(
    val categoryId: String,
    val categoryPath: List<String>,
    val color: String,
    val pattern: String,
    val rank: Int,
    val depth: Int,
    /** 这条规则实际是在哪些字段上命中的（`fields` 里没命中那些不列）。 */
    val matchedFields: List<RuleField>,
) {
    val pathText: String get() = categoryPath.joinToString("/")
}

/** 一次「为什么这样分类」的解释。[hits] 按排名从高到低，第一条就是 [chosen] 的来源。 */
data class ClassificationExplanation(
    val app: String?,
    val title: String?,
    val url: String?,
    val hits: List<RuleHit>,
    val chosen: CategoryMatch,
)

// ── 出厂分类的构造辅助（只在 DEFAULT_CATEGORIES 里用） ────────────────────

private fun category(
    id: String,
    name: String,
    parentId: String?,
    color: String,
    order: Int,
    vararg rules: CategoryRule,
): CategoryNode = CategoryNode(
    id = id,
    name = name,
    parentId = parentId,
    color = color,
    rules = rules.toList(),
    order = order,
)

private fun rule(
    pattern: String,
    fields: Set<RuleField> = setOf(RuleField.APP),
    ignoreCase: Boolean = true,
    priority: Int? = null,
): CategoryRule = CategoryRule(
    pattern = pattern,
    fields = fields,
    ignoreCase = ignoreCase,
    priority = priority,
)