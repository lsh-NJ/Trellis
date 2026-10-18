package dev.trellis.ui

import dev.trellis.kernel.extension.DataContext

/**
 * 用**用户所在时区**算出「现在」和「今天」的边界。
 *
 * 这是 `expect/actual` 该用的地方：一个纯粹的平台原语。
 * 内核和领域层拿不到时区（也不该拿到），UI 层拿得到  所以这个函数住在 UI 层。
 *
 * 每次求值都重新算：用户可能跨过午夜，也可能改了系统时区。
 */
expect fun currentDataContext(): DataContext
