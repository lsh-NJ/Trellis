package dev.trellis.dsl

/**
 * 内存文件系统。
 *
 * 有了它，加载器的测试不用碰磁盘、不留临时目录、跑得飞快，
 * 而且「目录里有个坏文件」这类场景可以随手构造  用真实文件系统写这种测试很烦。
 */
class InMemoryFileSystem(vararg sources: Map<String, String>) : PluginFileSystem {

    private val files: MutableMap<String, String> = mutableMapOf()

    init {
        for (source in sources) files.putAll(source)
    }

    fun write(path: String, content: String) {
        this.files[path] = content
    }

    fun contents(): Map<String, String> = files.toMap()

    override fun listDirectories(root: String): List<String> {
        val prefix = if (root.endsWith("/")) root else root + "/"
        return files.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { it.contains('/') }
            .map { it.substringBefore('/') }
            .distinct()
            .sorted()
    }

    override fun exists(path: String): Boolean = files.containsKey(path)

    override fun readText(path: String): String? = files[path]

    override fun join(vararg parts: String): String = parts.joinToString("/")
}