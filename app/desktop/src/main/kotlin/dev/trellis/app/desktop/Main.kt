package dev.trellis.app.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.trellis.ui.LocalPluginImage
import dev.trellis.ui.LocalPluginCardStyle
import kotlinx.coroutines.launch

/**
 * 按屏幕大小自适应。
 *
 * 不是多余的谨慎：WSLg 的虚拟显示器只有 640x480，写死 980x780 的窗口会被裁掉一半。
 * 真实桌面上会取到更大的尺寸。
 */
@Composable
private fun initialWindowSize(): DpSize {
    val screen = remember {
        runCatching { java.awt.Toolkit.getDefaultToolkit().screenSize }
            .getOrElse { java.awt.Dimension(1280, 900) }
    }
    return DpSize(
        width = minOf(screen.width - 40, 980).coerceAtLeast(520).dp,
        height = minOf(screen.height - 60, 780).coerceAtLeast(420).dp,
    )
}

/** 持有运行时，好让窗口的关闭回调能拿到它。 */
private class RuntimeHolder {
    var runtime: AppRuntime? = null
}

/** 数据根目录：和 AppRuntime 同一套解析（TRELLIS_HOME 优先），日志也落在同一个根下。 */
private fun trellisHomeDir(): java.io.File =
    System.getenv("TRELLIS_HOME")?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
        ?: java.io.File(System.getProperty("user.home"), ".trellis")

/**
 * 闪退日志。
 *
 * 图形程序最难受的失败模式是「双击了，什么都没发生」
 * 因为出错时那个控制台窗口已经关了。所以先把异常落成文件，
 * 出问题时至少有个地方能看。
 */
private fun installCrashLog() {
    val logFile = java.io.File(trellisHomeDir(), "app.log")
    Thread.setDefaultUncaughtExceptionHandler { _, t ->
        runCatching {
            logFile.parentFile?.mkdirs()
            logFile.appendText(
                "\n[" + java.time.Instant.now() + "] 未捕获异常\n" + t.stackTraceToString() + "\n",
                Charsets.UTF_8,
            )
        }
    }
    runCatching {
        logFile.parentFile?.mkdirs()
        logFile.appendText("\n[" + java.time.Instant.now() + "] 启动\n", Charsets.UTF_8)
    }
}

fun main() {
    installCrashLog()
    // 只在 TRELLIS_DEV=1 时启动；卡死时自动把线程栈写进 app.log。
    UiWatchdog.startIfEnabled(trellisHomeDir())
    val holder = RuntimeHolder()

    application {
        val scope = rememberCoroutineScope()

        Window(
            onCloseRequest = {
                // 关闭必须永远能关掉，哪怕关它的代码自己坏了。
                //
                // 踩过的坑：`gradlew run` 的 classpath 是 build/classes 目录。应用开着
                // 的时候又跑了一次编译，编译会重写这个目录；旧进程里的 lambda 类可能
                // 直到点关闭的那一刻才第一次加载，于是抛 NoClassDefFoundError ——
                // 如果它发生在 exitApplication() 之前，窗口就再也关不掉了
                // （进程活着、窗口还在、日志里一行 ClassNotFoundException）。
                // 所以这里兜住一切异常，exitApplication() 无论如何都要执行。
                runCatching { scope.launch { holder.runtime?.shutdown() } }
                    .onFailure { println("[CT] 关闭时没能启动 shutdown：" + it) }
                exitApplication()
            },
            // 备用的退路：万一窗口的关闭按钮被什么挡住，Ctrl+Q 永远能退。
            onPreviewKeyEvent = { event ->
                if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.Q) {
                    runCatching { scope.launch { holder.runtime?.shutdown() } }
                    exitApplication()
                    true
                } else {
                    false
                }
            },
            title = "Trellis",
            // 默认最大化。
            //
            // 不只是习惯问题：小窗口很容易被别的窗口盖住、或者落在屏幕边缘被忽略，
            // 然后用户会说「双击了没反应」。全屏铺开就没有这个歧义。
            state = rememberWindowState(
                placement = WindowPlacement.Maximized,
                size = initialWindowSize(),
            ),
        ) {
            // 主窗口重新获得焦点 = 用户可能刚回到椅子前：触发一次计划检查。
            // 监听器挂在 AWT 窗口上：焦点事件不经过 Compose 的 composition。
            DisposableEffect(window) {
                val listener = object : java.awt.event.WindowAdapter() {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {
                        holder.runtime?.onWindowFocused()
                    }
                }
                window.addWindowFocusListener(listener)
                onDispose { window.removeWindowFocusListener(listener) }
            }
            App(holder)
        }
    }
}

@Composable
private fun App(holder: RuntimeHolder) {
    var runtime by remember { mutableStateOf<AppRuntime?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val created = AppRuntime.create()
            holder.runtime = created
            runtime = created
        } catch (t: Throwable) {
            runCatching {
                java.io.File(System.getProperty("user.home"), ".trellis/app.log")
                    .appendText("\n[" + java.time.Instant.now() + "] 内核启动失败\n" + t.stackTraceToString(), Charsets.UTF_8)
            }
            println("[CT] AppRuntime 创建失败: " + t)
            failure = (t.message ?: t.toString()) + "\n" + t.stackTraceToString().take(1200)
        }
    }

    val current = runtime
    when {
        // 启动失败/加载中还没有偏好可读，用一套中性的浅色主题兜住（不能没有主题，
        // 否则 MaterialTheme 的默认值是空的，文字颜色会落到黑色）。
        failure != null -> MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                FailureView(failure!!)
            }
        }

        current == null -> MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                LoadingView()
            }
        }

        else -> {
            // 外观只有一个开关（auto/light/dark）：auto 才问系统，别的都明确指定。
            // 整页的底色和正文颜色由 AppTheme 里的根 Surface 统一刷，
            // 不能在窗口这一层先刷一遍浅色，否则就会「卡片跟随、页面不跟随」。
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val dark = when (current.uiPrefs.appearance) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            val scheme = current.themeScheme(dark)
            AppTheme(
                current.uiPrefs,
                scheme,
                dark,
                chrome = current.chromeStyle(),
                extra = current.extraColors(dark),
            ) {
                CompositionLocalProvider(
                    LocalPluginImage provides { owner, ref -> current.pluginImage(owner, ref) },
                    // 逐插件的卡片样式：返回 null 的插件落回全局 LocalCardStyle。
                    LocalPluginCardStyle provides { owner -> current.pluginCardStyle(owner) },
                ) {
                    AppScreen(current)
                    if (current.timerVisible) TimerWindow(current)
                }
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text("正在启动内核", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FailureView(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("启动失败", style = MaterialTheme.typography.headlineSmall)
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}


/**
 * 计时器小窗：独立窗口，不挡主窗口，永远置顶。
 *
 * Window 是另一套 composition，主题/字体不会从主窗口继承，所以这里把 AppTheme 再包一层；
 * 每秒走一格的驱动也挂在这里，窗口关掉就自然停。
 */
@Composable
private fun TimerWindow(runtime: AppRuntime) {
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = when (runtime.uiPrefs.appearance) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    Window(
        onCloseRequest = { runtime.closeTimer() },
        title = "计时 · " + runtime.timerTaskTitle,
        state = rememberWindowState(
            size = DpSize(380.dp, 400.dp),
            position = WindowPosition(Alignment.BottomEnd),
        ),
        alwaysOnTop = true,
        resizable = false,
    ) {
        AppTheme(
            runtime.uiPrefs,
            runtime.themeScheme(dark),
            dark,
            chrome = runtime.chromeStyle(),
            extra = runtime.extraColors(dark),
        ) {
            LaunchedEffect(runtime.timerRunning) {
                while (runtime.timerRunning) {
                    kotlinx.coroutines.delay(1_000)
                    runtime.timerTick(1)
                }
            }
            FocusTimerWindow(runtime)
        }
    }
}
