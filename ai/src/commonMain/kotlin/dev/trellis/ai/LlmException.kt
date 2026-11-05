package dev.trellis.ai

/**
 * 模型接口「能说清原因」的失败。
 *
 * 单独一个类型，是为了让界面能区分「模型/网络的问题」（把消息原样给用户看）
 * 与「我们代码的问题」（记日志、说一句通用的话）。
 */
class LlmException(message: String) : Exception(message)
