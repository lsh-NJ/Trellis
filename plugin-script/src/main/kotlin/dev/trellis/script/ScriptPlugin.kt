package dev.trellis.script

import dev.trellis.domain.PlanService
import dev.trellis.kernel.api.ActionOutcome
import dev.trellis.kernel.core.ExtensionPointId
import dev.trellis.kernel.extension.DataContext
import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest
import dev.trellis.kernel.slot.DynamicSlot
import dev.trellis.kernel.slot.Slots
import dev.trellis.kernel.slot.UiNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 轨道 B：跑代码的插件。
 *
 * 它做了一件声明式插件做不到的事：**根据数据决定画什么**，并且能处理点击。
 * 但它的自由到此为止  它拿不到文件、拿不到网络、拿不到别的插件的抽屉，
 * 也画不出宿主不认识的界面元素。
 *
 * 每一次 build / perform 都:
 *   1. 到 Dispatchers.Default 上去跑（绝不在渲染线程上跑插件代码）
 *   2. 带指令预算（死循环会被掐断）
 *   3. 出错就降级成一张说明卡（**插件崩了，窗口不能崩**）
 */
class ScriptPlugin(
    override val manifest: PluginManifest,
    private val sandbox: ScriptSandbox,
) : Plugin {

    private lateinit var context: PluginContext
    private var plan: PlanService? = null

    override suspend fun init(ctx: PluginContext) {
        context = ctx
        sandbox.hostLog = { ctx.log(it) }
        sandbox.boot()
        plan = try {
            ctx.services.require(PlanService::class)
        } catch (t: Throwable) {
            null
        }

        for (card in sandbox.declaredCards()) {
            val point = Slots.byId(ExtensionPointId(card.pointId))
            if (point == null) {
                ctx.log("卡片 " + card.id + " 挂在了一个不存在的挂载点：" + card.pointId + "，已跳过")
                continue
            }
            ctx.extensions.register(point, ScriptSlot(card))
        }
    }

    override suspend fun dispose() {
        sandbox.close()
    }

    private inner class ScriptSlot(private val card: CardDecl) : DynamicSlot {

        override val id: String get() = card.id

        override val order: Int get() = card.order

        override suspend fun build(context: DataContext): UiNode = withContext(Dispatchers.Default) {
            try {
                NodeBuilder.build(sandbox.render(card.id, read(context)))
            } catch (t: Throwable) {
                UiNode.Notice("这张卡片画不出来：" + explain(t))
            }
        }

        override suspend fun perform(handler: String, payload: Map<String, String>): ActionOutcome =
            withContext(Dispatchers.Default) {
                try {
                    val now = System.currentTimeMillis()
                    val result = sandbox.runHandler(handler, read(DataContext(now, now, now)), payload)
                    for ((key, value) in result.save) this@ScriptPlugin.context.kv.put(key, value)
                    for (key in result.remove) this@ScriptPlugin.context.kv.delete(key)
                    ActionOutcome(true, result.say.ifBlank { "记下了" })
                } catch (t: Throwable) {
                    ActionOutcome(false, explain(t))
                }
            }
    }

    /**
     * 交给脚本的那个 data 对象。
     *
     * 这是**能力的正脸**：能给什么，全在这个函数里。没在这里面的东西，
     * 脚本就是有通天的本事也拿不到。
     */
    private suspend fun read(context: DataContext): Map<String, Any?> {
        val data = LinkedHashMap<String, Any?>()
        data["now"] = context.nowMs
        data["dayStart"] = context.dayStartMs
        data["dayEnd"] = context.dayEndMs

        val service = plan
        if (service != null) {
            val summary = service.summary(context.nowMs, context.dayEndMs)
            data["summary"] = mapOf(
                "total" to summary.total,
                "done" to summary.done,
                "doing" to summary.doing,
                "todo" to (summary.total - summary.done - summary.doing),
                "overdue" to summary.overdue,
                "dueToday" to summary.dueToday,
                "spentMinutes" to summary.spentMinutes,
            )
            data["tasks"] = service.tasks().map { task ->
                mapOf(
                    "id" to task.id.value,
                    "title" to task.title,
                    "status" to task.status.name,
                    "spentMinutes" to task.spentMinutes,
                )
            }
        }

        val store = LinkedHashMap<String, String>()
        for (key in this@ScriptPlugin.context.kv.keys()) {
            if (store.size >= MAX_STORE_KEYS) break
            store[key] = this@ScriptPlugin.context.kv.get(key) ?: ""
        }
        data["store"] = store
        return data
    }

    private fun explain(t: Throwable): String {
        val message = t.message
        return if (message.isNullOrBlank()) (t::class.simpleName ?: "未知原因") else message
    }

    private companion object {
        /** 一次最多往脚本里塞这么多键。防止有人存了一万条还想全拿出来。 */
        const val MAX_STORE_KEYS = 200
    }
}
