package dev.trellis.app.desktop

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform

/**
 * 段落式编辑器（JavaFX WebView）的启动态。
 *
 * 为什么要有这一层：JavaFX 的 toolkit 初始化是**重**操作，而且会失败——
 * 原生库默认解压到 `~/.openjfx/cache`，家目录只读/被沙箱限制/旧缓存损坏时，
 * `JFXPanel` 构造会失败；而 `JFXPanel` 在 EDT 上是「开一个 secondary loop 等 FX 线程初始化」，
 * 初始化一失败/一卡住，EDT 就出不来，表现就是**窗口还在、整页点不动**。
 *
 * 所以这里把 JavaFX 启动挪到后台线程，并且：
 * 1. 先把 `javafx.cachedir` 指到系统临时目录（可写性比用户家目录可靠）；
 * 2. 带超时，失败/超时都只返回 false，绝不让调用方阻塞；
 * 3. 失败原因留着给界面显示，并据此回退到 Compose 编辑器。
 *
 * 调用约定：只能在**非 EDT** 线程调用 [ensureReadyBlocking]（例如 Dispatchers.IO），
 * 成功后再在 Compose 里挂 `MarkdownWebEditor`（那时 JFXPanel 已经不需要等 toolkit 了）。
 */
internal object MarkdownWebRuntime {

    enum class State { Uninitialized, Starting, Ready, Failed }

    @Volatile
    private var state: State = State.Uninitialized

    @Volatile
    private var failure: String? = null

    /** 首次启动结束时放行；只放行一次，之后读它永远是 0。 */
    private val bootFinished = CountDownLatch(1)

    private val lock = Any()

    fun state(): State = state

    /** 最近一次启动失败的原因（给编辑器里的回退提示用）；成功时为 null。 */
    fun failureMessage(): String? = failure

    /**
     * 启动 JavaFX toolkit；返回是否可用。
     *
     * 幂等：并发/重复调用共享同一个启动结果。超时 20 秒——正常机器一两秒就绪；
     * 卡住说明环境和 JavaFX 不对付，再等只会让界面更像「卡死」。
     */
    fun ensureReadyBlocking(timeoutMillis: Long = 20_000): Boolean {
        val owner = synchronized(lock) {
            if (state == State.Uninitialized) {
                state = State.Starting
                true
            } else {
                false
            }
        }
        if (owner) bootJavaFx(timeoutMillis)
        // 别人正在启动时也只等一个有上限的时间，不会无限挂住调用线程。
        runCatching { bootFinished.await(timeoutMillis, TimeUnit.MILLISECONDS) }
        return state == State.Ready
    }

    /**
     * jpackage 的 jlink 运行时缺 `jdk.unsupported.desktop` 时，`JFXPanel` 会在 postEvent /
     * setCursor 时抛 `NoClassDefFoundError: jdk/swing/interop/SwingInterOpUtils`——
     * 表现是编辑器一块白板、整页交互失灵（FX pulse 每次都在抛异常）。
     *
     * jdeps 推断不出这个依赖（JavaFX 用反射调 jdk.swing.interop），必须手写进
     * `app/desktop/build.gradle.kts` 的 `modules(...)`；这里在启动 JavaFX 之前先探一下，
     * 缺了就当作「不可用」回退 Compose，而不是让界面挂着。
     */
    internal fun javafxSwingInteropMissing(
        probe: () -> Unit = { Class.forName("jdk.swing.interop.SwingInterOpUtils") },
    ): Boolean = runCatching(probe).isFailure

    private fun bootJavaFx(timeoutMillis: Long) {
        if (javafxSwingInteropMissing()) {
            state = State.Failed
            failure = "运行时缺少 jdk.unsupported.desktop 模块（JFXPanel 需要 jdk.swing.interop）"
            System.err.println("Trellis：段落式编辑器启动失败（" + failure + "），回退单文本编辑器")
            bootFinished.countDown()
            return
        }
        // JavaFX 原生库解压目录：默认是 ~/.openjfx/cache。家目录不可写时初始化会直接失败，
        // 先指到系统临时目录（macOS 的 /var/folders 临时区、Linux 的 /tmp 都可写）。
        if (System.getProperty("javafx.cachedir").isNullOrBlank()) {
            val cacheDir = File(System.getProperty("java.io.tmpdir"), "trellis-javafx-cache")
            runCatching { cacheDir.mkdirs() }
            System.setProperty("javafx.cachedir", cacheDir.absolutePath)
        }
        val fxStarted = CountDownLatch(1)
        var error: Throwable? = null
        val bootThread = Thread {
            try {
                Platform.startup { fxStarted.countDown() }
            } catch (t: Throwable) {
                // 同进程里 JavaFX 已经被启动过（例如别的实验开关）：不算失败。
                if (t is IllegalStateException && t.message.orEmpty().contains("already", ignoreCase = true)) {
                    fxStarted.countDown()
                } else {
                    error = t
                    fxStarted.countDown()
                }
            }
        }
        bootThread.name = "trellis-javafx-boot"
        bootThread.isDaemon = true
        bootThread.start()
        val finished = runCatching { fxStarted.await(timeoutMillis, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        val problem = when {
            !finished -> "启动超时（" + (timeoutMillis / 1000) + " 秒）"
            error != null -> error!!.message ?: error!!.javaClass.simpleName
            else -> null
        }
        state = if (problem == null) State.Ready else State.Failed
        failure = problem
        if (problem != null) {
            // 只打一行：排查「怎么点了段落式没反应」时，stderr 比界面截图先到。
            System.err.println("Trellis：段落式编辑器启动失败（" + problem + "），回退单文本编辑器")
        }
        bootFinished.countDown()
    }
}
