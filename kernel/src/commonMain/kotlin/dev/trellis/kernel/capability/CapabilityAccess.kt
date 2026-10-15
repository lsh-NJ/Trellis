package dev.trellis.kernel.capability

import dev.trellis.kernel.core.CapabilityId

/** 插件在使用特权能力前的唯一检查入口。默认拒绝。 */
interface CapabilityAccess {

    /** 有没有这个能力。拿不到不会抛  用这个实现优雅降级。 */
    fun has(capability: CapabilityId): Boolean

    /**
     * 断言必须拥有这个能力，否则抛 [CapabilityDeniedException]。
     *
     * 「缺能力就让插件整个失败」这条规则**由插件自己决定**，不由内核替它决定：
     * 只有插件知道哪个能力是「没有它我别跑了」，哪个是「没有它我少显示一块」。
     * 内核的职责是**执行**，不是**揣测**。
     */
    fun require(capability: CapabilityId)

    /** 本插件拿到的全部能力，给诊断面板用。 */
    fun granted(): Set<CapabilityId>
}

class CapabilityDeniedException(
    val capability: CapabilityId,
    detail: String,
) : IllegalStateException(detail)

internal class FixedCapabilityAccess(
    private val granted: Set<CapabilityId>,
) : CapabilityAccess {

    override fun has(capability: CapabilityId): Boolean = capability in granted

    override fun require(capability: CapabilityId) {
        if (capability in granted) return
        throw CapabilityDeniedException(
            capability,
            "插件缺少能力「" + Capabilities.describe(capability) + "」(" + capability.value + ")；" +
                "它没有在 manifest 里声明，或者你拒绝了这项授权",
        )
    }

    override fun granted(): Set<CapabilityId> = granted
}