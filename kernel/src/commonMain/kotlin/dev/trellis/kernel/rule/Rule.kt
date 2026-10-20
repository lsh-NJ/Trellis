package dev.trellis.kernel.rule

import dev.trellis.kernel.action.ActionArgs
import dev.trellis.kernel.action.KernelAction
import dev.trellis.kernel.event.KernelEvent

/** 一次动作调用：用哪个动作、填什么参数。参数值支持 {{ event.xxx }}。 */
data class ActionCall(val action: String, val args: Map<String, String> = emptyMap())

/** 一条规则：某个事件发生时，依次做这几件事。 */
data class RuleDecl(val on: String, val actions: List<ActionCall>)

/** 一条规则跑完的结果，写进审计。 */
data class RuleExecution(
    val ruleIndex: Int,
    val action: String,
    val ok: Boolean,
    val message: String,
)

/**
 * 规则引擎。
 *
 * 只做三件事：**匹配事件 -> 把参数里的占位符换成值 -> 按顺序执行动作**。
 *
 * 它不认识任何一个具体动作，也不认识任何一个具体事件 ——
 * 动作从扩展点里查，事件从总线来。所以内核依然是领域中立的。
 */
class RuleEngine(private val actions: () -> List<KernelAction>) {

    private val rulesByOwner = mutableMapOf<String, List<RuleDecl>>()

    fun install(owner: String, rules: List<RuleDecl>) {
        if (rules.isNotEmpty()) rulesByOwner[owner] = rules
    }

    fun removeOwner(owner: String) {
        rulesByOwner.remove(owner)
    }

    fun ruleCount(): Int = rulesByOwner.values.sumOf { it.size }

    /** 这条事件会触发哪些规则的哪些动作。给界面预告用，不执行。 */
    fun preview(eventType: String): List<ActionCall> =
        rulesByOwner.values.flatten().filter { it.on == eventType }.flatMap { it.actions }

    /**
     * 跑一条事件。
     *
     * 动作按声明顺序执行，**前一个失败不影响后面的** ——
     * 一条规则里有两个动作时，用户不会希望第一个失败就全没了。
     * 每个动作的结果都会返回，让调用方写日志。
     */
    suspend fun handle(event: KernelEvent): List<RuleExecution> {
        val matched = rulesByOwner.values.flatten().filter { it.on == event.type }
        if (matched.isEmpty()) return emptyList()

        val results = mutableListOf<RuleExecution>()
        matched.forEachIndexed { index, rule ->
            for (call in rule.actions) {
                val action = findAction(call.action)
                if (action == null) {
                    results += RuleExecution(index, call.action, false, "没有这个动作")
                    continue
                }
                val args = call.args.mapValues { (_, value) -> substitute(value, event) }
                results += try {
                    RuleExecution(index, call.action, true, action.execute(ActionArgs(args)))
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    RuleExecution(index, call.action, false, t.message ?: "执行失败")
                }
            }
        }
        return results
    }

    /**
     * 把参数里的 {{ event.xxx }} 换成事件载荷。
     *
     * 刻意只认 `event.` 前缀：规则参数只能引用**这次事件**的内容，
     * 不能引用存储、不能引用别的字段。少一个数据来源，就少一类出错的可能。
     */
    private fun substitute(value: String, event: KernelEvent): String {
        var result = value
        for ((key, payload) in event.payload) {
            result = result
                .replace("{{ event." + key + " }}", payload)
                .replace("{{event." + key + "}}", payload)
        }
        return result
    }

    private fun findAction(id: String): KernelAction? =
        actions().firstOrNull { it.id == id }
}

/**
 * 插件登记规则的入口。
 *
 * 刻意不带 owner 参数：**它只能是调用者自己**。
 * 和 kv 一样，隔离做进类型里 —— 想给别人装规则，写都写不出来。
 */
interface RuleRegistrar {
    fun declare(rules: List<RuleDecl>)
}

/** 什么都不做的登记处。声明了也不会有规则跑 —— 明确，而不是悄悄生效。 */
object NoRules : RuleRegistrar {
    override fun declare(rules: List<RuleDecl>) = Unit
}
