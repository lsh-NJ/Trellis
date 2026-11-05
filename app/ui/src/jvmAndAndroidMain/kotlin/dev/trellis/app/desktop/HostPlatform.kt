package dev.trellis.app.desktop

import java.io.File

/**
 * 宿主平台差异的唯一出口。
 *
 * 整个界面（70 个文件、3400 行的 `AppRuntime`）在桌面和手机上共用，只有这五件事
 * 天生属于操作系统：
 *
 * | 能力 | 桌面 | 手机 |
 * |---|---|---|
 * | 复制到剪贴板 | AWT `systemClipboard` | `ClipboardManager` |
 * | 按键 | `Robot.keyPress` | 不支持（应用不能往别的应用注入按键） |
 * | 截屏 | `Robot.createScreenCapture` | 不支持（不能截别人的界面） |
 * | 点击坐标 | `Robot.mouseMove/Press` | 不支持 |
 * | 选文件保存 | AWT `FileDialog` | 落到应用自己的 exports 目录 |
 *
 * 「不支持」不是偷懒：手机上的沙箱就是不让一个应用去看/操作别的应用，
 * 硬做只能走无障碍服务，那是另一个量级的权限问题。所以这三个能力在手机上
 * 返回一句明确的中文，AI 工具链会把它当成工具失败的理由，而不是崩掉。
 */
expect object HostPlatform {

    /** 是不是桌面。给「同一句话在两个平台上说法不同」的地方用（配置指引、快捷键提示）。 */
    val isDesktop: Boolean

    fun copyToClipboard(text: String)

    /** 敲一个键。返回值是给人看的说明（成功或失败原因）。 */
    fun pressKey(key: String): String

    /** 截一张当前界面。给模型看的那张会缩到最长边 1280。 */
    fun captureScreen(home: File): HostCapture

    /** 往当前焦点里打一段字。 */
    fun typeText(text: String): String

    /**
     * 把字节解成可画的位图。
     *
     * 桌面用 Skia，安卓用 `BitmapFactory` —— 以前这里直接 `ImageIO.read`，
     * 那是桌面专属的。
     */
    fun decodeImage(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap?

    /** 在系统文件管理器里打开一个目录。 */
    fun openFolder(dir: File): Boolean

    /** 按最近一张截图的坐标点一下。 */
    fun clickScreen(rect: CaptureRect, x: Int, y: Int): String

    /**
     * 让用户选一个保存位置。
     *
     * 桌面弹系统保存面板；手机没有等价的同步 API（SAF 要 ActivityResult），
     * 所以直接落到应用自己的 exports 目录并返回那个文件。
     */
    fun chooseExportFile(defaultName: String): File?

    /**
     * 让用户选一个或多个 Markdown 文件（笔记导入用）。
     *
     * 桌面弹系统打开面板（AWT `FileDialog` 多选）；手机没有等价的同步 API
     * （SAF 要 `ActivityResult`），返回空列表，界面据此说明「手机上暂时不能导入」。
     */
    fun chooseImportFiles(): List<File>

    /**
     * 「这里可以拖动」的指针图标。
     *
     * 桌面是左右箭头，手机没有 hover 这回事（返回默认图标）。
     * 之所以放进这个出口：`PointerIcon(java.awt.Cursor…)` 只有桌面能构造。
     */
    fun resizePointerIcon(): androidx.compose.ui.input.pointer.PointerIcon
}

/** 最近一次截图覆盖的区域；`clickScreen` 的坐标以它为准。 */
data class CaptureRect(val x: Int, val y: Int, val width: Int, val height: Int)

/** 一次截图的结果：给人看的说明 + 给模型看的 data URL + 区域。 */
data class HostCapture(val text: String, val dataUrl: String?, val rect: CaptureRect?)
