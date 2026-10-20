package dev.trellis.kernel.diag

import dev.trellis.kernel.core.PluginId

/**
 * 错误码表。见设计文档 13。
 *
 * K001-K012 来自设计文档；K013-K017 是实现阶段补的 
 * 设计文档把「依赖版本不匹配」「依赖被跳过」「加载/初始化/启动失败」混在了
 * MISSING_DEPENDENCY 和 INIT_TIMEOUT 里，实现时发现那样诊断面板分不清，就拆开了。
 */
enum class ErrorCode(val code: String, val title: String, val recoverable: Boolean) {
    SDK_VERSION_MISMATCH("K001", "此插件需要更新版本的 App", true),
    MISSING_DEPENDENCY("K002", "此插件缺少依赖", true),
    CIRCULAR_DEPENDENCY("K003", "插件之间存在循环依赖", false),
    MANIFEST_INVALID("K004", "插件配置不合法", false),
    CAPABILITY_DENIED("K005", "此插件需要额外权限", true),
    INIT_TIMEOUT("K006", "插件初始化超时", true),
    CONTRIBUTION_CONFLICT("K007", "扩展点被重复占用", true),
    MIGRATION_FAILED("K008", "数据升级失败", true),
    LOADER_UNAVAILABLE("K009", "此插件在你的设备上不可用", false),
    QUOTA_EXCEEDED("K010", "插件超出资源限额", true),
    MANIFEST_TAMPERED("K011", "插件被修改过，需要重新授权", true),
    SERVICE_NOT_FOUND("K012", "依赖的服务不可用", true),
    DEPENDENCY_VERSION_MISMATCH("K013", "依赖的插件版本不匹配", true),
    DEPENDENCY_UNAVAILABLE("K014", "依赖的插件未能加载", true),
    PLUGIN_LOAD_FAILED("K015", "插件加载失败", true),
    PLUGIN_INIT_FAILED("K016", "插件初始化失败", true),
    PLUGIN_START_FAILED("K017", "插件启动失败", true),
    REQUIRED_PLUGIN_NOT_READY("K018", "必需插件未就绪", false),
    CONTRIBUTION_REJECTED("K019", "扩展点注册被拒绝", false),
    CAPABILITY_UNDECLARED("K020", "manifest 声明了内核不认识的能力", false),
    FILE_OPERATION_FAILED("K021", "插件文件操作失败", true),
}

/** 一条可诊断的错误。永远不要吞掉它们  诊断面板靠这个活。 */
data class KernelError(
    val code: ErrorCode,
    val pluginId: PluginId?,
    val detail: String,
    val atMs: Long,
) {
    override fun toString(): String = "[${code.code}] ${pluginId?.value ?: "-"}: $detail"
}