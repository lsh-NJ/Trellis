package dev.trellis.kernel.core

/** 语义化版本。预发布号与构建号在解析时被丢弃（内核不依赖它们的排序语义）。 */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major - other.major
        if (minor != other.minor) return minor - other.minor
        return patch - other.patch
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(text: String): SemVer {
            val core = text.trim().substringBefore('-').substringBefore('+')
            val parts = core.split('.')
            require(parts.size == 3) { "非法语义化版本: $text" }
            val major = parts[0].trim().toIntOrNull()
            val minor = parts[1].trim().toIntOrNull()
            val patch = parts[2].trim().toIntOrNull()
            require(major != null) { "非法主版本号: $text" }
            require(minor != null) { "非法次版本号: $text" }
            require(patch != null) { "非法修订号: $text" }
            return SemVer(major, minor, patch)
        }
    }
}

/**
 * 版本区间，支持空格分隔的合取式，例如 ">=1.0.0 <2.0.0"。
 * 支持的操作符: >= <= > < == =  以及通配 "*"。
 */
class VersionRange private constructor(
    private val raw: String,
    private val tests: List<(SemVer) -> Boolean>,
) {
    operator fun contains(version: SemVer): Boolean = tests.all { it(version) }

    override fun toString(): String = raw

    override fun equals(other: Any?): Boolean = other is VersionRange && other.raw == raw
    override fun hashCode(): Int = raw.hashCode()

    companion object {
        val ANY: VersionRange = VersionRange("*", emptyList())

        fun parse(expr: String): VersionRange {
            val trimmed = expr.trim()
            if (trimmed.isEmpty() || trimmed == "*") return ANY
            val tests = trimmed
                .split(' ', ',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { parseToken(it, expr) }
            require(tests.isNotEmpty()) { "非法版本区间: $expr" }
            return VersionRange(trimmed, tests)
        }

        private fun parseToken(token: String, whole: String): (SemVer) -> Boolean {
            val op = OPERATORS.firstOrNull { token.startsWith(it) }
            require(op != null) { "非法版本区间记号 [$token]，整个区间为 [$whole]" }
            val version = SemVer.parse(token.removePrefix(op))
            return when (op) {
                ">=" -> { v -> v >= version }
                "<=" -> { v -> v <= version }
                ">" -> { v -> v > version }
                "<" -> { v -> v < version }
                else -> { v -> v == version }
            }
        }

        private val OPERATORS = listOf(">=", "<=", "==", ">", "<", "=")
    }
}