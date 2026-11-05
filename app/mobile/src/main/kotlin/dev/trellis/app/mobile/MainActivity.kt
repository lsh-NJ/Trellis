package dev.trellis.app.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.trellis.app.desktop.AppRuntime
import dev.trellis.app.desktop.HostPlatform
import dev.trellis.app.desktop.installAndroidNotifications
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 安卓入口。
 *
 * 这个文件是手机端**唯一**的安卓专属代码：四件事 —— edge-to-edge、
 * 注入 Context（剪贴板要用）、起 `AppRuntime`、把 `PhoneApp` 画出来。
 * 界面本身在 `:app:ui` 里，和桌面是同一份，所以功能不会「手机少一半」。
 */
class MainActivity : ComponentActivity() {

    private var runtime: AppRuntime? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容画到状态栏、导航栏下面，再由 Compose 的 safeDrawing 让出安全区。
        enableEdgeToEdge()

        // 剪贴板需要一个 Context；这是 HostPlatform 唯一的注入点。
        HostPlatform.context = applicationContext
        // 系统通知：建 channel + 记住 Context。Android 13+ 还要用户给一次通知权限；
        // 不给也不崩 —— 通知是尽力而为，会话里仍有未读红点。
        installAndroidNotifications(applicationContext)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        // 家目录放在应用私有目录：卸载即清，不需要任何存储权限。
        val home = File(filesDir, "trellis").apply { mkdirs() }
        // 崩溃要留证据。手机上没有 jstack、也没法连 adb 的时候，
        // `<home>/app.log`（诊断页能直接看）就是唯一能拿到的栈。
        installCrashLogger(home)

        setContent { MobileHost(home) }
    }

    override fun onDestroy() {
        val current = runtime
        runtime = null
        if (current != null) {
            // 关内核（停插件、落盘）不该阻塞主线程。
            ioScope.launch { runCatching { current.shutdown() } }
        }
        super.onDestroy()
    }
}

@Composable
private fun MobileHost(home: File) {
    var ready by remember { mutableStateOf<AppRuntime?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            ready = AppRuntime.create(home)
        } catch (t: Throwable) {
            failure = (t.message ?: t.toString()) + "\n" + t.stackTraceToString().take(800)
        }
    }

    val current = ready
    when {
        failure != null -> BootFailure(failure!!)
        current == null -> BootSplash()
        else -> {
            // 手机没有窗口 chrome，安全区在这里一次性让出来：
            // 共享界面不知道也不该知道「状态栏有多高」。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .safeDrawingPadding(),
            ) {
                PhoneApp(
                    runtime = current,
                    // 安卓返回键：不在首页就先回首页，在首页才真的退出。
                    // 桌面上没有这个键，所以这段逻辑只属于壳，不进共享界面。
                    backHandler = { enabled, onBack ->
                        BackHandler(enabled = enabled, onBack = onBack)
                    },
                )
            }
        }
    }
}

@Composable
private fun BootSplash() {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("正在装配内核", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BootFailure(message: String) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)) {
        Column {
            Text("启动失败", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * 未捕获异常落盘。
 *
 * 为什么必须装：手机上线程/协程里漏出来的异常会直接杀掉进程，用户只能看到「闪退」，
 * 而这边既没有控制台也常常连不上 adb。写进 `<home>/app.log` 之后，诊断页（我的 → 诊断）
 * 里就能看到完整的栈，下一次反馈不用再靠猜。
 *
 * 写完仍然交给系统默认处理器：不吞异常，系统的崩溃行为保持不变。
 */
private var crashLoggerInstalled = false

private fun installCrashLogger(home: File) {
    if (crashLoggerInstalled) return
    crashLoggerInstalled = true
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching {
            home.mkdirs()
            File(home, "app.log").appendText(
                "\n[" + java.time.Instant.now() + "] 崩溃（" + thread.name + "）\n" +
                    error.stackTraceToString() + "\n",
                Charsets.UTF_8,
            )
        }
        previous?.uncaughtException(thread, error)
    }
}
