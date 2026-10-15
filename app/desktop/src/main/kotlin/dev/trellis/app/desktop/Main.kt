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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
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

/**
 * 闪退日志。
 *
 * 图形程序最难受的失败模式是「双击了，什么都没发生」
 * 因为出错时那个控制台窗口已经关了。所以先把异常落成文件，
 * 出问题时至少有个地方能看。
 */
private fun installCrashLog() {
    val logFile = java.io.File(System.getProperty("user.home"), ".trellis/app.log")
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
    println("[CT] main() 进入")
    val holder = RuntimeHolder()

    application {
        println("[CT] application {} 进入")
        val scope = rememberCoroutineScope()

        Window(
            onCloseRequest = {
                scope.launch {
                    holder.runtime?.shutdown()
                }
                exitApplication()
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
            LaunchedEffect(Unit) {
                println("[CT] 刚组合完: visible=" + window.isVisible + " showing=" + window.isShowing)
                kotlinx.coroutines.delay(6_000)
                println(
                    "[CT] 6 秒后: visible=" + window.isVisible +
                        " showing=" + window.isShowing +
                        " size=" + window.size +
                        " 位置=" + window.locationOnScreen +
                        " graphicsConf=" + window.graphicsConfiguration,
                )
                kotlinx.coroutines.delay(6_000)
                println("[CT] 12 秒后: visible=" + window.isVisible + " showing=" + window.isShowing)
            }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    App(holder)
                }
            }
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
            println("[CT] AppRuntime 创建完成")
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
        failure != null -> FailureView(failure!!)
        current == null -> LoadingView()
        else -> AppScreen(current)
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
