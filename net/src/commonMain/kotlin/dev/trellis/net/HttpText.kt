package dev.trellis.net

/**
 * 一次 HTTP GET 的结果：状态码 + 正文。
 *
 * 这里刻意不判「算不算成功」—— B 站把 412 当风控、ActivityWatch 把 404 当「没有这个
 * bucket」，两边的说法完全不同。判定留给各自的客户端，这个模块只管把字节取回来。
 */
class HttpTextResult(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
}

/**
 * 取一段文本。
 *
 * 为什么需要这个 expect：`java.net.http.HttpClient` **只有桌面 JVM 有**，安卓上没有。
 * 之前 `BilibiliClient` 直接在共享源集里 import 它，桌面编得过、安卓一加载类就
 * `NoClassDefFoundError`，用户看到的就是对话里回一句「出错了：
 * dev.trellis.app.desktop.BilibiliClient」——错误信息里既没有「网络」也没有「B 站」，
 * 完全看不出发生了什么。
 *
 * 所以规则是：**凡是只在桌面存在的 JDK 包，都必须收进平台出口**，不能留在共享源码里。
 * 同一个坑在 `ActivityWatchClient` 上也有一份（见 `:features:activity`）。
 *
 * 实现里都用 `Dispatchers.IO`：两个平台都不允许在主线程上做网络。
 */
expect suspend fun httpGetText(
    url: String,
    headers: Map<String, String> = emptyMap(),
    /** 读超时（秒）。连接超时另算，统一 8 秒 —— 连不上要快速失败，不要让人干等。 */
    timeoutSeconds: Int = 20,
): HttpTextResult
