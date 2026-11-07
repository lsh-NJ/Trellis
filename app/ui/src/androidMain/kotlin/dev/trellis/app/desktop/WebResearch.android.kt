package dev.trellis.app.desktop

/**
 * 安卓端还没有调研网关：直接返回 null，`research_plan` 会降级成「没有资料」，
 * 仍然能按模型的记忆拆阶段。将来接 Ktor/OkHttp 时在这里实现。
 */
internal actual fun createWebResearch(
    config: dev.trellis.ai.LlmConfig,
    timeoutSeconds: Long,
): ResearchGateway? = null
