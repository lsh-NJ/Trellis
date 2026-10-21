package dev.trellis.script

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/** 插件的代码出问题了。**这不是内核崩溃**，它会被降级成一张说明卡。 */
class ScriptFailure(message: String) : RuntimeException(message)

/** 插件在注册时交回来的一张卡片。 */
data class CardDecl(val id: String, val pointId: String, val order: Int)

/** 脚本自己声明的一个动作：外面能按名字调它，参数名只用于告诉人怎么填。 */
data class ActionDecl(val id: String, val label: String, val params: List<String>)

/** 插件处理函数交回来的东西。它能说一句话，能存几个键，能删几个键 —— 仅此而已。 */
data class HandlerResult(
    val say: String = "",
    val save: Map<String, String> = emptyMap(),
    val remove: List<String> = emptyList(),
)

/**
 * 一个插件的 JS 沙箱。
 *
 * 三条边界，缺一条这个沙箱就不成立：
 *
 * 1. **看不见 Java**：`initSafeStandardObjects` + `ClassShutter` 全拒。
 *    JS 里没有 `Packages`、没有 `java`，`java.lang.System.exit(0)` 是个未定义变量。
 * 2. **跑不完就得停**：Rhino 的指令计数器。`while(true){}` 没有挂起点，
 *    `withTimeout` 对它无效 —— 只有指令计数能把它掐断。
 * 3. **不回调宿主**：处理函数不能直接调 Kotlin。它只能*返回*「要存什么」，
 *    由宿主在 JS 返回之后再落盘。这样就不存在「JS 卡在 Kotlin 调用里」这种死法。
 */
class ScriptSandbox(
    private val pluginId: String,
    private val source: String,
    private val entryName: String = "main.js",
    private val budgetMs: Long = 1500,
) {

    @Volatile
    private var deadline: Long = 0L

    private val lock = Any()
    private var scope: ScriptableObject? = null
    private val cards = mutableListOf<CardDecl>()
    private val actions = mutableListOf<ActionDecl>()
    private val renders = mutableMapOf<String, Function>()
    private val handlers = mutableMapOf<String, Function>()

    private val factory = object : ContextFactory() {
        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.optimizationLevel = -1
            cx.instructionObserverThreshold = 10_000
            cx.setClassShutter(ClassShutter { false })
            cx.languageVersion = Context.VERSION_ES6
            return cx
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val until = deadline
            if (until != 0L && System.nanoTime() > until) {
                throw ScriptFailure("插件的代码跑太久了（超过 " + budgetMs + " 毫秒），已经停下")
            }
        }

        override fun hasFeature(cx: Context, featureIndex: Int): Boolean =
            if (featureIndex == Context.FEATURE_ENHANCED_JAVA_ACCESS) false else super.hasFeature(cx, featureIndex)
    }

    fun declaredCards(): List<CardDecl> = synchronized(lock) { cards.toList() }

    fun declaredActions(): List<ActionDecl> = synchronized(lock) { actions.toList() }

    /** 注册了哪些点击处理函数。自检时要一个个点过去。 */
    fun declaredHandlers(): List<String> = synchronized(lock) { handlers.keys.toList() }

    /** 跑一遍脚本，收下它注册的卡片和处理函数。 */
    fun boot() {
        call { cx ->
            val s = cx.initSafeStandardObjects(null, false)
            scope = s
            val api = cx.newObject(s)

            ScriptableObject.putProperty(api, "id", pluginId)

            fun put(name: String, value: Any?) = ScriptableObject.putProperty(api, name, value)

            put("log", fn(cx, s, "log") { args ->
                hostLog(Context.toString(args.getOrNull(0)))
                Context.getUndefinedValue()
            })
            put("register", fn(cx, s, "register") { args ->
                onRegister(cx, args.getOrNull(0))
                Context.getUndefinedValue()
            })

            ScriptableObject.putProperty(s, "trellis", api)
            cx.evaluateString(s, source, entryName, 1, null)
        }
    }

    /** 求值一张卡片。返回的是**纯数据**，宿主还要校验一遍才认。 */
    fun render(cardId: String, data: Map<String, Any?>): Any? = call { cx ->
        val s = scope ?: throw ScriptFailure("插件还没启动")
        val f = renders[cardId] ?: throw ScriptFailure("插件没有卡片「" + cardId + "」")
        f.call(cx, s, s, arrayOf<Any?>(toJs(cx, s, data)))
    }

    /** 跑一个处理函数。注意：它只能*返回*要存的东西。 */
    fun runHandler(name: String, data: Map<String, Any?>, payload: Map<String, String>): HandlerResult =
        call { cx ->
            val s = scope ?: throw ScriptFailure("插件还没启动")
            val f = handlers[name] ?: throw ScriptFailure("插件没有处理「" + name + "」这个动作")
            val out = f.call(cx, s, s, arrayOf<Any?>(toJs(cx, s, data), toJs(cx, s, payload)))
            readResult(out)
        }

    fun close() = synchronized(lock) {
        cards.clear()
        actions.clear()
        renders.clear()
        handlers.clear()
        scope = null
    }

    // ---------------------------------------------------------------- 内部

    private fun <T> call(block: (Context) -> T): T = synchronized(lock) {
        deadline = System.nanoTime() + budgetMs * 1_000_000L
        try {
            val cx = factory.enterContext()
            try {
                block(cx)
            } finally {
                Context.exit()
            }
        } catch (t: ScriptFailure) {
            throw t
        } catch (t: Throwable) {
            throw ScriptFailure(describe(t))
        } finally {
            deadline = 0L
        }
    }

    private fun onRegister(cx: Context, def: Any?) {
        if (def !is Scriptable) throw ScriptFailure("trellis.register 要传一个对象")
        val list = ScriptableObject.getProperty(def, "cards")
        if (list is NativeArray) {
            for (i in 0 until list.length.toInt()) {
                val item = list.get(i, list)
                if (item !is Scriptable) continue
                val id = ScriptableObject.getProperty(item, "id") as? String
                    ?: throw ScriptFailure("有一张卡片没写 id")
                val point = ScriptableObject.getProperty(item, "point") as? String
                    ?: throw ScriptFailure("卡片「" + id + "」没写 point（挂在哪）")
                val order = (ScriptableObject.getProperty(item, "order") as? Number)?.toInt() ?: 100
                val render = ScriptableObject.getProperty(item, "render")
                if (render !is Function) throw ScriptFailure("卡片「" + id + "」没写 render 函数")
                cards += CardDecl(id, point, order)
                renders[id] = render
            }
        }
        val acts = ScriptableObject.getProperty(def, "actions")
        if (acts is NativeArray) {
            for (i in 0 until acts.length.toInt()) {
                val item = acts.get(i, acts)
                if (item !is Scriptable) continue
                val id = ScriptableObject.getProperty(item, "id") as? String
                    ?: throw ScriptFailure("有一个动作没写 id")
                if (!ACTION_ID.matches(id)) {
                    throw ScriptFailure("动作 id「" + id + "」不合规：只能用小写字母、数字、点、横线")
                }
                if (actions.size >= MAX_ACTIONS) {
                    throw ScriptFailure("一次最多声明 " + MAX_ACTIONS + " 个动作，已经够了")
                }
                val label = (ScriptableObject.getProperty(item, "label") as? CharSequence)?.toString()
                    ?.takeIf { it.isNotBlank() } ?: id
                val names = mutableListOf<String>()
                val ps = ScriptableObject.getProperty(item, "params")
                if (ps is NativeArray) {
                    for (j in 0 until ps.length.toInt()) {
                        val one = ps.get(j, ps)
                        val name = when (one) {
                            is CharSequence -> one.toString()
                            is Scriptable -> ScriptableObject.getProperty(one, "name") as? String
                            else -> null
                        }
                        if (!name.isNullOrBlank()) names += name
                    }
                }
                actions += ActionDecl(id, label, names)
                val fn = ScriptableObject.getProperty(item, "on")
                if (fn is Function) handlers[id] = fn
            }
        }
        val on = ScriptableObject.getProperty(def, "on")
        if (on is Scriptable) {
            for (key in on.ids) {
                val name = key.toString()
                val f = ScriptableObject.getProperty(on, name)
                if (f is Function) handlers[name] = f
            }
        }
    }

    private fun readResult(out: Any?): HandlerResult {
        if (out == null || out is Undefined) return HandlerResult()
        if (out is String) return HandlerResult(say = out)
        if (out !is Scriptable) return HandlerResult()
        val say = (ScriptableObject.getProperty(out, "say") as? CharSequence)?.toString() ?: ""
        val save = LinkedHashMap<String, String>()
        val rawSave = ScriptableObject.getProperty(out, "save")
        if (rawSave is Scriptable) {
            for (key in rawSave.ids) {
                val name = key.toString()
                save[name] = Context.toString(ScriptableObject.getProperty(rawSave, name))
            }
        }
        val remove = mutableListOf<String>()
        val rawRemove = ScriptableObject.getProperty(out, "remove")
        if (rawRemove is NativeArray) {
            for (i in 0 until rawRemove.length.toInt()) remove += Context.toString(rawRemove.get(i, rawRemove))
        } else if (rawRemove is String) {
            remove += rawRemove
        }
        return HandlerResult(say, save, remove)
    }

    private fun toJs(cx: Context, scope: Scriptable, value: Any?): Any? = when (value) {
        null -> null
        is Map<*, *> -> cx.newObject(scope).also { o ->
            for ((k, v) in value) ScriptableObject.putProperty(o, k.toString(), toJs(cx, scope, v))
        }
        is List<*> -> cx.newArray(scope, value.size).also { arr ->
            value.forEachIndexed { i, v -> arr.put(i, arr, toJs(cx, scope, v)) }
        }
        is Number, is String, is Boolean -> value
        else -> value.toString()
    }

    private fun fn(cx: Context, scope: Scriptable, name: String, body: (Array<out Any?>) -> Any?): BaseFunction =
        object : BaseFunction() {
            override fun getFunctionName(): String = name
            override fun call(cx2: Context, s2: Scriptable, thisObj: Scriptable, args: Array<out Any?>): Any? =
                body(args)
        }

    private fun describe(t: Throwable): String {
        val raw = t.message ?: t::class.java.simpleName
        return raw.replace("org.mozilla.javascript.", "").replace("sun.org.mozilla.javascript.", "")
    }

    /** 脚本里的 log 走这里。默认丢掉，测试里可以接管。 */
    var hostLog: (String) -> Unit = {}

    /** 给测试用：拿到脚本里定义的全局对象。 */
    internal fun hasHandler(name: String): Boolean = synchronized(lock) { handlers.containsKey(name) }

}

private val ACTION_ID = Regex("^[A-Za-z][A-Za-z0-9._-]*$")

/** 一个脚本最多声明几个动作。动作会出现在外层的动作清单里，多了没人看得过来。 */
private const val MAX_ACTIONS = 5
