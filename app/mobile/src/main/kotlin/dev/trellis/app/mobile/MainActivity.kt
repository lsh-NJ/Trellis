package dev.trellis.app.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.trellis.app.desktop.AndroidNav
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

    /**
     * 通知点开要去的页签。放在 Activity 字段而不是 remember：
     * `onNewIntent` 回来时界面早就组合过了，只有 Activity 级别的状态才能把它顶过去。
     */
    private var openTab by mutableStateOf<Int?>(null)

    /** 每次点击自增 —— 「再点同一条通知」也要重新跳一次。 */
    private var openTabToken by mutableIntStateOf(0)

    private fun readOpenTab(intent: Intent?) {
        val index = AndroidNav.tabIndexFrom(intent) ?: return
        openTab = index
        openTabToken++
    }

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

        // 冷启动时通知就在 intent 里（应用被杀掉的情况下点通知）。
        readOpenTab(intent)

        // 家目录放在应用私有目录：卸载即清，不需要任何存储权限。
        val home = File(filesDir, "trellis").apply { mkdirs() }
        // 崩溃要留证据。手机上没有 jstack、也没法连 adb 的时候，
        // `<home>/app.log`（诊断页能直接看）就是唯一能拿到的栈。
        installCrashLogger(home)

        setContent {
            MobileHost(
                home = home,
                openTab = openTab,
                openTabToken = openTabToken,
            )
        }
    }

    /**
     * 应用在后台时点通知：系统复用这个实例（manifest 里 singleTop），
     * 新的 intent 从这里进来。不处理的话表现就是「点通知只是把应用拉到前台」。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readOpenTab(intent)
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
private fun MobileHost(
    home: File,
    openTab: Int?,
    openTabToken: Int,
) {
    var ready by remember { mutableStateOf<AppRuntime?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    // 「从文件安装插件」：共享层没有文件选择器（桌面是 JFileChooser），
    // 安卓这一套 SAF 只属于壳。选中的 uri 复制成 cache 里的临时 zip，
    // 共享层只认 File —— 它不该知道 content:// 是什么。
    var pendingPluginPick by remember { mutableStateOf<((File) -> Unit)?>(null) }
    val pluginPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val deliver = pendingPluginPick
        pendingPluginPick = null
        if (uri == null || deliver == null) return@rememberLauncherForActivityResult
        val target = File(context.cacheDir, "plugin-" + System.currentTimeMillis() + ".zip")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
        }.getOrDefault(false)
        if (copied) deliver(target)
    }

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
                    // 通知点开落到哪一页，由壳从 intent 里读出来再交进去。
                    openTab = openTab,
                    openTabToken = openTabToken,
                    onPickPluginArchive = { onPicked ->
                        pendingPluginPick = onPicked
                        // 有些文件管理器把 zip 报成 octet-stream，所以显式列常见类型再兜底：
                        // 只写 application/zip 的话，用户会看到「没有可用的应用」。
                        pluginPicker.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream",
                                "*/*",
                            ),
                        )
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
