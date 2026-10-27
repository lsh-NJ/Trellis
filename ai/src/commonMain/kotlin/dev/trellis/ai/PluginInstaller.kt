package dev.trellis.ai

/**
 * 落地一个生成的插件。返回 null 表示成功，返回字符串表示失败原因。
 *
 * 抽成接口是为了**能测**：测试里塞一个「第一次失败第二次成功」的假安装器，
 * 就能验证工具循环的重试逻辑，完全不需要网络、不需要文件系统。
 */
interface PluginInstaller {
    suspend fun install(files: Map<String, String>): String?

    /**
     * 装之前先把这个插件要用的权限说清楚。返回 null 表示可以继续。
     *
     * 为什么装之前要单独一步：内核在 install 时会问「这些能力批不批」，
     * 没人应答就是拒绝；而 AI 生成不可能中途弹一个模态框问用户。
     * 所以规则是：**普通权限（读、写）当作这次请求的一部分直接给**，
     * 更危险的（删数据、联网…）一律拒绝，让用户自己去插件详情里授权。
     */
    suspend fun ensureGrants(id: String, capabilities: Set<String>): String? = null
}
