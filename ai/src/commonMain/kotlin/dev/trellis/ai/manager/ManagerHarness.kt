package dev.trellis.ai.manager

/**
 * 管理员 harness 的实验开关（P1）。
 *
 * 背景与完整设计见 `docs/agent-harness.md`。一句话：
 * 按「这句话像什么意图」裁工具，总会在一句话没有关键词时把手全收走
 * （真实事故：用户点选项「每天 60 分钟」被判成 Chat，零工具，模型想建计划却调不到），
 * 意图判错时还会整轮跑偏。P1 先在旧路径旁边接一条新路：
 *
 * - **工具集固定 = 全部**（含 Chat），不再按意图过滤；
 * - **静态前缀固定** = 全量工具目录 + 一份 `<task:general>` 通用工作流，不再按意图拼任务模块；
 * - 权限 / 审批 / 审计留在 P2（这一阶段只是「手一直在」，还没有「这只手要不要批」）。
 *
 * 开关是环境变量 [STABLE_TOOLS_ENV]=1；关掉时旧路径逐字节不变，方便 A/B 与回滚。
 */
const val STABLE_TOOLS_ENV: String = "TRELLIS_MANAGER_STABLE_TOOLS"

/**
 * 当前进程是否开了稳定工具集实验。
 *
 * 只读环境变量、不落盘：这是给开发/验收用的开关，不是产品设置。
 * P3 把旧路径删掉后，这个函数和开关一起下线。
 */
expect fun stableToolsEnabled(): Boolean
