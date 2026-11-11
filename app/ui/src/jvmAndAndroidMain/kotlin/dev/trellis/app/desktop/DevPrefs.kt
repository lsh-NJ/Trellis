package dev.trellis.app.desktop

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 开发者设置：`<home>/dev.json`。
 *
 * 以前调试模式只能靠启动前设 `TRELLIS_DEV=1`：设置页才会出现「时间调试」「清除数据」。
 * 验证周滚动/结算、演示前清数据都得改环境变量再重启，普通用户根本摸不到。
 * 现在设置页（手机是「我的」）有一个「调试模式」开关写在这里，开完立刻生效；
 * 环境变量仍然有效——演示脚本、截图、CI 不想依赖用户目录里的状态时用它，
 * 两者任一为真即开启（环境变量是启动级强制，界面开关关不掉它，见 [AppRuntime.setDebugMode]）。
 */
internal data class DevPrefs(
    /** 设置页「调试模式」开关；默认关。 */
    val debugMode: Boolean = false,
)

/** 落盘在宿主目录的 `dev.json`；坏文件当作没开，不阻断启动。 */
internal class DevPrefsStore(private val home: File) {
    private val file = File(home, "dev.json")

    fun load(): DevPrefs {
        // 编辑器可能写 UTF-8 BOM；JSON 解析器不认，会把整份设置悄悄退回默认值。
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?.removePrefix("\uFEFF") ?: return DevPrefs()
        val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return DevPrefs()
        return DevPrefs(debugMode = obj["debugMode"]?.jsonPrimitive?.booleanOrNull ?: false)
    }

    fun save(prefs: DevPrefs) {
        val root = buildJsonObject {
            put("debugMode", prefs.debugMode)
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(root.toString(), Charsets.UTF_8)
        }
    }
}
