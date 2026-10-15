package dev.trellis.dsl

/**
 * 极小的文件系统抽象。
 *
 * 为什么不直接用 java.io.File：内核与加载器都要能在 Android / 桌面 / iOS 上跑，
 * 而 KMP 的 common 代码里没有文件 API。
 *
 * 把这点平台差异收进一个 4 方法的接口，换来的是加载器可以完全跑在内存文件系统上 
 * 不碰磁盘、不留残留、测试跑得飞快，而且「磁盘上有个坏文件」这种场景可以随手构造。
 */
interface PluginFileSystem {

    /** 列出 root 下的一级子目录名（不递归）。root 不存在时返回空。 */
    fun listDirectories(root: String): List<String>

    fun exists(path: String): Boolean

    /** 读文本；读不到返回 null，不抛异常。 */
    fun readText(path: String): String?

    /** 用当前平台的路径分隔符拼接。 */
    fun join(vararg parts: String): String
}